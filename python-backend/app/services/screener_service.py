"""퀀트 스크리너 서비스 - 네이버 금융 기반

⚠ 정직성 원칙 (CLAUDE.md §4c — 2026-06-11 점검 수정):
과거엔 PER/PBR/ROE/PEG/epsGrowth/netIncomeChangeRate 를 등락률(changeRate)의
선형식으로 '생성'해 실데이터처럼 반환했음 — 날조 금지. 이 서비스는 네이버
거래량/상승률 상위만으로 만드는 **모멘텀 후보 리스트**이며, 실제 재무 기반
스크리닝(마법의 공식 등)은 Java backend 의 QuantScreenerService 가 담당한다.
응답에는 실측 가능한 필드(가격/등락률)와 선정 기준(basis)만 싣는다.
"""
import logging

from app.services.cache_service import redis_client
from app.services import naver_finance_service as naver
from app.utils.korean_market import get_cache_ttl

logger = logging.getLogger(__name__)


async def get_screener_summary() -> dict:
    """스크리너 요약 — 거래량/상승률 기반 모멘텀 후보 (재무 데이터 아님)"""
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
        # 소비자 주의 플래그 — 재무 기반 스크리닝이 아니라 모멘텀 후보임을 명시
        "dataBasis": "MOMENTUM_ONLY",
    }

    await redis_client.set(cache_key, result, get_cache_ttl(1800))
    return result


def _candidate(s: dict, rank: int, basis: str) -> dict:
    """실측 필드만 담은 후보 항목 — 재무 수치 날조 금지."""
    return {
        'stockCode': s['stockCode'],
        'stockName': s['stockName'],
        'currentPrice': s.get('currentPrice', 0),
        'changeRate': s.get('changeRate', 0),
        'basis': basis,
        'rank': rank,
    }


def _build_magic_formula(stocks: list) -> list:
    """거래량 상위 + 상승 대형주(1만원 이상) — 모멘텀 후보"""
    results = []
    for s in stocks:
        price = s.get('currentPrice', 0)
        change = s.get('changeRate', 0)
        if price < 10000 or change <= 0:
            continue
        results.append(_candidate(s, len(results) + 1, 'VOLUME_TOP_RISING'))
        if len(results) >= 5:
            break
    return results


def _build_low_peg(stocks: list) -> list:
    """상승률 상위 (5천원 이상) — 모멘텀 후보"""
    results = []
    for s in stocks:
        price = s.get('currentPrice', 0)
        change = s.get('changeRate', 0)
        if price < 5000 or change <= 0:
            continue
        results.append(_candidate(s, len(results) + 1, 'RISING_TOP'))
        if len(results) >= 5:
            break
    return results


def _build_turnaround(stocks: list) -> list:
    """급등(+3% 이상) — 모멘텀 후보"""
    results = []
    for s in stocks:
        change = s.get('changeRate', 0)
        if change < 3:
            continue
        results.append(_candidate(s, len(results) + 1, 'SURGE_3PCT'))
        if len(results) >= 5:
            break
    return results
