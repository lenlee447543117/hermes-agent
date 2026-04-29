"""
AI沪老 Termux Edge Agent - 边缘小脑
在 Termux 中运行，作为本地 Agent 引擎和执行中枢

架构：云端大脑(Hermes) + 端侧小脑(Termux Edge Agent) + 物理躯壳(Android)

核心能力分工：
  - 屏幕识别：AutoGLM 模型（云端视觉理解，精准定位 UI 元素）
  - 语音识别：Vosk 离线模型（本地运行，无需网络，支持中文）
  - 语音合成：Termux TTS / 系统TTS
  - UI 自动化：uiautomator2（替代 AccessibilityService，开发效率10倍提升）
  - 热更新：Python 脚本可随时 git pull 更新，无需发版 APK

启动方式：
  python agent_core.py

依赖安装：
  pkg install python termux-api android-tools
  pip install uiautomator2 websocket-client requests fastapi uvicorn vosk
"""

import uiautomator2 as u2
import subprocess
import json
import time
import threading
import logging
import os
from http.server import HTTPServer, BaseHTTPRequestHandler
from typing import Optional, Dict, Any

logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s [%(levelname)s] %(message)s',
    handlers=[
        logging.FileHandler(os.path.expanduser("~/hulao_agent.log")),
        logging.StreamHandler()
    ]
)
logger = logging.getLogger("HulaoEdgeAgent")


class DeviceController:
    """设备控制器 - 通过 uiautomator2 控制手机"""

    def __init__(self):
        self.device: Optional[u2.Device] = None
        self.connected = False

    def connect(self) -> bool:
        try:
            self.device = u2.connect("127.0.0.1")
            info = self.device.info
            self.connected = True
            logger.info(f"Device connected: {info.get('productName', 'unknown')}")
            return True
        except Exception as e:
            logger.error(f"Device connect failed: {e}")
            return False

    def click_text(self, text: str) -> bool:
        if not self.connected:
            return False
        try:
            self.device(text=text).click()
            logger.info(f"Clicked text: {text}")
            return True
        except Exception as e:
            logger.warning(f"Click text failed '{text}': {e}")
            return False

    def click_coord(self, x: int, y: int) -> bool:
        if not self.connected:
            return False
        try:
            self.device.click(x, y)
            logger.info(f"Clicked coord: ({x}, {y})")
            return True
        except Exception as e:
            logger.warning(f"Click coord failed ({x},{y}): {e}")
            return False

    def click_desc(self, desc: str) -> bool:
        if not self.connected:
            return False
        try:
            self.device(description=desc).click()
            logger.info(f"Clicked desc: {desc}")
            return True
        except Exception as e:
            logger.warning(f"Click desc failed '{desc}': {e}")
            return False

    def type_text(self, text: str, clear: bool = True) -> bool:
        if not self.connected:
            return False
        try:
            self.device.send_keys(text, clear=clear)
            logger.info(f"Typed: {text[:20]}...")
            return True
        except Exception as e:
            logger.warning(f"Type text failed: {e}")
            return False

    def swipe(self, direction: str) -> bool:
        if not self.connected:
            return False
        try:
            if direction == "up":
                self.device.swipe_ext("up")
            elif direction == "down":
                self.device.swipe_ext("down")
            elif direction == "left":
                self.device.swipe_ext("left")
            elif direction == "right":
                self.device.swipe_ext("right")
            logger.info(f"Swiped: {direction}")
            return True
        except Exception as e:
            logger.warning(f"Swipe failed: {e}")
            return False

    def press(self, key: str) -> bool:
        if not self.connected:
            return False
        try:
            self.device.press(key)
            logger.info(f"Pressed: {key}")
            return True
        except Exception as e:
            logger.warning(f"Press failed: {e}")
            return False

    def screenshot(self, path: str = "/sdcard/hulao_screenshot.png") -> Optional[str]:
        if not self.connected:
            return None
        try:
            self.device.screenshot(path)
            logger.info(f"Screenshot saved: {path}")
            return path
        except Exception as e:
            logger.warning(f"Screenshot failed: {e}")
            return None

    def get_current_app(self) -> Dict[str, str]:
        if not self.connected:
            return {}
        try:
            info = self.device.app_current()
            return {"package": info.get("package", ""), "activity": info.get("activity", "")}
        except Exception as e:
            logger.warning(f"Get current app failed: {e}")
            return {}

    def launch_app(self, package: str) -> bool:
        if not self.connected:
            return False
        try:
            self.device.app_start(package)
            logger.info(f"Launched: {package}")
            return True
        except Exception as e:
            logger.warning(f"Launch app failed: {e}")
            return False

    def get_ui_tree(self) -> Optional[str]:
        if not self.connected:
            return None
        try:
            xml = self.device.dump_hierarchy()
            return xml
        except Exception as e:
            logger.warning(f"Get UI tree failed: {e}")
            return None


