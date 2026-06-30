"""차트기법 백테스트 라우터 — P2-12 검증(승격 판단 데이터). unverified 유지.

POST /api/v2/chart/backtest — 온디맨드(무거움, pykrx 대량 조회). 결과는 데이터일 뿐, 승격은 사람이 결정.
"""
import asyncio
from typing import Any, Optional

from fastapi import APIRouter
from pydantic import BaseModel

from app.models.schemas import ok, error
from app.backtest import chart_backtest_service as bt

router = APIRouter(prefix="/api/v2/chart", tags=["chart-backtest"])


class BacktestRequest(BaseModel):
    start: str                                   # yyyyMMdd
    end: str                                     # yyyyMMdd
    mode: str = "deployed"                       # deployed | reconstructed
    universe: list[str] = []                     # deployed: 16섹터 종목 리스트(중복 제거)
    sectors: Optional[dict[str, list[str]]] = None  # 섹터강도 백테스트용 {섹터:[종목]}
    reconstructAsOf: Optional[str] = None        # reconstructed: 복원 기준일(미지정 시 start)
    market: str = "KOSPI"
    cap: Optional[int] = 80                      # reconstructed 유니버스 상한(런타임)
    params: Optional[dict[str, Any]] = None      # ChartPatternConfig override
    holdDays: int = bt.DEFAULT_HOLD_DAYS
    minScore: int = bt.DEFAULT_MIN_SCORE
    slippagePct: float = bt.cost.DEFAULT_SLIPPAGE_PCT
    kSlots: int = 10                             # 현실적 MDD용 동시보유 상한(균등배분)


@router.post("/backtest")
async def backtest(req: BacktestRequest):
    """차트 타이밍 백테스트(+선택적 섹터강도). 미검증 — 적중률/MDD/Sharpe 데이터 제공만."""
    if req.mode == "reconstructed":
        universe = await asyncio.to_thread(
            bt.reconstruct_universe, req.reconstructAsOf or req.start, req.market, req.cap)
    else:
        universe = list(dict.fromkeys(req.universe))   # 중복 제거, 순서 보존

    if not universe and not req.sectors:
        return error("universe 또는 sectors 가 필요합니다")

    result = {"mode": req.mode, "universeSize": len(universe)}
    if universe:
        result.update(await asyncio.to_thread(
            bt.backtest_timing, universe, req.start, req.end,
            req.params, req.holdDays, req.minScore, req.slippagePct, req.kSlots))
    if req.sectors:
        result["sectorStrength"] = await asyncio.to_thread(
            bt.backtest_sector_strength, req.sectors, req.start, req.end, req.params, req.holdDays)
    return ok(result)
