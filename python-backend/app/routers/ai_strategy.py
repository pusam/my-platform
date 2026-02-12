"""AI 전략 라우터 - yfinance 실시간 데이터 + Gemini AI 스코어링

1. yfinance.download()로 전략별 풀 종목 배치 조회
2. yfinance Ticker.info로 펀더멘탈 조회
3. 알고리즘 스코어링 → Gemini 블렌딩 → TOP 5 선정
"""
import asyncio
import logging

from fastapi import APIRouter

from app.models.schemas import ok
from app.services import yfinance_service, gemini_service
from app.services.cache_service import redis_client
from app.utils.korean_market import get_cache_ttl, now_kst
from app.utils.stock_codes import (
    POOL_SCALPING, POOL_SWING, POOL_TURNAROUND, POOL_VALUE,
    get_stock_name,
)

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/api/v2/ai-strategy", tags=["ai-strategy"])


@router.get("/latest")
async def get_latest_ai_strategy():
    """4개 전략별 TOP 5 + AI 점수/한줄평/테마태그"""
    cache_key = "ai_strategy_latest"
    cached = await redis_client.get(cache_key)
    if cached:
        return ok(cached)

    # 1. 전략별 종목 풀 합치기 → 배치 가격 조회
    all_codes = list(set(POOL_SCALPING + POOL_SWING + POOL_TURNAROUND + POOL_VALUE))
    prices = await yfinance_service.fetch_stocks_batch(all_codes)
    if not prices:
        return ok({"strategies": {}, "lastUpdated": {}})

    # 2. 펀더멘탈 조회 (스윙/가치/턴어라운드 전략에 필요)
    fund_codes = list(set(POOL_SWING + POOL_VALUE + POOL_TURNAROUND))
    funds = await yfinance_service.fetch_fundamentals_batch(fund_codes)

    # 3. 전략별 후보 생성
    scalping = _build_scalping(prices)
    swing = _build_swing(prices, funds)
    turnaround = _build_turnaround(prices, funds)
    value = _build_value(prices, funds)

    # 4. Gemini 스코어링 (순차 - 레이트 리미팅)
    strategies = {}
    for strategy_type, candidates in [
        ("SCALPING", scalping),
        ("SWING", swing),
        ("TURNAROUND", turnaround),
        ("VALUE", value),
    ]:
        if candidates:
            ai_scores = await gemini_service.score_candidates(candidates[:10], strategy_type)
            top5 = _apply_ai_scores(candidates, ai_scores)
        else:
            top5 = []
        strategies[strategy_type] = top5

    ts = now_kst().isoformat()
    result = {
        "strategies": strategies,
        "lastUpdated": {k: ts for k in strategies},
    }

    await redis_client.set(cache_key, result, get_cache_ttl(600))
    return ok(result)


# ──────────────────── 공통: AI 점수 적용 ────────────────────

def _apply_ai_scores(candidates: list, ai_scores: dict) -> list:
    for c in candidates:
        code = c.get("stockCode", "")
        ai = ai_scores.get(code, {})
        c["aiScore"] = ai.get("aiScore", c.get("score", 50))
        c["aiComment"] = ai.get("aiComment", "분석 대기중")
        themes = ai.get("themes", [])
        c["aiThemes"] = ",".join(themes) if isinstance(themes, list) else str(themes)
        c["originalScore"] = c.get("score", 0)
    candidates.sort(key=lambda x: x.get("aiScore", 0), reverse=True)
    for i, c in enumerate(candidates[:5]):
        c["rankNum"] = i + 1
    return candidates[:5]


# ──────────────────── 스캘핑: 거래량 + 등락률 ────────────────────

def _build_scalping(prices: dict) -> list:
    results = []
    for code in POOL_SCALPING:
        p = prices.get(code)
        if not p or p["currentPrice"] <= 0:
            continue
        vol = p.get("volume", 0)
        change = p.get("changeRate", 0)
        vol_ratio = int(vol / 10000) if vol else 0
        score = min(100, max(0, int(vol_ratio * 0.5 + abs(change) * 15)))
        results.append({
            **p,
            "score": score,
            "volumeRatio": vol_ratio,
            "reason": f"거래량 {vol_ratio:,}만주, {change:+.2f}% 변동",
        })
    results.sort(key=lambda x: x["score"], reverse=True)
    return results[:10]