class TermuxApi:
    """Termux:API 调用封装"""

    @staticmethod
    def speak(text: str) -> bool:
        try:
            subprocess.run(["termux-tts-speak", text], timeout=10)
            return True
        except Exception as e:
            logger.warning(f"TTS failed: {e}")
            return False

    @staticmethod
    def listen() -> Optional[str]:
        try:
            result = subprocess.run(
                ["termux-speech-to-text"],
                capture_output=True, text=True, timeout=15
            )
            return result.stdout.strip() if result.stdout else None
        except Exception as e:
            logger.warning(f"STT failed: {e}")
            return None

    @staticmethod
    def notify(title: str, content: str, nid: str = "hulao") -> bool:
        try:
            subprocess.run([
                "termux-notification",
                "--title", title,
                "--content", content,
                "--id", nid,
                "--sound"
            ], timeout=5)
            return True
        except Exception as e:
            logger.warning(f"Notify failed: {e}")
            return False

    @staticmethod
    def volume(stream: str = "music", level: int = 10) -> bool:
        try:
            subprocess.run([
                "termux-volume", stream, str(level)
            ], timeout=5)
            return True
        except Exception as e:
            logger.warning(f"Volume failed: {e}")
            return False

    @staticmethod
    def camera_photo(path: str = "/sdcard/DCIM/hulao_photo.jpg", camera_id: int = 0) -> bool:
        try:
            subprocess.run([
                "termux-camera-photo",
                "-c", str(camera_id),
                path
            ], timeout=10)
            return True
        except Exception as e:
            logger.warning(f"Camera failed: {e}")
            return False

    @staticmethod
    def location(provider: str = "network") -> Optional[Dict]:
        try:
            result = subprocess.run(
                ["termux-location", "-p", provider, "-r", "once"],
                capture_output=True, text=True, timeout=15
            )
            if result.stdout:
                return json.loads(result.stdout)
            return None
        except Exception as e:
            logger.warning(f"Location failed: {e}")
            return None

    @staticmethod
    def media_player(command: str, path: str = "") -> bool:
        try:
            cmd = ["termux-media-player", command]
            if command == "play" and path:
                cmd.append(path)
            subprocess.run(cmd, timeout=5)
            return True
        except Exception as e:
            logger.warning(f"Media player failed: {e}")
            return False

    @staticmethod
    def battery_status() -> Optional[Dict]:
        try:
            result = subprocess.run(
                ["termux-battery-status"],
                capture_output=True, text=True, timeout=5
            )
            if result.stdout:
                return json.loads(result.stdout)
            return None
        except Exception as e:
            logger.warning(f"Battery status failed: {e}")
            return None

    @staticmethod
    def vibrate(duration: int = 200) -> bool:
        try:
            subprocess.run(["termux-vibrate", "-d", str(duration)], timeout=5)
            return True
        except Exception as e:
            logger.warning(f"Vibrate failed: {e}")
            return False


class VoskSpeechEngine:
    """Vosk 离线语音识别引擎 - 本地运行，无需网络"""

    def __init__(self, model_path: str = None):
        self.model = None
        self.recognizer = None
        self.available = False
        self._init_model(model_path)

    def _init_model(self, model_path: str = None):
        try:
            import vosk

            if model_path is None:
                home = os.path.expanduser("~")
                model_path = os.path.join(home, "vosk-model-small-cn")

            if not os.path.exists(model_path):
                logger.warning(f"Vosk model not found at {model_path}")
                logger.info("Download: wget https://alphacephei.com/vosk/models/vosk-model-small-cn-0.22.zip")
                return

            self.model = vosk.Model(model_path)
            self.recognizer = vosk.KaldiRecognizer(self.model, 16000)
            self.available = True
            logger.info(f"Vosk model loaded: {model_path}")
        except ImportError:
            logger.warning("Vosk not installed. Install: pip install vosk")
        except Exception as e:
            logger.warning(f"Vosk init failed: {e}")

    def recognize_file(self, wav_path: str) -> Optional[str]:
        if not self.available:
            return None
        try:
            import wave
            wf = wave.open(wav_path, "rb")
            if wf.getnchannels() != 1 or wf.getsampwidth() != 2 or wf.getcomptype() != "NONE":
                logger.warning("WAV format not supported: must be 16kHz mono PCM")
                return None

            rec = type(self.recognizer)(self.model, wf.getframerate())
            while True:
                data = wf.readframes(4000)
                if len(data) == 0:
                    break
                rec.AcceptWaveform(data)

            result = json.loads(rec.FinalResult())
            text = result.get("text", "")
            logger.info(f"Vosk recognized: {text}")
            return text if text else None
        except Exception as e:
            logger.warning(f"Vosk recognize failed: {e}")
            return None


