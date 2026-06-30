"""섹터 상대강도 — '덜 빠지는 섹터' 랭킹 (발굴 유니버스 필터).

섹터지수 = 기존 16섹터 구성원(Java SectorStockConfig 가 POST body 로 전달)의
동일가중 합성지수. 시장(KOSPI)은 regime 과 동일하게 KIS 일봉(Java 경유) 재사용.
산식 미검증 — 발굴 보조 시그널 전용.
"""
import asyncio
import hashlib
import json
import logging
from datetime import datetime, timedelta
from typing import Optional

from app.config import ChartPatternConfig
from app.indicators import sector_strength as ss
from app.services.cache_service import redis_client
from app.services.chart_pattern_service import fetch_ohlcv
from app.utils.index_source import fetch_kospi_daily
from app.utils.korean_market import get_latest_trading_date, get_cache_ttl

logger = logging.getLogger(__name__)

CACHE_TTL_SEC = 1800


def _lookback_window_days(lookback_trading: int) -> int:
    """거래일 lookback 을 충분히 덮는 캘린더일(주말·공휴일 여유 ×2 + 15)."""
    return lookback_trading * 2 + 15


def _ticker_return(ticker: str, start: str, end: str, lookback: int) -> Optional[float]:
    """종목의 lookback 거래일 수익률(%). 결측이면 None(§4c)."""
    df = fetch_ohlcv(ticker, start, end)
    if df is None or len(df) < lookback + 1:
        return None
    closes = df["종가"].astype(float).tolist()
    return ss.pct_return(closes[-1 - lookback], closes[-1])


def _market_return(lookback: int) -> Optional[float]:
    """KOSPI lookback 거래일 수익률(%) — Java KIS 일봉 경유(pykrx 지수 대체). 결측 None(§4c)."""
    df = fetch_kospi_daily(_lookback_window_days(lookback))
    if df is None or len(df) < lookback + 1:
        return None
    closes = df["종가"].astype(float).tolist()
    return ss.pct_return(closes[-1 - lookback], closes[-1])


def _sectors_hash(sectors: dict[str, list[str]]) -> str:
    raw = json.dumps({k: sorted(v) for k, v in sectors.items()}, sort_keys=True)
    return hashlib.md5(raw.encode()).hexdigest()[:8]


def _compute(sectors: dict[str, list[str]], lookback: int, start: str, end: str) -> dict:
    market_ret = _market_return(lookback)
    sector_rel: dict[str, Optional[float]] = {}
    sector_ret_map: dict[str, Optional[float]] = {}
    for sector, members in sectors.items():
        member_returns = [_ticker_return(t, start, end, lookback) for t in members]
        sec_ret = ss.equal_weight_return(member_returns)
        sector_ret_map[sector] = sec_ret
        sector_rel[sector] = ss.relative_strength(sec_ret, market_ret)

    ranked = ss.rank_sectors(sector_rel)
    for row in ranked:
        sret = sector_ret_map.get(row["sector"])
        row["sector_ret"] = round(sret, 2) if sret is not None else None
    return {
        "asOf": get_latest_trading_date(),
        "unverified": True,
        "lookback": lookback,
        "marketReturn": round(market_ret, 2) if market_ret is not None else None,
        "ranked": ranked,
    }


async def rank_sector_strength(sectors: dict[str, list[str]],
                               overrides: Optional[dict] = None) -> dict:
    """섹터 상대강도 랭킹 — 1시간 캐시. 산식 미검증(발굴 보조 시그널 전용)."""
    cfg = ChartPatternConfig().merge(overrides)
    if not sectors:
        return {"asOf": get_latest_trading_date(), "unverified": True,
                "lookback": cfg.sector_lookback, "marketReturn": None, "ranked": []}

    lookback = cfg.sector_lookback
    trade_date = get_latest_trading_date()
    key = f"sector_strength:{trade_date}:{lookback}:{_sectors_hash(sectors)}"
    cached = await redis_client.get(key)
    if cached:
        return cached

    end = datetime.now()
    start = end - timedelta(days=_lookback_window_days(lookback))
    data = await asyncio.to_thread(
        _compute, sectors, lookback, start.strftime("%Y%m%d"), end.strftime("%Y%m%d"))
    if data.get("ranked"):  # 전부 결측이면 캐시 안 함
        await redis_client.set(key, data, get_cache_ttl(CACHE_TTL_SEC))
    return data
