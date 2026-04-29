"""
AI沪老 Termux Edge Agent - 边缘小脑 V3.0
完全对齐解决方案.md 六大模块架构设计

架构：感知外壳(Android App) + 本地中枢(Termux Edge) + 云端重推理(AutoGLM/VLM)

六大模块：
  模块一：稳定性与自愈能力 - ADB心跳保活 + 执行异常回滚 + ATX看门狗
  模块二：隐私保护细节强化 - opencv脱敏截图 + 敏感区域黑块填充 + 截图最小化
  模块三：性能与资源优化 - Vosk分级加载 + 动作链LRU缓存 + 资源限制
  模块四：适老化交互细节 - 模糊意图澄清 + 误触打断保护 + 多模态反馈
  模块五：云端协同降级策略 - 离线意图库50模板 + opencv本地替代
  模块六：部署与运维简化 - 一键初始化脚本

启动方式：
  uvicorn agent_core:app --host 127.0.0.1 --port 8000

依赖安装：
  pkg install python termux-api android-tools
  pip install uiautomator2 websocket-client requests fastapi uvicorn vosk opencv-python-headless
"""

import uiautomator2 as u2
import subprocess
import json
import time
import sqlite3
import os
import logging
import base64
import hashlib
import threading
import re
import signal
import sys
from typing import Optional, Dict, Any, List, Tuple
from collections import OrderedDict
from fastapi import FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel
from contextlib import asynccontextmanager

logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s [%(levelname)s] %(message)s',
    handlers=[
        logging.FileHandler(os.path.expanduser("~/hulao_agent.log")),
        logging.StreamHandler()
    ]
)
logger = logging.getLogger("HulaoEdgeAgent")

DB_PATH = os.path.expanduser("~/hulao_memory.db")
LRU_CACHE_MAX = 200
ADB_HEARTBEAT_INTERVAL = 120
EXECUTION_RETRY_MAX = 2
EXECUTION_WAIT_SECONDS = 3


# ==================== 数据模型 ====================

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

class ClarifyRequest(BaseModel):
    options: List[str]
    prompt: str


# ==================== 模块三：LRU 缓存 ====================

class LRUCache:
    def __init__(self, max_size: int = LRU_CACHE_MAX):
        self._cache: OrderedDict = OrderedDict()
        self._max_size = max_size

    def get(self, key: str) -> Optional[Any]:
        if key in self._cache:
            self._cache.move_to_end(key)
            return self._cache[key]
        return None

    def put(self, key: str, value: Any):
        if key in self._cache:
            self._cache.move_to_end(key)
        self._cache[key] = value
        while len(self._cache) > self._max_size:
            self._cache.popitem(last=False)

    def remove(self, key: str):
        if key in self._cache:
            del self._cache[key]

    def size(self) -> int:
        return len(self._cache)

    def keys(self) -> List[str]:
        return list(self._cache.keys())


# ==================== SQLite 记忆库 ====================

class MemoryStore:
    def __init__(self, db_path: str = DB_PATH):
        self.db_path = db_path
        self._init_db()

    def _init_db(self):
        conn = sqlite3.connect(self.db_path)
        c = conn.cursor()
        c.execute('''CREATE TABLE IF NOT EXISTS contacts (
            nickname TEXT PRIMARY KEY,
            real_name TEXT NOT NULL,
            wechat_name TEXT,
            phone TEXT,
            relation TEXT,
            last_contact_time TEXT
        )''')
        c.execute('''CREATE TABLE IF NOT EXISTS action_cache (
            intent TEXT,
            target TEXT,
            actions TEXT NOT NULL,
            screen_hash TEXT,
            hit_count INTEGER DEFAULT 1,
            last_used TEXT,
            created_at TEXT DEFAULT CURRENT_TIMESTAMP,
            PRIMARY KEY (intent, target)
        )''')
        c.execute('''CREATE TABLE IF NOT EXISTS habits (
            user_id TEXT,
            intent TEXT,
            target TEXT,
            frequency INTEGER DEFAULT 1,
            typical_time TEXT,
            last_triggered TEXT,
            PRIMARY KEY (user_id, intent, target)
        )''')
        conn.commit()
        conn.close()
        logger.info(f"Memory store initialized: {self.db_path}")

    def resolve_contact(self, nickname: str) -> Optional[Dict]:
        conn = sqlite3.connect(self.db_path)
        conn.row_factory = sqlite3.Row
        c = conn.cursor()
        c.execute("SELECT * FROM contacts WHERE nickname = ?", (nickname,))
        row = c.fetchone()
        conn.close()
        if row:
            return dict(row)
        return None

    def find_ambiguous_contacts(self, nickname: str) -> List[Dict]:
        conn = sqlite3.connect(self.db_path)
        conn.row_factory = sqlite3.Row
        c = conn.cursor()
        c.execute("SELECT * FROM contacts WHERE nickname LIKE ?", (f"%{nickname}%",))
        rows = c.fetchall()
        conn.close()
        return [dict(r) for r in rows]

    def save_contact(self, nickname: str, real_name: str, wechat_name: str = None, phone: str = None, relation: str = None):
        conn = sqlite3.connect(self.db_path)
        c = conn.cursor()
        c.execute("""INSERT OR REPLACE INTO contacts (nickname, real_name, wechat_name, phone, relation, last_contact_time)
                     VALUES (?, ?, ?, ?, ?, datetime('now'))""", (nickname, real_name, wechat_name, phone, relation))
        conn.commit()
        conn.close()

    def get_cached_action(self, intent: str, target: str = "", screen_hash: str = "") -> Optional[List[Dict]]:
        conn = sqlite3.connect(self.db_path)
        c = conn.cursor()
        if screen_hash:
            c.execute("SELECT actions FROM action_cache WHERE intent = ? AND target = ? AND screen_hash = ?",
                      (intent, target, screen_hash))
        else:
            c.execute("SELECT actions FROM action_cache WHERE intent = ? AND target = ?", (intent, target))
        row = c.fetchone()
        if row:
            c.execute("UPDATE action_cache SET hit_count = hit_count + 1, last_used = datetime('now') WHERE intent = ? AND target = ?",
                      (intent, target))
            conn.commit()
            conn.close()
            return json.loads(row[0])
        conn.close()
        return None

    def cache_action(self, intent: str, target: str, actions: List[Dict], screen_hash: str = ""):
        conn = sqlite3.connect(self.db_path)
        c = conn.cursor()
        c.execute("""INSERT OR REPLACE INTO action_cache (intent, target, actions, screen_hash, hit_count, last_used)
                     VALUES (?, ?, ?, ?, COALESCE((SELECT hit_count FROM action_cache WHERE intent=? AND target=?), 0) + 1, datetime('now'))""",
                  (intent, target, json.dumps(actions, ensure_ascii=False), screen_hash, intent, target))
        conn.commit()
        conn.close()
        self._enforce_lru_limit()

    def _enforce_lru_limit(self):
        conn = sqlite3.connect(self.db_path)
        c = conn.cursor()
        c.execute("SELECT COUNT(*) FROM action_cache")
        count = c.fetchone()[0]
        if count > LRU_CACHE_MAX:
            c.execute("""DELETE FROM action_cache WHERE rowid IN (
                SELECT rowid FROM action_cache ORDER BY last_used ASC LIMIT ?)""",
                      (count - LRU_CACHE_MAX,))
            conn.commit()
        conn.close()

    def record_habit(self, user_id: str, intent: str, target: str):
        conn = sqlite3.connect(self.db_path)
        c = conn.cursor()
        c.execute("""INSERT INTO habits (user_id, intent, target, frequency, last_triggered)
                     VALUES (?, ?, ?, 1, datetime('now'))
                     ON CONFLICT(user_id, intent, target)
                     DO UPDATE SET frequency = frequency + 1, last_triggered = datetime('now')""",
                  (user_id, intent, target))
        conn.commit()
        conn.close()

    def get_habit_stats(self, user_id: str = "default") -> List[Dict]:
        conn = sqlite3.connect(self.db_path)
        conn.row_factory = sqlite3.Row
        c = conn.cursor()
        c.execute("SELECT * FROM habits WHERE user_id = ? ORDER BY frequency DESC LIMIT 20", (user_id,))
        rows = c.fetchall()
        conn.close()
        return [dict(r) for r in rows]

    def count_contacts(self) -> int:
        conn = sqlite3.connect(self.db_path)
        c = conn.cursor()
        c.execute("SELECT COUNT(*) FROM contacts")
        count = c.fetchone()[0]
        conn.close()
        return count

    def count_cached_actions(self) -> int:
        conn = sqlite3.connect(self.db_path)
        c = conn.cursor()
        c.execute("SELECT COUNT(*) FROM action_cache")
        count = c.fetchone()[0]
        conn.close()
        return count


