import logging
from typing import List

from fastapi import APIRouter, HTTPException, WebSocket, WebSocketDisconnect, Depends
from app.models.schemas import (
    ChatRequest,
    ChatResponse,
    ActionRequest,
    VlmRequest,
    VlmResponse,
    HabitProfile,
    DailyReport,
    SyncConfigRequest,
    SyncConfigResponse,
    ProactiveCareMessage,
    ErrorResponse,
    DialectMode,
)
from app.services.claude_service import claude_service
from app.services.memory_service import memory_service
from app.services.habit_service import habit_service

logger = logging.getLogger(__name__)
router = APIRouter()


@router.post("/chat", response_model=ChatResponse, responses={1001: {"model": ErrorResponse}})
async def chat(request: ChatRequest):
    try:
        history = await memory_service.get_recent_messages(request.user_id)
        if request.context:
            history = request.context + history

        summary = await memory_service.get_summary(request.user_id)
        habit_context = None
        if summary:
            profile = await habit_service.get_profile(request.user_id)
            habit_context = f"历史摘要: {summary}\n用户画像: {profile.model_dump_json(exclude_none=True)}"

        result = await claude_service.chat(
            user_message=request.message,
            dialect=request.dialect,
            history=history[-20:],
            habit_context=habit_context,
        )

        from app.models.schemas import ChatMessage
        await memory_service.add_message(
            request.user_id,
            ChatMessage(role="user", content=request.message),
        )
        await memory_service.add_message(
            request.user_id,
            ChatMessage(role="assistant", content=result["reply"]),
        )

        if result.get("intent") and result["intent"].value != "UNKNOWN":
            await habit_service.record_action(
                user_id=request.user_id,
                intent=result["intent"].value,
                target=result.get("action_payload", {}).target_person if result.get("action_payload") else None,
            )

        return ChatResponse(**result)

    except Exception as e:
        logger.error(f"Chat error: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/action", responses={2001: {"model": ErrorResponse}})
async def process_action(request: ActionRequest):
    try:
        await habit_service.record_action(
            user_id="default",
            intent=request.payload.intent.value,
            target=request.payload.target_person,
        )
        return {"status": "accepted", "trace_id": request.trace_id}
    except Exception as e:
        logger.error(f"Action processing error: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/vlm/locate", response_model=VlmResponse)
async def vlm_locate(request: VlmRequest):
    try:
        return VlmResponse(x=0.5, y=0.5, confidence=0.0, description="VLM定位需端侧AutoGLM执行")
    except Exception as e:
        logger.error(f"VLM locate error: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/habit/{user_id}", response_model=HabitProfile)
async def get_habit_profile(user_id: str):
    profile = await habit_service.get_profile(user_id)
    return profile


@router.put("/habit/{user_id}", response_model=HabitProfile)
async def update_habit_profile(user_id: str, updates: dict):
    profile = await habit_service.update_profile(user_id, updates)
    return profile


@router.get("/habit/{user_id}/report", response_model=DailyReport)
async def get_daily_report(user_id: str):
    report = await habit_service.generate_daily_report(user_id)
    return report


@router.get("/habit/{user_id}/care", response_model=ProactiveCareMessage)
async def check_proactive_care(user_id: str):
    care = await habit_service.check_proactive_care(user_id)
    if not care:
        return ProactiveCareMessage(
            user_id=user_id,
            message="",
            dialect=DialectMode.SHANGHAI,
            care_type="none",
            priority="none",
        )
    return care


@router.post("/sync-config", response_model=SyncConfigResponse)
async def sync_config(request: SyncConfigRequest):
    try:
        updated_fields = []
        updates = {}

        if request.contacts is not None:
            updates["frequent_contacts"] = [c.get("name", "") for c in request.contacts]
            updated_fields.append("contacts")
        if request.app_whitelist is not None:
            updated_fields.append("app_whitelist")
        if request.dialect_mode is not None:
            updates["dialect_preference"] = request.dialect_mode
            updated_fields.append("dialect_mode")
        if request.volume is not None:
            updates["volume_preference"] = request.volume
            updated_fields.append("volume")
        if request.font_scale is not None:
            updates["font_scale"] = request.font_scale
            updated_fields.append("font_scale")

        if updates:
            await habit_service.update_profile(request.user_id, updates)

        return SyncConfigResponse(
            success=True,
            message="配置同步成功",
            updated_fields=updated_fields,
        )
    except Exception as e:
        logger.error(f"Sync config error: {e}")
        return SyncConfigResponse(success=False, message=str(e))


@router.websocket("/ws/{user_id}")
async def websocket_endpoint(websocket: WebSocket, user_id: str):
    await websocket.accept()
    logger.info(f"WebSocket connected for user: {user_id}")

    try:
        while True:
            data = await websocket.receive_text()
            import json
            try:
                msg = json.loads(data)
                message = msg.get("message", "")

                history = await memory_service.get_recent_messages(user_id)
                result = await claude_service.chat(
                    user_message=message,
                    dialect=DialectMode(msg.get("dialect", "shanghai")),
                    history=history[-20:],
                )

                await websocket.send_json(result)

            except json.JSONDecodeError:
                await websocket.send_json({"error": "Invalid JSON"})

    except WebSocketDisconnect:
        logger.info(f"WebSocket disconnected for user: {user_id}")
