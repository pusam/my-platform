"""시장 데이터 집계 서비스 - 섹터, 지수, 글로벌 통합"""
import logging

from app.services import stock_data_service, yfinance_service
from app.services.cache_service import redis_client
from app.utils.korean_market import get_cache_ttl

logger = logging.getLogger(__name__)


async def get_leading_sectors() -> list:
    """주도 섹터 랭킹 (등락률 상위)"""
    cache_key = "leading_sectors"
    cached = await redis_client.get(cache_key)
    if cached:
        return cached

    sectors = await stock_data_service.get_sector_data()
    # 등락률 기준 정렬
    leading = sorted(sectors, key=lambda x: x.get("changeRate", 0), reverse=True)[:5]
    result = [
        {"sectorName": s["sectorName"], "changeRate": s["changeRate"]}
        for s in leading
    ]

    if result:
        await redis_client.set(cache_key, result, get_cache_ttl(300))
    return result


async def get_market_overview() -> dict:
    """시장 전체 요약 (V2 대시보드 MarketMap 섹션용)"""
    status = await stock_data_service.get_market_status()
    sectors = await stock_data_service.get_sector_data()
    nasdaq = await yfinance_service.get_nasdaq_futures()
    leading = await get_leading_sectors()

    return {
        "status": status,
        "sectors": sectors,
        "global": {
            "nasdaqFutures": nasdaq,
            "leadingSectors": leading,
        }
    }