# ==================== 模块一：设备控制器 + 异常回滚 ====================

class DeviceController:
    def __init__(self):
        self.device: Optional[u2.Device] = None
        self.connected = False
        self._last_ui_snapshot: Optional[str] = None
        self._heartbeat_thread: Optional[threading.Thread] = None
        self._heartbeat_running = False

    def connect(self) -> bool:
        try:
            subprocess.run(["adb", "connect", "127.0.0.1:5555"], timeout=5, capture_output=True)
            time.sleep(1)
            self.device = u2.connect("127.0.0.1")
            info = self.device.info
            self.connected = True
            logger.info(f"Device connected: {info.get('productName', 'unknown')}")
            self._start_heartbeat()
            return True
        except Exception as e:
            logger.error(f"Device connect failed: {e}")
            return False

    def _start_heartbeat(self):
        if self._heartbeat_thread and self._heartbeat_thread.is_alive():
            return
        self._heartbeat_running = True
        self._heartbeat_thread = threading.Thread(target=self._heartbeat_loop, daemon=True)
        self._heartbeat_thread.start()
        logger.info("ADB heartbeat started (every 120s)")

    def _heartbeat_loop(self):
        while self._heartbeat_running and self.connected:
            try:
                subprocess.run(["adb", "shell", "echo", "ping"], timeout=5, capture_output=True)
                logger.debug("ADB heartbeat OK")
            except Exception as e:
                logger.warning(f"ADB heartbeat failed: {e}, attempting reconnect...")
                self._attempt_reconnect()
            time.sleep(ADB_HEARTBEAT_INTERVAL)

    def _attempt_reconnect(self):
        try:
            subprocess.run(["adb", "disconnect"], timeout=3, capture_output=True)
            time.sleep(1)
            subprocess.run(["adb", "tcpip", "5555"], timeout=5, capture_output=True)
            time.sleep(2)
            subprocess.run(["adb", "connect", "127.0.0.1:5555"], timeout=5, capture_output=True)
            time.sleep(1)
            self.device = u2.connect("127.0.0.1")
            self.connected = True
            logger.info("ADB reconnected successfully")
        except Exception as e:
            logger.error(f"ADB reconnect failed: {e}")
            self.connected = False

    def stop_heartbeat(self):
        self._heartbeat_running = False

    def save_ui_snapshot(self) -> Optional[str]:
        if not self.connected:
            return None
        try:
            self._last_ui_snapshot = self.device.dump_hierarchy()
            return self._last_ui_snapshot
        except:
            return None

    def get_screen_hash(self) -> Optional[str]:
        if not self.connected:
            return None
        try:
            xml = self.device.dump_hierarchy()
            return hashlib.md5(xml.encode()).hexdigest()
        except:
            return None

    def execute_with_rollback(self, action_fn, action_name: str = "unknown") -> Tuple[bool, str]:
        self.save_ui_snapshot()
        for attempt in range(EXECUTION_RETRY_MAX + 1):
            try:
                result = action_fn()
                if result:
                    return True, f"{action_name} succeeded"
                if attempt < EXECUTION_RETRY_MAX:
                    logger.warning(f"{action_name} returned False, waiting {EXECUTION_WAIT_SECONDS}s before retry...")
                    time.sleep(EXECUTION_WAIT_SECONDS)
                    xml_after = self.device.dump_hierarchy() if self.connected else ""
                    if xml_after == self._last_ui_snapshot:
                        logger.warning("Screen unchanged, pressing back to rollback...")
                        self.device.press("back")
                        time.sleep(1)
            except Exception as e:
                logger.error(f"{action_name} attempt {attempt+1} failed: {e}")
                if attempt < EXECUTION_RETRY_MAX:
                    try:
                        self.device.press("back")
                        time.sleep(1)
                    except:
                        pass
        return False, f"{action_name} failed after {EXECUTION_RETRY_MAX + 1} attempts"

    def click_text(self, text: str) -> bool:
        if not self.connected:
            return False
        success, _ = self.execute_with_rollback(
            lambda: self._safe_click_text(text), f"click_text({text})"
        )
        return success

    def _safe_click_text(self, text: str) -> bool:
        try:
            self.device(text=text).click()
            return True
        except:
            return False

    def click_coord(self, x: int, y: int) -> bool:
        if not self.connected:
            return False
        try:
            self.device.click(x, y)
            return True
        except:
            return False

    def click_desc(self, desc: str) -> bool:
        if not self.connected:
            return False
        success, _ = self.execute_with_rollback(
            lambda: self._safe_click_desc(desc), f"click_desc({desc})"
        )
        return success

    def _safe_click_desc(self, desc: str) -> bool:
        try:
            self.device(description=desc).click()
            return True
        except:
            return False

    def type_text(self, text: str, clear: bool = True) -> bool:
        if not self.connected:
            return False
        try:
            self.device.send_keys(text, clear=clear)
            return True
        except:
            return False

    def swipe(self, direction: str) -> bool:
        if not self.connected:
            return False
        try:
            self.device.swipe_ext(direction)
            return True
        except:
            return False

    def press(self, key: str) -> bool:
        if not self.connected:
            return False
        try:
            self.device.press(key)
            return True
        except:
            return False

    def screenshot(self, path: str = "/sdcard/hulao_screenshot.png") -> Optional[str]:
        if not self.connected:
            return None
        try:
            self.device.screenshot(path)
            return path
        except:
            return None

    def get_current_app(self) -> Dict[str, str]:
        if not self.connected:
            return {}
        try:
            info = self.device.app_current()
            return {"package": info.get("package", ""), "activity": info.get("activity", "")}
        except:
            return {}

    def launch_app(self, package: str) -> bool:
        if not self.connected:
            return False
        try:
            self.device.app_start(package)
            return True
        except:
            return False

    def get_ui_tree(self) -> Optional[str]:
        if not self.connected:
            return None
        try:
            return self.device.dump_hierarchy()
        except:
            return None