class AutoGLMScreenAnalyzer:
    """AutoGLM 屏幕识别 - 云端视觉理解，精准定位 UI 元素"""

    def __init__(self, api_key: str = None, base_url: str = "https://api.z.ai/api/paas/v4"):
        self.api_key = api_key or os.environ.get("AUTOGLM_PHONE_API_KEY", "")
        self.base_url = base_url
        self.available = bool(self.api_key)

    def analyze_screen(self, command: str, screenshot_path: str) -> Optional[Dict]:
        if not self.available:
            logger.warning("AutoGLM API key not configured")
            return None

        try:
            import base64
            import requests

            with open(screenshot_path, "rb") as f:
                image_b64 = base64.b64encode(f.read()).decode()

            payload = {
                "model": "autoglm-phone",
                "messages": [
                    {
                        "role": "user",
                        "content": [
                            {"type": "text", "text": command},
                            {"type": "image_url", "image_url": {"url": f"data:image/png;base64,{image_b64}"}}
                        ]
                    }
                ]
            }

            headers = {
                "Authorization": f"Bearer {self.api_key}",
                "Content-Type": "application/json"
            }

            resp = requests.post(
                f"{self.base_url}/chat/completions",
                json=payload, headers=headers, timeout=30
            )

            if resp.status_code == 200:
                data = resp.json()
                content = data["choices"][0]["message"]["content"]
                logger.info(f"AutoGLM response: {content[:100]}...")
                return self._parse_actions(content)
            else:
                logger.warning(f"AutoGLM API error: {resp.status_code}")
                return None
        except Exception as e:
            logger.warning(f"AutoGLM analyze failed: {e}")
            return None

    def _parse_actions(self, content: str) -> Dict:
        try:
            if "```json" in content:
                json_str = content.split("```json")[1].split("```")[0].strip()
            elif "```" in content:
                json_str = content.split("```")[1].split("```")[0].strip()
            elif "{" in content:
                start = content.index("{")
                end = content.rindex("}") + 1
                json_str = content[start:end]
            else:
                return {"description": content, "actions": []}

            parsed = json.loads(json_str)
            return parsed
        except Exception:
            return {"description": content, "actions": []}


class EdgeAgentAPIHandler(BaseHTTPRequestHandler):
    """HTTP API 处理器 - 接收 App 的请求"""

    agent = None

    def do_POST(self):
        if self.path == "/api/v1/chat":
            self._handle_chat()
        elif self.path == "/api/v1/execute":
            self._handle_execute()
        elif self.path == "/api/v1/device/info":
            self._handle_device_info()
        else:
            self._send_json(404, {"error": "Not found"})

    def do_GET(self):
        if self.path == "/health":
            self._send_json(200, {
                "status": "healthy",
                "service": "HulaoEdgeAgent",
                "version": "2.0",
                "device_connected": EdgeAgentAPIHandler.agent.device_controller.connected if EdgeAgentAPIHandler.agent else False
            })
        else:
            self._send_json(404, {"error": "Not found"})

    def _handle_chat(self):
        try:
            body = self._read_body()
            message = body.get("message", "")
            dialect = body.get("dialect", "shanghai")

            if not message:
                self._send_json(400, {"error": "Message required"})
                return

            if EdgeAgentAPIHandler.agent:
                result = EdgeAgentAPIHandler.agent.process_chat(message, dialect)
                self._send_json(200, result)
            else:
                self._send_json(500, {"error": "Agent not initialized"})
        except Exception as e:
            self._send_json(500, {"error": str(e)})

    def _handle_execute(self):
        try:
            body = self._read_body()
            action = body.get("action", "")
            params = body.get("params", {})

            if not action:
                self._send_json(400, {"error": "Action required"})
                return

            if EdgeAgentAPIHandler.agent:
                result = EdgeAgentAPIHandler.agent.execute_action(action, params)
                self._send_json(200, result)
            else:
                self._send_json(500, {"error": "Agent not initialized"})
        except Exception as e:
            self._send_json(500, {"error": str(e)})

    def _handle_device_info(self):
        try:
            if EdgeAgentAPIHandler.agent:
                info = EdgeAgentAPIHandler.agent.get_device_info()
                self._send_json(200, info)
            else:
                self._send_json(500, {"error": "Agent not initialized"})
        except Exception as e:
            self._send_json(500, {"error": str(e)})

    def _read_body(self) -> Dict:
        content_length = int(self.headers.get("Content-Length", 0))
        body = self.rfile.read(content_length)
        return json.loads(body) if body else {}

    def _send_json(self, code: int, data: Dict):
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Access-Control-Allow-Origin", "*")
        self.end_headers()
        self.wfile.write(json.dumps(data, ensure_ascii=False).encode())

    def log_message(self, format, *args):
        logger.debug(f"HTTP {args[0]}")


