"""종합점수 4카테고리 예측력 백테스트 (P1-6 근거 확보용 — 측정 전용, 라이브 산식 무변경).

chart_backtest_service 패턴 확장: point-in-time(df.loc[:D]) 신호 재생 + D+1 시가 진입 /
+3거래일 종가 청산 / 비용 차감 / hit=SignalOutcome 미러. 산출 = 카테고리 × 점수밴드 ×
regime(v1 point-in-time) 별 hitRate·평균 alpha·표본수 + K슬롯 MDD.

카테고리 재현 커버리지(§4c — 재현 불가는 미측정으로 정직 표기):
  - technical: OHLCV 만으로 완전 재현 가능 — RecommendationService.scoreTechnical 미러
    (buySignalStrength/RSI Wilder/골든크로스/정배열/과열 페널티. breakout 은 calculate()
    가격-only 경로에서 항상 미설정=false 라 미러도 false 고정).
  - supplyDemand: 기관/외국인 순매매(investor_flows)로 scoreSupplyDemand 미러.
    ⚠ 네이버 경로는 금액이 수량×종가 근사 + '당일 상위' 랭킹이 전시장이 아닌 유니버스 내
    상대 랭킹 — 근사임을 리포트에 명시. CSV(운영 export) 주입 시 정밀.
  - earnings / sectorMomentum: 가격 데이터로 재현 불가(DART 재무·섹터 거래대금 필요)
    → 운영 RecommendationSnapshot CSV(load_snapshot_csv) 구간만 측정.

강세 임계 = c85f304 카테고리별 값 미러: 실적>=20 · 수급>=15 · 기술>=13 · 섹터>=14.
"""
import json
import logging
from datetime import datetime, timedelta
from typing import Optional

import pandas as pd

from app.backtest import cost, metrics
from app.backtest.index_bm import fetch_kospi_daily_bm
from app.backtest.investor_flows import (avg_amount_over_streak,
                                         consecutive_net_buy_days, get_flows)
from app.services.chart_pattern_service import fetch_ohlcv
from app.services.regime_service import classify_regime

logger = logging.getLogger(__name__)

HOLD_DAYS = 3                       # SignalOutcome 3거래일 미러
LOOKBACK_CALENDAR_DAYS = 160        # 기술 60거래일 + 여유
CATEGORY_THRESHOLDS = {"earnings": 20, "supplyDemand": 15, "technical": 13, "sectorMomentum": 14}
BAND_EDGES = [(0, 4), (5, 9), (10, 14), (15, 20)]   # 점수밴드(카테고리 /20 공통)
MIN_SAMPLE = 10                     # 미만 = insufficientSample(§4c — 숨기지 않고 명시)


# ==================== 순수: scoreTechnical 미러 ====================

def sma(closes_desc: list, period: int) -> Optional[float]:
    """단순이동평균 — Java calculateMA 미러(prices DESC, 유효값만 평균)."""
    if closes_desc is None or len(closes_desc) < period:
        return None
    vals = [p for p in closes_desc[:period] if p is not None and p > 0]
    return sum(vals) / len(vals) if vals else None


def wilder_rsi(closes_desc: list, period: int = 14) -> Optional[float]:
    """RSI — Java calculateRSI(Wilder smoothing) 미러. prices DESC 입력."""
    if closes_desc is None or len(closes_desc) < period + 1:
        return None
    chrono = list(reversed(closes_desc))
    n = len(chrono)
    total_gain = total_loss = 0.0
    valid_seed = 0
    for i in range(1, min(period + 1, n)):
        cur, prev = chrono[i], chrono[i - 1]
        if cur is None or prev is None or prev == 0:
            continue
        change = cur - prev
        if change > 0:
            total_gain += change
        else:
            total_loss += -change
        valid_seed += 1
    if valid_seed == 0:
        return None
    avg_gain = total_gain / period
    avg_loss = total_loss / period
    for i in range(period + 1, n):
        cur, prev = chrono[i], chrono[i - 1]
        if cur is None or prev is None or prev == 0:
            continue
        change = cur - prev
        gain = change if change > 0 else 0.0
        loss = -change if change < 0 else 0.0
        avg_gain = (avg_gain * (period - 1) + gain) / period
        avg_loss = (avg_loss * (period - 1) + loss) / period
    if avg_loss == 0:
        return 100.0
    if avg_gain == 0:
        return 0.0
    rs = avg_gain / avg_loss
    return 100.0 - 100.0 / (1.0 + rs)


