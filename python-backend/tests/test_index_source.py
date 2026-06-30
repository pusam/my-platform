"""KOSPI 지수 소스 어댑터 — Java 응답 → pykrx 동형 DataFrame 변환 (네트워크 없음).

regime/sector 가 기대하는 형태(DatetimeIndex 오름차순 + '종가' 컬럼) 보장 + 결측 graceful(§4c).
"""
import pandas as pd

from app.utils.index_source import to_dataframe


def _body(rows, success=True):
    return {"success": success, "data": rows, "count": len(rows)}


def test_parses_and_sorts_ascending():
    body = _body([
        {"date": "2026-06-30", "open": 3480.0, "high": 3490.0, "low": 3470.0, "close": 3486.5},
        {"date": "2026-06-27", "open": 3450.0, "high": 3460.0, "low": 3440.0, "close": 3455.0},
    ])
    df = to_dataframe(body)
    assert list(df.columns) == ["시가", "고가", "저가", "종가"]
    assert df.index[0] == pd.Timestamp("2026-06-27")    # 오름차순(과거 먼저)
    assert df.index[-1] == pd.Timestamp("2026-06-30")
    assert df["종가"].iloc[-1] == 3486.5
    assert df["시가"].iloc[-1] == 3480.0


def test_skips_missing_close_and_allows_null_ohl():
    body = _body([
        {"date": "2026-06-30", "close": 3486.5},      # 시고저 누락 → None 허용(종가만 필수)
        {"date": "2026-06-29", "close": None},        # 종가 결측 → 제외
        {"date": "", "close": 3400.0},                # 영업일자 결측 → 제외
    ])
    df = to_dataframe(body)
    assert len(df) == 1
    assert df["종가"].iloc[0] == 3486.5
    assert pd.isna(df["시가"].iloc[0])


def test_empty_or_failure_returns_none():
    assert to_dataframe(None) is None
    assert to_dataframe({"success": False, "data": []}) is None
    assert to_dataframe({"success": True, "data": []}) is None
    # data 는 있으나 전부 종가 결측 → None (위장값 금지)
    assert to_dataframe(_body([{"date": "2026-06-30", "close": None}])) is None
