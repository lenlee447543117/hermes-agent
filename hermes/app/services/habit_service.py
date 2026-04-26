import json
import logging
from datetime import datetime, timedelta
from typing import Optional, List, Dict, Any

from app.models.schemas import HabitProfile, DailyReport, ProactiveCareMessage, DialectMode
from app.services.memory_service import memory_service
from app.services.claude_service import claude_service

logger = logging.getLogger(__name__)


class HabitService:
    def __init__(self):
        self._profiles: Dict[str, HabitProfile] = {}

    async def get_profile(self, user_id: str) -> HabitProfile:
        if user_id not in self._profiles:
            self._profiles[user_id] = HabitProfile(user_id=user_id)
        return self._profiles[user_id]

    async def update_profile(self, user_id: str, updates: dict) -> HabitProfile:
        profile = await self.get_profile(user_id)
        for key, value in updates.items():
            if hasattr(profile, key) and value is not None:
                setattr(profile, key, value)
        self._profiles[user_id] = profile
        return profile

    async def record_action(
        self,
        user_id: str,
        intent: str,
        target: Optional[str] = None,
        success: bool = True,
        timestamp: Optional[int] = None,
    ) -> None:
        action_key = f"hermes:{user_id}:actions"
        action_data = {
            "intent": intent,
            "target": target,
            "success": success,
            "timestamp": timestamp or int(datetime.now().timestamp()),
        }
        if memory_service.redis:
            await memory_service.redis.rpush(action_key, json.dumps(action_data, ensure_ascii=False))
            await memory_service.redis.expire(action_key, 86400 * 30)

    async def get_recent_actions(self, user_id: str, hours: int = 24) -> List[dict]:
        action_key = f"hermes:{user_id}:actions"
        if not memory_service.redis:
            return []

        raw_actions = await memory_service.redis.lrange(action_key, 0, -1)
        cutoff = int((datetime.now() - timedelta(hours=hours)).timestamp())
        actions = []
        for raw in raw_actions:
            try:
                action = json.loads(raw)
                if action.get("timestamp", 0) >= cutoff:
                    actions.append(action)
            except json.JSONDecodeError:
                continue
        return actions

    async def generate_daily_report(self, user_id: str) -> DailyReport:
        actions = await self.get_recent_actions(user_id, hours=24)
        profile = await self.get_profile(user_id)

        intent_counts: Dict[str, int] = {}
        failed_intents: List[str] = []
        targets: Dict[str, int] = {}

        for action in actions:
            intent = action.get("intent", "UNKNOWN")
            intent_counts[intent] = intent_counts.get(intent, 0) + 1
            if not action.get("success", True):
                failed_intents.append(intent)
            target = action.get("target")
            if target:
                targets[target] = targets.get(target, 0) + 1

        active_hours = sorted(set(
            datetime.fromtimestamp(a["timestamp"]).hour
            for a in actions
            if "timestamp" in a
        ))

        anomalies = []
        if not actions:
            anomalies.append("24小时内无任何设备操作")
        if len(failed_intents) >= 3:
            anomalies.append(f"操作频繁失败: {', '.join(set(failed_intents))}")

        report = DailyReport(
            date=datetime.now().strftime("%Y-%m-%d"),
            device_usage_minutes=len(actions) * 2,
            operation_difficulties=list(set(failed_intents)),
            emotion_tendency="neutral",
            anomaly_warnings=anomalies,
        )

        profile.active_hours = active_hours
        if targets:
            sorted_targets = sorted(targets.items(), key=lambda x: x[1], reverse=True)
            profile.frequent_contacts = [t[0] for t in sorted_targets[:5]]
        profile.daily_report = report
        self._profiles[user_id] = profile

        return report

    async def check_proactive_care(self, user_id: str) -> Optional[ProactiveCareMessage]:
        actions = await self.get_recent_actions(user_id, hours=48)
        profile = await self.get_profile(user_id)

        anomaly_info = "无异常"
        should_care = False

        if not actions:
            should_care = True
            anomaly_info = "用户48小时内无任何操作记录"

        daily_actions = [a for a in actions if a.get("timestamp", 0) >= int((datetime.now() - timedelta(hours=24)).timestamp())]
        if not daily_actions and profile.active_hours:
            current_hour = datetime.now().hour
            if current_hour in profile.active_hours:
                should_care = True
                anomaly_info = "用户今日尚未执行日常操作"

        if should_care:
            habit_str = profile.model_dump_json(exclude_none=True)
            care_result = await claude_service.generate_proactive_care(
                habit_profile=habit_str,
                anomaly_info=anomaly_info,
                dialect=profile.dialect_preference,
            )
            return ProactiveCareMessage(
                user_id=user_id,
                message=care_result.get("message", "阿叔，今朝哪能啦？"),
                dialect=profile.dialect_preference,
                care_type=care_result.get("care_type", "routine"),
                priority="normal" if care_result.get("care_type") == "routine" else "high",
            )

        return None


habit_service = HabitService()
