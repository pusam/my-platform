"""종합점수 백테스트(composite_backtest_service) 순수 함수 테스트.

미러 함수(technical/supply)는 Java 산식의 사본 — 값이 바뀌면 운영 산식과 어긋난 것이므로
이 테스트는 회귀 가드다(산식 변경은 별도 세션에서 사람이 결정, 측정 코드는 따라간다).
"""
import pandas as pd
import pytest

from app.backtest.composite_backtest_service import (aggregate_by, aggregate_category, band_of,
                                                     buy_signal_strength, foreign_streak_points,
                                                     inst_streak_points, is_strong,
                                                     overheat_penalty, regime_at, sma,
                                                     supply_score_mirror,
                                                     technical_score_mirror, wilder_rsi)
from app.backtest.investor_flows import avg_amount_over_streak, consecutive_net_buy_days


# ==================== technical 미러 ====================

def test_sma_mirrors_java_calculate_ma():
    assert sma([10.0, 20.0, 30.0], 3) == pytest.approx(20.0)
    assert sma([10.0, 20.0], 3) is None            # 데이터 부족 → None
    assert sma(None, 3) is None


def test_wilder_rsi_all_up_is_100_all_down_is_0():
    up = list(reversed([100 + i for i in range(30)]))      # DESC 최신이 최고가
    down = list(reversed([100 - i for i in range(30)]))
    assert wilder_rsi(up, 14) == 100.0
    assert wilder_rsi(down, 14) == 0.0
    assert wilder_rsi([1.0] * 10, 14) is None              # period+1 미만 → None


def test_overheat_penalty_mirrors_java_steps():
    assert overheat_penalty(None, False, None) == 0
    assert overheat_penalty(70.0, False, None) == 3
    assert overheat_penalty(75.0, False, None) == 5
    assert overheat_penalty(80.0, False, None) == 8
    assert overheat_penalty(None, True, None) == 3
    assert overheat_penalty(None, False, 15.0) == 3
    assert overheat_penalty(None, False, 20.0) == 5
    assert overheat_penalty(None, False, 30.0) == 8
    assert overheat_penalty(80.0, True, 30.0) == 19


def test_buy_signal_strength_neutral_base_and_clamp():
    # 데이터 전무 → 기본 50
    assert buy_signal_strength(None, None, None, None, None, False, False) == 50
    # 전부 우호(가격>MA 전부 +30, GC+15, AU+15, RSI<=30 +20) → 130 → 100 클램프
    assert buy_signal_strength(100, 90, 80, 70, 25.0, True, True) == 100
    # 전부 불리(-30, RSI>=70 -10) → 10
    assert buy_signal_strength(60, 90, 80, 70, 75.0, False, False) == 10


def test_technical_score_mirror_insufficient_history_is_none():
    assert technical_score_mirror([100.0] * 4) is None     # <5 → 미측정(§4c)


def test_technical_score_mirror_overheated_runup_scores_low():
    # 5일 +40% 급등 + RSI 고공 — 과열 페널티로 낮은 점수(0 포함)여야 함
    closes_desc = list(reversed([100 * (1.06 ** i) for i in range(60)]))
    score = technical_score_mirror(closes_desc)
    assert score is not None and 0 <= score <= 5


def test_technical_score_mirror_flat_series_java_rsi100_quirk():
    closes_desc = [100.0] * 60
    score = technical_score_mirror(closes_desc)
    # 완전 플랫: Java calculateRSI 는 avgLoss==0 분기가 먼저라 RSI=100 (변화 0 인데 '하락 없음' 취급).
    # → bss 50-10(RSI>=70)=40 → ts=min(12, 40*12//100)=4 → 과열 페널티 RSI>=80 -8 → -4 → 클램프 0.
    # 이 quirk 까지 미러하는 것이 목적(산식 재현) — 값이 바뀌면 미러가 어긋난 것.
    assert score == 0


# ==================== supply 미러 ====================

def test_streak_points_mirror_java_tables():
    # 외국인: dp 2일=8, 3일=10, 4일=6, 5일+=4 (2~3일 정점 곡선)
    assert foreign_streak_points(2, 0) == 8
    assert foreign_streak_points(3, 0) == 10
    assert foreign_streak_points(4, 0) == 6
    assert foreign_streak_points(5, 0) == 4
    # ab: 50억+ 4, 20억+ 2, 5억+ 1
    assert foreign_streak_points(2, 50) == 12
    assert foreign_streak_points(2, 20) == 10
    assert foreign_streak_points(2, 5) == 9
    # 기관: dp 2일=6, 3일=8, 4일=5, 5일+=3 / ab 50억+ 3, 20억+ 1
    assert inst_streak_points(3, 20) == 9
    assert inst_streak_points(5, 50) == 6


