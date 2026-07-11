"""섹터 상대강도 — '덜 빠지는 섹터' 랭킹 (발굴 유니버스 필터).

섹터지수 = 기존 16섹터 구성원(Java SectorStockConfig 가 POST body 로 전달)의
동일가중 합성지수. 시장(KOSPI)은 regime 과 동일하게 KIS 일봉(Java 경유) 재사용.
산식 미검증 — 발굴 보조 시그널 전용.
"""
import asyncio
import hashlib
import json
import logging
from concurrent.futures import ThreadPoolExecutor
from datetime import timedelta
from typing import Optional

from app.config import ChartPatternConfig
from app.indicators import sector_strength as ss
from app.services.cache_service import redis_client
from app.services.chart_pattern_service import fetch_ohlcv
from app.utils.index_source import fetch_kospi_daily
from app.utils.korean_market import get_latest_trading_date, get_cache_ttl, now_kst

logger = logging.getLogger(__name__)

CACHE_TTL_SEC = 1800
# 멤버 종목 OHLCV 병렬 fetch 동시성 상한 — KRX 레이트리밋 대비 보수적(순차 134콜 t≈7.8s가 Java 8s 타임아웃
# 근접이라 병렬로 헤드룸 확보). _ticker_return 은 무상태(스레드안전). 8~12 권장, KRX 부담 고려 8.
MAX_FETCH_WORKERS = 8


def _lookback_window_days(lookback_trading: int) -> int:
    """거래일 lookback 을 충분히 덮는 캘린더일(주말·공휴일 여유 ×2 + 15)."""
    return lookback_trading * 2 + 15


def _ticker_return(ticker: str, start: str, end: str, lookback: int) -> Optional[float]:
    """종목의 lookback 거래일 수익률(%). 결측이면 None(§4c).

    예외도 None — fetch 실패는 fetch_ohlcv 가 삼키지만, pykrx 가 예상 밖 포맷(컬럼명 변경 등)으로
    응답하면 파싱(KeyError/ValueError)이 여기서 터진다. ex.map 소비 시점에 재발생해 종목 1개가
    전체 섹터 랭킹을 500 으로 죽이던 것을 해당 종목만 결측 처리로 격리(2026-07-11 감사).
    """
    try:
        df = fetch_ohlcv(ticker, start, end)
        if df is None or len(df) < lookback + 1:
            return None
        closes = df["종가"].astype(float).tolist()
        return ss.pct_return(closes[-1 - lookback], closes[-1])
    except Exception as e:
        logger.warning(f"[SectorStrength] 수익률 계산 실패 {ticker}: {e}")
        return None


def _market_return(lookback: int) -> Optional[float]:
    """KOSPI lookback 거래일 수익률(%) — Java KIS 일봉 경유(pykrx 지수 대체). 결측 None(§4c)."""
    df = fetch_kospi_daily(_lookback_window_days(lookback))
    if df is None or len(df) < lookback + 1:
        return None
    closes = df["종가"].astype(float).tolist()
    return ss.pct_return(closes[-1 - lookback], closes[-1])


def _sectors_hash(sectors: dict[str, list[str]]) -> str:
    raw = json.dumps({k: sorted(v) for k, v in sectors.items()}, sort_keys=True)
    return hashlib.md5(raw.encode()).hexdigest()[:8]


def _fetch_returns_parallel(tickers: list[str], start: str, end: str, lookback: int) -> dict[str, Optional[float]]:
    """유니크 종목별 lookback 수익률 병렬 fetch(스레드풀). _ticker_return 은 무상태(스레드안전).
    결과는 순차 fetch 와 동일(값 결정적, 순서무관 집계) — 속도만 향상. 결측은 None(§4c)."""
    if not tickers:
        return {}
    with ThreadPoolExecutor(max_workers=MAX_FETCH_WORKERS) as ex:
        results = ex.map(lambda t: _ticker_return(t, start, end, lookback), tickers)
        return dict(zip(tickers, results))


def _compute(sectors: dict[str, list[str]], lookback: int, start: str, end: str) -> dict:
    market_ret = _market_return(lookback)
    # 유니크 종목 1회씩 병렬 fetch(중복 섹터 종목 재fetch 방지 + 동시성 상한). 섹터별 집계는 lookup 으로 복원.
    unique = sorted({t for members in sectors.values() for t in members})
    ret_map = _fetch_returns_parallel(unique, start, end, lookback)
    sector_rel: dict[str, Optional[float]] = {}
    sector_ret_map: dict[str, Optional[float]] = {}
    for sector, members in sectors.items():
        member_returns = [ret_map.get(t) for t in members]
        sec_ret = ss.equal_weight_return(member_returns)
        sector_ret_map[sector] = sec_ret
        sector_rel[sector] = ss.relative_strength(sec_ret, market_ret)

    ranked = ss.rank_sectors(sector_rel)
    for row in ranked:
        sret = sector_ret_map.get(row["sector"])
        row["sector_ret"] = round(sret, 2) if sret is not None else None
    return {
        "asOf": get_latest_trading_date(),
        "unverified": True,
        "lookback": lookback,
        "marketReturn": round(market_ret, 2) if market_ret is not None else None,
        "ranked": ranked,
    }


async def rank_sector_strength(sectors: dict[str, list[str]],
                               overrides: Optional[dict] = None) -> dict:
    """섹터 상대강도 랭킹 — 1시간 캐시. 산식 미검증(발굴 보조 시그널 전용)."""
    cfg = ChartPatternConfig().merge(overrides)
    if not sectors:
        return {"asOf": get_latest_trading_date(), "unverified": True,
                "lookback": cfg.sector_lookback, "marketReturn": None, "ranked": []}

    lookback = cfg.sector_lookback
    trade_date = get_latest_trading_date()
    key = f"sector_strength:{trade_date}:{lookback}:{_sectors_hash(sectors)}"
    cached = await redis_client.get(key)
    if cached:
        return cached

    # KST 기준 — 캐시 키/asOf(get_latest_trading_date=KST)와 시간 기준 일치(컨테이너 TZ 미설정=UTC 대비).
    end = now_kst()
    start = end - timedelta(days=_lookback_window_days(lookback))
    data = await asyncio.to_thread(
        _compute, sectors, lookback, start.strftime("%Y%m%d"), end.strftime("%Y%m%d"))
    if data.get("ranked"):  # 전부 결측이면 캐시 안 함
        await redis_client.set(key, data, get_cache_ttl(CACHE_TTL_SEC))
    return data
