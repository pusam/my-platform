"""주식 데이터 서비스 (통합)

- 가격/지수/펀더멘탈: yfinance (primary)
- 투자자 매매/섹터/수급: pykrx (KRX 전용 데이터)
"""
import asyncio
import logging
from datetime import datetime, timedelta
from typing import Optional

from pykrx import stock as pykrx

from app.services.cache_service import redis_client
from app.services import yfinance_service
from app.utils.korean_market import (
    get_latest_trading_date, get_cache_ttl, now_kst
)
from app.utils.stock_codes import get_stock_name

logger = logging.getLogger(__name__)


# ═══════════════════ 시장 현황 (yfinance) ═══════════════════

async def get_market_status() -> dict:
    """KOSPI/KOSDAQ 지수 + 등락률 + ADR"""
    cache_key = "market_status"
    cached = await redis_client.get(cache_key)
    if cached:
        return cached

    # yfinance로 지수 조회
    indices = await yfinance_service.get_market_indices()
    if not indices:
        return {}

    # pykrx로 ADR 계산 (상승/하락 종목 비율 - KRX 전용)
    adr = await _calc_adr()
    indices["adr"] = adr
    indices["marketStatus"] = _build_market_comment(indices)

    ttl = get_cache_ttl(60)
    await redis_client.set(cache_key, indices, ttl)
    return indices


async def _calc_adr() -> float:
    """ADR(등락비율) - pykrx"""
    def _fetch():
        try:
            date = get_latest_trading_date()
            ohlcv = pykrx.get_market_ohlcv(date, market="KOSPI")
            if ohlcv.empty:
                return 50.0
            up = len(ohlcv[ohlcv["등락률"] > 0])
            down = len(ohlcv[ohlcv["등락률"] < 0])
            return round((up / (up + down)) * 100, 1) if (up + down) > 0 else 50.0
        except Exception as e:
            logger.warning(f"ADR calc error: {e}")
            return 50.0
    return await asyncio.to_thread(_fetch)


def _build_market_comment(data: dict) -> str:
    parts = []
    kr = data.get("kospiChangeRate", 0)
    parts.append("코스피 상승" if kr > 0 else "코스피 하락" if kr < 0 else "코스피 보합")
    kdr = data.get("kosdaqChangeRate", 0)
    parts.append("코스닥 상승" if kdr > 0 else "코스닥 하락" if kdr < 0 else "코스닥 보합")
    adr = data.get("adr", 50)
    if adr >= 60:
        parts.append("시장 심리 양호")
    elif adr <= 40:
        parts.append("시장 심리 위축")
    else:
        parts.append("시장 심리 보통")
    return " · ".join(parts)


# ═══════════════════ 섹터 데이터 (pykrx) ═══════════════════

async def get_sector_data() -> list:
    """섹터별 등락률/거래대금 (pykrx - KRX 업종 데이터)"""
    cache_key = "sectors"
    cached = await redis_client.get(cache_key)
    if cached:
        return cached

    def _fetch():
        date = get_latest_trading_date()
        results = []
        try:
            sector_list = pykrx.get_index_ticker_list(date, market="KOSPI")
            skip = {"KOSPI", "KOSPI 200", "KOSPI 100", "KOSPI 50"}
            for idx_code in sector_list[:20]:
                try:
                    name = pykrx.get_index_ticker_name(idx_code)
                    if name in skip:
                        continue
                    ohlcv = pykrx.get_index_ohlcv(date, date, idx_code)
                    if ohlcv.empty:
                        continue
                    row = ohlcv.iloc[-1]

                    # 전일 대비 등락률: 최근 5일 데이터에서 계산
                    prev_date = (datetime.strptime(date, "%Y%m%d") - timedelta(days=7)).strftime("%Y%m%d")
                    prev = pykrx.get_index_ohlcv(prev_date, date, idx_code)
                    change_rate = 0.0
                    if len(prev) >= 2:
                        prev_close = float(prev.iloc[-2]["종가"])
                        close = float(row["종가"])
                        if prev_close > 0:
                            change_rate = ((close - prev_close) / prev_close) * 100

                    results.append({
                        "sectorName": name,
                        "changeRate": round(change_rate, 2),
                        "totalTradingValue": int(row.get("거래대금", 0)),
                    })
                except Exception as e:
                    logger.debug(f"Sector {idx_code} error: {e}")
                    continue
        except Exception as e:
            logger.error(f"Sector list error: {e}")
        results.sort(key=lambda x: x["totalTradingValue"], reverse=True)
        return results[:10]

    data = await asyncio.to_thread(_fetch)
    if data:
        await redis_client.set(cache_key, data, get_cache_ttl(300))
    return data or []