# ──────────────────── 스윙: PER + ROE + 영업이익률 ────────────────────

def _build_swing(prices: dict, funds: dict) -> list:
    results = []
    for code in POOL_SWING:
        p = prices.get(code)
        f = funds.get(code, {})
        if not p or p["currentPrice"] <= 0:
            continue
        per = f.get("per", 0)
        roe = f.get("roe", 0)
        op_margin = f.get("operatingMargin", 0)
        if per <= 0:
            continue
        score = min(100, max(0, int(roe * 2 + max(0, 30 - per) * 2 + op_margin * 0.5)))
        results.append({
            **p,
            "score": score,
            "per": per,
            "roe": roe,
            "operatingMargin": op_margin,
            "reason": f"ROE {roe:.1f}%, PER {per:.1f}배, 영업이익률 {op_margin:.1f}%",
        })
    results.sort(key=lambda x: x["score"], reverse=True)
    return results[:10]


# ──────────────────── 턴어라운드: 이익성장률 ────────────────────

def _build_turnaround(prices: dict, funds: dict) -> list:
    results = []
    for code in POOL_TURNAROUND:
        p = prices.get(code)
        f = funds.get(code, {})
        if not p or p["currentPrice"] <= 0:
            continue
        eps_growth = f.get("epsGrowth", 0)
        rev_growth = f.get("revenueGrowth", 0)
        per = f.get("per", 0)

        if eps_growth > 50:
            t_type = "PROFIT_GROWTH"
            nic_rate = round(eps_growth, 0)
            score = min(100, max(0, int(eps_growth * 0.5 + rev_growth * 0.3)))
        elif per > 30:
            t_type = "LOSS_TO_PROFIT"
            nic_rate = 999.99
            score = 75
        elif per > 0:
            t_type = "PROFIT_GROWTH"
            nic_rate = round(max(eps_growth, rev_growth), 0)
            score = min(100, max(0, int(eps_growth * 0.4 + rev_growth * 0.3 + 20)))
        else:
            continue

        results.append({
            **p,
            "score": score,
            "turnaroundType": t_type,
            "netIncomeChangeRate": nic_rate,
            "reason": f"{'적자→흑자 전환' if t_type == 'LOSS_TO_PROFIT' else f'순이익 {nic_rate:.0f}% 성장'}",
        })
    results.sort(key=lambda x: x["score"], reverse=True)
    return results[:10]


# ──────────────────── 가치투자: PEG + 배당수익률 ────────────────────

def _build_value(prices: dict, funds: dict) -> list:
    results = []
    for code in POOL_VALUE:
        p = prices.get(code)
        f = funds.get(code, {})
        if not p or p["currentPrice"] <= 0:
            continue
        per = f.get("per", 0)
        roe = f.get("roe", 0)
        eps_growth = f.get("epsGrowth", 0)
        div_yield = f.get("dividendYield", 0)

        if per <= 0 or eps_growth <= 0:
            # eps_growth 없으면 ROE 기반 추정
            eps_growth = max(roe * 1.5, 5) if roe > 0 else 5

        peg = per / eps_growth if eps_growth > 0 else 99
        if peg > 5 or peg <= 0:
            continue

        score = min(100, max(0, int((3 - min(peg, 3)) * 25 + roe + div_yield * 5)))
        results.append({
            **p,
            "score": score,
            "peg": round(peg, 2),
            "epsGrowth": round(eps_growth, 0),
            "roe": roe,
            "per": per,
            "reason": f"PEG {peg:.2f}, EPS성장 {eps_growth:.0f}%, ROE {roe:.1f}%",
        })
    results.sort(key=lambda x: x["score"], reverse=True)
    return results[:10]
