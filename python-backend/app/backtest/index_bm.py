"""KOSPI 벤치마크 일봉 소스 — 백테스트 전용 (alpha + regime v1 point-in-time).

소스 우선순위(전부 실데이터 — §4c 가짜값 금지, 실패 시 None):
  1. Java KIS 일봉(`app.utils.index_source.fetch_kospi_daily`) — 운영/서버 환경(진짜 종합지수 0001).
  2. Yahoo ^KS11 — 로컬(백엔드 미기동) 폴백. GlobalFuturesService 가 이미 쓰는 소스 계열.
⚠ pykrx 지수는 시도하지 않는다 — KRX 포맷 변경으로 전구간 빈값(CLAUDE.md §4c, P0-pykrx).
"""
import logging
from typing import Optional

import pandas as pd
import requests

logger = logging.getLogger(__name__)

YAHOO_KOSPI_URL = "https://query1.finance.yahoo.com/v8/finance/chart/%5EKS11"


def fetch_kospi_via_java(days: int) -> Optional[pd.DataFrame]:
    """Java KIS 일봉 경유 — index_source 재사용(운영 경로와 동일 데이터)."""
    try:
        from app.utils.index_source import fetch_kospi_daily
        df = fetch_kospi_daily(days)
        return df if df is not None and not df.empty else None
    except Exception as e:
        logger.info(f"[BM] Java KIS 일봉 미가용(로컬?): {e}")
        return None


def fetch_kospi_via_yahoo(range_: str = "2y") -> Optional[pd.DataFrame]:
    """Yahoo ^KS11 일봉 → pykrx 동형 DataFrame(오름차순, 시가/고가/저가/종가)."""
    try:
        r = requests.get(YAHOO_KOSPI_URL,
                         params={"range": range_, "interval": "1d"},
                         headers={"User-Agent": "Mozilla/5.0"}, timeout=15)
        r.raise_for_status()
        result = r.json()["chart"]["result"][0]
        ts = result["timestamp"]
        q = result["indicators"]["quote"][0]
        rows = []
        for i, t in enumerate(ts):
            o, h, lo, c = q["open"][i], q["high"][i], q["low"][i], q["close"][i]
            if c is None:
                continue   # 휴장/결측 봉 제외(§4c: 보간 금지)
            rows.append({"date": pd.Timestamp(t, unit="s", tz="Asia/Seoul").normalize().tz_localize(None),
                         "시가": o if o is not None else c,
                         "고가": h if h is not None else c,
                         "저가": lo if lo is not None else c,
                         "종가": c})
        if not rows:
            return None
        df = pd.DataFrame(rows).set_index("date").sort_index()
        return df
    except Exception as e:
        logger.warning(f"[BM] Yahoo ^KS11 조회 실패: {e}")
        return None


def fetch_kospi_daily_bm(days: int = 500) -> Optional[pd.DataFrame]:
    """KOSPI 일봉(오름차순) — Java KIS 우선, Yahoo 폴백. 둘 다 실패면 None(alpha/regime 미산출)."""
    df = fetch_kospi_via_java(days)
    if df is not None:
        df.attrs["source"] = "java-kis"
        return df
    df = fetch_kospi_via_yahoo()
    if df is not None:
        df.attrs["source"] = "yahoo-ks11"
    return df
