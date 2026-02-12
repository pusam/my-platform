"""한국 주식시장 운영 시간 / 공휴일 유틸리티"""
from datetime import datetime, time, timedelta
import asyncio


# KST timezone offset
KST_OFFSET = timedelta(hours=9)


def now_kst() -> datetime:
    """현재 KST 시간"""
    from datetime import timezone
    kst = timezone(KST_OFFSET)
    return datetime.now(kst)


def today_str(fmt: str = "%Y%m%d") -> str:
    """오늘 날짜 문자열 (yyyyMMdd)"""
    return now_kst().strftime(fmt)


def is_market_open() -> bool:
    """장 운영 중인지 (평일 09:00~15:30)"""
    n = now_kst()
    if n.weekday() >= 5:  # 토/일
        return False
    t = n.time()
    return time(9, 0) <= t <= time(15, 30)


def is_after_market_close() -> bool:
    """장 마감 이후인지 (15:30 이후)"""
    n = now_kst()
    if n.weekday() >= 5:
        return True
    return n.time() > time(15, 30)


def get_latest_trading_date() -> str:
    """가장 최근 거래일 (yyyyMMdd)"""
    n = now_kst()
    # 주말이면 금요일로
    if n.weekday() == 5:  # 토
        n -= timedelta(days=1)
    elif n.weekday() == 6:  # 일
        n -= timedelta(days=2)
    # 장 시작 전이면 전일
    elif n.time() < time(9, 0):
        n -= timedelta(days=1)
        if n.weekday() == 5:
            n -= timedelta(days=1)
        elif n.weekday() == 6:
            n -= timedelta(days=2)
    return n.strftime("%Y%m%d")


def get_cache_ttl(default_ttl: int) -> int:
    """장중/장후에 따라 TTL 조정"""
    if is_market_open():
        return default_ttl
    return default_ttl * 6  # 장후에는 6배 길게
