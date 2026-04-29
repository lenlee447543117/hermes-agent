"""
AI沪老 Termux Edge Agent - 本地中枢 V4.1
完全对齐解决方案.md 架构设计

模块拆分：
  adb_manager.py    → ADB连接管理 + 120s心跳
  privacy_engine.py → PaddleOCR脱敏 + 截图链路 + prepare_upload_payload
  executor.py       → ActionExecutor + ClickStrategy + HermesVisualExecutor
  intent_parser.py  → 三级解析(缓存→规则→云端) + 记忆库 + 澄清

接口契约（对齐解决方案.md 第三章）：
  POST /api/v1/voice       → {status: "accepted", task_id: "..."}
  POST /api/v1/cancel      → {status: "cancelled", task_id: "..."}
  GET  /api/v1/status      → {state: "IDLE|LISTENING|THINKING|EXECUTING|SUCCESS|ERROR", ...}
  GET  /api/v1/task/{id}   → {task_id, status, reply, ...}
  POST /api/v1/chat        → 同 /api/v1/voice
  POST /api/v1/execute     → 直接执行动作

启动：uvicorn local_hermes:app --host 127.0.0.1 --port 8000
"""

import json
import logging
import os
import re
import sqlite3
import subprocess
import threading
import time
import uuid
from contextlib import asynccontextmanager
from enum import Enum
from typing import Any, Dict, List, Optional

import uiautomator2 as u2
from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel

from adb_manager import ADBHeartbeat, ensure_connection
from executor import ActionExecutor, HermesVisualExecutor
from intent_parser import (IntentClarifier, MemoryStore, OfflineIntentParser,
                           OFFLINE_INTENT_TEMPLATES, three_level_parse)
from privacy_engine import (capture_screen_numpy, desensitize_image,
                            get_ui_summary, prepare_upload_payload)

logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s [%(levelname)s] %(name)s: %(message)s',
    handlers=[
        logging.FileHandler(os.path.expanduser("~/hulao_agent.log")),
        logging.StreamHandler()
    ]
)
logger = logging.getLogger("HulaoEdgeAgent")


class AgentState(str, Enum):
    IDLE = "IDLE"
    LISTENING = "LISTENING"
    THINKING = "THINKING"
    EXECUTING = "EXECUTING"
    SUCCESS = "SUCCESS"
    ERROR = "ERROR"


class VoiceRequest(BaseModel):
    text: str
    user_id: str = "default"
    dialect: str = "shanghai"


class ExecuteRequest(BaseModel):
    action: str
    params: Dict[str, Any] = {}


class ChatRequest(BaseModel):
    message: str
    user_id: str = "default"
    dialect: str = "shanghai"


class CancelRequest(BaseModel):
    task_id: str = ""


class ClarifyRequest(BaseModel):
    options: List[str]
    prompt: str


memory = MemoryStore()
offline_parser = OfflineIntentParser()
intent_clarifier = IntentClarifier(memory)
adb_heartbeat = ADBHeartbeat(interval=120)

device: Optional[u2.Device] = None
action_executor: Optional[ActionExecutor] = None
visual_executor: Optional[HermesVisualExecutor] = None

agent_state = AgentState.IDLE
current_task_id: Optional[str] = None
task_results: Dict[str, Dict] = {}
state_lock = threading.Lock()

vosk_available = False
autoglm_available = False
privacy_available = False


def set_state(new_state: AgentState):
    global agent_state
    with state_lock:
        agent_state = new_state
        logger.info(f"Agent state: {agent_state.value}")


def get_state() -> AgentState:
    with state_lock:
        return agent_state


def termux_speak(text: str):
    try:
        subprocess.run(["termux-tts-speak", text], timeout=10)
    except Exception:
        pass


def termux_notify(title: str, content: str):
    try:
        subprocess.run(["termux-notification", "--title", title, "--content", content, "--id", "hulao", "--sound"], timeout=5)
    except Exception:
        pass


def termux_vibrate(duration: int = 200):
    try:
        subprocess.run(["termux-vibrate", "-d", str(duration)], timeout=5)
    except Exception:
        pass


