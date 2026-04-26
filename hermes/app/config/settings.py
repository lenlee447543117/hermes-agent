from pydantic_settings import BaseSettings
from typing import Optional


class Settings(BaseSettings):
    APP_NAME: str = "Hermes AI Agent"
    APP_VERSION: str = "2.0.0"
    DEBUG: bool = False

    ZHIPU_API_KEY: str = ""
    GLM_47_API_KEY: str = ""
    AUTOGLM_PHONE_API_KEY: str = ""
    GLM_MODEL: str = "glm-4.7"
    AUTOGLM_MODEL: str = "autoglm-phone"
    GLM_MAX_TOKENS: int = 4096

    REDIS_URL: str = "redis://localhost:6379/0"
    DATABASE_URL: str = "postgresql+asyncpg://hermes:hermes_secret@localhost:5432/hermes"

    SUPABASE_URL: str = ""
    SUPABASE_ANON_KEY: str = ""

    USER_ID: str = "default"

    DIALECT_DEFAULT: str = "shanghai"
    DIALECT_TTS_VOICE: str = "zh-CN-shanghai"
    DIALECT_ASR_LOCALE: str = "zh-CN-shanghai"

    MEMORY_SHORT_TERM_TTL: int = 3600
    MEMORY_SUMMARY_THRESHOLD: int = 50
    MEMORY_SUMMARY_INTERVAL_HOURS: int = 24

    HABIT_ANALYSIS_HOUR: int = 2
    PROACTIVE_CARE_INTERVAL_MINUTES: int = 30

    WEBSOCKET_HEARTBEAT_INTERVAL: int = 30

    ERROR_CODE_ASR_FAILED: int = 1001
    ERROR_CODE_INTENT_CONFLICT: int = 1002
    ERROR_CODE_AUTOMATION_TIMEOUT: int = 2001
    ERROR_CODE_INSTANCE_START_FAILED: int = 4001

    class Config:
        env_file = ".env"
        env_file_encoding = "utf-8"


settings = Settings()
