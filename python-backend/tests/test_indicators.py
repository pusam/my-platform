"""차트 추세추종 지표 순수함수 단위테스트 (경계값 위주).

python-backend 첫 테스트 인프라. Java RecommendationOversoldTest 스타일 —
gate/경계/결측(None) 안전성 검증. 산식 자체의 승률은 별도 백테스트(VERIFICATION_BACKLOG).
실행: cd python-backend && pytest
"""
from app.config import ChartPatternConfig
from app.indicators import (
    moving_average as mavg,
    disparity as disp,
    envelope as env,
    support_rebound as sup,
    box_breakout as box,
    timing_score as ts,
    sector_strength as ss,
)


# ── 1. 이동평균 / 정배열 ──────────────────────────────────────────
def test_sma_basic_and_insufficient():
    assert mavg.sma([1, 2, 3, 4], 2) == 3.5
    assert mavg.sma([1, 2], 3) is None       # 데이터 부족 → None
    assert mavg.sma([1, 2, 3], 0) is None


def test_is_aligned():
    assert mavg.is_aligned([5, 4, 3, 2, 1]) is True   # 완전 정배열
    assert mavg.is_aligned([5, 4, 3, 3, 1]) is False  # 등호는 strictly 아님
    assert mavg.is_aligned([5, 4, 3, 2, None]) is False  # 결측은 정배열 위장 안 함


def test_alignment_strength():
    assert mavg.alignment_strength([5, 4, 3, 2, 1]) == 4
    assert mavg.alignment_strength([5, 4, 3, 2, None]) == 3  # 마지막 쌍 미충족
    assert mavg.alignment_strength([1]) == 0


# ── 2. 이격도 / 과열 ──────────────────────────────────────────────
def test_disparity_and_overheat():
    assert disp.ma60_ma240_disparity(110, 100) == 110.0
    assert disp.ma60_ma240_disparity(110, 0) is None   # 0분모
    assert disp.ma60_ma240_disparity(None, 100) is None
    assert disp.is_overheated(120, 115) is True
    assert disp.is_overheated(115, 115) is True         # 임계 이상
    assert disp.is_overheated(114, 115) is False
    assert disp.is_overheated(None, 115) is False       # 결측은 과열 아님


# ── 3 & 5. 엔벨로프 하단 터치 / 위험필터 ──────────────────────────
def test_envelope_band_and_touch():
    assert env.lower_band(100, 0.1) == 90
    assert env.lower_band(None, 0.1) is None
    assert env.lower_touch(89, 90) is True
    assert env.lower_touch(91, 90) is False
    assert env.lower_touch(90, None) is False


def test_touch_count_and_risk():
    lows = [89, 100, 88, 100]
    ma20s = [100, 100, 100, 100]   # band=90
    assert env.touch_count_in_window(lows, ma20s, 0.1, 4) == 2   # 89,88
    assert env.touch_count_in_window(lows, ma20s, 0.1, 2) == 1   # 최근 2봉만
    assert env.is_risk_excluded(2, 2) is True
    assert env.is_risk_excluded(1, 2) is False


# ── 4. 중심선 지지 반등 ───────────────────────────────────────────
def test_center_support_rebound():
    assert sup.center_support_rebound(99, 101, 100) is True    # 눌렸다 회복
    assert sup.center_support_rebound(101, 102, 100) is False  # 중심선 안 닿음
    assert sup.center_support_rebound(99, 99, 100) is False    # 회복 실패(종가<중심선)
    assert sup.center_support_rebound(99, 101, None) is False  # 결측


# ── 6. 박스 돌파 후 눌림목 (A안) ──────────────────────────────────
# box_len=5, lookahead_m=3 → need 8봉. 박스창=앞5봉, 돌파탐색=뒤3봉.
_HIGHS = [100, 101, 100, 101, 100, 105, 103, 103]
_LOWS = [98, 99, 98, 99, 98, 101, 100, 100]
_CLOSES = [99, 100, 99, 100, 99, 104, 102, 102]