class HulaoEdgeAgent:
    """AI沪老边缘小脑 - 核心调度器

    能力分工：
    - 屏幕识别：AutoGLM 模型（云端视觉理解）
    - 语音识别：Vosk 离线模型（本地运行，无需网络）
    - UI 自动化：uiautomator2
    - 系统控制：Termux:API
    """

    def __init__(self, hermes_url: str = "http://192.168.3.34:8642"):
        self.device_controller = DeviceController()
        self.termux = TermuxApi()
        self.vosk = VoskSpeechEngine()
        self.autoglm = AutoGLMScreenAnalyzer()
        self.hermes_url = hermes_url
        self.session_state = "IDLE"
        self.last_action = None

    def start(self):
        logger.info("Starting HulaoEdgeAgent...")

        if self.device_controller.connect():
            logger.info("Device controller connected via uiautomator2")
        else:
            logger.warning("Device controller not available, falling back to Termux:API only")

        TermuxApi.speak("沪老助手已启动")

        server = HTTPServer(("127.0.0.1", 8643), EdgeAgentAPIHandler)
        EdgeAgentAPIHandler.agent = self

        logger.info("Edge Agent API server started on http://127.0.0.1:8643")
        try:
            server.serve_forever()
        except KeyboardInterrupt:
            logger.info("Shutting down...")
            server.server_close()

    def process_chat(self, message: str, dialect: str = "shanghai") -> Dict:
        logger.info(f"Processing chat: {message[:50]}...")

        local_commands = {
            "音量": self._handle_volume,
            "声音": self._handle_volume,
            "拍照": self._handle_camera,
            "拍一张": self._handle_camera,
            "电量": self._handle_battery,
            "电池": self._handle_battery,
            "位置": self._handle_location,
            "我在哪": self._handle_location,
            "音乐": self._handle_music,
            "放歌": self._handle_music,
        }

        for keyword, handler in local_commands.items():
            if keyword in message:
                return handler(message, dialect)

        return self._forward_to_hermes(message, dialect)

    def execute_action(self, action: str, params: Dict) -> Dict:
        logger.info(f"Executing action: {action}, params: {params}")

        action_map = {
            "click_text": lambda: self.device_controller.click_text(params.get("text", "")),
            "click_coord": lambda: self.device_controller.click_coord(
                int(params.get("x", 0)), int(params.get("y", 0))
            ),
            "click_desc": lambda: self.device_controller.click_desc(params.get("desc", "")),
            "type_text": lambda: self.device_controller.type_text(params.get("text", "")),
            "swipe": lambda: self.device_controller.swipe(params.get("direction", "up")),
            "press_back": lambda: self.device_controller.press("back"),
            "press_home": lambda: self.device_controller.press("home"),
            "launch_app": lambda: self.device_controller.launch_app(params.get("package", "")),
            "screenshot": lambda: self.device_controller.screenshot(),
            "speak": lambda: self.termux.speak(params.get("text", "")),
            "notify": lambda: self.termux.notify(
                params.get("title", ""), params.get("content", "")
            ),
            "volume": lambda: self.termux.volume(
                params.get("stream", "music"), int(params.get("level", 10))
            ),
            "camera": lambda: self.termux.camera_photo(params.get("path", "")),
            "vibrate": lambda: self.termux.vibrate(int(params.get("duration", 200))),
        }

        handler = action_map.get(action)
        if handler:
            try:
                success = handler()
                return {"success": success, "action": action}
            except Exception as e:
                return {"success": False, "action": action, "error": str(e)}
        else:
            return {"success": False, "error": f"Unknown action: {action}"}

    def get_device_info(self) -> Dict:
        info = {
            "device_connected": self.device_controller.connected,
            "session_state": self.session_state,
        }
        if self.device_controller.connected:
            info["current_app"] = self.device_controller.get_current_app()
        battery = self.termux.battery_status()
        if battery:
            info["battery"] = battery
        return info

    def _handle_volume(self, message: str, dialect: str) -> Dict:
        level = 10
        if "大" in message or "高" in message:
            level = 15
        elif "小" in message or "低" in message:
            level = 5
        elif "静音" in message or "关掉" in message:
            level = 0

        self.termux.volume("music", level)
        reply = f"好的，音量已调到{level}" if dialect != "shanghai" else f"好额，声音调到{level}了"
        self.termux.speak(reply)
        return {"reply": reply, "mode": "COMMAND", "intent": "CONTROL_VOLUME"}

    def _handle_camera(self, message: str, dialect: str) -> Dict:
        path = f"/sdcard/DCIM/hulao_photo_{int(time.time())}.jpg"
        success = self.termux.camera_photo(path)
        if success:
            reply = "好的，照片已拍好" if dialect != "shanghai" else "好额，照片拍好了"
        else:
            reply = "拍照失败了，请重试" if dialect != "shanghai" else "拍照失败了，再试一次"
        self.termux.speak(reply)
        return {"reply": reply, "mode": "COMMAND", "intent": "TAKE_PHOTO", "path": path if success else None}

    def _handle_battery(self, message: str, dialect: str) -> Dict:
        battery = self.termux.battery_status()
        if battery:
            pct = battery.get("percentage", "未知")
            status = battery.get("status", "未知")
            reply = f"手机电量{pct}%，{status}" if dialect != "shanghai" else f"手机还有{pct}%的电，{status}"
        else:
            reply = "无法获取电量信息" if dialect != "shanghai" else "查不到电量"
        self.termux.speak(reply)
        return {"reply": reply, "mode": "COMMAND", "intent": "CHAT", "battery": battery}

    def _handle_location(self, message: str, dialect: str) -> Dict:
        loc = self.termux.location()
        if loc:
            lat = loc.get("latitude", 0)
            lon = loc.get("longitude", 0)
            reply = f"您当前位置：纬度{lat:.4f}，经度{lon:.4f}"
        else:
            reply = "无法获取位置信息" if dialect != "shanghai" else "查不到位置"
        self.termux.speak(reply)
        return {"reply": reply, "mode": "COMMAND", "intent": "SHARE_LOCATION", "location": loc}

    def _handle_music(self, message: str, dialect: str) -> Dict:
        if "暂停" in message or "停" in message:
            self.termux.media_player("stop")
            reply = "好的，音乐已暂停" if dialect != "shanghai" else "好额，音乐停了"
        else:
            self.termux.media_player("play")
            reply = "好的，正在播放音乐" if dialect != "shanghai" else "好额，音乐放起来了"
        self.termux.speak(reply)
        return {"reply": reply, "mode": "COMMAND", "intent": "PLAY_MUSIC"}

    def _forward_to_hermes(self, message: str, dialect: str) -> Dict:
        try:
            import requests
            resp = requests.post(
                f"{self.hermes_url}/api/v1/chat",
                json={"user_id": "local", "message": message, "dialect": dialect},
                headers={"Authorization": "Bearer hulao_2026_secure_api_key_change_in_production"},
                timeout=30
            )
            if resp.status_code == 200:
                data = resp.json()
                reply = data.get("reply", "")
                if reply:
                    self.termux.speak(reply)
                return data
            else:
                return {"reply": "云端服务暂时不可用", "mode": "CHAT"}
        except Exception as e:
            logger.error(f"Forward to Hermes failed: {e}")
            fallback = "网络好像睡着了，稍后再试试" if dialect == "shanghai" else "网络连接失败，请稍后再试"
            self.termux.speak(fallback)
            return {"reply": fallback, "mode": "CHAT"}


if __name__ == "__main__":
    agent = HulaoEdgeAgent()
    agent.start()