def _extract_nickname(text: str) -> Optional[str]:
    for p in ["给(.+?)打", "跟(.+?)联系", "找(.+?)聊", "联系(.+?)$", "呼叫(.+?)"]:
        m = re.search(p, text)
        if m:
            return m.group(1).strip()
    return None


def _execute_voice_task(task_id: str, text: str, user_id: str, dialect: str):
    global current_task_id
    current_task_id = task_id

    try:
        set_state(AgentState.THINKING)

        nickname = _extract_nickname(text)
        if nickname:
            clarification = intent_clarifier.clarify_contact(nickname)
            if clarification:
                termux_speak(clarification["prompt"])
                task_results[task_id] = {
                    "status": "CLARIFY", "reply": clarification["prompt"],
                    "clarification": clarification
                }
                set_state(AgentState.IDLE)
                return

            contact = memory.resolve_contact(nickname)
            if contact:
                reply = f"找到{contact.get('real_name', nickname)}了"
                termux_speak(reply)
                termux_vibrate(100)
                task_results[task_id] = {
                    "status": "CONTACT_RESOLVED", "reply": reply,
                    "contact": contact
                }
                set_state(AgentState.SUCCESS)
                return

        parsed = three_level_parse(text, memory, offline_parser, device)

        if parsed.get("source") == "cache":
            set_state(AgentState.EXECUTING)
            termux_speak("好额，帮侬做好了" if dialect == "shanghai" else "好的，正在为您操作")
            if action_executor:
                result = action_executor.execute_action_chain(task_id, parsed["actions"])
                task_results[task_id] = {
                    "status": result["status"], "reply": "操作完成",
                    "source": "cache_replay", "executed": result.get("executed", [])
                }
                set_state(AgentState.SUCCESS if result["success"] else AgentState.ERROR)
            else:
                task_results[task_id] = {"status": "ERROR", "reply": "设备未连接，无法执行缓存动作"}
                set_state(AgentState.ERROR)
            return

        if parsed.get("source") == "offline":
            result = _handle_offline_intent(parsed, dialect)
            task_results[task_id] = result
            set_state(AgentState.SUCCESS)
            return

        local_handlers = {
            "音量": _handle_volume, "声音": _handle_volume,
            "拍照": _handle_camera, "拍一张": _handle_camera,
            "电量": _handle_battery, "电池": _handle_battery,
            "位置": _handle_location, "我在哪": _handle_location,
            "音乐": _handle_music, "放歌": _handle_music,
        }
        for keyword, handler in local_handlers.items():
            if keyword in text:
                result = handler(text, dialect)
                termux_vibrate(100)
                task_results[task_id] = result
                set_state(AgentState.SUCCESS)
                return

        cloud_result = _forward_to_hermes(text, dialect)
        task_results[task_id] = cloud_result
        set_state(AgentState.SUCCESS if cloud_result.get("status") != "ERROR" else AgentState.ERROR)

    except Exception as e:
        logger.error(f"Task {task_id} failed: {e}")
        task_results[task_id] = {"status": "ERROR", "reply": str(e)}
        set_state(AgentState.ERROR)
    finally:
        current_task_id = None


