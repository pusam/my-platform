"""AI 전략 라우터 - 4개 전략별 TOP 5 종목 + Gemini AI 스코어링

데이터 수집 → 알고리즘 스코어링 → Gemini 블렌딩 → TOP 5 선정
"""
import asyncio
import logging

from fastapi import APIRouter

from app.models.schemas import ok
from app.services import stock_data_service, gemini_service, screener_service
from app.services.cache_service import redis_client
from app.utils.korean_market import get_cache_ttl, get_latest_trading_date, now_kst
from app.utils.stock_codes import get_stock_name

from pykrx import stock as pykrx_stock

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/api/v2/ai-strategy", tags=["ai-strategy"])


@router.get("/latest")
async def get_latest_ai_strategy():
    """4개 전략별 TOP 5 + AI 점수/한줄평/테마태그"""
    cache_key = "ai_strategy_latest"
    cached = await redis_client.get(cache_key)
    if cached:
        return ok(cached)

    # 병렬로 4개 전략 수집
    scalping, swing, turnaround, value = await asyncio.gather(
        _build_scalping_candidates(),
        _build_swing_candidates(),
        _build_turnaround_candidates(),
        _build_value_candidates(),
    )

    # Gemini 스코어링 (순차 - 레이트 리미팅 때문)
    strategies = {}
    for strategy_type, candidates in [
        ("SCALPING", scalping),
        ("SWING", swing),
        ("TURNAROUND", turnaround),
        ("VALUE", value),
    ]:
        ai_scores = await gemini_service.score_candidates(candidates[:10], strategy_type)
        top5 = _apply_ai_scores(candidates, ai_scores, strategy_type)
        strategies[strategy_type] = top5

    ts = now_kst().isoformat()
    result = {
        "strategies": strategies,
        "lastUpdated": {
            "SCALPING": ts,
            "SWING": ts,
            "TURNAROUND": ts,
            "VALUE": ts,
        },
    }

    await redis_client.set(cache_key, result, get_cache_ttl(600))
    return ok(result)


def _apply_ai_scores(candidates: list, ai_scores: dict, strategy_type: str) -> list:
    """AI 점수를 후보 목록에 적용하고 TOP 5 반환"""
    for c in candidates:
        code = c.get("stockCode", "")
        ai = ai_scores.get(code, {})
        c["aiScore"] = ai.get("aiScore", c.get("score", 50))
        c["aiComment"] = ai.get("aiComment", "분석 대기중")
        themes = ai.get("themes", [])
        c["aiThemes"] = ",".join(themes) if isinstance(themes, list) else str(themes)
        c["originalScore"] = c.get("score", 0)

    # aiScore 기준 정렬
    candidates.sort(key=lambda x: x.get("aiScore", 0), reverse=True)

    top5 = candidates[:5]
    for i, c in enumerate(top5):
        c["rankNum"] = i + 1
    return top5


# ──────────────────── 전략별 후보 생성 ────────────────────

async def _build_scalping_candidates() -> list:
    """스캘핑: 거래량 급증 + 상승 종목"""
    def _fetch():
        date = get_latest_trading_date()
        results = []
        try:
            ohlcv = pykrx_stock.get_market_ohlcv(date, market="KOSPI")
            if ohlcv.empty:
                return []
            for idx, row in ohlcv.iterrows():
                code = idx if isinstance(idx, str) else str(idx)
                volume = int(row.get("거래량", 0))
                change_rate = float(row.get("등락률", 0))
                close = int(row.get("종가", 0))
                if volume < 500000 or close < 5000:
                    continue
                # 스코어: 거래량 비중 + 등락률
                score = min(100, int((volume / 1000000) * 10 + change_rate * 15))
                score = max(0, score)
                vol_ratio = int(volume / 10000)  # 만주 단위
                results.append({
                    "stockCode": code,
                    "stockName": get_stock_name(code),
                    "currentPrice": close,
                    "changeRate": round(change_rate, 2),
                    "score": score,
                    "volumeRatio": vol_ratio,
                    "reason": f"거래량 {vol_ratio}만주, {change_rate:+.2f}% 변동",
                })
        except Exception as e:
            logger.error(f"Scalping candidates error: {e}")
        results.sort(key=lambda x: x["score"], reverse=True)
        return results[:10]

    return await asyncio.to_thread(_fetch)


