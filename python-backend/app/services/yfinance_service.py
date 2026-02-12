"""yfinance 기반 통합 주식 데이터 서비스

- 한국 주식: .KS(코스피) / .KQ(코스닥)
- 글로벌 지수: NQ=F(나스닥), ES=F(S&P500), ^KS11(KOSPI), ^KQ11(KOSDAQ)
"""
import asyncio
import logging
from typing import Optional

import yfinance as yf
import pandas as pd

from app.services.cache_service import redis_client
from app.utils.korean_market import get_cache_ttl
from app.utils.stock_codes import (
    get_stock_name, to_yf_ticker, to_yf_tickers, from_yf_ticker, STOCKS
)

logger = logging.getLogger(__name__)


# ═══════════════════ 한국 주식 (배치) ═══════════════════

async def fetch_stocks_batch(codes: list[str]) -> dict[str, dict]:
    """복수 종목 실시간 데이터를 yfinance 배치로 가져오기

    Returns: { "005930": { stockCode, stockName, currentPrice, changeRate, volume, ... }, ... }
    """
    cache_key = f"yf_batch_{'_'.join(sorted(codes[:5]))}"
    cached = await redis_client.get(cache_key)
    if cached:
        return cached

    yf_tickers = to_yf_tickers(codes)
    ticker_str = " ".join(yf_tickers)

    def _fetch():
        result = {}
        try:
            # 5일치 데이터 다운로드 (전일 대비 등락률 계산용)
            df = yf.download(ticker_str, period="5d", group_by="ticker", progress=False)
            if df.empty:
                logger.warning("yfinance batch download returned empty")
                return {}

            single_ticker = len(codes) == 1

            for code in codes:
                yf_t = to_yf_ticker(code)
                try:
                    if single_ticker:
                        ticker_df = df
                    else:
                        if yf_t not in df.columns.get_level_values(0):
                            continue
                        ticker_df = df[yf_t]

                    if ticker_df.empty or len(ticker_df) < 1:
                        continue

                    latest = ticker_df.iloc[-1]
                    close = float(latest.get("Close", 0))
                    volume = int(latest.get("Volume", 0))
                    open_p = float(latest.get("Open", 0))
                    high = float(latest.get("High", 0))
                    low = float(latest.get("Low", 0))

                    # 전일 대비 등락률
                    if len(ticker_df) >= 2:
                        prev_close = float(ticker_df.iloc[-2].get("Close", 0))
                        change_rate = ((close - prev_close) / prev_close * 100) if prev_close > 0 else 0
                    else:
                        change_rate = 0

                    result[code] = {
                        "stockCode": code,
                        "stockName": get_stock_name(code),
                        "currentPrice": int(close),
                        "changeRate": round(change_rate, 2),
                        "volume": volume,
                        "open": int(open_p),
                        "high": int(high),
                        "low": int(low),
                    }
                except Exception as e:
                    logger.debug(f"Parse {code} error: {e}")
                    continue

        except Exception as e:
            logger.error(f"yfinance batch error: {e}")
        return result

    data = await asyncio.to_thread(_fetch)
    if data:
        await redis_client.set(cache_key, data, get_cache_ttl(60))
    return data


# ═══════════════════ 한국 주식 (개별 상세) ═══════════════════

async def fetch_stock_detail(code: str) -> Optional[dict]:
    """개별 종목 상세: 가격 + 펀더멘탈 (PER/PBR/배당 등)"""
    cache_key = f"yf_detail_{code}"
    cached = await redis_client.get(cache_key)
    if cached:
        return cached

    yf_ticker = to_yf_ticker(code)

    def _fetch():
        try:
            t = yf.Ticker(yf_ticker)

            # fast_info: 빠른 가격 데이터
            fi = t.fast_info
            price = fi.get("lastPrice", 0) or fi.get("regularMarketPrice", 0) or 0
            prev = fi.get("previousClose", 0) or fi.get("regularMarketPreviousClose", 0) or 0
            change_rate = ((price - prev) / prev * 100) if prev > 0 else 0

            # info: 펀더멘탈 (느리지만 PER/PBR 등 포함)
            info = {}
            try:
                info = t.info or {}
            except Exception:
                pass

            return {
                "stockCode": code,
                "stockName": get_stock_name(code),
                "currentPrice": int(price),
                "changeRate": round(change_rate, 2),
                "volume": int(fi.get("lastVolume", 0) or info.get("regularMarketVolume", 0) or 0),
                "open": int(fi.get("open", 0) or info.get("regularMarketOpen", 0) or 0),
                "high": int(fi.get("dayHigh", 0) or info.get("regularMarketDayHigh", 0) or 0),
                "low": int(fi.get("dayLow", 0) or info.get("regularMarketDayLow", 0) or 0),
                "per": round(float(info.get("trailingPE", 0) or 0), 1),
                "pbr": round(float(info.get("priceToBook", 0) or 0), 2),
                "eps": round(float(info.get("trailingEps", 0) or 0), 0),
                "div": round(float(info.get("dividendYield", 0) or 0) * 100, 2),
                "marketCap": int(info.get("marketCap", 0) or 0),
                "fiftyTwoWeekHigh": int(info.get("fiftyTwoWeekHigh", 0) or 0),
                "fiftyTwoWeekLow": int(info.get("fiftyTwoWeekLow", 0) or 0),
            }
        except Exception as e:
            logger.error(f"yfinance detail {code} error: {e}")
            return None

    data = await asyncio.to_thread(_fetch)
    if data:
        await redis_client.set(cache_key, data, get_cache_ttl(60))
    return data