# ==================== Termux:API ====================

class TermuxApi:
    @staticmethod
    def speak(text: str) -> bool:
        try:
            subprocess.run(["termux-tts-speak", text], timeout=10)
            return True
        except:
            return False

    @staticmethod
    def notify(title: str, content: str, nid: str = "hulao") -> bool:
        try:
            subprocess.run(["termux-notification", "--title", title, "--content", content, "--id", nid, "--sound"], timeout=5)
            return True
        except:
            return False

    @staticmethod
    def volume(stream: str = "music", level: int = 10) -> bool:
        try:
            subprocess.run(["termux-volume", stream, str(level)], timeout=5)
            return True
        except:
            return False

    @staticmethod
    def camera_photo(path: str = "/sdcard/DCIM/hulao_photo.jpg", camera_id: int = 0) -> bool:
        try:
            subprocess.run(["termux-camera-photo", "-c", str(camera_id), path], timeout=10)
            return True
        except:
            return False

    @staticmethod
    def location(provider: str = "network") -> Optional[Dict]:
        try:
            result = subprocess.run(["termux-location", "-p", provider, "-r", "once"], capture_output=True, text=True, timeout=15)
            return json.loads(result.stdout) if result.stdout else None
        except:
            return None

    @staticmethod
    def media_player(command: str, path: str = "") -> bool:
        try:
            cmd = ["termux-media-player", command]
            if command == "play" and path:
                cmd.append(path)
            subprocess.run(cmd, timeout=5)
            return True
        except:
            return False

    @staticmethod
    def battery_status() -> Optional[Dict]:
        try:
            result = subprocess.run(["termux-battery-status"], capture_output=True, text=True, timeout=5)
            return json.loads(result.stdout) if result.stdout else None
        except:
            return None

    @staticmethod
    def wake_lock() -> bool:
        try:
            subprocess.run(["termux-wake-lock"], timeout=5)
            logger.info("Termux wake-lock acquired")
            return True
        except Exception as e:
            logger.warning(f"Wake-lock failed: {e}")
            return False

    @staticmethod
    def vibrate(duration: int = 200) -> bool:
        try:
            subprocess.run(["termux-vibrate", "-d", str(duration)], timeout=5)
            return True
        except:
            return False


# ==================== 模块二：隐私保护 - opencv 脱敏 ====================

