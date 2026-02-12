from fastapi import APIRouter
from app.models.schemas import ok
from app.utils.korean_market import now_kst, is_market_open

router = APIRouter(prefix="/api/v2", tags=["health"])


@router.get("/health")
async def health_check():
    n = now_kst()
    return ok({
        "status": "UP",
        "service": "python-backend",
        "timestamp": n.isoformat(),
        "marketOpen": is_market_open()
    })
