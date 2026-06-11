from pydantic_settings import BaseSettings
from functools import lru_cache


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
