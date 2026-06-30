"""차트 백테스트 순수함수 + point-in-time 슬라이스 단위테스트 (네트워크 없음).

look-ahead 차단(≤D 슬라이스) / 진입 D+1 시가 / +hold 종가 청산 / 비용 보수성 / hit 정의 / 집계·Spearman.
"""
import pandas as pd
from pytest import approx

from app.backtest import cost, metrics


# ── 비용 ──────────────────────────────────────────────────────────
def test_gross_and_net_return():
    assert cost.gross_return_pct(100, 110) == approx(10.0)
    # 순손익 = 슬리피지(가격적용) + 수수료·세금 차감 → gross 보다 작아야(보수적)
    net = cost.net_return_pct(100, 110, slippage_pct=0.15)
    assert net < cost.gross_return_pct(100, 110)
    # 슬리피지 0 이면 더 좋음(수수료·세금만 차감)
    assert cost.net_return_pct(100, 110, slippage_pct=0.0) > net
    # 수수료·세금만 차감분 = 0.21%
    assert cost.commission_tax_pct() == approx(0.21)
    assert cost.net_return_pct(100, 110, 0.0) == approx(10.0 - 0.21, abs=1e-6)


# ── hit 정의 (SignalOutcome 미러) ─────────────────────────────────
def test_is_hit():
    assert metrics.is_hit(0.5, 1.0) is True      # alpha>=0 AND pct>0
    assert metrics.is_hit(0.0, 1.0) is True       # alpha=0 경계
    assert metrics.is_hit(-0.1, 1.0) is False     # alpha<0
    assert metrics.is_hit(0.5, 0.0) is False      # pct>0 실패
    assert metrics.is_hit(None, 3.0) is True       # alpha 없으면 pct>=3% 폴백
    assert metrics.is_hit(None, 2.99) is False
    assert metrics.is_hit(None, None) is False


# ── 집계 ──────────────────────────────────────────────────────────
def test_hit_rate_and_aggregate():
    assert metrics.hit_rate([True, False, True, None]) == approx(66.666, abs=0.01)  # None 제외 2/3
    agg = metrics.aggregate([10.0, -5.0, 20.0])
    assert agg["n"] == 3
    assert agg["avgNetReturn"] == approx(8.333, abs=0.01)
    assert agg["mdd"] == approx(5.0, abs=0.01)    # peak 1.1 → 1.045 = 5% 낙폭
    assert agg["sharpe"] > 0
    # 빈 시퀀스 안전
    assert metrics.aggregate([])["n"] == 0
    assert metrics.mdd([]) == 0.0
    assert metrics.sharpe([1.0]) == 0.0           # n<2


# ── Spearman 순위상관 ─────────────────────────────────────────────
def test_spearman():
    assert metrics.spearman([1, 2, 3, 4], [10, 20, 30, 40]) == approx(1.0)   # 완전 양
    assert metrics.spearman([1, 2, 3, 4], [40, 30, 20, 10]) == approx(-1.0)  # 완전 음
    assert metrics.spearman([1], [1]) is None                                # 표본<2
    assert metrics.spearman([1, 1, 1], [1, 1, 1]) is None                    # 분산0


# ── point-in-time 슬라이스 + 진입/청산 추출 (look-ahead 핵심) ──────
def _synthetic_df():
    idx = pd.to_datetime(["2026-01-02", "2026-01-05", "2026-01-06",
                          "2026-01-07", "2026-01-08", "2026-01-09", "2026-01-12"])
    return pd.DataFrame({
        "시가": [100, 101, 102, 103, 104, 105, 106],
        "고가": [101, 102, 103, 104, 105, 106, 107],
        "저가": [99, 100, 101, 102, 103, 104, 105],
        "종가": [100, 101, 102, 103, 104, 105, 106],
    }, index=idx)


def test_pointintime_slice_and_entry_exit():
    df = _synthetic_df()
    D = pd.Timestamp("2026-01-06")

    gen = df.loc[:D]                       # 신호 생성: ≤ D 만
    assert gen.index.max() == D            # 미래 캔들 물리적 배제(look-ahead 차단)
    assert len(gen) == 3                    # 01-02, 01-05, 01-06

    fwd = df.loc[df.index > D]             # 평가: > D (gen 과 disjoint)
    assert fwd.index.min() > D
    assert set(gen.index).isdisjoint(set(fwd.index))   # 신호용/평가용 절대 안 섞임

    # 진입 = D+1 시가(01-07 open=103), 청산 = +3거래일 종가(01-09 close=105, index 2)
    hold = 3
    entry_open = float(fwd.iloc[0]["시가"])
    exit_close = float(fwd.iloc[hold - 1]["종가"])
    assert entry_open == 103
    assert exit_close == 105
    assert cost.gross_return_pct(entry_open, exit_close) == approx((105 / 103 - 1) * 100)
