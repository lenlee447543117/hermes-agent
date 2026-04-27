import json
import logging
from typing import Optional, List

import httpx
from app.config.settings import settings
from app.models.schemas import ChatMessage, DialectMode, InteractionMode, IntentType, ActionPayload
from app.prompts.hermes_prompt import get_system_prompt

logger = logging.getLogger(__name__)


class GlmService:
    def __init__(self):
        self.api_key = settings.ZHIPU_API_KEY
        self.model = settings.GLM_MODEL
        self.max_tokens = settings.GLM_MAX_TOKENS
        self.base_url = "https://open.bigmodel.cn/api/paas/v4/chat/completions"
        self.timeout = 60.0

    async def close(self):
        """Close any resources held by the service."""
        pass

    async def chat(
        self,
        user_message: str,
        dialect: DialectMode = DialectMode.SHANGHAI,
        history: Optional[List[ChatMessage]] = None,
        habit_context: Optional[str] = None,
    ) -> dict:
        system_prompt = get_system_prompt(dialect.value)

        if habit_context:
            system_prompt += f"\n\n【当前用户习惯画像】\n{habit_context}"

        messages = [{"role": "system", "content": system_prompt}]
        if history:
            for msg in history[-20:]:
                role = "user" if msg.role == "user" else "assistant"
                messages.append({"role": role, "content": msg.content})

        messages.append({"role": "user", "content": user_message})

        try:
            async with httpx.AsyncClient(timeout=self.timeout) as client:
                response = await client.post(
                    self.base_url,
                    headers={
                        "Authorization": f"Bearer {self.api_key}",
                        "Content-Type": "application/json",
                    },
                    json={
                        "model": self.model,
                        "messages": messages,
                        "max_tokens": self.max_tokens,
                        "temperature": 0.7,
                    },
                )
                response.raise_for_status()
                data = response.json()
                content = data["choices"][0]["message"]["content"]
                return self._parse_response(content, dialect)

        except httpx.HTTPError as e:
            logger.error(f"GLM API error: {e}")
            fallback_reply = (
                "啊呀，我脑子有点糊涂了，等一歇再试好伐？"
                if dialect == DialectMode.SHANGHAI
                else "抱歉，我暂时无法处理，请稍后再试。"
            )
            return {
                "mode": InteractionMode.CHAT,
                "reply": fallback_reply,
                "intent": None,
                "action_payload": None,
                "dialect": dialect,
            }

    async def generate_proactive_care(
        self, habit_profile: str, anomaly_info: str, dialect: DialectMode = DialectMode.SHANGHAI
    ) -> dict:
        from app.prompts.hermes_prompt import PROACTIVE_CARE_PROMPT

        prompt = PROACTIVE_CARE_PROMPT.format(
            habit_profile=habit_profile,
            anomaly_info=anomaly_info,
        )

        try:
            async with httpx.AsyncClient(timeout=self.timeout) as client:
                response = await client.post(
                    self.base_url,
                    headers={
                        "Authorization": f"Bearer {self.api_key}",
                        "Content-Type": "application/json",
                    },
                    json={
                        "model": self.model,
                        "messages": [{"role": "user", "content": prompt}],
                        "max_tokens": 512,
                        "temperature": 0.7,
                    },
                )
                response.raise_for_status()
                data = response.json()
                content = data["choices"][0]["message"]["content"]
                return json.loads(content)
        except (json.JSONDecodeError, httpx.HTTPError) as e:
            logger.error(f"Proactive care generation failed: {e}")
            return {
                "message": "阿叔，今朝哪能啦？有啥需要帮忙伐？" if dialect == DialectMode.SHANGHAI else "您好，今天怎么样？有什么需要帮忙吗？",
                "care_type": "routine",
            }

    async def summarize_messages(self, messages: List[ChatMessage]) -> str:
        from app.prompts.hermes_prompt import SUMMARY_PROMPT

        msg_text = "\n".join([f"[{m.role}]: {m.content}" for m in messages])

        try:
            async with httpx.AsyncClient(timeout=self.timeout) as client:
                response = await client.post(
                    self.base_url,
                    headers={
                        "Authorization": f"Bearer {self.api_key}",
                        "Content-Type": "application/json",
                    },
                    json={
                        "model": self.model,
                        "messages": [{"role": "user", "content": SUMMARY_PROMPT.format(messages=msg_text)}],
                        "max_tokens": 256,
                        "temperature": 0.7,
                    },
                )
                response.raise_for_status()
                data = response.json()
                return data["choices"][0]["message"]["content"]
        except httpx.HTTPError as e:
            logger.error(f"Summary generation failed: {e}")
            return ""

    def _parse_response(self, content: str, dialect: DialectMode) -> dict:
        try:
            json_start = content.find("{")
            json_end = content.rfind("}") + 1
            if json_start >= 0 and json_end > json_start:
                json_str = content[json_start:json_end]
                parsed = json.loads(json_str)

                mode = InteractionMode(parsed.get("mode", "CHAT"))
                intent = None
                action_payload = None
                reply = parsed.get("reply", "")

                if mode == InteractionMode.COMMAND:
                    intent_str = parsed.get("intent", "UNKNOWN")
                    try:
                        intent = IntentType(intent_str)
                    except ValueError:
                        intent = IntentType.UNKNOWN

                    payload_data = parsed.get("payload", {})
                    action_payload = ActionPayload(
                        intent=intent,
                        target_person=payload_data.get("target_person"),
                        parameters=payload_data.get("parameters"),
                    )

                return {
                    "mode": mode,
                    "reply": reply,
                    "intent": intent,
                    "action_payload": action_payload,
                    "dialect": dialect,
                }
        except (json.JSONDecodeError, KeyError, ValueError) as e:
            logger.warning(f"Failed to parse GLM response as JSON: {e}")

        return {
            "mode": InteractionMode.CHAT,
            "reply": content,
            "intent": None,
            "action_payload": None,
            "dialect": dialect,
        }


glm_service = GlmService()
