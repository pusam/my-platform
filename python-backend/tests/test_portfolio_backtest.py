"""포트폴리오 재생(고정 vs ATR세트) 단위테스트 — 네트워크 없음, 합성 일봉.

Java 미러 계약: PositionSizer(축소 전용·결측 폴백) / 가상 브레이커(당일 실현 <= -한도 →
그날 진입만 차단·다음 거래일 자동 해제) / ATR 결측 = 완전 현행 폴백(§4c).
"""
import pandas as pd
from pytest import approx

from app.backtest.portfolio_backtest_service import (atr_set_quantity, compare_verdict,
                                                     replay_portfolio)


def make_df(start: str, bars: list) -> pd.DataFrame:
    """bars = [(시가, 고가, 저가, 종가), ...] — 평일 연속 거래일."""
    idx = pd.bdate_range(start, periods=len(bars))
    return pd.DataFrame(bars, index=idx, columns=["시가", "고가", "저가", "종가"])


# ── PositionSizer 미러 ────────────────────────────────────────────
def test_atr_set_quantity_mirror():
    # 리스크 균등: 50,000 / (10,000 x 7.5%) = 66주, 상한 25만원 = 25주 → 25(현행 캡)
    assert atr_set_quantity(10_000, 7.5, 50_000, 250_000) == 25
    # 대형 상한이면 리스크 수량이 바인딩
    assert atr_set_quantity(10_000, 7.5, 50_000, 50_000_000) == 66
    # 결측 → 현행 폴백(§4c: 확대 금지)
    assert atr_set_quantity(10_000, None, None, 250_000) == 25
    # 판정 불가
    assert atr_set_quantity(0, 7.5, 50_000, 250_000) == 0
    assert atr_set_quantity(10_000, 7.5, 50_000, 0) == 0


# ── 고정 규칙 진입/청산 + 실현손익 ────────────────────────────────
def test_fixed_entry_and_stop():
    # D=첫날 신호 → D+1 시가 10,000 진입. 3일째 저가가 -3% 손절가 관통.
    bars = [(10_000, 10_100, 9_900, 10_000)] * 2 + [(9_800, 9_800, 9_500, 9_600)] \
        + [(9_600, 9_700, 9_500, 9_600)] * 5
    price = {"A": make_df("2026-01-05", bars)}
    signals = [{"ticker": "A", "date": "2026-01-05"}]
    r = replay_portfolio(signals, price, "fixed", capital=500_000)
    assert r["trades"] == 1
    assert r["exitBreakdown"]["stop"] == 1
    assert r["totalReturnPct"] < 0
    # 손실 실현 → 일일 최대 실현손실이 음수로 기록
    assert r["maxDailyRealizedLossKrw"] < 0


def test_breaker_blocks_entry_only_that_day():
    # 손절 손실이 브레이커 한도를 넘는 날, 같은 날 진입 예정 신호는 차단 — 다음 거래일 진입은 허용.
    bars_a = [(10_000, 10_100, 9_900, 10_000),        # D(신호)
              (10_000, 10_000, 9_000, 9_100),          # D+1: 진입 즉시 손절(-3% 관통)
              (9_100, 9_200, 9_000, 9_100),
              (9_100, 9_200, 9_000, 9_100), (9_100, 9_200, 9_000, 9_100)]
    # B: D+1(같은 날) 진입 예정 — 브레이커에 차단돼야
    bars_b = [(5_000, 5_100, 4_900, 5_000)] * 5
    price = {"A": make_df("2026-01-05", bars_a), "B": make_df("2026-01-05", bars_b)}
    signals = [{"ticker": "A", "date": "2026-01-05"}, {"ticker": "B", "date": "2026-01-05"}]
    # 한도 1,000원 — A 손절(약 -7,700원)로 즉시 발동
    r = replay_portfolio(signals, price, "fixed", capital=500_000, breaker_limit=1_000)
    # 검증 포인트: 같은 날 진입 예정 신호(B)가 차단되고 trip 이 1회만 기록.
    assert r["breakerTripDays"] == 1
    assert r["blockedEntriesByBreaker"] >= 1

