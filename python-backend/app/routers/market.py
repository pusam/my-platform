from fastapi import APIRouter, Query

from app.models.schemas import ok, error
from app.services import stock_data_service, yfinance_service, market_service

router = APIRouter(prefix="/api/v2/market", tags=["market"])


@router.get("/status")
async def get_market_status():
    """KOSPI/KOSDAQ 지수 + 등락률 + ADR"""
    data = await stock_data_service.get_market_status()
    if not data:
        return error("시장 데이터를 가져올 수 없습니다")
    return ok(data)


@router.get("/sectors")
async def get_sectors(period: str = Query("TODAY")):
    """섹터별 등락률/거래대금"""
    data = await stock_data_service.get_sector_data()
    return ok(data)


@router.get("/sectors/leading")
async def get_leading_sectors():
    """주도 섹터 랭킹"""
    data = await market_service.get_leading_sectors()
    return ok(data)


@router.get("/global/nasdaq-futures")
async def get_nasdaq_futures():
    """나스닥 선물"""
    data = await yfinance_service.get_nasdaq_futures()
    return ok(data)


@router.get("/global/sp500-futures")
async def get_sp500_futures():
    """S&P 500 선물"""
    data = await yfinance_service.get_sp500_futures()
    return ok(data)
