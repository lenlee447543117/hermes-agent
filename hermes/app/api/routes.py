import json
import logging

from fastapi import APIRouter, HTTPException, WebSocket, WebSocketDisconnect, Request
from app.models.schemas import (
    ChatRequest,
    ChatResponse,
    ChatMessage,
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
    RobotEmotion,
    RobotUiState,
)
from app.services.glm_service import glm_service
from app.services.memory_service import memory_service
from app.services.habit_service import habit_service

logger = logging.getLogger(__name__)
router = APIRouter()


async def _get_habit_context(user_id: str) -> str | None:
    summary = await memory_service.get_summary(user_id)
    if not summary:
        return None
    profile = await habit_service.get_profile(user_id)
    return f"历史摘要: {summary}\n用户画像: {profile.model_dump_json(exclude_none=True)}"


def _build_robot_ui_state(result: dict) -> RobotUiState | None:
    mode = result.get("mode")
    intent = result.get("intent")
    if mode and mode.value == "COMMAND" and intent:
        return RobotUiState(
            emotion=RobotEmotion.FOCUS,
            status_text=f"正在执行: {intent.value}",
            show_mask=True,
        )
    reply = result.get("reply", "")
    if any(kw in reply for kw in ["啊呀", "等一歇", "抱歉", "无法"]):
        return RobotUiState(
            emotion=RobotEmotion.WORRIED,
            status_text="遇到问题",
            show_mask=False,
        )
    return RobotUiState(
        emotion=RobotEmotion.SPEAKING,
        status_text="",
        show_mask=False,
    )


@router.post("/chat", response_model=ChatResponse, responses={1001: {"model": ErrorResponse}})
async def chat(request: ChatRequest):
    try:
        history = await memory_service.get_recent_messages(request.user_id)
        if request.context:
            history = request.context + history

        habit_context = await _get_habit_context(request.user_id)

        result = await glm_service.chat(
            user_message=request.message,
            dialect=request.dialect,
            history=history[-20:],
            habit_context=habit_context,
        )

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

        result["robot_ui_state"] = _build_robot_ui_state(result)
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
            try:
                msg = json.loads(data)
                message = msg.get("message", "")

                history = await memory_service.get_recent_messages(user_id)
                try:
                    dialect = DialectMode(msg.get("dialect", "shanghai"))
                except ValueError:
                    dialect = DialectMode.SHANGHAI

                result = await glm_service.chat(
                    user_message=message,
                    dialect=dialect,
                    history=history[-20:],
                    habit_context=await _get_habit_context(user_id),
                )

                await memory_service.add_message(
                    user_id,
                    ChatMessage(role="user", content=message),
                )
                await memory_service.add_message(
                    user_id,
                    ChatMessage(role="assistant", content=result.get("reply", "")),
                )

                result["robot_ui_state"] = _build_robot_ui_state(result)
                await websocket.send_json(result)

            except json.JSONDecodeError:
                await websocket.send_json({"error": "Invalid JSON"})

    except WebSocketDisconnect:
        logger.info(f"WebSocket disconnected for user: {user_id}")