def overheat_penalty(rsi: Optional[float], is_breakout: bool, five_day_return: Optional[float]) -> int:
    """RecommendationService.overheatPenalty 미러 (단일 출처의 사본 — 값 변경 금지)."""
    p = 0
    if rsi is not None:
        if rsi >= 80.0:
            p += 8
        elif rsi >= 75.0:
            p += 5
        elif rsi >= 70.0:
            p += 3
    if is_breakout:
        p += 3
    if five_day_return is not None:
        if five_day_return >= 30.0:
            p += 8
        elif five_day_return >= 20.0:
            p += 5
        elif five_day_return >= 15.0:
            p += 3
    return p


def buy_signal_strength(price: float, ma5, ma20, ma60, rsi,
                        is_golden_cross: bool, is_arranged_up: bool) -> int:
    """TechnicalIndicatorService.calculateBuySignalStrength 미러."""
    score = 50
    if price is not None:
        if ma5 is not None:
            score += 5 if price > ma5 else (-5 if price < ma5 else 0)
        if ma20 is not None:
            score += 10 if price > ma20 else (-10 if price < ma20 else 0)
        if ma60 is not None:
            score += 15 if price > ma60 else (-15 if price < ma60 else 0)
    if is_golden_cross:
        score += 15
    if is_arranged_up:
        score += 15
    if rsi is not None:
        if rsi <= 30:
            score += 20
        elif 40 <= rsi <= 60:
            pass
        elif rsi >= 70:
            score -= 10
    return max(0, min(100, score))