def test_breaker_releases_next_day():
    # A 가 D+1 손절(브레이커 발동) → B 신호는 D+1 발생(진입 D+2) — 다음 날이라 정상 진입.
    bars_a = [(10_000, 10_100, 9_900, 10_000),
              (10_000, 10_000, 9_000, 9_100),
              (9_100, 9_200, 9_000, 9_100), (9_100, 9_200, 9_000, 9_100),
              (9_100, 9_200, 9_000, 9_100)]
    bars_b = [(5_000, 5_100, 4_900, 5_000)] * 5
    price = {"A": make_df("2026-01-05", bars_a), "B": make_df("2026-01-05", bars_b)}
    signals = [{"ticker": "A", "date": "2026-01-05"},
               {"ticker": "B", "date": "2026-01-06"}]   # 진입 D+2 = 01-07
    r = replay_portfolio(signals, price, "fixed", capital=500_000, breaker_limit=1_000)
    assert r["breakerTripDays"] == 1
    assert r["blockedEntriesByBreaker"] == 0            # 다음 거래일 자동 해제
    assert r["trades"] == 2                              # A 손절 + B(최종 청산 포함)


# ── ATR 세트: 결측 폴백 + 사이징 축소 ─────────────────────────────
def test_atr_set_fallback_when_history_short():
    # 히스토리 3일뿐 → ATR None → 완전 현행(고정 청산 + 캡 수량) + atrFallbackEntries 카운트
    bars = [(10_000, 10_100, 9_900, 10_000)] * 8
    price = {"A": make_df("2026-01-05", bars)}
    signals = [{"ticker": "A", "date": "2026-01-06"}]
    r = replay_portfolio(signals, price, "atr_set", capital=500_000)
    assert r["atrFallbackEntries"] == 1
    assert r["trades"] == 1                              # 타임컷/최종 청산으로 기록됨


def test_atr_set_uses_wider_stop_and_smaller_risk_qty():
    # 20일 히스토리(TR=200, ATR=200) → 손절폭 = 2.5x200/진입가 ≈ 5% → 고정(-3%)보다 넓은 손절.
    # 진입 D+1 저가 9,600(-4%) : 고정은 손절(-3%), ATR 세트는 버팀 → 청산 사유가 갈린다.
    hist = [(10_000, 10_100, 9_900, 10_000)] * 20        # TR 200
    d1 = (10_000, 10_050, 9_600, 9_980)                  # -4% 저가
    rest = [(9_980, 10_050, 9_900, 9_980)] * 5
    price = {"A": make_df("2026-01-05", hist + [d1] + rest)}
    sig_date = str(pd.bdate_range("2026-01-05", periods=20)[-1].date())   # 신호 = 히스토리 마지막 날
    signals = [{"ticker": "A", "date": sig_date}]

    fixed = replay_portfolio(signals, price, "fixed", capital=500_000)
    atr = replay_portfolio(signals, price, "atr_set", capital=500_000)
    assert fixed["exitBreakdown"]["stop"] == 1           # 고정 -3% 는 D+1 손절
    assert atr["exitBreakdown"]["stop"] == 0             # ATR -5% 는 버팀
    assert atr["atrFallbackEntries"] == 0


def test_compare_verdict_strings():
    fixed = {"breakerTripDays": 1, "totalReturnPct": -1.0, "maxDailyRealizedLossKrw": -8000.0}
    good = {"breakerTripDays": 0, "totalReturnPct": 2.0, "maxDailyRealizedLossKrw": -9000.0}
    bad = {"breakerTripDays": 3, "totalReturnPct": 2.0, "maxDailyRealizedLossKrw": -20000.0}
    assert "동수 이하 충족" in compare_verdict(fixed, good)
    assert "재검토 필요" in compare_verdict(fixed, bad)