def test_supply_score_mirror_streak_beats_topbuy_and_caps_at_20():
    # 연속매수 있으면 top10 폴백 미적용 (Java: supplyDemand>0 → continue)
    assert supply_score_mirror(3, 60.0, 3, 60.0) == 20     # 14+11=25 → 캡 20
    # 연속 없고 top10 외국인 100억 → 8
    assert supply_score_mirror(0, 0, 0, 0, frgn_today_eok=100.0, frgn_in_top10=True) == 8
    # top10 이지만 10억 미만 → 0
    assert supply_score_mirror(0, 0, 0, 0, frgn_today_eok=9.0, frgn_in_top10=True) == 0
    # top10 밖이면 금액 커도 0
    assert supply_score_mirror(0, 0, 0, 0, frgn_today_eok=200.0, frgn_in_top10=False) == 0


def test_consecutive_and_avg_streak():
    net = [1.0, 2.0, -1.0, 3.0, 4.0, 5.0]
    assert consecutive_net_buy_days(net, 5) == 3           # 3,4,5 양수
    assert consecutive_net_buy_days(net, 2) == 0
    assert avg_amount_over_streak(net, 5, 3) == pytest.approx(4.0)
    assert avg_amount_over_streak(net, 5, 0) == 0.0


# ==================== 밴드/임계/regime/집계 ====================

def test_band_and_threshold():
    assert band_of(0) == "0-4" and band_of(4) == "0-4"
    assert band_of(5) == "5-9" and band_of(14) == "10-14" and band_of(20) == "15-20"
    # c85f304 카테고리별 임계 미러
    assert is_strong("technical", 13) and not is_strong("technical", 12)
    assert is_strong("supplyDemand", 15) and not is_strong("supplyDemand", 14)
    assert is_strong("earnings", 20) and not is_strong("earnings", 19)
    assert is_strong("sectorMomentum", 14) and not is_strong("sectorMomentum", 13)


def test_regime_at_point_in_time_no_lookahead():
    # 강한 상승 시계열: 마지막 시점 BULL. idx 이후 데이터가 있어도 결과 불변(point-in-time).
    closes = [100 + i * 2 for i in range(100)]
    r_at_80 = regime_at(closes, 80)
    assert r_at_80 == "BULL"
    closes_more = closes + [1.0] * 50                      # 미래 폭락 붙여도
    assert regime_at(closes_more, 80) == r_at_80           # idx 시점 판정 불변
    assert regime_at(closes, 70) == "BULL"
    assert regime_at(closes, 50) is None                   # 65거래일 미만 → 미산출(§4c)


def _sig(cat_score, net, alpha, hit, regime="BULL", date="2026-03-02"):
    return {"scores": {"technical": cat_score}, "netPct": net, "alpha": alpha, "hit": hit,
            "regime": regime, "exitDate": date, "date": date, "ticker": "000000"}


def test_aggregate_by_marks_insufficient_sample():
    signals = [_sig(15, 1.0, 0.5, True) for _ in range(3)]
    agg = aggregate_by(signals, lambda s: band_of(s["scores"]["technical"]))
    assert agg["15-20"]["n"] == 3
    assert agg["15-20"]["insufficientSample"] is True       # n<10 명시(§4c)
    assert agg["15-20"]["hitRate"] == 100.0
    assert agg["15-20"]["avgAlpha"] == 0.5


def test_aggregate_category_unmeasured_is_honest():
    signals = [{"scores": {"technical": None}, "netPct": 1.0, "alpha": None, "hit": False,
                "regime": None, "exitDate": "2026-03-02", "date": "2026-03-02", "ticker": "0"}]
    out = aggregate_category(signals, "technical")
    assert out["measured"] is False                         # 위장값 없이 미측정 표기


def test_aggregate_category_full_shape():
    signals = ([_sig(15, 2.0, 1.0, True)] * 12 + [_sig(3, -1.0, -0.5, False)] * 12)
    out = aggregate_category(signals, "technical")
    assert out["measured"] and out["n"] == 24
    assert out["byStrong"]["strong"]["hitRate"] == 100.0
    assert out["byStrong"]["weak"]["hitRate"] == 0.0
    assert out["byBand"]["15-20"]["insufficientSample"] is False
    assert out["spearmanScoreVsNet"] == 1.0                 # 점수↑=수익↑ 완전 단조
    assert out["strongPortfolioMdd"] is not None
