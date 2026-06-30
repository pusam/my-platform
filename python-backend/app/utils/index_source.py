"""KOSPI 지수 일봉 소스 — Java(KIS) 내부 엔드포인트 경유.

pykrx 지수 엔드포인트가 KRX 포맷 변경으로 전구간 빈값(2026-06-30)이 되어,
Java 의 KIS 일봉 지수(GET /api/market/index/kospi-daily, 진짜 종합지수 0001)를 소비한다.
반환은 pykrx get_index_ohlcv 와 동형(DatetimeIndex 오름차순 + '시가/고가/저가/종가' 컬럼)이라
regime/sector 의 기존 계산 로직을 그대로 재사용한다. 실패 시 None(위장값 금지, §4c).
"""
import logging
import os
from typing import Optional

import pandas as pd
import requests

logger = logging.getLogger(__name__)

JAVA_BACKEND_URL = os.getenv("JAVA_BACKEND_URL", "http://backend:8080")
_ENDPOINT = "/api/market/index/kospi-daily"
_TIMEOUT_SEC = 5


def fetch_kospi_daily(days: int = 130) -> Optional[pd.DataFrame]:
    """KOSPI 종합지수 일봉 — Java KIS 엔드포인트 호출 후 pykrx 동형 DataFrame. 실패 시 None."""
    try:
        resp = requests.get(JAVA_BACKEND_URL + _ENDPOINT,
                            params={"days": days}, timeout=_TIMEOUT_SEC)
        resp.raise_for_status()
        body = resp.json()
    except Exception as e:
        logger.warning(f"[IndexSource] KOSPI 일봉 조회 실패: {e}")
        return None
    return to_dataframe(body)


def to_dataframe(body: Optional[dict]) -> Optional[pd.DataFrame]:
    """Java 응답 {success, data:[{date,open,high,low,close}]} → 오름차순 DataFrame. 순수(테스트 대상).

    종가 결측 행은 제외(위장값 금지). 전부 결측/실패면 None.
    """
    if not body or not body.get("success") or not body.get("data"):
        return None
    idx, rows = [], []
    for r in body["data"]:
        date = r.get("date")
        close = _to_float(r.get("close"))
        if not date or close is None:
            continue   # 영업일자/종가 결측 → 제외
        idx.append(pd.Timestamp(date))
        rows.append({"시가": _to_float(r.get("open")), "고가": _to_float(r.get("high")),
                     "저가": _to_float(r.get("low")), "종가": close})
    if not rows:
        return None
    return pd.DataFrame(rows, index=pd.DatetimeIndex(idx)).sort_index()


def _to_float(v):
    if v is None:
        return None
    try:
        return float(v)
    except (TypeError, ValueError):
        return None