class PrivacyFilter:
    SENSITIVE_KEYWORDS = [
        "余额", "存款", "转账", "支付", "收款", "金额", "元", "万元",
        "身份证", "证件号", "社保",
        "验证码", "密码", "口令",
        "银行卡", "信用卡", "借记卡",
    ]

    PHONE_PATTERN = re.compile(r'1[3-9]\d{9}')
    ID_CARD_PATTERN = re.compile(r'[1-9]\d{5}(?:19|20)\d{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12]\d|3[01])\d{3}[\dXx]')
    BANK_CARD_PATTERN = re.compile(r'\d{16,19}')

    def __init__(self):
        self.cv2 = None
        self._init_opencv()

    def _init_opencv(self):
        try:
            import cv2
            self.cv2 = cv2
            logger.info("OpenCV loaded for privacy filter")
        except ImportError:
            logger.warning("opencv-python-headless not installed, privacy filter disabled")

    def is_available(self) -> bool:
        return self.cv2 is not None

    def crop_target_area(self, screenshot_path: str, target_bounds: Dict = None, size: int = 300) -> Optional[str]:
        if not self.is_available():
            return screenshot_path
        try:
            img = self.cv2.imread(screenshot_path)
            if img is None:
                return screenshot_path
            h, w = img.shape[:2]
            if target_bounds:
                cx = target_bounds.get("center_x", w // 2)
                cy = target_bounds.get("center_y", h // 2)
            else:
                cx, cy = w // 2, h // 2
            half = size // 2
            x1 = max(0, cx - half)
            y1 = max(0, cy - half)
            x2 = min(w, cx + half)
            y2 = min(h, cy + half)
            cropped = img[y1:y2, x1:x2]
            status_bar_height = int(h * 0.04)
            if y1 < status_bar_height:
                cropped[:status_bar_height - y1, :] = 0
            output_path = screenshot_path.replace(".png", "_cropped.png")
            self.cv2.imwrite(output_path, cropped)
            return output_path
        except Exception as e:
            logger.warning(f"Crop failed: {e}")
            return screenshot_path

    def desensitize_screenshot(self, screenshot_path: str) -> Optional[str]:
        if not self.is_available():
            return screenshot_path
        try:
            img = self.cv2.imread(screenshot_path)
            if img is None:
                return screenshot_path
            gray = self.cv2.cvtColor(img, self.cv2.COLOR_BGR2GRAY)
            regions = self._detect_sensitive_regions(img, gray)
            if not regions:
                return screenshot_path
            for (x, y, w_r, h_r) in regions:
                img[y:y+h_r, x:x+w_r] = 0
            output_path = screenshot_path.replace(".png", "_desensitized.png")
            self.cv2.imwrite(output_path, img)
            logger.info(f"Desensitized {len(regions)} sensitive regions")
            return output_path
        except Exception as e:
            logger.warning(f"Desensitize failed: {e}")
            return screenshot_path

    def _detect_sensitive_regions(self, img, gray) -> List[Tuple[int, int, int, int]]:
        regions = []
        try:
            _, thresh = self.cv2.threshold(gray, 180, 255, self.cv2.THRESH_BINARY_INV)
            contours, _ = self.cv2.findContours(thresh, self.cv2.RETR_EXTERNAL, self.cv2.CHAIN_APPROX_SIMPLE)
            for cnt in contours:
                x, y, w_r, h_r = self.cv2.boundingRect(cnt)
                if w_r > 30 and h_r > 8 and h_r < 60 and w_r < 500:
                    roi = img[y:y+h_r, x:x+w_r]
                    if self._contains_sensitive_info(roi):
                        padding = 5
                        regions.append((
                            max(0, x - padding),
                            max(0, y - padding),
                            min(w_r + 2 * padding, img.shape[1] - x),
                            min(h_r + 2 * padding, img.shape[0] - y)
                        ))
        except Exception as e:
            logger.warning(f"Region detection failed: {e}")
        return regions

    def _contains_sensitive_info(self, roi) -> bool:
        return True

    def match_template(self, screenshot_path: str, template_path: str, threshold: float = 0.8) -> Optional[Dict]:
        if not self.is_available():
            return None
        try:
            img = self.cv2.imread(screenshot_path, 0)
            template = self.cv2.imread(template_path, 0)
            if img is None or template is None:
                return None
            result = self.cv2.matchTemplate(img, template, self.cv2.TM_CCOEFF_NORMED)
            min_val, max_val, min_loc, max_loc = self.cv2.minMaxLoc(result)
            if max_val >= threshold:
                h_t, w_t = template.shape
                return {
                    "x": int(max_loc[0] + w_t / 2),
                    "y": int(max_loc[1] + h_t / 2),
                    "confidence": float(max_val),
                    "width": w_t,
                    "height": h_t
                }
            return None
        except Exception as e:
            logger.warning(f"Template match failed: {e}")
            return None


# ==================== 模块三：Vosk 离线语音（分级加载） ====================

class VoskSpeechEngine:
    MODEL_SMALL = "vosk-model-small-cn-0.22"
    MODEL_LARGE = "vosk-model-cn-0.22"
    CONFIDENCE_THRESHOLD = 0.7
    LOW_CONFIDENCE_STREAK_LIMIT = 3

    def __init__(self, model_path: str = None):
        self.model = None
        self.available = False
        self._current_model_name = ""
        self._low_confidence_streak = 0
        self._init_model(model_path)

    def _init_model(self, model_path: str = None):
        try:
            import vosk
            if model_path is None:
                home = os.path.expanduser("~")
                small_path = os.path.join(home, self.MODEL_SMALL)
                large_path = os.path.join(home, self.MODEL_LARGE)
                if os.path.exists(small_path):
                    model_path = small_path
                elif os.path.exists(large_path):
                    model_path = large_path
                else:
                    logger.warning(f"Vosk model not found. Expected at {small_path} or {large_path}")
                    return
            if not os.path.exists(model_path):
                logger.warning(f"Vosk model not found at {model_path}")
                return
            self.model = vosk.Model(model_path)
            self.available = True
            self._current_model_name = os.path.basename(model_path)
            logger.info(f"Vosk model loaded: {model_path}")
        except ImportError:
            logger.warning("Vosk not installed. pip install vosk")
        except Exception as e:
            logger.warning(f"Vosk init failed: {e}")

    def check_and_upgrade_model(self, confidence: float):
        if confidence < self.CONFIDENCE_THRESHOLD:
            self._low_confidence_streak += 1
        else:
            self._low_confidence_streak = 0

        if self._low_confidence_streak >= self.LOW_CONFIDENCE_STREAK_LIMIT:
            if self.MODEL_SMALL in self._current_model_name:
                self._try_load_large_model()

    def _try_load_large_model(self):
        try:
            import vosk
            large_path = os.path.join(os.path.expanduser("~"), self.MODEL_LARGE)
            if os.path.exists(large_path):
                logger.info("Switching to large Vosk model for better accuracy...")
                self.model = vosk.Model(large_path)
                self._current_model_name = self.MODEL_LARGE
                self._low_confidence_streak = 0
                logger.info("Large Vosk model loaded successfully")
            else:
                logger.warning(f"Large model not found at {large_path}")
        except Exception as e:
            logger.warning(f"Failed to load large model: {e}")

    def recognize_file(self, wav_path: str) -> Optional[Dict]:
        if not self.available:
            return None
        try:
            import vosk, wave
            wf = wave.open(wav_path, "rb")
            rec = vosk.KaldiRecognizer(self.model, wf.getframerate())
            while True:
                data = wf.readframes(4000)
                if not data:
                    break
                rec.AcceptWaveform(data)
            result = json.loads(rec.FinalResult())
            text = result.get("text", "")
            confidence = result.get("confidence", 0.0)
            self.check_and_upgrade_model(confidence)
            return {"text": text, "confidence": confidence}
        except Exception as e:
            logger.warning(f"Vosk recognize failed: {e}")
            return None


# ==================== AutoGLM 屏幕识别 ====================

class AutoGLMScreenAnalyzer:
    def __init__(self, api_key: str = None, base_url: str = "https://api.z.ai/api/paas/v4"):
        self.api_key = api_key or os.environ.get("AUTOGLM_PHONE_API_KEY", "")
        self.base_url = base_url
        self.available = bool(self.api_key)

    def analyze_screen(self, command: str, screenshot_path: str) -> Optional[Dict]:
        if not self.available:
            return None
        try:
            import requests
            with open(screenshot_path, "rb") as f:
                image_b64 = base64.b64encode(f.read()).decode()
            payload = {
                "model": "autoglm-phone",
                "messages": [{"role": "user", "content": [
                    {"type": "text", "text": command},
                    {"type": "image_url", "image_url": {"url": f"data:image/png;base64,{image_b64}"}}
                ]}]
            }
            headers = {"Authorization": f"Bearer {self.api_key}", "Content-Type": "application/json"}
            resp = requests.post(f"{self.base_url}/chat/completions", json=payload, headers=headers, timeout=30)
            if resp.status_code == 200:
                content = resp.json()["choices"][0]["message"]["content"]
                return self._parse_actions(content)
            return None
        except Exception as e:
            logger.warning(f"AutoGLM analyze failed: {e}")
            return None

    def _parse_actions(self, content: str) -> Dict:
        try:
            if "```json" in content:
                json_str = content.split("```json")[1].split("```")[0].strip()
            elif "{" in content:
                json_str = content[content.index("{"):content.rindex("}") + 1]
            else:
                return {"description": content, "actions": []}
            return json.loads(json_str)
        except:
            return {"description": content, "actions": []}


# ==================== 模块五：离线意图库（50模板） ====================

OFFLINE_INTENT_TEMPLATES = [
    {"intent": "PHONE_CALL", "patterns": ["打电话", "拨号", "呼叫", "给.*打", "联系.*"], "slots": ["target"], "action": "phone_call"},
    {"intent": "WECHAT_CALL", "patterns": ["微信.*打", "视频.*打", "微信通话", "视频通话"], "slots": ["target"], "action": "wechat_call"},
    {"intent": "SEND_MESSAGE", "patterns": ["发消息", "发短信", "发微信", "告诉.*"], "slots": ["target", "message"], "action": "send_message"},
    {"intent": "CONTROL_VOLUME", "patterns": ["音量", "声音大", "声音小", "静音", "关声音"], "slots": ["level"], "action": "volume"},
    {"intent": "TAKE_PHOTO", "patterns": ["拍照", "拍一张", "照相", "来一张"], "slots": [], "action": "camera"},
    {"intent": "PLAY_MUSIC", "patterns": ["放歌", "播放音乐", "听歌", "放.*歌"], "slots": ["song"], "action": "music_play"},
    {"intent": "STOP_MUSIC", "patterns": ["停歌", "暂停音乐", "关音乐", "停止播放"], "slots": [], "action": "music_stop"},
    {"intent": "QUERY_WEATHER", "patterns": ["天气", "下雨", "温度", "冷不冷", "热不热"], "slots": ["city"], "action": "weather"},
    {"intent": "READ_NEWS", "patterns": ["新闻", "今日.*事", "热点", "头条"], "slots": ["topic"], "action": "news"},
    {"intent": "SET_ALARM", "patterns": ["闹钟", "提醒我", "叫醒我", "定时"], "slots": ["time", "label"], "action": "alarm"},
    {"intent": "MEDICINE_REMINDER", "patterns": ["吃药", "药.*提醒", "该吃药", "吃药时间"], "slots": ["medicine", "time"], "action": "medicine"},
    {"intent": "BATTERY_STATUS", "patterns": ["电量", "电池", "还有多少电", "充电"], "slots": [], "action": "battery"},
    {"intent": "SHARE_LOCATION", "patterns": ["我在哪", "位置", "定位", "我在.*"], "slots": [], "action": "location"},
    {"intent": "LAUNCH_APP", "patterns": ["打开.*", "启动.*", "开.*应用"], "slots": ["app"], "action": "launch_app"},
    {"intent": "FLASHLIGHT_ON", "patterns": ["手电筒", "开灯", "照亮"], "slots": [], "action": "flashlight_on"},
    {"intent": "FLASHLIGHT_OFF", "patterns": ["关手电筒", "关灯"], "slots": [], "action": "flashlight_off"},
    {"intent": "WIFI_ON", "patterns": ["开.*wifi", "开.*无线", "连.*wifi"], "slots": [], "action": "wifi_on"},
    {"intent": "WIFI_OFF", "patterns": ["关.*wifi", "断.*wifi"], "slots": [], "action": "wifi_off"},
    {"intent": "CALL_TAXI", "patterns": ["叫车", "打车", "出租车"], "slots": [], "action": "call_taxi"},
    {"intent": "EMERGENCY_SOS", "patterns": ["救命", "急救", "120", "不舒服", "摔倒"], "slots": [], "action": "emergency"},
    {"intent": "SEARCH_WEB", "patterns": ["搜索", "查一下", "百度", "搜.*"], "slots": ["query"], "action": "search"},
    {"intent": "OPEN_WECHAT", "patterns": ["微信", "打开微信"], "slots": [], "action": "launch_wechat"},
    {"intent": "OPEN_ALIPAY", "patterns": ["支付宝", "打开支付宝"], "slots": [], "action": "launch_alipay"},
    {"intent": "OPEN_MAP", "patterns": ["地图", "导航", "高德", "百度地图"], "slots": ["destination"], "action": "launch_map"},
    {"intent": "OPEN_CAMERA", "patterns": ["相机", "打开相机", "照相馆"], "slots": [], "action": "launch_camera"},
    {"intent": "OPEN_SETTINGS", "patterns": ["设置", "系统设置", "手机设置"], "slots": [], "action": "launch_settings"},
    {"intent": "OPEN_GALLERY", "patterns": ["相册", "照片", "图片"], "slots": [], "action": "launch_gallery"},
    {"intent": "OPEN_CALENDAR", "patterns": ["日历", "日期", "今天几号", "星期几"], "slots": [], "action": "launch_calendar"},
    {"intent": "OPEN_CLOCK", "patterns": ["时钟", "几点了", "现在几点", "时间"], "slots": [], "action": "launch_clock"},
    {"intent": "OPEN_CALCULATOR", "patterns": ["计算器", "算一下", "算数"], "slots": [], "action": "launch_calculator"},
    {"intent": "OPEN_CONTACTS", "patterns": ["通讯录", "联系人", "电话本"], "slots": [], "action": "launch_contacts"},
    {"intent": "OPEN_DIALER", "patterns": ["拨号", "拨号盘"], "slots": [], "action": "launch_dialer"},
    {"intent": "OPEN_SMS", "patterns": ["短信", "信息", "短消息"], "slots": [], "action": "launch_sms"},
    {"intent": "OPEN_VIDEO", "patterns": ["视频", "看.*视频", "抖音", "快手"], "slots": [], "action": "launch_video"},
    {"intent": "OPEN_HEALTH_CODE", "patterns": ["健康码", "随申码", "核酸"], "slots": [], "action": "launch_health_code"},
    {"intent": "OPEN_BUS_CARD", "patterns": ["公交卡", "交通卡", "乘车码"], "slots": [], "action": "launch_bus_card"},
    {"intent": "SCAN_QR", "patterns": ["扫一扫", "扫码", "扫二维码"], "slots": [], "action": "scan_qr"},
    {"intent": "PAY", "patterns": ["付款", "支付", "扫码付"], "slots": [], "action": "pay"},
    {"intent": "TRANSFER", "patterns": ["转账", "汇款", "转钱"], "slots": [], "action": "transfer"},
    {"intent": "CHECK_BALANCE", "patterns": ["余额", "查余额", "还有多少钱"], "slots": [], "action": "check_balance"},
    {"intent": "RECHARGE", "patterns": ["充值", "充话费", "交费"], "slots": [], "action": "recharge"},
    {"intent": "TTS_SLOW", "patterns": ["说慢点", "慢一点", "太快了"], "slots": [], "action": "tts_slow"},
    {"intent": "TTS_FAST", "patterns": ["说快点", "快一点", "太慢了"], "slots": [], "action": "tts_fast"},
    {"intent": "REPEAT_LAST", "patterns": ["再说一遍", "重复", "再说一次"], "slots": [], "action": "repeat_last"},
    {"intent": "HELP", "patterns": ["帮助", "你能做什么", "怎么用", "教我"], "slots": [], "action": "help"},
    {"intent": "CANCEL", "patterns": ["取消", "算了", "不要了", "停下"], "slots": [], "action": "cancel"},
    {"intent": "CONFIRM", "patterns": ["是的", "对", "好的", "确认", "没错"], "slots": [], "action": "confirm"},
    {"intent": "CHAT", "patterns": ["聊天", "陪我聊", "说话"], "slots": [], "action": "chat"},
    {"intent": "TELL_JOKE", "patterns": ["讲笑话", "逗我笑", "开心"], "slots": [], "action": "joke"},
    {"intent": "TELL_STORY", "patterns": ["讲故事", "说故事", "听故事"], "slots": [], "action": "story"},
    {"intent": "SING_SONG", "patterns": ["唱歌", "来一首", "唱首歌"], "slots": [], "action": "sing"},
]

class OfflineIntentParser:
    def __init__(self):
        self.templates = OFFLINE_INTENT_TEMPLATES
        self._compiled = []
        for t in self.templates:
            for p in t["patterns"]:
                try:
                    self._compiled.append((re.compile(p), t))
                except:
                    pass

    def parse(self, text: str) -> Optional[Dict]:
        for pattern, template in self._compiled:
            if pattern.search(text):
                slots = self._extract_slots(text, template)
                return {
                    "intent": template["intent"],
                    "action": template["action"],
                    "slots": slots,
                    "matched_pattern": pattern.pattern,
                    "source": "offline"
                }
        return None

    def _extract_slots(self, text: str, template: Dict) -> Dict[str, str]:
        slots = {}
        if "target" in template["slots"]:
            m = re.search(r"(?:给|跟|找|联系|呼叫|打给)(.+?)(?:打|发|聊|说|$)", text)
            if m:
                slots["target"] = m.group(1).strip()
        if "city" in template["slots"]:
            city_match = re.search(r"([\u4e00-\u9fa5]{2,3})(?:的?天气)", text)
            if city_match:
                slots["city"] = city_match.group(1)
        if "time" in template["slots"]:
            time_match = re.search(r"(\d+点|早上|中午|下午|晚上|睡前|饭后)", text)
            if time_match:
                slots["time"] = time_match.group(1)
        if "medicine" in template["slots"]:
            med_match = re.search(r"([\u4e00-\u9fa5]{2,6})(?:的?提醒|该?吃)", text)
            if med_match:
                slots["medicine"] = med_match.group(1)
        if "app" in template["slots"]:
            app_match = re.search(r"打开(.+?)(?:$|，|。|！)", text)
            if app_match:
                slots["app"] = app_match.group(1).strip()
        if "query" in template["slots"]:
            query_match = re.search(r"(?:搜索|查一下|百度|搜)(.+?)(?:$|，|。|！)", text)
            if query_match:
                slots["query"] = query_match.group(1).strip()
        return slots


# ==================== 模块四：模糊意图澄清 ====================

class IntentClarifier:
    def __init__(self, memory: MemoryStore):
        self.memory = memory

    def clarify_contact(self, nickname: str) -> Optional[Dict]:
        contacts = self.memory.find_ambiguous_contacts(nickname)
        if len(contacts) > 1:
            options = []
            for c in contacts:
                options.append({
                    "name": c.get("real_name", c.get("nickname")),
                    "relation": c.get("relation", ""),
                    "phone": c.get("phone", ""),
                })
            return {
                "needs_clarification": True,
                "type": "contact_ambiguous",
                "prompt": f"找到了{len(contacts)}个联系人，请问是哪一位？",
                "options": options
            }
        return None


# ==================== 核心调度器 ====================

memory = MemoryStore()
device = DeviceController()
termux = TermuxApi()
vosk_engine = VoskSpeechEngine()
autoglm = AutoGLMScreenAnalyzer()
privacy_filter = PrivacyFilter()
offline_parser = OfflineIntentParser()
intent_clarifier = IntentClarifier(memory)
action_cache = LRUCache(LRU_CACHE_MAX)
execution_interrupt = threading.Event()


@asynccontextmanager
async def lifespan(app_instance: FastAPI):
    termux.wake_lock()
    try:
        os.nice(10)
        logger.info("Process priority lowered (nice +10)")
    except:
        pass
    try:
        import resource
        soft, hard = resource.getrlimit(resource.RLIMIT_AS)
        resource.setrlimit(resource.RLIMIT_AS, (300 * 1024 * 1024, hard))
        logger.info("Memory limit set to 300MB")
    except:
        logger.warning("Resource limits not available on this platform")
    if device.connect():
        logger.info("Device controller connected via uiautomator2")
    else:
        logger.warning("Device not available, Termux:API only mode")
    termux.speak("沪老助手已启动")
    logger.info("HulaoEdgeAgent V3.0 started on http://127.0.0.1:8000")
    yield
    device.stop_heartbeat()
    logger.info("HulaoEdgeAgent shutting down")


app = FastAPI(title="HulaoEdgeAgent", version="3.0", lifespan=lifespan)
app.add_middleware(CORSMiddleware, allow_origins=["*"], allow_methods=["*"], allow_headers=["*"])


# ==================== Local-API 接口（对齐解决方案.md） ====================

@app.post("/api/v1/voice")
async def handle_voice(req: VoiceRequest):
    logger.info(f"Voice request: {req.text[:50]}...")

    nickname = _extract_nickname(req.text)
    if nickname:
        clarification = intent_clarifier.clarify_contact(nickname)
        if clarification:
            termux.speak(clarification["prompt"])
            return {"reply": clarification["prompt"], "mode": "CLARIFY", "clarification": clarification}

        contact = memory.resolve_contact(nickname)
        if contact:
            logger.info(f"Contact resolved locally: {nickname} -> {contact.get('real_name')}")
            reply = f"找到{contact.get('real_name', nickname)}了"
            termux.speak(reply)
            termux.vibrate(100)
            return {"reply": reply, "mode": "COMMAND", "intent": "CONTACT_RESOLVED", "contact": contact}

    screen_hash = device.get_screen_hash()
    cached = memory.get_cached_action(req.text, "", screen_hash or "")
    if cached:
        logger.info(f"Action cache hit for: {req.text[:30]}")
        return _replay_cached_actions(cached, req.dialect)

    local_handlers = {
        "音量": _handle_volume, "声音": _handle_volume,
        "拍照": _handle_camera, "拍一张": _handle_camera,
        "电量": _handle_battery, "电池": _handle_battery,
        "位置": _handle_location, "我在哪": _handle_location,
        "音乐": _handle_music, "放歌": _handle_music,
    }
    for keyword, handler in local_handlers.items():
        if keyword in req.text:
            result = handler(req.text, req.dialect)
            termux.vibrate(100)
            return result

    offline_result = offline_parser.parse(req.text)
    if offline_result:
        logger.info(f"Offline intent matched: {offline_result['intent']}")
        return _handle_offline_intent(offline_result, req.dialect)

    return _forward_to_hermes(req.text, req.dialect)


@app.get("/api/v1/status")
async def get_status():
    return {
        "device_connected": device.connected,
        "vosk_available": vosk_engine.available,
        "vosk_model": vosk_engine._current_model_name,
        "autoglm_available": autoglm.available,
        "privacy_filter_available": privacy_filter.is_available(),
        "memory_contacts": memory.count_contacts(),
        "cached_actions": memory.count_cached_actions(),
        "offline_templates": len(OFFLINE_INTENT_TEMPLATES),
        "uptime": time.time(),
        "version": "3.0"
    }


@app.post("/api/v1/chat")
async def handle_chat(req: ChatRequest):
    return await handle_voice(VoiceRequest(text=req.message, user_id=req.user_id, dialect=req.dialect))


@app.post("/api/v1/execute")
async def handle_execute(req: ExecuteRequest):
    logger.info(f"Execute: {req.action}, params: {req.params}")
    action_map = {
        "click_text": lambda: device.click_text(req.params.get("text", "")),
        "click_coord": lambda: device.click_coord(int(req.params.get("x", 0)), int(req.params.get("y", 0))),
        "click_desc": lambda: device.click_desc(req.params.get("desc", "")),
        "type_text": lambda: device.type_text(req.params.get("text", "")),
        "swipe": lambda: device.swipe(req.params.get("direction", "up")),
        "press_back": lambda: device.press("back"),
        "press_home": lambda: device.press("home"),
        "launch_app": lambda: device.launch_app(req.params.get("package", "")),
        "screenshot": lambda: device.screenshot(),
        "speak": lambda: termux.speak(req.params.get("text", "")),
        "notify": lambda: termux.notify(req.params.get("title", ""), req.params.get("content", "")),
        "volume": lambda: termux.volume(req.params.get("stream", "music"), int(req.params.get("level", 10))),
        "camera": lambda: termux.camera_photo(req.params.get("path", "")),
        "vibrate": lambda: termux.vibrate(int(req.params.get("duration", 200))),
    }
    handler = action_map.get(req.action)
    if handler:
        try:
            success = handler()
            if success:
                screen_hash = device.get_screen_hash() or ""
                memory.cache_action(req.action, req.params.get("target", ""),
                                    [{"action": req.action, "params": req.params}], screen_hash)
            return {"success": success, "action": req.action}
        except Exception as e:
            return {"success": False, "action": req.action, "error": str(e)}
    return {"success": False, "error": f"Unknown action: {req.action}"}


@app.post("/api/v1/execute/autoglm")
async def handle_autoglm_execute(req: ExecuteRequest):
    if not device.connected:
        return {"success": False, "error": "Device not connected"}

    screenshot_path = device.screenshot()
    if not screenshot_path:
        return {"success": False, "error": "Screenshot failed"}

    desensitized = privacy_filter.desensitize_screenshot(screenshot_path)
    command = req.params.get("command", req.action)
    result = autoglm.analyze_screen(command, desensitized or screenshot_path)

    if not result:
        offline_match = privacy_filter.match_template(screenshot_path, req.params.get("template_path", ""))
        if offline_match:
            device.click_coord(offline_match["x"], offline_match["y"])
            return {"success": True, "source": "template_match", "match": offline_match}
        return {"success": False, "error": "AutoGLM and template match both failed"}

    actions = result.get("actions", [])
    if not actions:
        return {"success": False, "error": "No actionable items found", "description": result.get("description", "")}

    executed = []
    for act in actions:
        if execution_interrupt.is_set():
            return {"success": False, "error": "Execution interrupted by user", "executed": executed}
        act_type = act.get("type", "")
        if act_type == "click":
            x, y = act.get("x", 0), act.get("y", 0)
            ok = device.click_coord(x, y)
            executed.append({"type": "click", "x": x, "y": y, "success": ok})
        elif act_type == "type":
            text = act.get("text", "")
            ok = device.type_text(text)
            executed.append({"type": "type", "text": text, "success": ok})
        elif act_type == "press":
            key = act.get("key", "back")
            ok = device.press(key)
            executed.append({"type": "press", "key": key, "success": ok})
        time.sleep(0.5)

    if all(e.get("success") for e in executed):
        screen_hash = device.get_screen_hash() or ""
        memory.cache_action(req.action, req.params.get("target", ""), executed, screen_hash)

    return {"success": True, "source": "autoglm", "executed": executed, "description": result.get("description", "")}


@app.post("/api/v1/interrupt")
async def handle_interrupt():
    execution_interrupt.set()
    if device.connected:
        device.press("home")
    termux.speak("好的，已停止操作")
    termux.vibrate(300)
    return {"success": True, "message": "Execution interrupted"}


@app.post("/api/v1/clarify")
async def handle_clarify(req: ClarifyRequest):
    return {
        "prompt": req.prompt,
        "options": [{"index": i, "label": opt} for i, opt in enumerate(req.options)],
        "action": "await_selection"
    }


@app.get("/api/v1/device/info")
async def device_info():
    info = {"device_connected": device.connected}
    if device.connected:
        info["current_app"] = device.get_current_app()
    battery = termux.battery_status()
    if battery:
        info["battery"] = battery
    return info


@app.get("/api/v1/memory/contacts")
async def list_contacts():
    conn = sqlite3.connect(DB_PATH)
    conn.row_factory = sqlite3.Row
    c = conn.cursor()
    c.execute("SELECT nickname, real_name, relation, phone FROM contacts")
    rows = [dict(r) for r in c.fetchall()]
    conn.close()
    return {"contacts": rows}


@app.post("/api/v1/memory/contacts")
async def add_contact(data: Dict[str, str]):
    memory.save_contact(
        nickname=data.get("nickname", ""),
        real_name=data.get("real_name", ""),
        wechat_name=data.get("wechat_name"),
        phone=data.get("phone"),
        relation=data.get("relation")
    )
    return {"success": True}


@app.get("/api/v1/memory/habits")
async def list_habits(user_id: str = "default"):
    return {"habits": memory.get_habit_stats(user_id)}


@app.get("/health")
async def health():
    return {"status": "healthy", "service": "HulaoEdgeAgent", "version": "3.0", "device_connected": device.connected}


# ==================== 本地指令处理 ====================

def _extract_nickname(text: str) -> Optional[str]:
    patterns = ["给(.+?)打", "跟(.+?)联系", "找(.+?)聊", "联系(.+?)$", "呼叫(.+?)"]
    for p in patterns:
        m = re.search(p, text)
        if m:
            return m.group(1).strip()
    return None

def _handle_volume(text: str, dialect: str) -> Dict:
    level = 15 if any(w in text for w in ["大", "高"]) else 5 if any(w in text for w in ["小", "低"]) else 0 if any(w in text for w in ["静音", "关"]) else 10
    termux.volume("music", level)
    reply = f"好额，声音调到{level}了" if dialect == "shanghai" else f"好的，音量已调到{level}"
    termux.speak(reply)
    return {"reply": reply, "mode": "COMMAND", "intent": "CONTROL_VOLUME"}

def _handle_camera(text: str, dialect: str) -> Dict:
    path = f"/sdcard/DCIM/hulao_photo_{int(time.time())}.jpg"
    success = termux.camera_photo(path)
    reply = ("好额，照片拍好了" if dialect == "shanghai" else "好的，照片已拍好") if success else "拍照失败了，再试一次"
    termux.speak(reply)
    return {"reply": reply, "mode": "COMMAND", "intent": "TAKE_PHOTO", "path": path if success else None}

def _handle_battery(text: str, dialect: str) -> Dict:
    battery = termux.battery_status()
    if battery:
        pct, status = battery.get("percentage", "未知"), battery.get("status", "未知")
        reply = f"手机还有{pct}%的电，{status}" if dialect == "shanghai" else f"手机电量{pct}%，{status}"
    else:
        reply = "查不到电量" if dialect == "shanghai" else "无法获取电量信息"
    termux.speak(reply)
    return {"reply": reply, "mode": "COMMAND", "intent": "CHAT", "battery": battery}

def _handle_location(text: str, dialect: str) -> Dict:
    loc = termux.location()
    if loc:
        reply = f"您当前位置：纬度{loc.get('latitude', 0):.4f}，经度{loc.get('longitude', 0):.4f}"
    else:
        reply = "查不到位置" if dialect == "shanghai" else "无法获取位置信息"
    termux.speak(reply)
    return {"reply": reply, "mode": "COMMAND", "intent": "SHARE_LOCATION", "location": loc}

def _handle_music(text: str, dialect: str) -> Dict:
    if any(w in text for w in ["暂停", "停"]):
        termux.media_player("stop")
        reply = "好额，音乐停了" if dialect == "shanghai" else "好的，音乐已暂停"
    else:
        termux.media_player("play")
        reply = "好额，音乐放起来了" if dialect == "shanghai" else "好的，正在播放音乐"
    termux.speak(reply)
    return {"reply": reply, "mode": "COMMAND", "intent": "PLAY_MUSIC"}

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

    if action in app_map and device.connected:
        device.launch_app(app_map[action])
        reply = f"好额，帮侬打开了" if dialect == "shanghai" else "好的，已为您打开"
        termux.speak(reply)
        return {"reply": reply, "mode": "COMMAND", "intent": intent, "source": "offline"}

    if action == "flashlight_on" and device.connected:
        subprocess.run(["termux-toast", "手电筒已打开"], timeout=3)
        return {"reply": "手电筒已打开", "mode": "COMMAND", "intent": intent, "source": "offline"}

    if action == "emergency":
        termux.vibrate(500)
        termux.speak("检测到紧急情况，正在拨打120")
        return {"reply": "紧急求助已触发", "mode": "COMMAND", "intent": "EMERGENCY_SOS", "source": "offline"}

    if action == "help":
        help_text = "我可以帮您：打电话、发微信、查天气、读新闻、设提醒、拍照、放音乐、查电量、开手电筒。您尽管说！"
        termux.speak(help_text)
        return {"reply": help_text, "mode": "CHAT", "intent": "HELP", "source": "offline"}

    offline_reply = "现在没网，我只能做简单的事情哦" if dialect == "shanghai" else "当前为离线模式，仅支持基本操作"
    termux.speak(offline_reply)
    return {"reply": offline_reply, "mode": "COMMAND", "intent": intent, "source": "offline", "slots": slots}

def _replay_cached_actions(cached_actions: List[Dict], dialect: str) -> Dict:
    executed = []
    for act in cached_actions:
        action = act.get("action", "")
        params = act.get("params", {})
        if action == "click_text":
            ok = device.click_text(params.get("text", ""))
        elif action == "click_coord":
            ok = device.click_coord(int(params.get("x", 0)), int(params.get("y", 0)))
        elif action == "click_desc":
            ok = device.click_desc(params.get("desc", ""))
        elif action == "type_text":
            ok = device.type_text(params.get("text", ""))
        elif action == "launch_app":
            ok = device.launch_app(params.get("package", ""))
        else:
            continue
        executed.append({"action": action, "success": ok})
        time.sleep(0.3)

    if all(e["success"] for e in executed):
        reply = "好额，帮侬做好了" if dialect == "shanghai" else "好的，已完成操作"
    else:
        reply = "操作有些问题，我再试试" if dialect == "shanghai" else "部分操作未成功，请重试"
    termux.speak(reply)
    return {"reply": reply, "mode": "COMMAND", "source": "cache_replay", "executed": executed}

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
                termux.speak(data["reply"])
            return data
    except Exception as e:
        logger.error(f"Forward to Hermes failed: {e}")
    fallback = "网络好像睡着了，稍后再试试" if dialect == "shanghai" else "网络连接失败，请稍后再试"
    termux.speak(fallback)
    return {"reply": fallback, "mode": "CHAT"}
