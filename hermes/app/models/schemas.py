from datetime import datetime
from typing import Optional, List
from pydantic import BaseModel, Field
from enum import Enum


class IntentType(str, Enum):
    WECHAT_VIDEO_CALL = "WECHAT_VIDEO_CALL"
    WECHAT_VOICE_CALL = "WECHAT_VOICE_CALL"
    PHONE_CALL = "PHONE_CALL"
    CALL_TAXI = "CALL_TAXI"
    LAUNCH_APP = "LAUNCH_APP"
    CONTROL_VOLUME = "CONTROL_VOLUME"
    SYSTEM_SETTING = "SYSTEM_SETTING"
    SET_ALARM = "SET_ALARM"
    SEARCH_WEB = "SEARCH_WEB"
    QUERY_WEATHER = "QUERY_WEATHER"
    READ_NEWS = "READ_NEWS"
    MEDICINE_REMINDER = "MEDICINE_REMINDER"
    SHARE_LOCATION = "SHARE_LOCATION"
    EMERGENCY_SOS = "EMERGENCY_SOS"
    CHAT = "CHAT"
    HELP = "HELP"
    UNKNOWN = "UNKNOWN"


class InteractionMode(str, Enum):
    COMMAND = "COMMAND"
    CHAT = "CHAT"


class DialectMode(str, Enum):
    SHANGHAI = "shanghai"
    MANDARIN = "mandarin"


class ActionRequest(BaseModel):
    trace_id: str = Field(default_factory=lambda: __import__("uuid").uuid4().hex)
    timestamp: int = Field(default_factory=lambda: int(datetime.now().timestamp()))
    type: str = "ACTION_REQUEST"
    payload: "ActionPayload"


class ActionPayload(BaseModel):
    intent: IntentType
    target_person: Optional[str] = None
    parameters: Optional[dict] = None


class VlmRequest(BaseModel):
    user_id: str
    screen_snapshot: str
    query: str
    context: Optional[str] = None


class VlmResponse(BaseModel):
    x: float
    y: float
    confidence: float = 1.0
    description: Optional[str] = None


class ChatMessage(BaseModel):
    role: str
    content: str
    timestamp: int = Field(default_factory=lambda: int(datetime.now().timestamp()))


class ChatRequest(BaseModel):
    user_id: str
    message: str
    dialect: DialectMode = DialectMode.SHANGHAI
    context: Optional[List[ChatMessage]] = None


class RobotEmotion(str, Enum):
    IDLE = "IDLE"
    HAPPY = "HAPPY"
    FOCUS = "FOCUS"
    THINKING = "THINKING"
    WORRIED = "WORRIED"
    SPEAKING = "SPEAKING"


class RobotUiState(BaseModel):
    emotion: RobotEmotion = RobotEmotion.IDLE
    status_text: str = ""
    show_mask: bool = False


class ChatResponse(BaseModel):
    reply: str
    mode: InteractionMode
    intent: Optional[IntentType] = None
    action_payload: Optional[ActionPayload] = None
    dialect: DialectMode = DialectMode.SHANGHAI
    robot_ui_state: Optional[RobotUiState] = None


class HabitProfile(BaseModel):
    user_id: str
    active_hours: List[int] = Field(default_factory=list)
    frequent_contacts: List[str] = Field(default_factory=list)
    preferred_call_type: Optional[str] = None
    dialect_preference: DialectMode = DialectMode.SHANGHAI
    volume_preference: int = 70
    font_scale: float = 1.5
    chat_frequency: float = 0.0
    daily_report: Optional["DailyReport"] = None


class DailyReport(BaseModel):
    date: str
    sleep_status: Optional[str] = None
    device_usage_minutes: int = 0
    operation_difficulties: List[str] = Field(default_factory=list)
    emotion_tendency: Optional[str] = None
    anomaly_warnings: List[str] = Field(default_factory=list)


class SyncConfigRequest(BaseModel):
    user_id: str
    contacts: Optional[List[dict]] = None
    app_whitelist: Optional[List[str]] = None
    care_rules: Optional[dict] = None
    dialect_mode: Optional[DialectMode] = None
    volume: Optional[int] = None
    font_scale: Optional[float] = None


class SyncConfigResponse(BaseModel):
    success: bool
    message: str
    updated_fields: List[str] = Field(default_factory=list)


class ProactiveCareMessage(BaseModel):
    user_id: str
    message: str
    dialect: DialectMode = DialectMode.SHANGHAI
    care_type: str = "routine"
    priority: str = "normal"


class ErrorResponse(BaseModel):
    error_code: int
    message: str
    detail: Optional[str] = None


ActionRequest.model_rebuild()
HabitProfile.model_rebuild()