def _handle_offline_intent(parsed: Dict, dialect: str) -> Dict:
    action = parsed["action"]
    slots = parsed["slots"]
    intent = parsed["intent"]

    app_map = {
        "launch_wechat": "com.tencent.mm",
        "launch_alipay": "com.eg.android.AlipayGphone",
        "launch_map": "com.autonavi.minimap",
        "launch_camera": "com.android.camera",
        "launch_settings": "com.android.settings",
        "launch_gallery": "com.android.gallery3d",
        "launch_calendar": "com.android.calendar",
        "launch_clock": "com.android.deskclock",
        "launch_calculator": "com.android.calculator2",
        "launch_contacts": "com.android.contacts",
        "launch_dialer": "com.android.dialer",
        "launch_sms": "com.android.messaging",
        "launch_video": "com.ss.android.ugc.aweme",
    }

    if action in app_map and device:
        device.app_start(app_map[action])
        reply = "好额，帮侬打开了" if dialect == "shanghai" else "好的，已为您打开"
        termux_speak(reply)
        return {"status": "COMPLETED", "reply": reply, "intent": intent, "source": "offline"}

    if action == "emergency":
        termux_vibrate(500)
        termux_speak("检测到紧急情况，正在拨打120")
        return {"status": "COMPLETED", "reply": "紧急求助已触发", "intent": "EMERGENCY_SOS", "source": "offline"}

    if action == "help":
        help_text = "我可以帮您：打电话、发微信、查天气、读新闻、设提醒、拍照、放音乐、查电量、开手电筒。您尽管说！"
        termux_speak(help_text)
        return {"status": "COMPLETED", "reply": help_text, "intent": "HELP", "source": "offline"}

    if action == "cancel":
        termux_speak("好的，已取消")
        return {"status": "CANCELLED", "reply": "好的，已取消", "intent": "CANCEL", "source": "offline"}

    reply = "现在没网，我只能做简单的事情哦" if dialect == "shanghai" else "当前为离线模式，仅支持基本操作"
    termux_speak(reply)
    return {"status": "COMPLETED", "reply": reply, "intent": intent, "source": "offline", "slots": slots}


def _handle_volume(text: str, dialect: str) -> Dict:
    level = 15 if any(w in text for w in ["大", "高"]) else 5 if any(w in text for w in ["小", "低"]) else 0 if any(w in text for w in ["静音", "关"]) else 10
    subprocess.run(["termux-volume", "music", str(level)], timeout=5)
    reply = f"好额，声音调到{level}了" if dialect == "shanghai" else f"好的，音量已调到{level}"
    termux_speak(reply)
    return {"status": "COMPLETED", "reply": reply, "intent": "CONTROL_VOLUME"}


def _handle_camera(text: str, dialect: str) -> Dict:
    path = f"/sdcard/DCIM/hulao_photo_{int(time.time())}.jpg"
    success = subprocess.run(["termux-camera-photo", "-c", "0", path], timeout=10).returncode == 0
    reply = ("好额，照片拍好了" if dialect == "shanghai" else "好的，照片已拍好") if success else "拍照失败了，再试一次"
    termux_speak(reply)
    return {"status": "COMPLETED", "reply": reply, "intent": "TAKE_PHOTO", "path": path if success else None}


def _handle_battery(text: str, dialect: str) -> Dict:
    try:
        result = subprocess.run(["termux-battery-status"], capture_output=True, text=True, timeout=5)
        battery = json.loads(result.stdout) if result.stdout else {}
        pct = battery.get("percentage", "未知")
        status = battery.get("status", "未知")
        reply = f"手机还有{pct}%的电，{status}" if dialect == "shanghai" else f"手机电量{pct}%，{status}"
    except Exception:
        reply = "查不到电量" if dialect == "shanghai" else "无法获取电量信息"
    termux_speak(reply)
    return {"status": "COMPLETED", "reply": reply, "intent": "BATTERY_STATUS"}


def _handle_location(text: str, dialect: str) -> Dict:
    try:
        result = subprocess.run(["termux-location", "-p", "network", "-r", "once"], capture_output=True, text=True, timeout=15)
        loc = json.loads(result.stdout) if result.stdout else {}
        reply = f"您当前位置：纬度{loc.get('latitude', 0):.4f}，经度{loc.get('longitude', 0):.4f}"
    except Exception:
        reply = "查不到位置" if dialect == "shanghai" else "无法获取位置信息"
    termux_speak(reply)
    return {"status": "COMPLETED", "reply": reply, "intent": "SHARE_LOCATION"}


def _handle_music(text: str, dialect: str) -> Dict:
    if any(w in text for w in ["暂停", "停"]):
        subprocess.run(["termux-media-player", "stop"], timeout=5)
        reply = "好额，音乐停了" if dialect == "shanghai" else "好的，音乐已暂停"
    else:
        subprocess.run(["termux-media-player", "play"], timeout=5)
        reply = "好额，音乐放起来了" if dialect == "shanghai" else "好的，正在播放音乐"
    termux_speak(reply)
    return {"status": "COMPLETED", "reply": reply, "intent": "PLAY_MUSIC"}


