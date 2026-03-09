"""주식 데이터 서비스 (통합)

- 1차: 네이버 금융 크롤링 (실시간 한국 주식)
- 2차: yfinance 폴백 (글로벌 지수)
"""
import logging
from typing import Optional

from app.services import naver_finance_service as naver
from app.services import yfinance_service
from app.services.cache_service import redis_client
from app.utils.korean_market import get_cache_ttl

logger = logging.getLogger(__name__)


# ═══════════════════ 시장 현황 ═══════════════════

async def get_market_status() -> dict:
    """KOSPI/KOSDAQ 지수 + 등락률"""
    # 1차: 네이버
    try:
        data = await naver.get_market_indices()
        if data and data.get('kospiIndex'):
            return data
    except Exception as e:
        logger.warning(f"Naver market indices failed: {e}")

    # 2차: yfinance
    try:
        return await yfinance_service.get_market_indices()
    except Exception as e:
        logger.warning(f"yfinance market indices failed: {e}")
        return {}


# ═══════════════════ 섹터 데이터 ═══════════════════

async def get_sector_data() -> list:
    """업종별 등락률/거래대금 (네이버 금융 크롤링)"""
    try:
        data = await naver.get_sector_data()
        if data:
            return data
    except Exception as e:
        logger.warning(f"Naver sector data failed: {e}")
    return []


# ═══════════════════ 투자자 매매 ═══════════════════

async def get_investor_top_trades(investor_type: str = 'FOREIGN', limit: int = 10) -> list:
    """외국인/기관 순매수 TOP N (네이버 금융 크롤링)"""
    try:
        data = await naver.get_investor_top_trades(investor_type, limit)
        if data:
            return data
    except Exception as e:
        logger.warning(f"Naver investor trades failed: {e}")
    return []


# ═══════════════════ 연속 매수 ═══════════════════

async def get_consecutive_buy(min_days: int = 3) -> list:
    """연속 순매수 종목 (네이버 기반 추정)"""
    try:
        return await naver.get_consecutive_buy(10)
    except Exception as e:
        logger.warning(f"Naver consecutive buy failed: {e}")
        return []


# ═══════════════════ 수급 급증 ═══════════════════

async def get_surge_stocks() -> list:
    """수급 급증 종목 (네이버 거래량 상위 + 상승)"""
    try:
        return await naver.get_surge_stocks(10)
    except Exception as e:
        logger.warning(f"Naver surge stocks failed: {e}")
        return []


# ═══════════════════ 단일 종목 상세 ═══════════════════

async def get_stock_info(ticker: str) -> Optional[dict]:
    """개별 종목 상세 정보 (yfinance)"""
    return await yfinance_service.fetch_stock_detail(ticker)
