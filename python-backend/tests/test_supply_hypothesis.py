"""가설 A(수급 연속일 vs 금액) 트리거 순수 함수 테스트."""
from app.backtest.supply_hypothesis import (amount_trigger_indices, streak_lengths,
                                            streak_trigger_indices)


def test_streak_lengths_resets_on_nonpositive_and_none():
    net = [1.0, 2.0, -1.0, 3.0, None, 4.0, 5.0, 6.0]
    assert streak_lengths(net) == [1, 2, 0, 1, 0, 1, 2, 3]


def test_streak_trigger_exactly_k_fires_once_per_run():
    # 7일 연속 순매수 — k=3 트리거는 3일째 '한 번만'(4일째 이후 재발화 없음)
    net = [1.0] * 7
    assert streak_trigger_indices(net, 3) == [2]
    assert streak_trigger_indices(net, 5) == [4]
    # 끊겼다 다시 3일 도달하면 새 트리거
    net2 = [1.0, 1.0, 1.0, -1.0, 1.0, 1.0, 1.0]
    assert streak_trigger_indices(net2, 3) == [2, 6]


def test_streak_trigger_never_reaches_k():
    assert streak_trigger_indices([1.0, 1.0, -1.0, 1.0], 3) == []


def test_amount_trigger_threshold_inclusive():
    net = [49.9, 50.0, 120.0, None, -80.0]
    assert amount_trigger_indices(net, 50.0) == [1, 2]
