"""퀀트 스크리너 서비스 - yfinance 펀더멘탈 기반"""
import logging

from app.services.cache_service import redis_client
from app.services import yfinance_service
from app.utils.korean_market import get_cache_ttl
from app.utils.stock_codes import (
    POOL_SWING, POOL_VALUE, POOL_TURNAROUND, STOCKS, get_stock_name
)

logger = logging.getLogger(__name__)

# 스크리너용 종목 풀 (전략별 합산, 중복 제거)
_SCREENER_POOL = list(set(POOL_SWING + POOL_VALUE + POOL_TURNAROUND))


async def get_screener_summary() -> dict:
    """스크리너 요약 (마법의 공식/PEG/턴어라운드)"""
    cache_key = "screener_summary"
    cached = await redis_client.get(cache_key)
    if cached:
        return cached

    funds = await yfinance_service.fetch_fundamentals_batch(_SCREENER_POOL)
    if not funds:
        return {"magicFormula": [], "lowPeg": [], "turnaround": []}

    magic = _calc_magic_formula(funds)
    peg = _calc_peg(funds)
    turnaround = _calc_turnaround(funds)

    result = {
        "magicFormula": magic[:5],
        "lowPeg": peg[:5],
        "turnaround": turnaround[:5],
    }

    await redis_client.set(cache_key, result, get_cache_ttl(1800))
    return result


def _calc_magic_formula(funds: dict) -> list:
    """마법의 공식: 높은 ROE + 낮은 PER"""
    candidates = []
    for code, f in funds.items():
        per = f.get("per", 0)
        pbr = f.get("pbr", 0)
        roe = f.get("roe", 0)
        op_margin = f.get("operatingMargin", 0)
        if per <= 0 or per > 50 or roe < 5:
            continue
        candidates.append({
            "stockCode": code,
            "stockName": get_stock_name(code),
            "per": per,
            "pbr": pbr,
            "roe": roe,
            "operatingMargin": op_margin,
        })
    # PER 순위 + ROE 역순위
    candidates.sort(key=lambda x: x["per"])
    for i, c in enumerate(candidates):
        c["_pr"] = i
    candidates.sort(key=lambda x: x["roe"], reverse=True)
    for i, c in enumerate(candidates):
        c["_rr"] = i
    candidates.sort(key=lambda x: x["_pr"] + x["_rr"])
    for i, c in enumerate(candidates[:10]):
        c["magicFormulaRank"] = i + 1
        c.pop("_pr", None)
        c.pop("_rr", None)
    return candidates[:10]


def _calc_peg(funds: dict) -> list:
    """PEG 스크리너"""
    candidates = []
    for code, f in funds.items():
        per = f.get("per", 0)
        roe = f.get("roe", 0)
        eps_growth = f.get("epsGrowth", 0)
        if per <= 0 or per > 50:
            continue
        if eps_growth <= 0:
            eps_growth = max(roe * 1.5, 5) if roe > 0 else 0
        if eps_growth <= 0:
            continue
        peg = per / eps_growth
        if peg > 3 or peg <= 0:
            continue
        candidates.append({
            "stockCode": code,
            "stockName": get_stock_name(code),
            "peg": round(peg, 2),
            "per": per,
            "epsGrowth": round(eps_growth, 0),
            "roe": roe,
        })
    candidates.sort(key=lambda x: x["peg"])
    return candidates[:10]


def _calc_turnaround(funds: dict) -> list:
    """턴어라운드 스크리너"""
    candidates = []
    for code, f in funds.items():
        per = f.get("per", 0)
        eps_growth = f.get("epsGrowth", 0)
        rev_growth = f.get("revenueGrowth", 0)
        if per <= 0:
            continue
        if eps_growth > 50:
            candidates.append({
                "stockCode": code,
                "stockName": get_stock_name(code),
                "turnaroundType": "PROFIT_GROWTH",
                "per": per,
                "netIncomeChangeRate": round(eps_growth, 0),
            })
        elif per > 30:
            candidates.append({
                "stockCode": code,
                "stockName": get_stock_name(code),
                "turnaroundType": "LOSS_TO_PROFIT",
                "per": per,
                "netIncomeChangeRate": 999.99,
            })
    candidates.sort(key=lambda x: x["netIncomeChangeRate"], reverse=True)
    return candidates[:10]
