from fastapi import FastAPI
from contextlib import asynccontextmanager

from app.config import get_settings
from app.services.cache_service import redis_client, close_redis
from app.routers import health, market, investor, research, analysis, ai_strategy


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
    title="My Platform - Python Backend",
    version="2.0.0",
    lifespan=lifespan,
)

# Routers
app.include_router(health.router)
app.include_router(market.router)
app.include_router(investor.router)
app.include_router(research.router)
app.include_router(analysis.router)
app.include_router(ai_strategy.router)
