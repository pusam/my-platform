"""차트 추세추종 보조 시그널 라우터.

POST /api/v2/chart/timing          — 매수후보 타이밍(정배열·눌림목) 종목별 분석
POST /api/v2/chart/sector-strength — 발굴 섹터 상대강도('덜 빠지는 섹터') 랭킹

산식 미검증 — Java thin client 가 소비해 발굴 탭 보조 시그널(미검증 배지)로만 노출.
실거래/봇/매수후보 랭킹 편입 금지(VERIFICATION_BACKLOG 검증 통과 전).
"""
from typing import Any, Optional

from fastapi import APIRouter
from pydantic import BaseModel

from app.models.schemas import ok, error
from app.services import chart_pattern_service, sector_strength_service

router = APIRouter(prefix="/api/v2/chart", tags=["chart-patterns"])


class TimingRequest(BaseModel):
    tickers: list[str] = []
    params: Optional[dict[str, Any]] = None  # ChartPatternConfig 일부 override


class SectorStrengthRequest(BaseModel):
    sectors: dict[str, list[str]] = {}       # {섹터명: [종목코드,...]} — Java SectorStockConfig
    params: Optional[dict[str, Any]] = None


@router.post("/timing")
async def timing(req: TimingRequest):
    """매수후보 타이밍 보조 분석(종목별). 미검증 — 발굴 보조 시그널 전용."""
    data = await chart_pattern_service.analyze_timing(req.tickers, req.params)
    return ok(data)


@router.post("/sector-strength")
async def sector_strength(req: SectorStrengthRequest):
    """발굴 섹터 상대강도 랭킹('덜 빠지는 섹터'). 미검증 — 발굴 보조 시그널 전용."""
    data = await sector_strength_service.rank_sector_strength(req.sectors, req.params)
    if not data.get("ranked"):
        return error("섹터 상대강도를 계산할 수 없습니다 (OHLCV 조회 실패/데이터 부족)", data)
    return ok(data)
