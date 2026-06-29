"""차트 추세추종 — 매수후보 타이밍 분석 (pykrx OHLCV 오케스트레이션).

regime_service 패턴 미러: async 진입(캐시) + threadpool 동기연산 + 순수함수(indicators) 결합.
무거운 벌크 OHLCV 조회를 pykrx 로 처리(§4c "비싼 일"). 산식 미검증 → 보조 시그널 전용.
"""
import asyncio
import hashlib
import json
import logging
from datetime import datetime, timedelta
from typing import Optional

import pandas as pd
from pykrx import stock

from app.config import ChartPatternConfig
from app.indicators import (
    moving_average as ma_mod,
    disparity as disp_mod,
    envelope as env_mod,
    support_rebound as sup_mod,
    box_breakout as box_mod,
    timing_score as score_mod,
)
from app.services.cache_service import redis_client
from app.utils.korean_market import get_latest_trading_date, get_cache_ttl

logger = logging.getLogger(__name__)

# MA240 + 박스(box_len+lookahead) + 여유 → 넉넉히 500 캘린더일(≈340 거래일)
LOOKBACK_CALENDAR_DAYS = 500
CACHE_TTL_SEC = 1800  # 30분 (장후 get_cache_ttl 로 6배)


def _cfg_hash(cfg: ChartPatternConfig) -> str:
    raw = json.dumps(cfg.model_dump(), sort_keys=True, default=str)
    return hashlib.md5(raw.encode()).hexdigest()[:8]


def fetch_ohlcv(ticker: str, start: str, end: str) -> Optional[pd.DataFrame]:
    """pykrx 일봉 OHLCV. 실패/빈 데이터면 None(§4c 결측 정직)."""
    try:
        df = stock.get_market_ohlcv(start, end, ticker)
    except Exception as e:
        logger.warning(f"[ChartPattern] OHLCV 조회 실패 {ticker}: {e}")
        return None
    if df is None or df.empty:
        return None
    return df


def _analyze_one(ticker: str, cfg: ChartPatternConfig, start: str, end: str) -> dict:
    df = fetch_ohlcv(ticker, start, end)
    if df is None:
        return {"ticker": ticker, "available": False, "reason": "no_data"}

    closes = df["종가"].astype(float).tolist()
    highs = df["고가"].astype(float).tolist()
    lows = df["저가"].astype(float).tolist()

    longest = max(cfg.ma_periods)
    if len(closes) < longest:
        return {"ticker": ticker, "available": False,
                "reason": f"insufficient_history({len(closes)}/{longest})"}

    # 이동평균 (1)
    mas = [ma_mod.sma(closes, p) for p in cfg.ma_periods]
    aligned = ma_mod.is_aligned(mas)
    strength = ma_mod.alignment_strength(mas)
    alignment_max = len(cfg.ma_periods) - 1
    ma20 = ma_mod.sma(closes, 20)
    ma60 = ma_mod.sma(closes, 60)
    ma240 = ma_mod.sma(closes, 240)

    # 이격도 (2)
    disparity = disp_mod.ma60_ma240_disparity(ma60, ma240)
    overheated = disp_mod.is_overheated(disparity, cfg.disparity_overheat)

    # 엔벨로프 하단 터치 — 현재 봉 (3)
    band = env_mod.lower_band(ma20, cfg.envelope_k)
    envelope_touch = env_mod.lower_touch(lows[-1], band)

    # 위험필터 — window 내 하단 터치 횟수 (5)
    ma20_series = df["종가"].astype(float).rolling(20).mean().tolist()
    touches = env_mod.touch_count_in_window(lows, ma20_series, cfg.envelope_k, cfg.risk_window)
    risk_excluded = env_mod.is_risk_excluded(touches, cfg.risk_touch_max)

    # 중심선 지지 반등 — 현재 봉 (4)
    rebound = sup_mod.center_support_rebound(lows[-1], closes[-1], ma20)

    # 박스 돌파 후 눌림목 (6, A안)
    box = box_mod.evaluate(highs, lows, closes,
                           cfg.box_len, cfg.box_range_max, cfg.breakout_buf,
                           cfg.pullback_tol, cfg.lookahead_m)

    # 타이밍 보조 점수
    score = score_mod.timing_score(
        aligned=aligned, alignment_strength=strength, alignment_max=alignment_max,
        envelope_touch=envelope_touch, center_rebound=rebound,
        box_signal=bool(box.get("signal")), overheated=overheated,
        risk_excluded=risk_excluded)

    return {
        "ticker": ticker,
        "available": True,
        "aligned": aligned,
        "alignmentStrength": strength,
        "alignmentMax": alignment_max,
        "disparity": round(disparity, 2) if disparity is not None else None,
        "overheated": overheated,
        "envelopeTouch": envelope_touch,
        "rebound": rebound,
        "riskExcluded": risk_excluded,
        "touchCount": touches,
        "box": box,
        "timingScore": score.get("score"),
        "scoreMax": score.get("max"),
        "signals": score.get("signals", []),
        "unverified": True,  # 미검증 보조 시그널 — 실거래 신호 아님
    }


async def _analyze_one_cached(ticker: str, cfg: ChartPatternConfig,
                              start: str, end: str, trade_date: str, cfg_h: str) -> dict:
    key = f"chart_timing:{ticker}:{trade_date}:{cfg_h}"
    cached = await redis_client.get(key)
    if cached:
        return cached
    result = await asyncio.to_thread(_analyze_one, ticker, cfg, start, end)
    if result.get("available"):  # 결측/실패는 캐시 안 함 → 다음 기회 재시도
        await redis_client.set(key, result, get_cache_ttl(CACHE_TTL_SEC))
    return result


async def analyze_timing(tickers: list[str], overrides: Optional[dict] = None) -> dict:
    """매수후보 타이밍 분석(종목별). 산식 미검증 — 발굴 보조 시그널 전용.

    반환: {"asOf": str, "unverified": True, "params": {...}, "results": [per-ticker ...]}
    """
    cfg = ChartPatternConfig().merge(overrides)
    if not tickers:
        return {"asOf": get_latest_trading_date(), "unverified": True,
                "params": cfg.model_dump(), "results": []}

    end = datetime.now()
    start = end - timedelta(days=LOOKBACK_CALENDAR_DAYS)
    start_s, end_s = start.strftime("%Y%m%d"), end.strftime("%Y%m%d")
    trade_date = get_latest_trading_date()
    cfg_h = _cfg_hash(cfg)

    # 중복 제거하되 입력 순서 보존
    uniq = list(dict.fromkeys(tickers))
    results = await asyncio.gather(*[
        _analyze_one_cached(t, cfg, start_s, end_s, trade_date, cfg_h) for t in uniq
    ])

    return {
        "asOf": trade_date,
        "unverified": True,
        "params": cfg.model_dump(),
        "results": list(results),
    }
