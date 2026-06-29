from pydantic import BaseModel
from pydantic_settings import BaseSettings
from functools import lru_cache
from typing import Any, Optional


class Settings(BaseSettings):
    # Redis (기존 인스턴스 재사용 — 프리픽스 py: 로 충돌 방지)
    # Gemini/뉴스/스크리너 설정은 2026-06-11 재편(해당 서비스 제거)으로 삭제.
    redis_host: str = "localhost"
    redis_port: int = 6379
    redis_password: str = ""

    model_config = {"env_file": ".env", "extra": "ignore"}


@lru_cache
def get_settings() -> Settings:
    return Settings()


class ChartPatternConfig(BaseModel):
    """차트 추세추종 기법(정배열·이격도·엔벨로프 눌림목·박스·섹터강도) 파라미터.

    모든 임계값은 하드코딩 금지 — 기본값은 여기, 요청별 override 는 merge() 로.
    검증 전 보조 시그널 전용이라 산식은 미검증(VERIFICATION_BACKLOG 참조).
    """
    # 이동평균 정배열 (1)
    ma_periods: list[int] = [5, 20, 60, 120, 240]
    # 60-240 이격도 과열 임계 (2): MA60/MA240*100
    disparity_overheat: float = 115.0
    # 엔벨로프 하단 비율 k (3): 하단 = MA20*(1-k)
    envelope_k: float = 0.10
    # 위험필터 (5): rolling window 내 하단 터치 허용 한도(이상이면 매수 제외)
    risk_window: int = 20
    risk_touch_max: int = 2
    # 박스 돌파 후 눌림목 (6, A안 변동성 박스)
    box_len: int = 20            # 박스(횡보) 구간 길이(거래일)
    box_range_max: float = 0.12  # (고가-저가)/저가 ≤ 이 값이면 '박스'로 인정
    breakout_buf: float = 0.005  # 박스상단 대비 돌파 버퍼
    pullback_tol: float = 0.05   # 돌파 후 박스상단 아래 허용 되돌림(지지 판정)
    lookahead_m: int = 10        # 돌파 후 눌림목 탐색 기간(거래일)
    # 섹터 상대강도 (7)
    sector_lookback: int = 20    # 상대수익률 측정 기간(거래일)

    def merge(self, overrides: Optional[dict[str, Any]]) -> "ChartPatternConfig":
        """요청 body 의 params 로 일부 파라미터만 덮어쓴 새 config 반환(원본 불변).

        알 수 없는 키는 무시(pydantic extra 기본 ignore) — 결측은 기본값 유지.
        """
        if not overrides:
            return self
        known = {k: v for k, v in overrides.items()
                 if k in self.__class__.model_fields and v is not None}
        return self.model_copy(update=known)
