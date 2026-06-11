from fastapi import APIRouter

from app.models.schemas import ok, error
from app.services import regime_service

router = APIRouter(prefix="/api/v2", tags=["regime"])


@router.get("/regime/current")
async def get_current_regime():
    """현재 시장 국면 (BULL/BEAR/SIDEWAYS) — Java 가 시그널 기록 시 스냅샷용으로 소비."""
    data = await regime_service.get_current_regime()
    if not data:
        return error("시장 국면을 계산할 수 없습니다 (KOSPI 히스토리 부족/조회 실패)")
    return ok(data)
