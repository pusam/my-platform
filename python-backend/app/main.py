"""python-backend — pykrx 보조분석 마이크로서비스.

2026-06-11 재편: 과거 네이버 크롤링/yfinance/Gemini 라우터들은 Java backend 의
열화 복제 + 가짜 데이터 생성 문제로 전부 제거 (git 히스토리 보존). 현재 역할:
  - /api/v2/health          : 도커 헬스체크
  - /api/v2/regime/current  : pykrx 기반 시장 국면 (Java 시그널 스냅샷 V32 가 소비)
  - /api/v2/chart/*         : 차트 추세추종 보조 시그널 (타이밍/섹터강도, 미검증 — 발굴 보조 전용)
새 기능은 "Java/KIS 로 비싼 일(히스토리·벌크 분석)"일 때만 여기에 추가할 것.
"""
from fastapi import FastAPI
from contextlib import asynccontextmanager

from app.config import get_settings
from app.services.cache_service import redis_client, close_redis
from app.routers import health, regime, chart_patterns, chart_backtest


@asynccontextmanager
async def lifespan(app: FastAPI):
    # Startup
    settings = get_settings()
    await redis_client.initialize(
        host=settings.redis_host,
        port=settings.redis_port,
        password=settings.redis_password,
    )
    yield
    # Shutdown
    await close_redis()


app = FastAPI(
    title="My Platform - Python Backend (pykrx 보조분석)",
    version="3.0.0",
    lifespan=lifespan,
)

# Routers
app.include_router(health.router)
app.include_router(regime.router)
app.include_router(chart_patterns.router)
app.include_router(chart_backtest.router)
