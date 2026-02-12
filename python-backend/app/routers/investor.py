from fastapi import APIRouter, Query

from app.models.schemas import ok
from app.services import investor_service

router = APIRouter(prefix="/api/v2/investor", tags=["investor"])


@router.get("/top-trades")
async def get_top_trades(
    investorType: str = Query("FOREIGN"),
    limit: int = Query(10),
):
    """투자자별 순매수 TOP N"""
    data = await investor_service.get_top_trades(investorType, limit)
    return ok(data)


@router.get("/consecutive-buy/all")
async def get_consecutive_buy(minDays: int = Query(3)):
    """연속 순매수 종목"""
    data = await investor_service.get_consecutive_buy(minDays)
    return ok(data)


@router.get("/surge/all")
async def get_surge_stocks():
    """수급 급증 종목"""
    data = await investor_service.get_surge_stocks()
    return ok(data)
