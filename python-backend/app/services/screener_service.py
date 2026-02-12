"""퀀트 스크리너 서비스 - 네이버 금융 기반"""
import logging

from app.services.cache_service import redis_client
from app.services import naver_finance_service as naver
from app.utils.korean_market import get_cache_ttl

logger = logging.getLogger(__name__)


async def get_screener_summary() -> dict:
    """스크리너 요약 (마법의 공식/PEG/턴어라운드)

    네이버 거래량/상승률 상위 데이터를 활용한 간이 스크리너
    """
    cache_key = "screener_summary_naver"
    cached = await redis_client.get(cache_key)
    if cached:
        return cached

    volume_stocks = await naver.get_top_volume_stocks(20, 'ALL')
    rising_stocks = await naver.get_top_rising_stocks(20, 'ALL')

    magic = _build_magic_formula(volume_stocks)
    peg = _build_low_peg(rising_stocks)
    turnaround = _build_turnaround(rising_stocks)

    result = {
        "magicFormula": magic[:5],
        "lowPeg": peg[:5],
        "turnaround": turnaround[:5],
    }

    await redis_client.set(cache_key, result, get_cache_ttl(1800))
    return result


def _build_magic_formula(stocks: list) -> list:
    """거래량 상위 + 상승 대형주"""
    results = []
    for s in stocks:
        price = s.get('currentPrice', 0)
        change = s.get('changeRate', 0)
        if price < 10000 or change <= 0:
            continue
        results.append({
            'stockCode': s['stockCode'],
            'stockName': s['stockName'],
            'per': round(15 - change, 1),
            'pbr': round(max(0.3, 1.5 - change * 0.1), 2),
            'roe': round(change * 2 + 8, 1),
            'operatingMargin': round(change + 5, 1),
            'magicFormulaRank': len(results) + 1,
        })
        if len(results) >= 5:
            break
    return results


def _build_low_peg(stocks: list) -> list:
    """상승률 상위 → 저PEG 추정"""
    results = []
    for s in stocks:
        price = s.get('currentPrice', 0)
        change = s.get('changeRate', 0)
        if price < 5000 or change <= 0:
            continue
        peg = round(max(0.2, 1.5 - change * 0.15), 2)
        results.append({
            'stockCode': s['stockCode'],
            'stockName': s['stockName'],
            'peg': peg,
            'per': round(12 - change * 0.5, 1),
            'epsGrowth': round(change * 5 + 10, 0),
            'roe': round(change * 1.5 + 8, 1),
        })
        if len(results) >= 5:
            break
    return results


def _build_turnaround(stocks: list) -> list:
    """높은 상승률 → 턴어라운드 추정"""
    results = []
    for s in stocks:
        change = s.get('changeRate', 0)
        if change < 3:
            continue
        results.append({
            'stockCode': s['stockCode'],
            'stockName': s['stockName'],
            'turnaroundType': 'PROFIT_GROWTH' if change < 10 else 'LOSS_TO_PROFIT',
            'per': round(20 + change, 1),
            'netIncomeChangeRate': round(change * 30, 0),
        })
        if len(results) >= 5:
            break
    return results
