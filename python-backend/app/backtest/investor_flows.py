"""종목별 기관/외국인 일별 순매매 — 백테스트 전용 데이터 어댑터.

소스 우선순위(§4c — 전부 실데이터, 실패/결측은 빈 DF·None):
  1. CSV(운영 InvestorDailyTrade export) — 금액(억) 정밀. 스키마: date,stock_code,frgn_net_eok,inst_net_eok
  2. 네이버 금융 frgn 페이지 크롤 — 순매매 '수량' → 금액 근사 = 수량×종가(억). 로컬 재현 경로.
     ⚠ pykrx 투자자 거래대금(get_market_trading_value_by_date)은 2026-07 현재 전구간 0행
     (지수와 같은 KRX 포맷 깨짐 계열) — 시도하지 않는다.

근사 한계(리포트 명시): 네이버 수량×종가 는 체결단가 분포를 무시(당일 종가 일괄) — 금액 밴드
(5/10/20/50/100억) 경계 근처에서 오차. 부호(순매수/순매도)와 연속일 판정에는 영향 없음.
"""
import io
import logging
import time
from pathlib import Path
from typing import Optional

import pandas as pd
import requests

logger = logging.getLogger(__name__)

NAVER_FRGN_URL = "https://finance.naver.com/item/frgn.naver"
REQUEST_DELAY_SEC = 0.25    # 예의상 딜레이(차단 방지) — 139종목×~8페이지 ≈ 5분


def _parse_frgn_page(html: str) -> pd.DataFrame:
    """frgn 페이지 HTML → DataFrame(date, close, inst_qty, frgn_qty). 컬럼은 위치 기반(인코딩 무관)."""
    tables = pd.read_html(io.StringIO(html))
    big = max(tables, key=lambda t: t.shape[0] * t.shape[1])
    if big.shape[1] < 7:
        return pd.DataFrame()
    df = big.iloc[:, [0, 1, 5, 6]].copy()
    df.columns = ["date", "close", "inst_qty", "frgn_qty"]
    df = df.dropna(subset=["date", "close"])
    df["date"] = pd.to_datetime(df["date"], format="%Y.%m.%d", errors="coerce")
    df = df.dropna(subset=["date"])
    for c in ("close", "inst_qty", "frgn_qty"):
        df[c] = pd.to_numeric(df[c], errors="coerce")
    return df.dropna(subset=["inst_qty", "frgn_qty"])


def fetch_naver_flows(ticker: str, start: pd.Timestamp, end: pd.Timestamp,
                      max_pages: int = 40, session: Optional[requests.Session] = None) -> pd.DataFrame:
    """네이버 frgn 페이지를 start 이전까지 페이징 크롤 → 일별 flows(오름차순 date 인덱스).

    반환 컬럼: close, inst_qty, frgn_qty, frgn_net_eok, inst_net_eok(수량×종가/1e8 근사).
    실패/빈 결과 = 빈 DataFrame(§4c: 가짜값 생성 금지).
    """
    sess = session or requests.Session()
    frames = []
    for page in range(1, max_pages + 1):
        try:
            r = sess.get(NAVER_FRGN_URL, params={"code": ticker, "page": page},
                         headers={"User-Agent": "Mozilla/5.0"}, timeout=15)
            r.encoding = "euc-kr"
            part = _parse_frgn_page(r.text)
        except Exception as e:
            logger.warning(f"[flows] 네이버 crawl 실패 {ticker} p{page}: {e}")
            break
        if part.empty:
            break
        frames.append(part)
        if part["date"].min() <= start:
            break
        time.sleep(REQUEST_DELAY_SEC)
    if not frames:
        return pd.DataFrame()
    df = pd.concat(frames).drop_duplicates(subset=["date"]).sort_values("date")
    df = df[(df["date"] >= start) & (df["date"] <= end)].set_index("date")
    # 금액 근사(억) — 수량×당일종가. CSV(진짜 금액) 경로와 동일 컬럼명으로 정규화.
    df["frgn_net_eok"] = df["frgn_qty"] * df["close"] / 1e8
    df["inst_net_eok"] = df["inst_qty"] * df["close"] / 1e8
    return df


def load_flows_csv(path: str) -> dict:
    """운영 InvestorDailyTrade export CSV → {ticker: DataFrame(date 인덱스, frgn_net_eok, inst_net_eok)}.

    스키마(헤더 필수): date,stock_code,frgn_net_eok,inst_net_eok — 금액은 억원 단위.
    """
    df = pd.read_csv(path, dtype={"stock_code": str})
    df["date"] = pd.to_datetime(df["date"])
    out = {}
    for code, g in df.groupby("stock_code"):
        out[code] = g.sort_values("date").set_index("date")[["frgn_net_eok", "inst_net_eok"]]
    return out


def get_flows(ticker: str, start: pd.Timestamp, end: pd.Timestamp,
              csv_flows: Optional[dict] = None, cache_dir: Optional[str] = None,
              session: Optional[requests.Session] = None) -> pd.DataFrame:
    """flows 단일 진입점 — CSV 우선, 없으면 네이버(파일 캐시 지원). 빈 DF = 미수집(수급 미측정)."""
    if csv_flows is not None and ticker in csv_flows:
        f = csv_flows[ticker]
        return f[(f.index >= start) & (f.index <= end)]
    if cache_dir:
        p = Path(cache_dir) / f"flows_{ticker}.csv"
        if p.exists():
            df = pd.read_csv(p, index_col=0, parse_dates=True)
            if not df.empty and df.index.min() <= start and df.index.max() >= end - pd.Timedelta(days=7):
                return df[(df.index >= start) & (df.index <= end)]
    df = fetch_naver_flows(ticker, start, end, session=session)
    if cache_dir and not df.empty:
        Path(cache_dir).mkdir(parents=True, exist_ok=True)
        df.to_csv(Path(cache_dir) / f"flows_{ticker}.csv")
    return df


# ==================== 순수: 연속일/스트릭 (테스트 대상) ====================

def consecutive_net_buy_days(net_series: list, idx: int) -> int:
    """idx 일 기준, idx 포함 뒤로 연속 순매수(>0) 일수 — 순수 함수."""
    days = 0
    i = idx
    while i >= 0 and net_series[i] is not None and net_series[i] > 0:
        days += 1
        i -= 1
    return days


def avg_amount_over_streak(net_series: list, idx: int, days: int) -> float:
    """스트릭 구간(idx 포함 직전 days 일)의 평균 일 순매수 금액 — 순수 함수. days<=0 → 0."""
    if days <= 0:
        return 0.0
    window = net_series[idx - days + 1: idx + 1]
    vals = [v for v in window if v is not None]
    return sum(vals) / len(vals) if vals else 0.0
