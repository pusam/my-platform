"""차트기법 백테스트 오케스트레이션 — point-in-time 신호 재생 + forward 평가.

look-ahead 차단: 티커당 OHLCV 1회 풀조회 후, 신호일 D 마다 df.loc[:D](≤D 행만)로 신호 산출,
평가는 disjoint 한 df.loc[>D] 만 사용. 진입 D+1 시가 / 청산 +hold 거래일 종가. 비용·hit 은 cost/metrics.
프로덕션과 동일 신호: chart_pattern_service.compute_timing 재사용. unverified — 승격 판단 데이터일 뿐.
"""
import logging
from datetime import datetime, timedelta
from typing import Optional

import pandas as pd
from pykrx import stock

from app.config import ChartPatternConfig
from app.services.chart_pattern_service import compute_timing, fetch_ohlcv
from app.backtest import cost, metrics

logger = logging.getLogger(__name__)

LOOKBACK_CALENDAR_DAYS = 500     # MA240 + 박스 여유 (chart_pattern_service 와 동일)
KOSPI_INDEX_TICKER = "1001"
DEFAULT_HOLD_DAYS = 3
DEFAULT_MIN_SCORE = 1            # timingScore>=1(>0) = 신호 발생 (배포 ChartSignalRanker.topByScore 와 일치)
MAX_SIGNALS_IN_RESPONSE = 200


def _ymd(d: datetime) -> str:
    return d.strftime("%Y%m%d")


def _fetch_index(start: str, end: str) -> Optional[pd.DataFrame]:
    try:
        df = stock.get_index_ohlcv(start, end, KOSPI_INDEX_TICKER)
        return df if df is not None and not df.empty else None
    except Exception as e:
        logger.warning(f"[Backtest] KOSPI 조회 실패: {e}")
        return None


def _bm_return(kospi: pd.DataFrame, entry_date, exit_date):
    """KOSPI 같은 창(진입일 시가 → 청산일 종가) 수익률(%). 결측 None."""
    try:
        if entry_date not in kospi.index or exit_date not in kospi.index:
            return None
        eo = float(kospi.loc[entry_date]["시가"])
        ec = float(kospi.loc[exit_date]["종가"])
        return cost.gross_return_pct(eo, ec) if eo > 0 else None
    except Exception:
        return None


def reconstruct_universe(as_of: str, market: str = "KOSPI", cap: Optional[int] = None) -> list:
    """시점별 유니버스 복원 — as_of 당시 상장 종목(상폐 포함, 생존편향 방어). cap 으로 런타임 제한."""
    try:
        tickers = stock.get_market_ticker_list(as_of, market=market)
    except Exception:
        try:
            tickers = stock.get_market_ticker_list(as_of)
        except Exception as e:
            logger.warning(f"[Backtest] 유니버스 복원 실패: {e}")
            return []
    return list(tickers)[:cap] if cap else list(tickers)


def _replay_one(ticker, df, kospi, start_dt, end_dt, cfg, hold_days, min_score,
                slippage_pct, longest):
    out = []
    signal_dates = [d for d in df.index if start_dt <= d <= end_dt]
    for D in signal_dates:
        gen = df.loc[:D]                       # ≤ D — look-ahead 차단
        assert gen.index.max() <= D            # 방어 단언: 미래 캔들 물리적 배제
        if len(gen) < longest:
            continue
        closes = gen["종가"].astype(float).tolist()
        highs = gen["고가"].astype(float).tolist()
        lows = gen["저가"].astype(float).tolist()
        ma20_series = gen["종가"].astype(float).rolling(20).mean().tolist()

        r = compute_timing(highs, lows, closes, ma20_series, cfg)
        score = r.get("timingScore")
        if r.get("riskExcluded") or score is None or score < min_score:
            continue   # 신호 미발생

        fwd = df.loc[df.index > D]              # > D — gen 과 disjoint, 평가 전용
        if len(fwd) < hold_days:
            continue
        entry_open = float(fwd.iloc[0]["시가"])
        exit_close = float(fwd.iloc[hold_days - 1]["종가"])
        if entry_open <= 0:
            continue

        raw_pct = cost.gross_return_pct(entry_open, exit_close)
        net_pct = cost.net_return_pct(entry_open, exit_close, slippage_pct)

        alpha = None
        if kospi is not None:
            bm = _bm_return(kospi, fwd.index[0], fwd.index[hold_days - 1])
            if bm is not None:
                alpha = raw_pct - bm

        sig_change = ((closes[-1] / closes[-2] - 1) * 100.0
                      if len(closes) >= 2 and closes[-2] else None)

        out.append({
            "ticker": ticker,
            "date": D.strftime("%Y-%m-%d"),
            "timingScore": score,
            "entryOpen": round(entry_open, 2),
            "exitClose": round(exit_close, 2),
            "rawPct": round(raw_pct, 3),
            "netPct": round(net_pct, 3),
            "alpha": round(alpha, 3) if alpha is not None else None,
            "hit": metrics.is_hit(alpha, raw_pct),
            "signalDayChangeRate": round(sig_change, 3) if sig_change is not None else None,
        })
    return out