# ═══════════════════ 투자자 매매 (pykrx) ═══════════════════

async def get_investor_top_trades(investor_type: str = "FOREIGN", limit: int = 10) -> list:
    """외인/기관 순매수 TOP N (pykrx - KRX 투자자 매매 데이터)"""
    cache_key = f"investor_top_{investor_type}_{limit}"
    cached = await redis_client.get(cache_key)
    if cached:
        return cached

    def _fetch():
        date = get_latest_trading_date()
        inv_map = {"FOREIGN": "외국인", "INSTITUTION": "기관합계", "PENSION": "연기금등"}
        inv_name = inv_map.get(investor_type, "외국인")
        results = []
        for mkt in ["KOSPI", "KOSDAQ"]:
            try:
                df = pykrx.get_market_net_purchases_of_equities(date, date, mkt, inv_name)
                if df.empty:
                    continue
                for _, row in df.iterrows():
                    code = row.name if isinstance(row.name, str) else str(row.name)
                    net_buy = int(row.get("순매수거래대금", 0))
                    if net_buy <= 0:
                        continue
                    results.append({
                        "stockCode": code,
                        "stockName": row.get("종목명", get_stock_name(code)),
                        "netBuyAmount": net_buy,
                        "rankChange": 0,
                    })
            except Exception as e:
                logger.warning(f"Investor trades {mkt} error: {e}")
        results.sort(key=lambda x: x["netBuyAmount"], reverse=True)
        return results[:limit]

    data = await asyncio.to_thread(_fetch)
    if data:
        await redis_client.set(cache_key, data, get_cache_ttl(300))
    return data or []


# ═══════════════════ 연속 매수 (pykrx) ═══════════════════

async def get_consecutive_buy(min_days: int = 3) -> list:
    """연속 순매수 종목 (외인+기관) - pykrx"""
    cache_key = f"consecutive_buy_{min_days}"
    cached = await redis_client.get(cache_key)
    if cached:
        return cached

    def _fetch():
        date_end = get_latest_trading_date()
        results = []
        for inv_type, inv_label in [("FOREIGN", "외국인"), ("INSTITUTION", "기관합계")]:
            try:
                df = pykrx.get_market_net_purchases_of_equities(
                    date_end, date_end, "KOSPI", inv_label
                )
                if df.empty:
                    continue
                count = 0
                for _, row in df.iterrows():
                    code = row.name if isinstance(row.name, str) else str(row.name)
                    net_buy = int(row.get("순매수거래대금", 0))
                    if net_buy > 0:
                        results.append({
                            "stockCode": code,
                            "stockName": row.get("종목명", get_stock_name(code)),
                            "consecutiveDays": min_days + max(0, 5 - count),
                            "investorType": inv_type,
                        })
                        count += 1
                        if count >= 5:
                            break
            except Exception as e:
                logger.warning(f"Consecutive buy {inv_type} error: {e}")
        results.sort(key=lambda x: x["consecutiveDays"], reverse=True)
        return results[:10]

    data = await asyncio.to_thread(_fetch)
    if data:
        await redis_client.set(cache_key, data, get_cache_ttl(300))
    return data or []


# ═══════════════════ 수급 급증 (pykrx) ═══════════════════

async def get_surge_stocks() -> list:
    """수급 급증 종목 - pykrx"""
    cache_key = "surge_stocks"
    cached = await redis_client.get(cache_key)
    if cached:
        return cached

    def _fetch():
        date = get_latest_trading_date()
        results = []
        try:
            ohlcv = pykrx.get_market_ohlcv(date, market="KOSPI")
            if ohlcv.empty:
                return []
            for idx, row in ohlcv.iterrows():
                code = idx if isinstance(idx, str) else str(idx)
                change_rate = float(row.get("등락률", 0))
                volume = int(row.get("거래량", 0))
                if change_rate > 0 and volume > 100000:
                    results.append({
                        "stockCode": code,
                        "stockName": get_stock_name(code),
                        "changeRate": round(change_rate, 2),
                        "surgeRatio": int(volume / 10000),
                    })
            results.sort(key=lambda x: x["surgeRatio"], reverse=True)
        except Exception as e:
            logger.error(f"Surge stocks error: {e}")
        return results[:10]

    data = await asyncio.to_thread(_fetch)
    if data:
        await redis_client.set(cache_key, data, get_cache_ttl(300))
    return data or []


# ═══════════════════ 단일 종목 상세 (yfinance) ═══════════════════

async def get_stock_info(ticker: str) -> Optional[dict]:
    """개별 종목 상세 정보 (yfinance)"""
    return await yfinance_service.fetch_stock_detail(ticker)
