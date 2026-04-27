from sqlalchemy import Column, String, Integer, Float, DateTime, JSON, create_engine, func
from sqlalchemy.orm import DeclarativeBase, sessionmaker
from app.config.settings import settings


class Base(DeclarativeBase):
    pass


class HabitProfileORM(Base):
    __tablename__ = "habit_profiles"

    user_id = Column(String, primary_key=True, index=True)
    active_hours = Column(JSON, default=list)
    frequent_contacts = Column(JSON, default=list)
    preferred_call_type = Column(String, nullable=True)
    dialect_preference = Column(String, default="shanghai")
    volume_preference = Column(Integer, default=70)
    font_scale = Column(Float, default=1.5)
    chat_frequency = Column(Float, default=0.0)
    created_at = Column(DateTime, server_default=func.now())
    updated_at = Column(DateTime, server_default=func.now(), onupdate=func.now())


class ActionRecordORM(Base):
    __tablename__ = "action_records"

    id = Column(Integer, primary_key=True, autoincrement=True)
    user_id = Column(String, index=True, nullable=False)
    intent = Column(String, nullable=False)
    target = Column(String, nullable=True)
    success = Column(Integer, default=1)
    timestamp = Column(Integer, nullable=False)
    created_at = Column(DateTime, server_default=func.now())


class DailyReportORM(Base):
    __tablename__ = "daily_reports"

    id = Column(Integer, primary_key=True, autoincrement=True)
    user_id = Column(String, index=True, nullable=False)
    date = Column(String, nullable=False)
    sleep_status = Column(String, nullable=True)
    device_usage_minutes = Column(Integer, default=0)
    operation_difficulties = Column(JSON, default=list)
    emotion_tendency = Column(String, nullable=True)
    anomaly_warnings = Column(JSON, default=list)
    created_at = Column(DateTime, server_default=func.now())


def get_engine():
    return create_engine(
        settings.DATABASE_URL.replace("+asyncpg", ""),
        pool_size=5,
        max_overflow=10,
        pool_pre_ping=True,
    )


def get_session_factory():
    engine = get_engine()
    Base.metadata.create_all(engine)
    return sessionmaker(bind=engine)
