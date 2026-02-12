from fastapi import APIRouter

from app.models.schemas import ok
from app.services import screener_service, news_service

router = APIRouter(prefix="/api/v2", tags=["research"])


@router.get("/screener/summary")
async def get_screener_summary():
    """스크리너 요약 (마법의 공식 / PEG / 턴어라운드)"""
    data = await screener_service.get_screener_summary()
    return ok(data)


@router.get("/news/today")
async def get_today_news():
    """오늘의 뉴스"""
    data = await news_service.get_today_news()
    return ok(data)
