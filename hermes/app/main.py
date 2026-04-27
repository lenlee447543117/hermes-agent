import logging
import time
from contextlib import asynccontextmanager
from datetime import datetime
from starlette.middleware.base import BaseHTTPMiddleware
from starlette.requests import Request as StarletteRequest
from starlette.responses import Response as StarletteResponse

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from app.config.settings import settings
from app.api.routes import router
from app.api.admin_routes import router as admin_router
from app.services.memory_service import memory_service
from app.services.glm_service import glm_service

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
)
logger = logging.getLogger(__name__)


@asynccontextmanager
async def lifespan(app: FastAPI):
    logger.info(f"🧠 Hermes {settings.APP_VERSION} starting for user: {settings.USER_ID}")
    await memory_service.connect()
    logger.info("✅ Memory service (Redis) connected")
    yield
    await glm_service.close()
    await memory_service.disconnect()
    logger.info("👋 Hermes shutting down")


app = FastAPI(
    title=settings.APP_NAME,
    version=settings.APP_VERSION,
    description="AI沪老 Hermes 智能体后端 - 专属老年数字伴侣",
    lifespan=lifespan,
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

app.include_router(router, prefix="/api/v1", tags=["Hermes"])
app.include_router(admin_router, prefix="/api/v1/admin", tags=["Admin"])


class TimingMiddleware(BaseHTTPMiddleware):
    async def dispatch(self, request: StarletteRequest, call_next):
        start = time.time()
        response: StarletteResponse = await call_next(request)
        elapsed = time.time() - start
        response.headers["X-Process-Time"] = f"{elapsed:.3f}s"
        return response


class SecurityHeadersMiddleware(BaseHTTPMiddleware):
    async def dispatch(self, request: StarletteRequest, call_next):
        response: StarletteResponse = await call_next(request)
        response.headers["X-Content-Type-Options"] = "nosniff"
        response.headers["X-Frame-Options"] = "DENY"
        response.headers["X-XSS-Protection"] = "1; mode=block"
        return response


app.add_middleware(TimingMiddleware)
app.add_middleware(SecurityHeadersMiddleware)


@app.get("/health")
async def health_check():
    return {
        "status": "healthy",
        "service": settings.APP_NAME,
        "version": settings.APP_VERSION,
        "user_id": settings.USER_ID,
        "timestamp": int(datetime.now().timestamp()),
    }


@app.get("/")
async def root():
    return {
        "service": settings.APP_NAME,
        "version": settings.APP_VERSION,
        "docs": "/docs",
    }