# ═══════════════════ 펀더멘탈 배치 ═══════════════════

async def fetch_fundamentals_batch(codes: list[str]) -> dict[str, dict]:
    """복수 종목 펀더멘탈 (PER/PBR/ROE) 배치 조회

    yfinance Ticker.info는 HTTP 호출이므로 순차 처리하되 캐시 활용
    """
    cache_key = f"yf_funds_{'_'.join(sorted(codes[:5]))}_{len(codes)}"
    cached = await redis_client.get(cache_key)
    if cached:
        return cached

    def _fetch():
        result = {}
        for code in codes:
            yf_t = to_yf_ticker(code)
            try:
                t = yf.Ticker(yf_t)
                info = t.info or {}
                per = float(info.get("trailingPE", 0) or 0)
                pbr = float(info.get("priceToBook", 0) or 0)
                eps = float(info.get("trailingEps", 0) or 0)
                roe = (pbr / per * 100) if per > 0 and pbr > 0 else 0
                result[code] = {
                    "per": round(per, 1),
                    "pbr": round(pbr, 2),
                    "eps": round(eps, 0),
                    "roe": round(roe, 1),
                    "dividendYield": round(float(info.get("dividendYield", 0) or 0) * 100, 2),
                    "operatingMargin": round(float(info.get("operatingMargins", 0) or 0) * 100, 1),
                    "epsGrowth": round(float(info.get("earningsGrowth", 0) or 0) * 100, 1),
                    "revenueGrowth": round(float(info.get("revenueGrowth", 0) or 0) * 100, 1),
                }
            except Exception as e:
                logger.debug(f"Fundamentals {code} error: {e}")
                continue
        return result

    data = await asyncio.to_thread(_fetch)
    if data:
        await redis_client.set(cache_key, data, get_cache_ttl(1800))
    return data


# ═══════════════════ KOSPI / KOSDAQ 지수 ═══════════════════

async def get_market_indices() -> dict:
    """KOSPI/KOSDAQ 지수 (yfinance)"""
    cache_key = "yf_market_indices"
    cached = await redis_client.get(cache_key)
    if cached:
        return cached

    def _fetch():
        result = {}
        for name, symbol in [("kospi", "^KS11"), ("kosdaq", "^KQ11")]:
            try:
                t = yf.Ticker(symbol)
                fi = t.fast_info
                price = fi.get("lastPrice", 0) or fi.get("regularMarketPrice", 0) or 0
                prev = fi.get("previousClose", 0) or fi.get("regularMarketPreviousClose", 0) or 0
                change_rate = ((price - prev) / prev * 100) if prev > 0 else 0
                result[f"{name}Index"] = f"{price:,.2f}"
                result[f"{name}ChangeRate"] = round(change_rate, 2)
            except Exception as e:
                logger.warning(f"{name} index error: {e}")
        return result

    data = await asyncio.to_thread(_fetch)
    if data:
        await redis_client.set(cache_key, data, get_cache_ttl(60))
    return data or {}


# ═══════════════════ 글로벌 선물 ═══════════════════

async def get_nasdaq_futures() -> dict:
    """나스닥 선물 (NQ=F)"""
    cache_key = "nasdaq_futures"
    cached = await redis_client.get(cache_key)
    if cached:
        return cached

    def _fetch():
        try:
            t = yf.Ticker("NQ=F")
            fi = t.fast_info
            price = fi.get("lastPrice", 0) or fi.get("regularMarketPrice", 0) or 0
            prev = fi.get("previousClose", 0) or fi.get("regularMarketPreviousClose", 0) or 0
            change_rate = ((price - prev) / prev * 100) if prev else 0
            return {"price": f"{price:,.2f}", "changeRate": round(change_rate, 2)}
        except Exception as e:
            logger.warning(f"Nasdaq futures error: {e}")
            return None

    data = await asyncio.to_thread(_fetch)
    if data:
        await redis_client.set(cache_key, data, get_cache_ttl(120))
    return data or {"price": "0", "changeRate": 0}


async def get_sp500_futures() -> dict:
    """S&P 500 선물 (ES=F)"""
    cache_key = "sp500_futures"
    cached = await redis_client.get(cache_key)
    if cached:
        return cached

    def _fetch():
        try:
            t = yf.Ticker("ES=F")
            fi = t.fast_info
            price = fi.get("lastPrice", 0) or fi.get("regularMarketPrice", 0) or 0
            prev = fi.get("previousClose", 0) or fi.get("regularMarketPreviousClose", 0) or 0
            change_rate = ((price - prev) / prev * 100) if prev else 0
            return {"price": f"{price:,.2f}", "changeRate": round(change_rate, 2)}
        except Exception as e:
            logger.warning(f"S&P 500 futures error: {e}")
            return None

    data = await asyncio.to_thread(_fetch)
    if data:
        await redis_client.set(cache_key, data, get_cache_ttl(120))
    return data or {"price": "0", "changeRate": 0}