def technical_score_mirror(closes_desc: list) -> Optional[int]:
    """RecommendationService.scoreTechnical 의 '히스토리 충분(>=5)' 경로 미러 — 0~20.

    closes_desc: 최신→과거 종가(최대 60 — Java subList(0,60) 동등). len<5 → None(미측정).
    breakout 은 calculate() 경로에서 항상 미설정 → false 고정(운영과 동일 동작).
    """
    if closes_desc is None or len(closes_desc) < 5:
        return None
    prices = [p for p in closes_desc[:60] if p is not None]
    if len(prices) < 5:
        return None
    ma5, ma20, ma60 = sma(prices, 5), sma(prices, 20), sma(prices, 60)
    rsi = wilder_rsi(prices, 14)

    is_gc = False
    if ma5 is not None and ma20 is not None and len(prices) >= 2:
        prev = prices[1:]
        pma5, pma20 = sma(prev, 5), sma(prev, 20)
        if pma5 is not None and pma20 is not None:
            is_gc = pma5 < pma20 and ma5 > ma20
    is_au = (ma5 is not None and ma20 is not None and ma60 is not None
             and ma5 > ma20 and ma20 > ma60)

    bss = buy_signal_strength(prices[0], ma5, ma20, ma60, rsi, is_gc, is_au)
    ts = min(12, bss * 12 // 100)   # Java 정수 나눗셈 미러

    if rsi is not None:
        if 45 <= rsi <= 55:
            ts += 3
        elif 40 <= rsi <= 60:
            ts += 2
        elif 30 <= rsi < 40:
            ts += 2
        elif rsi < 30:
            ts += 1

    if is_gc and is_au:
        ts += 5
    elif is_gc:
        ts += 3
    elif is_au:
        ts += 2

    five_day = None
    if len(prices) >= 6 and prices[5]:
        five_day = (prices[0] - prices[5]) / prices[5] * 100.0

    ts -= overheat_penalty(rsi, False, five_day)
    return min(20, max(0, ts))


# ==================== 순수: scoreSupplyDemand 미러 ====================

def foreign_streak_points(days: int, avg_eok: float) -> int:
    """외국인 연속매수 가점 — dp(2~3일 정점, 5일+ 후반 축소) + ab(평균금액)."""
    dp = 4 if days >= 5 else 6 if days >= 4 else 10 if days >= 3 else 8
    ab = 4 if avg_eok >= 50 else 2 if avg_eok >= 20 else 1 if avg_eok >= 5 else 0
    return dp + ab


def inst_streak_points(days: int, avg_eok: float) -> int:
    """기관 연속매수 가점."""
    dp = 3 if days >= 5 else 5 if days >= 4 else 8 if days >= 3 else 6
    ab = 3 if avg_eok >= 50 else 1 if avg_eok >= 20 else 0
    return dp + ab


def foreign_topbuy_points(amt_eok: float) -> int:
    """외국인 당일 순매수(연속 아님) 가점 — 상위 10 안에 들 때만 적용."""
    return 8 if amt_eok >= 100 else 6 if amt_eok >= 50 else 4 if amt_eok >= 20 else 2 if amt_eok >= 10 else 0


def inst_topbuy_points(amt_eok: float) -> int:
    return 6 if amt_eok >= 100 else 4 if amt_eok >= 50 else 3 if amt_eok >= 20 else 1 if amt_eok >= 10 else 0


def supply_score_mirror(frgn_days: int, frgn_avg_eok: float, inst_days: int, inst_avg_eok: float,
                        frgn_today_eok: Optional[float] = None, frgn_in_top10: bool = False,
                        inst_today_eok: Optional[float] = None, inst_in_top10: bool = False) -> int:
    """scoreSupplyDemand 미러 — 연속(2일+) 가점, 없으면 당일 상위(top10 내) 폴백. 0~20 캡.

    Java 는 연속매수 리스트/당일 상위를 전시장 조회로 얻는다 — 백테스트는 유니버스 내
    상대 랭킹으로 top10 을 근사(리포트 명시).
    """
    sd = 0
    if frgn_days >= 2:
        sd = min(20, sd + foreign_streak_points(frgn_days, frgn_avg_eok))
    if inst_days >= 2:
        sd = min(20, sd + inst_streak_points(inst_days, inst_avg_eok))
    if sd == 0 and frgn_in_top10 and frgn_today_eok is not None:
        sd = min(20, sd + foreign_topbuy_points(frgn_today_eok))
    if sd == 0 and inst_in_top10 and inst_today_eok is not None:
        sd = min(20, sd + inst_topbuy_points(inst_today_eok))
    return sd


# ==================== 순수: 밴드/regime/집계 ====================

def band_of(score: int) -> str:
    """점수밴드 라벨 — /20 카테고리 공통(0-4/5-9/10-14/15-20)."""
    for lo, hi in BAND_EDGES:
        if lo <= score <= hi:
            return f"{lo}-{hi}"
    return "15-20"


def is_strong(category: str, score: int) -> bool:
    """카테고리별 강세 임계(c85f304 미러) 이상 여부."""
    return score >= CATEGORY_THRESHOLDS[category]


def regime_at(kospi_closes_asc: list, idx: int) -> Optional[str]:
    """regime v1 point-in-time — idx 일 종가 기준(≤idx 데이터만). MA60/MA20 slope(5일)."""
    if idx + 1 < 65:
        return None
    window = kospi_closes_asc[: idx + 1]
    close = window[-1]
    ma60 = sum(window[-60:]) / 60
    ma20_now = sum(window[-20:]) / 20
    ma20_prev = sum(window[-25:-5]) / 20
    return classify_regime(close, ma60, ma20_now, ma20_prev)


def aggregate_by(signals: list, key_fn, min_sample: int = MIN_SAMPLE) -> dict:
    """신호 리스트를 key_fn 버킷으로 집계 — n/hitRate/avgAlpha/avgNet + 표본부족 명시(§4c)."""
    buckets: dict = {}
    for s in signals:
        buckets.setdefault(key_fn(s), []).append(s)
    out = {}
    for k, rows in sorted(buckets.items(), key=lambda kv: str(kv[0])):
        alphas = [r["alpha"] for r in rows if r["alpha"] is not None]
        nets = [r["netPct"] for r in rows]
        out[str(k)] = {
            "n": len(rows),
            "hitRate": round(metrics.hit_rate([r["hit"] for r in rows]), 2),
            "avgAlpha": round(sum(alphas) / len(alphas), 3) if alphas else None,
            "alphaN": len(alphas),
            "avgNet": round(sum(nets) / len(nets), 3) if nets else None,
            "insufficientSample": len(rows) < min_sample,
        }
    return out


def aggregate_category(signals: list, category: str) -> dict:
    """카테고리 하나의 밴드/강세임계/regime 크로스 집계 + Spearman(점수 vs net)."""
    rows = [s for s in signals if s["scores"].get(category) is not None]
    if not rows:
        return {"measured": False, "reason": "데이터 소스 없음(§4c 미측정 — 위장값 생성 안 함)"}
    strong = [s for s in rows if is_strong(category, s["scores"][category])]
    return {
        "measured": True,
        "n": len(rows),
        "threshold": CATEGORY_THRESHOLDS[category],
        "byBand": aggregate_by(rows, lambda s: band_of(s["scores"][category])),
        "byStrong": aggregate_by(rows, lambda s: "strong" if is_strong(category, s["scores"][category]) else "weak"),
        "byRegime": aggregate_by(strong, lambda s: s["regime"] or "UNKNOWN"),
        "byBandAndRegime": aggregate_by(
            rows, lambda s: f"{band_of(s['scores'][category])}|{s['regime'] or 'UNKNOWN'}"),
        "spearmanScoreVsNet": _rounded(metrics.spearman(
            [s["scores"][category] for s in rows], [s["netPct"] for s in rows])),
        "strongPortfolioMdd": round(metrics.portfolio_mdd(
            [(s["exitDate"], s["netPct"]) for s in strong], 10), 3) if strong else None,
    }


def _rounded(v, nd=3):
    return round(v, nd) if v is not None else None


# ==================== 재생 엔진 ====================

def _forward_eval(df: pd.DataFrame, D, kospi: Optional[pd.DataFrame], hold_days: int = HOLD_DAYS,
                  slippage_pct: float = cost.DEFAULT_SLIPPAGE_PCT) -> Optional[dict]:
    """D 신호의 forward 평가 — 진입 D+1 시가 / 청산 +hold 거래일 종가(§ chart_backtest 동일)."""
    fwd = df.loc[df.index > D]
    if len(fwd) < hold_days:
        return None
    entry_open = float(fwd.iloc[0]["시가"])
    exit_close = float(fwd.iloc[hold_days - 1]["종가"])
    if entry_open <= 0:
        return None
    raw_pct = cost.gross_return_pct(entry_open, exit_close)
    net_pct = cost.net_return_pct(entry_open, exit_close, slippage_pct)
    alpha = None
    if kospi is not None:
        try:
            e_day, x_day = fwd.index[0], fwd.index[hold_days - 1]
            if e_day in kospi.index and x_day in kospi.index:
                eo = float(kospi.loc[e_day]["시가"])
                xc = float(kospi.loc[x_day]["종가"])
                if eo > 0:
                    alpha = raw_pct - cost.gross_return_pct(eo, xc)
        except Exception:
            alpha = None
    return {
        "entryDate": fwd.index[0].strftime("%Y-%m-%d"),
        "exitDate": fwd.index[hold_days - 1].strftime("%Y-%m-%d"),
        "rawPct": round(raw_pct, 3),
        "netPct": round(net_pct, 3),
        "alpha": round(alpha, 3) if alpha is not None else None,
        "hit": metrics.is_hit(alpha, raw_pct),
    }


def _daily_top10(day_amounts: dict) -> set:
    """당일 순매수 상위 10 (유니버스 내 근사) — {ticker}. 순매수 양수만."""
    ranked = sorted(((t, a) for t, a in day_amounts.items() if a is not None and a > 0),
                    key=lambda kv: kv[1], reverse=True)
    return {t for t, _ in ranked[:10]}


def fetch_market_data(universe: list, start: str, end: str,
                      csv_flows: Optional[dict] = None, flows_cache_dir: Optional[str] = None,
                      hold_days: int = HOLD_DAYS, with_supply: bool = True) -> tuple:
    """(price, flows, kospi) 일괄 수집 — composite/수급가설/ATR 백테스트가 공유(중복 fetch 방지)."""
    start_dt = datetime.strptime(start, "%Y%m%d")
    end_dt = datetime.strptime(end, "%Y%m%d")
    fetch_start = (start_dt - timedelta(days=LOOKBACK_CALENDAR_DAYS)).strftime("%Y%m%d")
    fetch_end = (end_dt + timedelta(days=hold_days * 3 + 10)).strftime("%Y%m%d")

    kospi = fetch_kospi_daily_bm()
    price: dict = {}
    flows: dict = {}
    for t in universe:
        df = fetch_ohlcv(t, fetch_start, fetch_end)
        if df is None or df.empty:
            continue
        price[t] = df
        if with_supply:
            f = get_flows(t, pd.Timestamp(start_dt) - pd.Timedelta(days=30),
                          pd.Timestamp(end_dt), csv_flows=csv_flows, cache_dir=flows_cache_dir)
            if not f.empty:
                flows[t] = f
    return price, flows, kospi


def replay_composite(universe: list, start: str, end: str,
                     csv_flows: Optional[dict] = None, flows_cache_dir: Optional[str] = None,
                     hold_days: int = HOLD_DAYS, with_supply: bool = True,
                     market_data: Optional[tuple] = None) -> dict:
    """유니버스 × 거래일 point-in-time 재생 — 재현 가능한 카테고리(기술/수급)만 산출.

    반환: {"signals": [...], "kospiSource": str|None, "flowsCoverage": {...}}
    signals 행: ticker/date/scores{technical,supplyDemand}/regime/forward 평가.
    market_data: fetch_market_data 결과 재사용(수급가설/ATR 러너와 공유 시).
    """
    start_dt = datetime.strptime(start, "%Y%m%d")
    end_dt = datetime.strptime(end, "%Y%m%d")

    price, flows, kospi = market_data if market_data is not None else fetch_market_data(
        universe, start, end, csv_flows=csv_flows, flows_cache_dir=flows_cache_dir,
        hold_days=hold_days, with_supply=with_supply)
    kospi_closes = kospi["종가"].astype(float).tolist() if kospi is not None else []
    kospi_dates = list(kospi.index) if kospi is not None else []
    kospi_pos = {d: i for i, d in enumerate(kospi_dates)}

    signals = []
    for t, df in price.items():
        sig_days = [d for d in df.index if start_dt <= d <= end_dt]
        f = flows.get(t)
        f_dates = list(f.index) if f is not None else []
        f_pos = {d: i for i, d in enumerate(f_dates)}
        frgn_series = f["frgn_net_eok"].tolist() if f is not None else []
        inst_series = f["inst_net_eok"].tolist() if f is not None else []

        for D in sig_days:
            past = df.loc[:D]
            assert past.index.max() <= D            # look-ahead 물리 차단
            closes_desc = list(reversed(past["종가"].astype(float).tolist()))[:60]
            tech = technical_score_mirror(closes_desc)

            supply = None
            if f is not None and D in f_pos:
                i = f_pos[D]
                fd = consecutive_net_buy_days(frgn_series, i)
                idn = consecutive_net_buy_days(inst_series, i)
                supply = supply_score_mirror(
                    fd, avg_amount_over_streak(frgn_series, i, fd),
                    idn, avg_amount_over_streak(inst_series, i, idn),
                    frgn_today_eok=frgn_series[i], frgn_in_top10=False,   # top10 은 아래 후처리
                    inst_today_eok=inst_series[i], inst_in_top10=False)

            fwd = _forward_eval(df, D, kospi, hold_days)
            if fwd is None:
                continue

            regime = None
            if D in kospi_pos:
                regime = regime_at(kospi_closes, kospi_pos[D])

            signals.append({"ticker": t, "date": D.strftime("%Y-%m-%d"),
                            "scores": {"technical": tech, "supplyDemand": supply},
                            "regime": regime, **fwd})

    if with_supply and flows:
        _apply_topbuy_fallback(signals, flows)

    return {
        "signals": signals,
        "kospiSource": kospi.attrs.get("source") if kospi is not None else None,
        "flowsCoverage": {"tickersWithFlows": len(flows), "tickersWithPrice": len(price)},
    }


def _apply_topbuy_fallback(signals: list, flows: dict):
    """수급 '당일 순매수 상위10' 폴백 — 날짜별 유니버스-내 랭킹으로 supply=0 행 재채점."""
    by_day_frgn: dict = {}
    by_day_inst: dict = {}
    for t, f in flows.items():
        for d, row in f.iterrows():
            key = d.strftime("%Y-%m-%d")
            by_day_frgn.setdefault(key, {})[t] = row["frgn_net_eok"]
            by_day_inst.setdefault(key, {})[t] = row["inst_net_eok"]
    top_frgn = {d: _daily_top10(m) for d, m in by_day_frgn.items()}
    top_inst = {d: _daily_top10(m) for d, m in by_day_inst.items()}
    for s in signals:
        if s["scores"].get("supplyDemand") != 0:
            continue
        d, t = s["date"], s["ticker"]
        f_amt = by_day_frgn.get(d, {}).get(t)
        i_amt = by_day_inst.get(d, {}).get(t)
        s["scores"]["supplyDemand"] = supply_score_mirror(
            0, 0.0, 0, 0.0,
            frgn_today_eok=f_amt, frgn_in_top10=t in top_frgn.get(d, set()),
            inst_today_eok=i_amt, inst_in_top10=t in top_inst.get(d, set()))


# ==================== 운영 스냅샷 CSV 경로 ====================

def load_snapshot_csv(path: str) -> list:
    """운영 RecommendationSnapshot export CSV → 신호 목록(스코어는 저장값 그대로 — 재계산 안 함).

    스키마(헤더 필수, /api/admin/recommendation-snapshots/export 산출과 동일):
    snapshot_date,stock_code,total_score,earnings,supply_demand,technical,sector_momentum
    point-in-time 보장: 스냅샷은 당시 저장된 역사적 기록 자체(미래 데이터 불포함).
    """
    df = pd.read_csv(path, dtype={"stock_code": str})
    df["snapshot_date"] = pd.to_datetime(df["snapshot_date"])
    # 같은 날 여러 배치 스냅샷 → 날짜·종목당 마지막 행(마감 스냅샷 우선)
    df = df.sort_values("snapshot_date").groupby(
        [df["snapshot_date"].dt.date, "stock_code"], as_index=False).last()
    out = []
    for _, r in df.iterrows():
        out.append({
            "ticker": r["stock_code"],
            "date": pd.Timestamp(r["snapshot_date"]).strftime("%Y-%m-%d"),
            "scores": {
                "earnings": int(r["earnings"]),
                "supplyDemand": int(r["supply_demand"]),
                "technical": int(r["technical"]),
                "sectorMomentum": int(r["sector_momentum"]),
            },
            "totalScore": int(r["total_score"]),
        })
    return out


def evaluate_snapshot_signals(snapshot_rows: list, hold_days: int = HOLD_DAYS) -> dict:
    """스냅샷 신호에 forward 평가/regime 부여 — 가격은 pykrx, BM 은 KOSPI 소스."""
    if not snapshot_rows:
        return {"signals": [], "kospiSource": None}
    dates = [datetime.strptime(r["date"], "%Y-%m-%d") for r in snapshot_rows]
    fetch_start = (min(dates) - timedelta(days=10)).strftime("%Y%m%d")
    fetch_end = (max(dates) + timedelta(days=hold_days * 3 + 10)).strftime("%Y%m%d")
    kospi = fetch_kospi_daily_bm()
    kospi_closes = kospi["종가"].astype(float).tolist() if kospi is not None else []
    kospi_pos = {d: i for i, d in enumerate(kospi.index)} if kospi is not None else {}

    price: dict = {}
    signals = []
    for r in snapshot_rows:
        t = r["ticker"]
        if t not in price:
            price[t] = fetch_ohlcv(t, fetch_start, fetch_end)
        df = price[t]
        if df is None or df.empty:
            continue
        D = pd.Timestamp(r["date"])
        if D not in df.index:
            continue
        fwd = _forward_eval(df, D, kospi, hold_days)
        if fwd is None:
            continue
        regime = regime_at(kospi_closes, kospi_pos[D]) if D in kospi_pos else None
        signals.append({**r, "regime": regime, **fwd})
    return {"signals": signals,
            "kospiSource": kospi.attrs.get("source") if kospi is not None else None}


# ==================== 리포트 ====================

def build_report(replayed: dict, start: str, end: str, universe_size: int,
                 snapshot_signals: Optional[list] = None, supply_source: str = "naver-approx") -> dict:
    """카테고리별 집계 리포트 — 재현 카테고리(로컬) + 스냅샷 카테고리(있으면) 정직 분리."""
    signals = replayed["signals"]
    categories = {}
    for cat in ("technical", "supplyDemand"):
        categories[cat] = aggregate_category(signals, cat)
    for cat in ("earnings", "sectorMomentum"):
        if snapshot_signals:
            categories[cat] = aggregate_category(snapshot_signals, cat)
            categories[cat]["source"] = "recommendation_snapshot_csv"
        else:
            categories[cat] = {"measured": False,
                               "reason": "가격 데이터로 재현 불가(DART 재무/섹터 거래대금 필요) — "
                                         "운영 RecommendationSnapshot CSV export 주입 시 측정(§4c)"}
    if snapshot_signals:
        # 스냅샷이 있으면 4카테고리 전부 + 총점 등급도 스냅샷 기준으로 추가 측정
        categories["technical_snapshot"] = aggregate_category(snapshot_signals, "technical")
        categories["supplyDemand_snapshot"] = aggregate_category(snapshot_signals, "supplyDemand")

    return {
        "meta": {
            "period": {"start": start, "end": end},
            "universeSize": universe_size,
            "signalCount": len(signals),
            "kospiSource": replayed.get("kospiSource"),
            "supplySource": supply_source,
            "flowsCoverage": replayed.get("flowsCoverage"),
            "holdDays": HOLD_DAYS,
            "hitDefinition": "alpha>=0 AND pct>0 (alpha 없으면 pct>=3%) — SignalOutcome 미러",
            "thresholds": CATEGORY_THRESHOLDS,
            "assumptions": {
                "entry": "D+1 시가", "exit": f"+{HOLD_DAYS}거래일 종가",
                "costModel": "수수료 0.03%+세금 0.18% flat + 슬리피지 0.15% 가격 적용",
                "lookAheadGuard": "df.loc[:D] + assert(≤D)",
                "populationNote": "운영 signal_outcome 은 top10 스냅샷 조건부 — 본 백테스트는 "
                                  "유니버스 전 종목·전 거래일 무조건부(랭킹 선택편향 없음, 모집단 상이)",
                "survivorshipCaveat": "유니버스 = 현재 SectorStockConfig 스냅샷(생존편향 가능, P3-4)",
                "supplyApproxCaveat": "수급 금액 = 네이버 순매매량×종가 근사, top10 = 유니버스-내 랭킹 근사",
                "autocorrelationCaveat": "동일 종목 연속 거래일 신호는 독립 표본이 아님(중첩 보유창)",
            },
        },
        "categories": categories,
    }


def save_report(report: dict, path: str):
    with open(path, "w", encoding="utf-8") as fp:
        json.dump(report, fp, ensure_ascii=False, indent=1)
    logger.info(f"[composite-backtest] 리포트 저장: {path}")
