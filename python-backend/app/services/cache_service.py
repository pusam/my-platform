"""Redis 캐시 서비스 - 기존 Redis 인스턴스 재사용, 프리픽스 py: 로 충돌 방지"""
import json
import logging
from typing import Any, Optional

import redis.asyncio as aioredis

logger = logging.getLogger(__name__)

PREFIX = "py:"


class RedisClient:
    def __init__(self):
        self._redis: Optional[aioredis.Redis] = None

    async def initialize(self, host: str, port: int, password: str):
        try:
            self._redis = aioredis.Redis(
                host=host, port=port, password=password or None,
                decode_responses=True, socket_connect_timeout=5,
            )
            await self._redis.ping()
            logger.info(f"Redis connected: {host}:{port}")
        except Exception as e:
            logger.warning(f"Redis connection failed: {e} - running without cache")
            self._redis = None

    async def get(self, key: str) -> Optional[Any]:
        if not self._redis:
            return None
        try:
            val = await self._redis.get(f"{PREFIX}{key}")
            if val:
                return json.loads(val)
        except Exception as e:
            logger.warning(f"Cache get error [{key}]: {e}")
        return None

    async def set(self, key: str, value: Any, ttl: int = 300):
        if not self._redis:
            return
        try:
            await self._redis.setex(f"{PREFIX}{key}", ttl, json.dumps(value, default=str))
        except Exception as e:
            logger.warning(f"Cache set error [{key}]: {e}")

    async def delete(self, key: str):
        if not self._redis:
            return
        try:
            await self._redis.delete(f"{PREFIX}{key}")
        except Exception as e:
            logger.warning(f"Cache delete error [{key}]: {e}")

    @property
    def available(self) -> bool:
        return self._redis is not None


redis_client = RedisClient()


async def close_redis():
    if redis_client._redis:
        await redis_client._redis.close()
        logger.info("Redis connection closed")
