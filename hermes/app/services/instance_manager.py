import logging
from typing import Optional, Dict, List
from dataclasses import dataclass, field
from datetime import datetime
from enum import Enum

logger = logging.getLogger(__name__)


class InstanceStatus(str, Enum):
    RUNNING = "running"
    PAUSED = "paused"
    STOPPED = "stopped"
    STARTING = "starting"
    ERROR = "error"


@dataclass
class HermesInstance:
    user_id: str
    container_id: Optional[str] = None
    port: int = 8000
    status: InstanceStatus = InstanceStatus.STOPPED
    created_at: datetime = field(default_factory=datetime.now)
    last_active: datetime = field(default_factory=datetime.now)
    memory_usage_mb: float = 0.0
    cpu_percent: float = 0.0


class InstanceManager:
    def __init__(self):
        self._instances: Dict[str, HermesInstance] = {}

    async def create_instance(self, user_id: str, port: int = 8000) -> HermesInstance:
        if user_id in self._instances:
            instance = self._instances[user_id]
            if instance.status == InstanceStatus.RUNNING:
                return instance
            if instance.status == InstanceStatus.PAUSED:
                return await self.unpause_instance(user_id)

        instance = HermesInstance(
            user_id=user_id,
            port=port,
            status=InstanceStatus.STARTING,
        )
        self._instances[user_id] = instance

        try:
            logger.info(f"Creating Hermes instance for user: {user_id}")
            instance.status = InstanceStatus.RUNNING
            instance.container_id = f"hermes-{user_id}"
            logger.info(f"Hermes instance created: {user_id} on port {port}")
        except Exception as e:
            instance.status = InstanceStatus.ERROR
            logger.error(f"Failed to create instance for {user_id}: {e}")
            raise

        return instance

    async def pause_instance(self, user_id: str) -> Optional[HermesInstance]:
        instance = self._instances.get(user_id)
        if not instance or instance.status != InstanceStatus.RUNNING:
            return None

        try:
            logger.info(f"Pausing instance: {user_id}")
            instance.status = InstanceStatus.PAUSED
            logger.info(f"Instance paused: {user_id}")
        except Exception as e:
            logger.error(f"Failed to pause instance {user_id}: {e}")
            instance.status = InstanceStatus.ERROR

        return instance

    async def unpause_instance(self, user_id: str) -> Optional[HermesInstance]:
        instance = self._instances.get(user_id)
        if not instance or instance.status != InstanceStatus.PAUSED:
            return None

        try:
            logger.info(f"Unpausing instance: {user_id}")
            instance.status = InstanceStatus.RUNNING
            instance.last_active = datetime.now()
            logger.info(f"Instance unpaused: {user_id}")
        except Exception as e:
            logger.error(f"Failed to unpause instance {user_id}: {e}")
            instance.status = InstanceStatus.ERROR

        return instance

    async def stop_instance(self, user_id: str) -> bool:
        instance = self._instances.get(user_id)
        if not instance:
            return False

        try:
            logger.info(f"Stopping instance: {user_id}")
            instance.status = InstanceStatus.STOPPED
            logger.info(f"Instance stopped: {user_id}")
        except Exception as e:
            logger.error(f"Failed to stop instance {user_id}: {e}")
            instance.status = InstanceStatus.ERROR
            return False

        return True

    async def get_instance(self, user_id: str) -> Optional[HermesInstance]:
        return self._instances.get(user_id)

    async def list_instances(self) -> List[HermesInstance]:
        return list(self._instances.values())

    async def health_check(self, user_id: str) -> Dict:
        instance = self._instances.get(user_id)
        if not instance:
            return {"status": "not_found", "user_id": user_id}

        return {
            "status": instance.status.value,
            "user_id": user_id,
            "port": instance.port,
            "last_active": instance.last_active.isoformat(),
            "memory_usage_mb": instance.memory_usage_mb,
            "cpu_percent": instance.cpu_percent,
        }


instance_manager = InstanceManager()
