"""종목코드 매핑 유틸리티"""
import asyncio
from functools import lru_cache

# 주요 종목 코드 → 이름 (폴백/빠른 조회용)
MAJOR_STOCKS = {
    "005930": "삼성전자",
    "000660": "SK하이닉스",
    "035420": "NAVER",
    "035720": "카카오",
    "005380": "현대차",
    "006400": "삼성SDI",
    "068270": "셀트리온",
    "055550": "신한지주",
    "105560": "KB금융",
    "003550": "LG",
    "003670": "포스코퓨처엠",
    "247540": "에코프로비엠",
    "034220": "LG디스플레이",
    "010120": "LS일렉트릭",
    "009150": "삼성전기",
    "032830": "삼성생명",
    "086790": "하나금융지주",
    "034730": "SK",
    "012330": "현대모비스",
    "316140": "우리금융지주",
    "051910": "LG화학",
    "066570": "LG전자",
    "207940": "삼성바이오로직스",
    "373220": "LG에너지솔루션",
    "000270": "기아",
    "028260": "삼성물산",
    "096770": "SK이노베이션",
    "017670": "SK텔레콤",
    "030200": "KT",
    "018260": "삼성에스디에스",
}


def get_stock_name(code: str) -> str:
    """종목코드로 이름 조회 (로컬 캐시 우선)"""
    return MAJOR_STOCKS.get(code, code)


async def get_all_stock_codes() -> dict:
    """pykrx에서 전체 종목코드 조회"""
    from pykrx import stock
    from app.utils.korean_market import get_latest_trading_date

    date = get_latest_trading_date()

    def _fetch():
        tickers = stock.get_market_ticker_list(date, market="ALL")
        result = {}
        for t in tickers:
            name = stock.get_market_ticker_name(t)
            result[t] = name
        return result

    return await asyncio.to_thread(_fetch)
