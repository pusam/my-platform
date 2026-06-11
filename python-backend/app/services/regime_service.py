"""시장 국면(레짐) 분류 — pykrx KOSPI 일봉 기반.

python-backend 의 유일한 실제 역할 (2026-06-11 재편):
Java/KIS 로는 비싼(레이트리밋·페이징) 지수 히스토리 조회를 pykrx 로 처리해
시장 국면(BULL/BEAR/SIDEWAYS)을 분류한다. Java 가 시그널 기록 시 이 국면을
스냅샷(V32)해 "상승장 적중률 vs 하락장 적중률"을 검증한다.

분류 규칙 (v1 휴리스틱 — 단순·설명가능 우선, 검증 데이터 쌓인 뒤 조정):
  BULL:     종가 > MA60  AND  MA20 이 5거래일 전보다 상승
  BEAR:     종가 < MA60  AND  MA20 이 5거래일 전보다 하락
  SIDEWAYS: 그 외 (혼조)

pykrx 는 KRX 일마감 데이터 — 장중 호출 시 전일 종가 기준 국면.
국면은 저속(수주 단위) 지표라 1시간 캐시 + 전일 기준으로 충분하다.
"""
import asyncio
import logging
from datetime import datetime, timedelta

from pykrx import stock

from app.services.cache_service import redis_client

logger = logging.getLogger(__name__)

KOSPI_INDEX_TICKER = "1001"   # pykrx KOSPI 종합지수
LOOKBACK_CALENDAR_DAYS = 130  # MA60 + 슬로프 5거래일 + 주말/공휴일 여유
MA_LONG = 60
MA_SHORT = 20
SLOPE_TRADING_DAYS = 5
CACHE_TTL_SEC = 3600
CACHE_KEY = "market_regime_kospi"


async def get_current_regime() -> dict:
    """현재 시장 국면 — 1시간 캐시. 계산 불가 시 빈 dict (위장값 금지, §4c)."""
    cached = await redis_client.get(CACHE_KEY)
    if cached:
        return cached

    data = await asyncio.to_thread(_classify)
    if data:
        await redis_client.set(CACHE_KEY, data, CACHE_TTL_SEC)
    return data or {}


def _classify() -> dict:
    try:
        end = datetime.now()
        start = end - timedelta(days=LOOKBACK_CALENDAR_DAYS)
        df = stock.get_index_ohlcv(
            start.strftime("%Y%m%d"), end.strftime("%Y%m%d"), KOSPI_INDEX_TICKER)
    except Exception as e:
        logger.warning(f"[Regime] pykrx KOSPI 조회 실패: {e}")
        return {}

    min_rows = MA_LONG + SLOPE_TRADING_DAYS
    if df is None or len(df) < min_rows:
        logger.warning(f"[Regime] KOSPI 데이터 부족: {0 if df is None else len(df)}건 (필요 {min_rows})")
        return {}

    close = df["종가"]
    ma_short_series = close.rolling(MA_SHORT).mean()
    ma_long_series = close.rolling(MA_LONG).mean()

    last_close = float(close.iloc[-1])
    ma_long = float(ma_long_series.iloc[-1])
    ma_short_now = float(ma_short_series.iloc[-1])
    ma_short_prev = float(ma_short_series.iloc[-1 - SLOPE_TRADING_DAYS])

    regime = classify_regime(last_close, ma_long, ma_short_now, ma_short_prev)

    return {
        "regime": regime,
        "kospiClose": round(last_close, 2),
        "ma20": round(ma_short_now, 2),
        "ma60": round(ma_long, 2),
        "ma20Slope5d": round(ma_short_now - ma_short_prev, 2),
        "asOf": df.index[-1].strftime("%Y-%m-%d"),
        "basis": "KOSPI 종가 vs MA60 + MA20 5거래일 슬로프 (pykrx 일마감)",
    }


def classify_regime(close: float, ma_long: float,
                    ma_short_now: float, ma_short_prev: float) -> str:
    """국면 판정 — 순수 함수 (규칙은 모듈 docstring 참조)."""
    rising = ma_short_now > ma_short_prev
    if close > ma_long and rising:
        return "BULL"
    if close < ma_long and not rising:
        return "BEAR"
    return "SIDEWAYS"
