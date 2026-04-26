import logging
from fastapi import APIRouter, HTTPException
from app.services.instance_manager import instance_manager, InstanceStatus
from app.models.schemas import ErrorResponse

logger = logging.getLogger(__name__)
router = APIRouter(prefix="/instances", tags=["Instance Management"])


@router.post("/{user_id}/start")
async def start_instance(user_id: str, port: int = 8000):
    try:
        instance = await instance_manager.create_instance(user_id, port)
        return {"status": instance.status.value, "user_id": user_id, "port": port}
    except Exception as e:
        logger.error(f"Start instance failed: {e}")
        raise HTTPException(status_code=4001, detail=str(e))


@router.post("/{user_id}/pause")
async def pause_instance(user_id: str):
    instance = await instance_manager.pause_instance(user_id)
    if not instance:
        raise HTTPException(status_code=404, detail="Instance not found or not running")
    return {"status": instance.status.value, "user_id": user_id}


@router.post("/{user_id}/unpause")
async def unpause_instance(user_id: str):
    instance = await instance_manager.unpause_instance(user_id)
    if not instance:
        raise HTTPException(status_code=404, detail="Instance not found or not paused")
    return {"status": instance.status.value, "user_id": user_id}


@router.post("/{user_id}/stop")
async def stop_instance(user_id: str):
    success = await instance_manager.stop_instance(user_id)
    if not success:
        raise HTTPException(status_code=404, detail="Instance not found")
    return {"status": "stopped", "user_id": user_id}


@router.get("/{user_id}/health")
async def instance_health(user_id: str):
    return await instance_manager.health_check(user_id)


@router.get("/")
async def list_instances():
    instances = await instance_manager.list_instances()
    return [
        {
            "user_id": i.user_id,
            "status": i.status.value,
            "port": i.port,
            "last_active": i.last_active.isoformat(),
        }
        for i in instances
    ]
