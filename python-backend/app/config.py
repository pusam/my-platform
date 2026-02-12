from pydantic_settings import BaseSettings
from functools import lru_cache


class Settings(BaseSettings):
    # Gemini AI
    gemini_api_key: str = ""
    gemini_model: str = "gemini-2.0-flash"

    # Redis
    redis_host: str = "localhost"
    redis_port: int = 6379
    redis_password: str = ""

    # Cache TTL (seconds)
    cache_ttl_market_status: int = 60
    cache_ttl_sectors: int = 300
    cache_ttl_investor: int = 300
    cache_ttl_screener: int = 1800
    cache_ttl_ai_strategy: int = 600
    cache_ttl_news: int = 1800
    cache_ttl_analysis: int = 300

    # Gemini rate limiting
    gemini_rpm_limit: int = 15
    gemini_min_interval: float = 2.0
    gemini_max_retries: int = 3

    model_config = {"env_file": ".env", "extra": "ignore"}


@lru_cache
def get_settings() -> Settings:
    return Settings()
