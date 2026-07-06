"""가설 B(ATR vs 고정 청산) 순수 함수 테스트 — 보수적 판정(동시 터치=손절 우선) 핵심 가드."""
import pytest

from app.backtest.exit_backtest_service import (atr_rule_prices, conclusion, fixed_rule_prices,
                                                simulate_exit, wilder_atr)


# ==================== wilder_atr ====================

def test_atr_insufficient_data_is_none():
    assert wilder_atr([1] * 10, [1] * 10, [1] * 10, 14) is None    # <period+1 → None(§4c)


def test_atr_constant_range():
    # 매일 고저폭 2, 갭 없음 → TR 전부 2 → ATR 2
    n = 30
    highs = [101.0] * n
    lows = [99.0] * n
    closes = [100.0] * n
    assert wilder_atr(highs, lows, closes, 14) == pytest.approx(2.0)


# ==================== simulate_exit — 보수적 판정 ====================

def test_same_day_touch_both_stop_wins():
    # 진입 100, 손절 97, 익절 105. 첫날 저가 96·고가 106 (둘 다 터치) → 손절 우선
    bars = [(106.0, 96.0, 100.0)]
    r = simulate_exit(bars, 100.0, 97.0, 105.0)
    assert r["reason"] == "stop"
    assert r["exitPrice"] == 97.0
    assert r["exitDayIdx"] == 0


def test_target_hit_when_stop_untouched():
    bars = [(101.0, 99.0, 100.5), (106.0, 100.0, 105.5)]
    r = simulate_exit(bars, 100.0, 97.0, 105.0)
    assert r["reason"] == "target" and r["exitDayIdx"] == 1


def test_time_exit_at_max_hold_close():
    bars = [(101.0, 99.0, 100.0 + i * 0.1) for i in range(20)]
    r = simulate_exit(bars, 100.0, 97.0, 105.0, max_hold_days=10)
    assert r["reason"] == "time"
    assert r["exitDayIdx"] == 9
    assert r["exitPrice"] == pytest.approx(100.9)


def test_entry_day_stop_counts():
    # 진입 당일(D+1) 저가가 손절을 뚫는 케이스 — 판정 대상
    bars = [(100.5, 90.0, 95.0), (110.0, 100.0, 109.0)]
    r = simulate_exit(bars, 100.0, 97.0, 105.0)
    assert r["reason"] == "stop" and r["exitDayIdx"] == 0


def test_empty_bars_none():
    assert simulate_exit([], 100.0, 97.0, 105.0) is None


# ==================== 규칙 가격 ====================

def test_fixed_rule_mirrors_plan_constants():
    stop, target = fixed_rule_prices(100.0)
    assert stop == pytest.approx(97.0)      # PLAN -3%
    assert target == pytest.approx(105.0)   # PLAN +5%


def test_atr_rule_preserves_risk_reward():
    stop, target = atr_rule_prices(100.0, 2.0, 1.5)
    assert stop == pytest.approx(97.0)                       # 100 - 1.5×2
    assert target == pytest.approx(100.0 + 3.0 * (5 / 3))    # 손익비 1.667 유지
    assert (target - 100.0) / (100.0 - stop) == pytest.approx(5.0 / 3.0)


# ==================== conclusion ====================

def test_conclusion_prefers_fixed_on_tie_or_win():
    rules = {"fixed_-3/+5": {"avgNet": 1.0, "portfolioMdd10": 5.0},
             "atr_x1.5": {"avgNet": 0.8, "portfolioMdd10": 4.0},
             "atr_x2.0": {"avgNet": 1.0, "portfolioMdd10": 4.0}}   # 동률 → 고정 유지
    assert "현행 유지" in conclusion(rules)


def test_conclusion_flags_atr_win_but_no_auto_adoption():
    rules = {"fixed_-3/+5": {"avgNet": 0.5, "portfolioMdd10": 5.0},
             "atr_x2.0": {"avgNet": 1.2, "portfolioMdd10": 6.0}}
    c = conclusion(rules)
    assert "atr_x2.0" in c and "자동 편입 금지" in c


def test_conclusion_insufficient_is_honest():
    assert "유보" in conclusion({"fixed_-3/+5": {"avgNet": None}})