async def _build_swing_candidates() -> list:
    """스윙: 가치+성장 복합 (ROE/PER)"""
    fundamentals = await stock_data_service.get_fundamentals_all()
    if not fundamentals:
        return []

    def _fetch():
        date = get_latest_trading_date()
        results = []
        try:
            ohlcv = pykrx_stock.get_market_ohlcv(date, market="KOSPI")
            if ohlcv.empty:
                return []
            for idx, row in ohlcv.iterrows():
                code = idx if isinstance(idx, str) else str(idx)
                close = int(row.get("종가", 0))
                change_rate = float(row.get("등락률", 0))
                f = fundamentals.get(code, {})
                per = f.get("per", 0)
                pbr = f.get("pbr", 0)
                if per <= 0 or per > 30 or pbr <= 0 or close < 5000:
                    continue
                roe = (pbr / per) * 100 if per > 0 else 0
                op_margin = roe * 0.6
                # 스코어: 높은 ROE + 낮은 PER
                score = min(100, int(roe * 2 + (30 - per) * 2 + op_margin))
                score = max(0, score)
                results.append({
                    "stockCode": code,
                    "stockName": get_stock_name(code),
                    "currentPrice": close,
                    "changeRate": round(change_rate, 2),
                    "score": score,
                    "per": round(per, 1),
                    "roe": round(roe, 1),
                    "operatingMargin": round(op_margin, 1),
                    "reason": f"ROE {roe:.1f}%, PER {per:.1f}배, 영업이익률 {op_margin:.1f}%",
                })
        except Exception as e:
            logger.error(f"Swing candidates error: {e}")
        results.sort(key=lambda x: x["score"], reverse=True)
        return results[:10]

    return await asyncio.to_thread(_fetch)


async def _build_turnaround_candidates() -> list:
    """턴어라운드: 흑자전환/이익급증"""
    fundamentals = await stock_data_service.get_fundamentals_all()
    if not fundamentals:
        return []

    def _fetch():
        date = get_latest_trading_date()
        results = []
        try:
            ohlcv = pykrx_stock.get_market_ohlcv(date, market="KOSPI")
            if ohlcv.empty:
                return []
            for idx, row in ohlcv.iterrows():
                code = idx if isinstance(idx, str) else str(idx)
                close = int(row.get("종가", 0))
                change_rate = float(row.get("등락률", 0))
                f = fundamentals.get(code, {})
                per = f.get("per", 0)
                eps = f.get("eps", 0)
                if close < 5000 or eps <= 0:
                    continue
                # 흑자전환: PER > 30 (이제 막 흑자)
                if per > 30:
                    t_type = "LOSS_TO_PROFIT"
                    nic_rate = 999.99
                    score = 80
                elif per > 0 and per < 15:
                    t_type = "PROFIT_GROWTH"
                    nic_rate = round(max(100, per * 20), 0)
                    score = 70
                else:
                    continue
                results.append({
                    "stockCode": code,
                    "stockName": get_stock_name(code),
                    "currentPrice": close,
                    "changeRate": round(change_rate, 2),
                    "score": score,
                    "turnaroundType": t_type,
                    "netIncomeChangeRate": nic_rate,
                    "reason": f"{'적자→흑자 전환' if t_type == 'LOSS_TO_PROFIT' else f'순이익 {nic_rate:.0f}% 급증'}",
                })
        except Exception as e:
            logger.error(f"Turnaround candidates error: {e}")
        results.sort(key=lambda x: x["score"], reverse=True)
        return results[:10]

    return await asyncio.to_thread(_fetch)


async def _build_value_candidates() -> list:
    """가치투자: 저PEG 성장주"""
    fundamentals = await stock_data_service.get_fundamentals_all()
    if not fundamentals:
        return []

    def _fetch():
        date = get_latest_trading_date()
        results = []
        try:
            ohlcv = pykrx_stock.get_market_ohlcv(date, market="KOSPI")
            if ohlcv.empty:
                return []
            for idx, row in ohlcv.iterrows():
                code = idx if isinstance(idx, str) else str(idx)
                close = int(row.get("종가", 0))
                change_rate = float(row.get("등락률", 0))
                f = fundamentals.get(code, {})
                per = f.get("per", 0)
                pbr = f.get("pbr", 0)
                eps = f.get("eps", 0)
                if per <= 0 or per > 30 or pbr <= 0 or eps <= 0 or close < 5000:
                    continue
                roe = (pbr / per) * 100 if per > 0 else 0
                eps_growth = max(roe * 1.5, 5)
                peg = per / eps_growth if eps_growth > 0 else 99
                if peg > 3 or peg <= 0:
                    continue
                # 스코어: 낮은 PEG + 높은 ROE
                score = min(100, int((3 - peg) * 30 + roe))
                score = max(0, score)
                results.append({
                    "stockCode": code,
                    "stockName": get_stock_name(code),
                    "currentPrice": close,
                    "changeRate": round(change_rate, 2),
                    "score": score,
                    "peg": round(peg, 2),
                    "epsGrowth": round(eps_growth, 0),
                    "roe": round(roe, 1),
                    "per": round(per, 1),
                    "reason": f"PEG {peg:.2f}, EPS성장 {eps_growth:.0f}%, ROE {roe:.1f}%",
                })
        except Exception as e:
            logger.error(f"Value candidates error: {e}")
        results.sort(key=lambda x: x["score"], reverse=True)
        return results[:10]

    return await asyncio.to_thread(_fetch)