def test_box_signal_true():
    r = box.evaluate(_HIGHS, _LOWS, _CLOSES,
                     box_len=5, box_range_max=0.12, breakout_buf=0.005,
                     pullback_tol=0.05, lookahead_m=3)
    assert r["box"] is True        # range_pct=(101-98)/98≈0.031 ≤ 0.12
    assert r["breakout"] is True   # 종가 104 > 101*1.005
    assert r["pullback"] is True   # cur_low 100 ≥ 95.95 & > box_low 98
    assert r["signal"] is True
    assert r["box_high"] == 101 and r["box_low"] == 98


def test_box_no_higher_low():
    # 현재 저가가 box_low(98) 이하면 higher-low 실패 → signal False
    lows = _LOWS[:-1] + [97]
    r = box.evaluate(_HIGHS, lows, _CLOSES,
                     box_len=5, box_range_max=0.12, breakout_buf=0.005,
                     pullback_tol=0.05, lookahead_m=3)
    assert r["pullback"] is False
    assert r["signal"] is False


def test_box_range_too_wide():
    # 박스창 변동성이 임계 초과면 박스 아님
    r = box.evaluate(_HIGHS, _LOWS, _CLOSES,
                     box_len=5, box_range_max=0.01, breakout_buf=0.005,
                     pullback_tol=0.05, lookahead_m=3)
    assert r["box"] is False
    assert r["signal"] is False


def test_box_insufficient_data():
    r = box.evaluate([1, 2], [1, 2], [1, 2],
                     box_len=5, box_range_max=0.12, breakout_buf=0.005,
                     pullback_tol=0.05, lookahead_m=3)
    assert r["signal"] is False
    assert r["reason"] == "insufficient_data"


# ── 타이밍 스코어 결합 ────────────────────────────────────────────
def test_timing_score_risk_excluded():
    r = ts.timing_score(True, 4, 4, True, True, True, False, risk_excluded=True)
    assert r["score"] is None              # 위장 점수 금지
    assert r["risk_excluded"] is True
    assert "위험필터제외" in r["signals"]


def test_timing_score_full():
    r = ts.timing_score(True, 4, 4, True, True, True, False, risk_excluded=False)
    assert r["score"] == 10                # 3+3+2+2, 상한 10
    assert set(r["signals"]) >= {"정배열", "엔벨로프눌림", "중심선반등", "박스눌림목"}


def test_timing_score_overheat_penalty():
    # 정배열만 + 과열 → 3 - 2 = 1
    r = ts.timing_score(True, 4, 4, False, False, False, True, risk_excluded=False)
    assert r["score"] == 1
    assert "과열주의" in r["signals"]


def test_timing_score_partial_alignment():
    r = ts.timing_score(False, 2, 4, False, False, False, False, risk_excluded=False)
    assert r["score"] == 2                  # 3*(2/4)=1.5 → round 2
    assert any("부분정배열" in s for s in r["signals"])


# ── 7. 섹터 상대강도 ──────────────────────────────────────────────
def test_pct_return_and_equal_weight():
    assert ss.pct_return(100, 110) == 10.0
    assert ss.pct_return(0, 110) is None
    assert ss.equal_weight_return([10, None, 20]) == 15.0   # 결측 제외
    assert ss.equal_weight_return([None, None]) is None


def test_relative_strength_and_rank():
    assert ss.relative_strength(15, 5) == 10
    assert ss.relative_strength(None, 5) is None
    ranked = ss.rank_sectors({"A": 5, "B": None, "C": 10})
    assert [r["sector"] for r in ranked] == ["C", "A"]      # rel desc, B(결측) 제외
    assert ranked[0]["rank"] == 1 and ranked[1]["rank"] == 2


# ── config override ───────────────────────────────────────────────
def test_config_merge():
    cfg = ChartPatternConfig()
    merged = cfg.merge({"envelope_k": 0.15, "unknown_key": 1, "box_len": None})
    assert merged.envelope_k == 0.15        # 덮어씀
    assert merged.box_len == cfg.box_len    # None/미지정은 기본 유지
    assert cfg.envelope_k == 0.10           # 원본 불변
