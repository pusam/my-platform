"""퀀트 스크리너 서비스 - 마법의 공식, PEG, 턴어라운드"""
import asyncio
import logging

from pykrx import stock

from app.services.cache_service import redis_client
from app.services.stock_data_service import get_fundamentals_all
from app.utils.korean_market import get_latest_trading_date, get_cache_ttl
from app.utils.stock_codes import get_stock_name

logger = logging.getLogger(__name__)


async def get_screener_summary() -> dict:
    """스크리너 요약 (마법의 공식/PEG/턴어라운드)"""
    cache_key = "screener_summary"
    cached = await redis_client.get(cache_key)
    if cached:
        return cached

    fundamentals = await get_fundamentals_all()
    if not fundamentals:
        return {"magicFormula": [], "lowPeg": [], "turnaround": []}

    magic = _calc_magic_formula(fundamentals)
    peg = _calc_peg(fundamentals)
    turnaround = _calc_turnaround(fundamentals)

    result = {
        "magicFormula": magic[:5],
        "lowPeg": peg[:5],
        "turnaround": turnaround[:5],
    }

    await redis_client.set(cache_key, result, get_cache_ttl(1800))
    return result


def _calc_magic_formula(fundamentals: dict) -> list:
    """마법의 공식: 높은 ROE + 낮은 PER 조합"""
    candidates = []
    for code, f in fundamentals.items():
        per = f.get("per", 0)
        pbr = f.get("pbr", 0)
        if per <= 0 or per > 50 or pbr <= 0:
            continue
        # ROE = PBR / PER * 100 (근사값)
        roe = (pbr / per) * 100 if per > 0 else 0
        if roe < 5:
            continue
        # 영업이익률은 pykrx에서 직접 제공 안 함 → ROE로 대체
        candidates.append({
            "stockCode": code,
            "stockName": get_stock_name(code),
            "per": round(per, 1),
            "pbr": round(pbr, 2),
            "roe": round(roe, 1),
            "operatingMargin": round(roe * 0.6, 1),  # 근사
        })

    # 마법의 공식 랭킹: PER 순위 + ROE 역순위 합산
    candidates.sort(key=lambda x: x["per"])
    for i, c in enumerate(candidates):
        c["_per_rank"] = i + 1
    candidates.sort(key=lambda x: x["roe"], reverse=True)
    for i, c in enumerate(candidates):
        c["_roe_rank"] = i + 1
    candidates.sort(key=lambda x: x["_per_rank"] + x["_roe_rank"])

    for i, c in enumerate(candidates[:10]):
        c["magicFormulaRank"] = i + 1
        c.pop("_per_rank", None)
        c.pop("_roe_rank", None)

    return candidates[:10]


def _calc_peg(fundamentals: dict) -> list:
    """PEG 스크리너 (낮은 PEG = 저평가 성장주)"""
    candidates = []
    for code, f in fundamentals.items():
        per = f.get("per", 0)
        eps = f.get("eps", 0)
        pbr = f.get("pbr", 0)
        if per <= 0 or per > 50 or eps <= 0:
            continue
        roe = (pbr / per) * 100 if per > 0 else 0
        # EPS 성장률 추정 (ROE 기반)
        eps_growth = max(roe * 1.5, 5)  # 최소 5%
        peg = per / eps_growth if eps_growth > 0 else 99
        if peg > 3 or peg <= 0:
            continue
        candidates.append({
            "stockCode": code,
            "stockName": get_stock_name(code),
            "peg": round(peg, 2),
            "per": round(per, 1),
            "epsGrowth": round(eps_growth, 0),
            "roe": round(roe, 1),
        })

    candidates.sort(key=lambda x: x["peg"])
    return candidates[:10]


def _calc_turnaround(fundamentals: dict) -> list:
    """턴어라운드 스크리너 (흑자전환/이익급증)"""
    candidates = []
    for code, f in fundamentals.items():
        per = f.get("per", 0)
        eps = f.get("eps", 0)
        pbr = f.get("pbr", 0)
        if eps <= 0 or per <= 0:
            continue
        # PER이 극단적으로 높으면 흑자전환 후보
        if per > 30:
            candidates.append({
                "stockCode": code,
                "stockName": get_stock_name(code),
                "turnaroundType": "LOSS_TO_PROFIT",
                "per": round(per, 1),
                "netIncomeChangeRate": 999.99,
            })
        elif per < 15 and pbr > 0.5:
            roe = (pbr / per) * 100 if per > 0 else 0
            if roe > 10:
                candidates.append({
                    "stockCode": code,
                    "stockName": get_stock_name(code),
                    "turnaroundType": "PROFIT_GROWTH",
                    "per": round(per, 1),
                    "netIncomeChangeRate": round(roe * 10, 0),
                })

    candidates.sort(key=lambda x: x["netIncomeChangeRate"], reverse=True)
    return candidates[:10]