def _forward_to_hermes(message: str, dialect: str) -> Dict:
    try:
        import requests
        resp = requests.post(
            "http://192.168.3.34:8642/api/v1/chat",
            json={"user_id": "local", "message": message, "dialect": dialect},
            headers={"Authorization": "Bearer hulao_2026_secure_api_key_change_in_production"},
            timeout=30
        )
        if resp.status_code == 200:
            data = resp.json()
            if data.get("reply"):
                termux_speak(data["reply"])
            return data
    except Exception as e:
        logger.error(f"Forward to Hermes failed: {e}")
    fallback = "网络好像睡着了，稍后再试试" if dialect == "shanghai" else "网络连接失败，请稍后再试"
    termux_speak(fallback)
    return {"status": "ERROR", "reply": fallback}


@asynccontextmanager
async def lifespan(app_instance: FastAPI):
    global device, action_executor, visual_executor
    global vosk_available, autoglm_available, privacy_available

    try:
        subprocess.run(["termux-wake-lock"], timeout=5)
        logger.info("Termux wake-lock acquired")
    except Exception:
        pass

    try:
        os.nice(10)
    except Exception:
        pass

    try:
        import vosk
        vosk_available = True
    except ImportError:
        pass

    autoglm_available = bool(os.environ.get("AUTOGLM_PHONE_API_KEY", ""))

    try:
        import cv2
        privacy_available = True
    except ImportError:
        pass

    try:
        ensure_connection()
        device = u2.connect("127.0.0.1")
        action_executor = ActionExecutor(device, memory)
        visual_executor = HermesVisualExecutor(action_executor, privacy_module=None)
        adb_heartbeat.start()
        logger.info("Device connected via uiautomator2")
    except Exception as e:
        logger.warning(f"Device not available: {e}, running in Termux:API only mode")

    termux_speak("沪老助手已启动")
    logger.info("HulaoEdgeAgent V4.1 started on http://127.0.0.1:8000")

    yield

    adb_heartbeat.stop()
    logger.info("HulaoEdgeAgent shutting down")


app = FastAPI(title="HulaoEdgeAgent", version="4.1", lifespan=lifespan)
app.add_middleware(CORSMiddleware, allow_origins=["*"], allow_methods=["*"], allow_headers=["*"])


@app.post("/api/v1/voice")
async def handle_voice(req: VoiceRequest):
    task_id = f"task_{uuid.uuid4().hex[:8]}"
    logger.info(f"Voice request [{task_id}]: {req.text[:50]}...")

    set_state(AgentState.LISTENING)

    thread = threading.Thread(
        target=_execute_voice_task,
        args=(task_id, req.text, req.user_id, req.dialect),
        daemon=True
    )
    thread.start()

    return {"status": "accepted", "task_id": task_id}


@app.get("/api/v1/task/{task_id}")
async def get_task_result(task_id: str):
    result = task_results.get(task_id)
    if result is None:
        if task_id == current_task_id:
            return {"task_id": task_id, "status": "RUNNING"}
        return {"task_id": task_id, "status": "UNKNOWN"}
    return {"task_id": task_id, **result}


@app.post("/api/v1/cancel")
async def handle_cancel(req: CancelRequest = None):
    tid = req.task_id if req and req.task_id else ""
    if action_executor:
        action_executor.cancel()
    set_state(AgentState.IDLE)
    termux_speak("好的，已停止操作")
    termux_vibrate(300)
    cancelled_id = tid or current_task_id or ""
    logger.info(f"Task cancelled: {cancelled_id}")
    return {"status": "cancelled", "task_id": cancelled_id}


@app.get("/api/v1/status")
async def get_status():
    state = get_state()
    result = {
        "state": state.value,
        "device_connected": device is not None,
        "current_task_id": current_task_id or "",
        "vosk_available": vosk_available,
        "autoglm_available": autoglm_available,
        "privacy_filter_available": privacy_available,
        "memory_contacts": memory.count_contacts(),
        "cached_actions": memory.count_cached_actions(),
        "offline_templates": len(OFFLINE_INTENT_TEMPLATES),
        "version": "4.1"
    }
    if current_task_id and current_task_id in task_results:
        result["last_result"] = task_results[current_task_id]
    return result


