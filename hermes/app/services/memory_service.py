import json
import logging
from typing import Optional, List

import redis.asyncio as redis
from app.config.settings import settings
from app.models.schemas import ChatMessage

logger = logging.getLogger(__name__)


class MemoryService:
    def __init__(self):
        self.redis: Optional[redis.Redis] = None
        self._ttl = settings.MEMORY_SHORT_TERM_TTL
        self._summary_threshold = settings.MEMORY_SUMMARY_THRESHOLD

    async def connect(self):
        self.redis = redis.from_url(settings.REDIS_URL, decode_responses=True)
        logger.info("Redis connected for memory service")

    async def disconnect(self):
        if self.redis:
            await self.redis.close()

    def _key_messages(self, user_id: str) -> str:
        return f"hermes:{user_id}:messages"

    def _key_summary(self, user_id: str) -> str:
        return f"hermes:{user_id}:summary"

    def _key_context(self, user_id: str) -> str:
        return f"hermes:{user_id}:context"

    async def add_message(self, user_id: str, message: ChatMessage) -> None:
        key = self._key_messages(user_id)
        msg_json = message.model_dump_json()
        await self.redis.rpush(key, msg_json)
        await self.redis.expire(key, self._ttl)

        count = await self.redis.llen(key)
        if count >= self._summary_threshold:
            logger.info(f"User {user_id} reached summary threshold ({count}), triggering summary")

    async def get_recent_messages(self, user_id: str, limit: int = 20) -> List[ChatMessage]:
        key = self._key_messages(user_id)
        raw_messages = await self.redis.lrange(key, -limit, -1)
        messages = []
        for raw in raw_messages:
            try:
                messages.append(ChatMessage.model_validate_json(raw))
            except Exception as e:
                logger.warning(f"Failed to parse message: {e}")
        return messages

    async def save_summary(self, user_id: str, summary: str) -> None:
        key = self._key_summary(user_id)
        await self.redis.set(key, summary, ex=86400 * 7)

    async def get_summary(self, user_id: str) -> Optional[str]:
        key = self._key_summary(user_id)
        return await self.redis.get(key)

    async def save_context(self, user_id: str, context: dict) -> None:
        key = self._key_context(user_id)
        await self.redis.set(key, json.dumps(context, ensure_ascii=False), ex=self._ttl)

    async def get_context(self, user_id: str) -> Optional[dict]:
        key = self._key_context(user_id)
        raw = await self.redis.get(key)
        if raw:
            try:
                return json.loads(raw)
            except json.JSONDecodeError:
                return None
        return None

    async def get_message_count(self, user_id: str) -> int:
        key = self._key_messages(user_id)
        return await self.redis.llen(key)

    async def clear_messages(self, user_id: str) -> None:
        key = self._key_messages(user_id)
        await self.redis.delete(key)


memory_service = MemoryService()
