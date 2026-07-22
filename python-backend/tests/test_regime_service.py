"""시장 국면(V32) 분류 규칙 v1 — classify_regime 순수함수 경계값 테스트.

봇 regime 가중치·시그널 스냅샷(V32)이 소비하는 신호인데 회귀 안전망이 없었다
(HANDOFF_2026-07-13 A-1). 규칙 v1 자체는 불변식 — 이 테스트는 규칙을 '고정'하는
용도이지 조정 근거가 아니다(조정은 검증 데이터 축적 후, CLAUDE.md §4c).

규칙 v1:
  BULL:     종가 > MA60  AND  MA20 5거래일 슬로프 상승
  BEAR:     종가 < MA60  AND  슬로프 하락
  SIDEWAYS: 그 외 (혼조·경계 동률 포함)
실행: cd python-backend && pytest tests/test_regime_service.py
"""
from app.services.regime_service import classify_regime, _classify


class TestClassifyRegime:
    def test_bull_requires_both_conditions(self):
        # 종가 > MA60 AND 슬로프 상승 — 둘 다 충족해야 BULL
        assert classify_regime(close=2700, ma_long=2600, ma_short_now=2680, ma_short_prev=2650) == "BULL"
        # 종가만 위 (슬로프 하락) → SIDEWAYS (혼조)
        assert classify_regime(2700, 2600, 2650, 2680) == "SIDEWAYS"
        # 슬로프만 상승 (종가 아래) → SIDEWAYS
        assert classify_regime(2500, 2600, 2680, 2650) == "SIDEWAYS"

    def test_bear_requires_both_conditions(self):
        assert classify_regime(2500, 2600, 2650, 2680) == "BEAR"
        # 종가 아래 + 슬로프 상승 → SIDEWAYS (2026-07-13 -8.95% 급락 후 반등 국면 유형)
        assert classify_regime(2500, 2600, 2680, 2650) == "SIDEWAYS"

    def test_boundary_equality(self):
        # 경계 동작을 '구현 기준'으로 고정 — V32 스냅샷이 이 규칙으로 축적돼 왔으므로
        # 경계 해석을 바꾸면 과거 표본과 단절된다(규칙 v1 불변식).
        assert classify_regime(2600, 2600, 2680, 2650) == "SIDEWAYS"   # 종가 == MA60 → 초과 아님
        assert classify_regime(2700, 2600, 2660, 2660) == "SIDEWAYS"   # 위 + 슬로프 0 → 상승 아님
        # 아래 + 슬로프 0 = BEAR — 구현은 '비상승(not rising)'을 하락 조건에 포함
        # (docstring 의 "하락"보다 넓은 해석이지만 축적된 정본 동작)
        assert classify_regime(2500, 2600, 2660, 2660) == "BEAR"

    def test_extreme_values(self):
        # 극단값에도 문자열 3종 외 반환 없음
        assert classify_regime(0.01, 1e9, 1, 2) == "BEAR"
        assert classify_regime(1e9, 0.01, 2, 1) == "BULL"


class TestClassifyDataGuard:
    def test_insufficient_rows_returns_empty(self, monkeypatch):
        # MA60+슬로프5 = 65행 미만이면 빈 dict(§4c — 부족 표본으로 위장 국면 금지)
        import pandas as pd
        import app.services.regime_service as rs

        short_df = pd.DataFrame(
            {"종가": range(30)},
            index=pd.date_range("2026-06-01", periods=30))
        monkeypatch.setattr(rs, "fetch_kospi_daily", lambda days: short_df)
        assert _classify() == {}

    def test_source_down_returns_empty(self, monkeypatch):
        import app.services.regime_service as rs
        monkeypatch.setattr(rs, "fetch_kospi_daily", lambda days: None)
        assert _classify() == {}

    def test_classify_end_to_end_bull(self, monkeypatch):
        # 단조 상승 시계열 → 종가>MA60, MA20 슬로프 상승 → BULL + 필드 배선 확인
        import pandas as pd
        import app.services.regime_service as rs

        n = 80
        df = pd.DataFrame(
            {"종가": [2000 + i * 5 for i in range(n)]},
            index=pd.date_range("2026-03-01", periods=n))
        monkeypatch.setattr(rs, "fetch_kospi_daily", lambda days: df)
        out = _classify()
        assert out["regime"] == "BULL"
        assert out["kospiClose"] == 2000 + (n - 1) * 5
        assert out["asOf"] == df.index[-1].strftime("%Y-%m-%d")
        assert out["ma20"] > out["ma60"]   # 단조 상승이면 단기 MA 가 위
