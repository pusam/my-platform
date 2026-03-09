"""AI 전략 라우터 - 네이버 금융 실시간 데이터 + Gemini AI 한줄평

1. 네이버 금융 크롤링으로 실시간 Top 종목 수집
2. Gemini에 종목명 전달 → 한줄평 + 테마 태그
3. 4개 전략별 TOP 5 반환
"""
import asyncio
import logging

from fastapi import APIRouter

from app.models.schemas import ok
from app.services import naver_finance_service as naver
from app.services import gemini_service
from app.services.cache_service import redis_client
from app.utils.korean_market import get_cache_ttl, now_kst

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/api/v2/ai-strategy", tags=["ai-strategy"])


@router.get("/latest")
async def get_latest_ai_strategy():
    """4개 전략별 TOP 5 + AI 한줄평/테마태그"""
    cache_key = "ai_strategy_naver"
    cached = await redis_client.get(cache_key)
    if cached:
        return ok(cached)

    # 1. 네이버에서 실시간 데이터 수집 (병렬)
    vol_kospi, vol_kosdaq, rise_kospi, rise_kosdaq = await asyncio.gather(
        naver.get_top_volume_stocks(15, 'KOSPI'),
        naver.get_top_volume_stocks(10, 'KOSDAQ'),
        naver.get_top_rising_stocks(15, 'KOSPI'),
        naver.get_top_rising_stocks(10, 'KOSDAQ'),
    )

    # 2. 전략별 후보 생성
    scalping = _build_scalping(vol_kospi + vol_kosdaq)
    swing = _build_swing(rise_kospi)
    turnaround = _build_turnaround(rise_kosdaq)
    value = _build_value(vol_kospi)

    # 3. Gemini AI 한줄평 (순차 - 레이트 리미팅)
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

    await redis_client.set(cache_key, result, get_cache_ttl(300))
    return ok(result)


# ──────────────────── AI 점수 적용 ────────────────────

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


# ──────────────────── 스캘핑: 거래량 상위 ────────────────────

def _build_scalping(stocks: list) -> list:
    results = []
    seen = set()
    for s in stocks:
        code = s.get('stockCode', '')
        if code in seen:
            continue
        seen.add(code)
        vol = s.get('volume', 0)
        change = s.get('changeRate', 0)
        vol_ratio = int(vol / 10000) if vol else 0
        score = min(100, max(0, int(vol_ratio * 0.3 + abs(change) * 10)))
        results.append({
            'stockCode': code,
            'stockName': s.get('stockName', ''),
            'currentPrice': s.get('currentPrice', 0),
            'changeRate': change,
            'volume': vol,
            'score': score,
            'volumeRatio': vol_ratio,
            'reason': f"거래량 {vol_ratio:,}만주, {change:+.2f}% 변동",
        })
    results.sort(key=lambda x: x['score'], reverse=True)
    return results[:10]


# ──────────────────── 스윙: KOSPI 상승률 상위 ────────────────────

def _build_swing(stocks: list) -> list:
    results = []
    seen = set()
    for s in stocks:
        code = s.get('stockCode', '')
        if code in seen:
            continue
        seen.add(code)
        change = s.get('changeRate', 0)
        price = s.get('currentPrice', 0)
        if price < 5000:
            continue
        score = min(100, max(0, int(change * 8 + 30)))
        results.append({
            'stockCode': code,
            'stockName': s.get('stockName', ''),
            'currentPrice': price,
            'changeRate': change,
            'volume': s.get('volume', 0),
            'score': score,
            'reason': f"KOSPI 상승률 +{change:.2f}%",
        })
    results.sort(key=lambda x: x['score'], reverse=True)
    return results[:10]


# ──────────────────── 턴어라운드: KOSDAQ 상승률 상위 ────────────────────

def _build_turnaround(stocks: list) -> list:
    results = []
    seen = set()
    for s in stocks:
        code = s.get('stockCode', '')
        if code in seen:
            continue
        seen.add(code)
        change = s.get('changeRate', 0)
        price = s.get('currentPrice', 0)
        if price < 3000:
            continue
        score = min(100, max(0, int(change * 7 + 25)))
        results.append({
            'stockCode': code,
            'stockName': s.get('stockName', ''),
            'currentPrice': price,
            'changeRate': change,
            'volume': s.get('volume', 0),
            'score': score,
            'turnaroundType': 'PROFIT_GROWTH',
            'netIncomeChangeRate': round(change * 10, 0),
            'reason': f"KOSDAQ 상승률 +{change:.2f}%",
        })
    results.sort(key=lambda x: x['score'], reverse=True)
    return results[:10]


# ──────────────────── 가치투자: KOSPI 대형주 거래량 상위 ────────────────────

def _build_value(stocks: list) -> list:
    results = []
    seen = set()
    for s in stocks:
        code = s.get('stockCode', '')
        if code in seen:
            continue
        seen.add(code)
        price = s.get('currentPrice', 0)
        change = s.get('changeRate', 0)
        if price < 10000:
            continue
        score = min(100, max(0, int(50 + change * 5)))
        results.append({
            'stockCode': code,
            'stockName': s.get('stockName', ''),
            'currentPrice': price,
            'changeRate': change,
            'volume': s.get('volume', 0),
            'score': score,
            'reason': f"KOSPI 대형주, {change:+.2f}%",
        })
    results.sort(key=lambda x: x['score'], reverse=True)
    return results[:10]