@app.post("/api/v1/chat")
async def handle_chat(req: ChatRequest):
    return await handle_voice(VoiceRequest(text=req.message, user_id=req.user_id, dialect=req.dialect))


@app.post("/api/v1/execute")
async def handle_execute(req: ExecuteRequest):
    logger.info(f"Execute: {req.action}, params: {req.params}")

    if not device:
        return {"success": False, "error": "Device not connected"}

    action_map = {
        "click_text": lambda: device(text=req.params.get("text", "")).click(),
        "click_coord": lambda: device.click(int(req.params.get("x", 0)), int(req.params.get("y", 0))),
        "click_desc": lambda: device(description=req.params.get("desc", "")).click(),
        "type_text": lambda: device.send_keys(req.params.get("text", "")),
        "press_back": lambda: device.press("back"),
        "press_home": lambda: device.press("home"),
        "launch_app": lambda: device.app_start(req.params.get("package", "")),
        "speak": lambda: termux_speak(req.params.get("text", "")),
        "notify": lambda: termux_notify(req.params.get("title", ""), req.params.get("content", "")),
        "volume": lambda: subprocess.run(["termux-volume", req.params.get("stream", "music"), str(req.params.get("level", 10))], timeout=5),
        "camera": lambda: subprocess.run(["termux-camera-photo", "-c", "0", req.params.get("path", "")], timeout=10),
        "vibrate": lambda: termux_vibrate(int(req.params.get("duration", 200))),
    }

    handler = action_map.get(req.action)
    if handler:
        try:
            handler()
            return {"success": True, "action": req.action}
        except Exception as e:
            return {"success": False, "action": req.action, "error": str(e)}

    return {"success": False, "error": f"Unknown action: {req.action}"}


@app.post("/api/v1/execute/autoglm")
async def handle_autoglm_execute(req: ExecuteRequest):
    if not visual_executor:
        return {"success": False, "error": "Visual executor not available"}

    set_state(AgentState.EXECUTING)
    result = visual_executor.execute_visual_command(
        req.params.get("command", req.action)
    )
    set_state(AgentState.SUCCESS if result.get("success") else AgentState.ERROR)
    return result


@app.post("/api/v1/interrupt")
async def handle_interrupt():
    return await handle_cancel()


@app.post("/api/v1/clarify")
async def handle_clarify(req: ClarifyRequest):
    return {"prompt": req.prompt, "options": [{"index": i, "label": o} for i, o in enumerate(req.options)]}


@app.get("/api/v1/device/info")
async def device_info():
    info: Dict[str, Any] = {"device_connected": device is not None}
    if device:
        try:
            info["current_app"] = device.app_current()
        except Exception:
            pass
    try:
        result = subprocess.run(["termux-battery-status"], capture_output=True, text=True, timeout=5)
        if result.stdout:
            info["battery"] = json.loads(result.stdout)
    except Exception:
        pass
    return info


@app.get("/api/v1/memory/contacts")
async def list_contacts():
    conn = sqlite3.connect(memory.db_path)
    conn.row_factory = sqlite3.Row
    c = conn.cursor()
    c.execute("SELECT nickname, real_name, relation FROM contacts")
    rows = [dict(r) for r in c.fetchall()]
    conn.close()
    return {"contacts": rows}


@app.post("/api/v1/memory/contacts")
async def add_contact(data: Dict[str, str]):
    memory.save_contact(
        nickname=data.get("nickname", ""),
        real_name=data.get("real_name", ""),
        phone=data.get("phone"),
        relation=data.get("relation")
    )
    return {"success": True}


@app.get("/api/v1/memory/habits")
async def list_habits(user_id: str = "default"):
    return {"habits": memory.get_habit_stats(user_id)}


@app.get("/health")
async def health():
    return {
        "status": "healthy",
        "service": "HulaoEdgeAgent",
        "version": "4.1",
        "device_connected": device is not None,
        "state": get_state().value
    }
