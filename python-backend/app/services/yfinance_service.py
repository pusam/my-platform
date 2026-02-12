"""yfinance 기반 글로벌 지수 서비스"""
import asyncio
import logging

import yfinance as yf

from app.services.cache_service import redis_client
from app.utils.korean_market import get_cache_ttl

logger = logging.getLogger(__name__)


async def get_nasdaq_futures() -> dict:
    """나스닥 선물 (NQ=F)"""
    cache_key = "nasdaq_futures"
    cached = await redis_client.get(cache_key)
    if cached:
        return cached

    def _fetch():
        try:
            ticker = yf.Ticker("NQ=F")
            info = ticker.fast_info
            price = info.get("lastPrice", 0) or info.get("regularMarketPrice", 0)
            prev = info.get("previousClose", 0) or info.get("regularMarketPreviousClose", 0)
            change_rate = ((price - prev) / prev * 100) if prev else 0
            return {
                "price": f"{price:,.2f}",
                "changeRate": round(change_rate, 2),
            }
        except Exception as e:
            logger.warning(f"Nasdaq futures error: {e}")
            return None

    data = await asyncio.to_thread(_fetch)
    if data:
        await redis_client.set(cache_key, data, get_cache_ttl(120))
    return data or {"price": "0", "changeRate": 0}


async def get_sp500_futures() -> dict:
    """S&P 500 선물 (ES=F)"""
    cache_key = "sp500_futures"
    cached = await redis_client.get(cache_key)
    if cached:
        return cached

    def _fetch():
        try:
            ticker = yf.Ticker("ES=F")
            info = ticker.fast_info
            price = info.get("lastPrice", 0) or info.get("regularMarketPrice", 0)
            prev = info.get("previousClose", 0) or info.get("regularMarketPreviousClose", 0)
            change_rate = ((price - prev) / prev * 100) if prev else 0
            return {
                "price": f"{price:,.2f}",
                "changeRate": round(change_rate, 2),
            }
        except Exception as e:
            logger.warning(f"S&P 500 futures error: {e}")
            return None

    data = await asyncio.to_thread(_fetch)
    if data:
        await redis_client.set(cache_key, data, get_cache_ttl(120))
    return data or {"price": "0", "changeRate": 0}
