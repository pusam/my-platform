"""서비스 복원력 — 종목 1개 이상 데이터가 배치 전체를 죽이지 않는다(§4c 결측 정직, 2026-07-11 감사).

배경: pykrx 가 예상 밖 포맷(컬럼명 변경/비숫자 셀)으로 응답하면 '종가' 파싱(KeyError 등)이
per-ticker 함수 밖으로 전파돼 — 섹터강도는 ex.map 소비 시점, 타이밍은 asyncio.gather 에서 —
정상 종목 전부의 결과까지 버리고 엔드포인트가 500 이 났다. 해당 종목만 결측(None/available=false)
처리로 격리한다.
"""
import pandas as pd

import app.services.chart_pattern_service as cps
import app.services.sector_strength_service as sss
from app.config import ChartPatternConfig


def _malformed_df():
    """pykrx 예상 밖 포맷 — '종가' 컬럼 없음(KeyError 유발)."""
    return pd.DataFrame({"Close": [1.0, 2.0, 3.0]})


def _good_df(n=30):
    vals = [float(i) for i in range(1, n + 1)]
    return pd.DataFrame({"종가": vals, "고가": vals, "저가": vals})


# ── 섹터 상대강도 ──────────────────────────────────────────────

def test_ticker_return_malformed_df_returns_none(monkeypatch):
    monkeypatch.setattr(sss, "fetch_ohlcv", lambda t, s, e: _malformed_df())
    assert sss._ticker_return("000001", "20260101", "20260110", 5) is None


def test_fetch_returns_parallel_one_bad_ticker_does_not_abort(monkeypatch):
    def fake_fetch(ticker, start, end):
        return _malformed_df() if ticker == "BAD" else _good_df(11)

    monkeypatch.setattr(sss, "fetch_ohlcv", fake_fetch)
    out = sss._fetch_returns_parallel(["BAD", "GOOD"], "20260101", "20260110", 5)
    assert out["BAD"] is None          # 이상 종목만 결측
    assert out["GOOD"] is not None     # 정상 종목은 살아남는다


# ── 차트 타이밍 ────────────────────────────────────────────────

def test_analyze_one_malformed_df_degrades_to_unavailable(monkeypatch):
    monkeypatch.setattr(cps, "fetch_ohlcv", lambda t, s, e: _malformed_df())
    res = cps._analyze_one("000001", ChartPatternConfig(), "20250101", "20260110")
    assert res["available"] is False
    assert res["reason"] == "analysis_error"


def test_analyze_one_no_data_reason_preserved(monkeypatch):
    monkeypatch.setattr(cps, "fetch_ohlcv", lambda t, s, e: None)
    res = cps._analyze_one("000001", ChartPatternConfig(), "20250101", "20260110")
    assert res["available"] is False
    assert res["reason"] == "no_data"


def test_analyze_one_insufficient_history_reason_preserved(monkeypatch):
    monkeypatch.setattr(cps, "fetch_ohlcv", lambda t, s, e: _good_df(10))
    res = cps._analyze_one("000001", ChartPatternConfig(), "20250101", "20260110")
    assert res["available"] is False
    assert res["reason"].startswith("insufficient_history")