def replay_timing(universe, start, end, overrides=None, hold_days=DEFAULT_HOLD_DAYS,
                  min_score=DEFAULT_MIN_SCORE, slippage_pct=cost.DEFAULT_SLIPPAGE_PCT):
    """[start,end] 거래일마다 universe 각 종목 타이밍 신호 point-in-time 재생 + forward 평가."""
    cfg = ChartPatternConfig().merge(overrides)
    start_dt = datetime.strptime(start, "%Y%m%d")
    end_dt = datetime.strptime(end, "%Y%m%d")
    fetch_start = _ymd(start_dt - timedelta(days=LOOKBACK_CALENDAR_DAYS))
    fetch_end = _ymd(end_dt + timedelta(days=hold_days * 3 + 10))
    kospi = _fetch_index(fetch_start, fetch_end)
    longest = max(cfg.ma_periods)

    results = []
    for ticker in universe:
        df = fetch_ohlcv(ticker, fetch_start, fetch_end)
        if df is None or len(df) < longest + 1:
            continue
        results.extend(_replay_one(ticker, df, kospi, start_dt, end_dt, cfg,
                                   hold_days, min_score, slippage_pct, longest))
    return results


def _tie_break_overlap(changes):
    """신호일 등락률 분포 — tie-break(changeRate asc) 이중작용 점검(P2-12 #3)."""
    vals = [c for c in changes if c is not None]
    n = len(vals)
    if n == 0:
        return {"n": 0}
    srt = sorted(vals)
    median = srt[n // 2] if n % 2 else (srt[n // 2 - 1] + srt[n // 2]) / 2
    return {
        "n": n,
        "meanChangeRate": round(sum(vals) / n, 3),
        "medianChangeRate": round(median, 3),
        "pctNegative": round(sum(1 for c in vals if c < 0) / n * 100, 1),
        "note": "신호일 등락률이 음수/낮음에 쏠리면 tie-break(changeRate asc)와 이중작용 위험",
    }


def _assumptions(hold_days, slippage_pct):
    return {
        "entry": "D+1 시가(open)",
        "exit": f"진입 후 {hold_days}거래일 종가(close)",
        "holdDays": hold_days,
        "costModel": (f"수수료 {round(cost.COMMISSION_RATE * 2 * 100, 3)}% + 세금 {round(cost.TAX_RATE * 100, 3)}% "
                      f"(flat 차감) + 슬리피지 {slippage_pct}% (진입/청산 가격 적용)"),
        "hitDefinition": "alpha>=0 AND pct>0 (alpha 없으면 pct>=3%) — SignalOutcome 정의 미러(raw, 비용 미반영)",
        "survivorshipCaveat": "deployed 모드 = 현재 생존 유니버스(생존편향 가능) → reconstructed 모드 교차검증 권장",
        "lookAheadGuard": "신호 df.loc[:D](≤D) / 평가 df.loc[>D], assert gen.index.max()<=D",
    }


def backtest_timing(universe, start, end, overrides=None, hold_days=DEFAULT_HOLD_DAYS,
                    min_score=DEFAULT_MIN_SCORE, slippage_pct=cost.DEFAULT_SLIPPAGE_PCT) -> dict:
    """타이밍 백테스트 집계 — 적중률/평균순손익/MDD/Sharpe + tie-break 겹침 + 가정 echo."""
    signals = sorted(replay_timing(universe, start, end, overrides, hold_days, min_score, slippage_pct),
                     key=lambda s: s["date"])   # 날짜순(MDD 누적)
    net = [s["netPct"] for s in signals]
    agg = metrics.aggregate(net)
    agg["hitRate"] = round(metrics.hit_rate([s["hit"] for s in signals]), 2)
    return {
        "signalCount": len(signals),
        "aggregate": agg,
        "tieBreakOverlap": _tie_break_overlap([s["signalDayChangeRate"] for s in signals]),
        "assumptions": _assumptions(hold_days, slippage_pct),
        "signals": signals[:MAX_SIGNALS_IN_RESPONSE],
        "unverified": True,
    }


def _rank_sectors_at(sectors, price, kospi, D, lookback, hold_days):
    """D 시점 섹터별 rel-강도(과거 lookback) + forward(D+1 시가→+hold 종가) 수익률. point-in-time."""
    bm_past = _bm_return_lookback(kospi, D, lookback)
    out = []
    for sector, codes in sectors.items():
        rels, fwds = [], []
        for t in codes:
            df = price.get(t)
            if df is None:
                continue
            past = df.loc[:D]
            if len(past) < lookback + 1:
                continue
            c = past["종가"].astype(float).tolist()
            if c[-1 - lookback]:
                rels.append((c[-1] / c[-1 - lookback] - 1) * 100.0)
            fwd = df.loc[df.index > D]
            if len(fwd) >= hold_days:
                eo = float(fwd.iloc[0]["시가"])
                ec = float(fwd.iloc[hold_days - 1]["종가"])
                if eo > 0:
                    fwds.append(cost.gross_return_pct(eo, ec))
        if not rels:
            continue
        sec_ret = sum(rels) / len(rels)
        rel = sec_ret - bm_past if bm_past is not None else None
        fwd_ret = sum(fwds) / len(fwds) if fwds else None
        if rel is not None:
            out.append((sector, rel, fwd_ret))
    return out


def _bm_return_lookback(kospi, D, lookback):
    try:
        past = kospi.loc[:D]
        c = past["종가"].astype(float).tolist()
        if len(c) < lookback + 1 or not c[-1 - lookback]:
            return None
        return (c[-1] / c[-1 - lookback] - 1) * 100.0
    except Exception:
        return None


def backtest_sector_strength(sectors, start, end, overrides=None,
                             hold_days=DEFAULT_HOLD_DAYS, sample_step=5) -> dict:
    """섹터강도 유효성 — 표본일마다 rel-강도 랭크 → 향후 섹터수익률 → 상위-하위 spread + Spearman."""
    cfg = ChartPatternConfig().merge(overrides)
    lookback = cfg.sector_lookback
    start_dt = datetime.strptime(start, "%Y%m%d")
    end_dt = datetime.strptime(end, "%Y%m%d")
    fetch_start = _ymd(start_dt - timedelta(days=lookback * 2 + 15))
    fetch_end = _ymd(end_dt + timedelta(days=hold_days * 3 + 10))

    kospi = _fetch_index(fetch_start, fetch_end)
    if kospi is None:
        return {"error": "KOSPI 미수집", "unverified": True}

    price = {}
    for t in sorted({c for codes in sectors.values() for c in codes}):
        df = fetch_ohlcv(t, fetch_start, fetch_end)
        if df is not None and not df.empty:
            price[t] = df

    sample_dates = [d for d in kospi.index if start_dt <= d <= end_dt][::sample_step]
    spreads, rank_returns = [], []
    for D in sample_dates:
        ranks = _rank_sectors_at(sectors, price, kospi, D, lookback, hold_days)
        if len(ranks) < 4:
            continue
        ranks_sorted = sorted(ranks, key=lambda x: x[1], reverse=True)   # rel desc('덜 빠지는' 상위)
        half = len(ranks_sorted) // 2
        top = [x[2] for x in ranks_sorted[:half] if x[2] is not None]
        bot = [x[2] for x in ranks_sorted[-half:] if x[2] is not None]
        if top and bot:
            spreads.append(sum(top) / len(top) - sum(bot) / len(bot))
        for i, (_sector, _rel, fwd) in enumerate(ranks_sorted):
            if fwd is not None:
                rank_returns.append((len(ranks_sorted) - i, fwd))   # 높은 rel = 높은 rank num
    corr = metrics.spearman([r for r, _ in rank_returns], [f for _, f in rank_returns])
    return {
        "sampleDays": len(sample_dates),
        "avgTopBottomSpread": round(sum(spreads) / len(spreads), 3) if spreads else None,
        "spearmanRankVsForward": round(corr, 3) if corr is not None else None,
        "note": "spread>0 / 상관>0 이면 '덜 빠지는 상위 섹터'가 실제로 향후 덜 빠짐(필터 유효)",
        "unverified": True,
    }
