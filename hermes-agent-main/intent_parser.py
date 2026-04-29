import re
import json
import sqlite3
import os
import logging
from typing import Optional, Dict, List
from collections import OrderedDict

logger = logging.getLogger("HulaoEdgeAgent")

DB_PATH = os.path.expanduser("~/hulao_memory.db")
LRU_CACHE_MAX = 200

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


class MemoryStore:
    def __init__(self, db_path: str = DB_PATH):
        self.db_path = db_path
        self._fernet = None
        self._init_db()
        self._init_encryption()

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

    def _init_encryption(self):
        try:
            from cryptography.fernet import Fernet
            key_path = os.path.expanduser("~/.hermes/encryption_key")
            if os.path.exists(key_path):
                with open(key_path, "rb") as f:
                    key = f.read()
            else:
                key = Fernet.generate_key()
                os.makedirs(os.path.dirname(key_path), exist_ok=True)
                with open(key_path, "wb") as f:
                    f.write(key)
            self._fernet = Fernet(key)
            logger.info("Memory encryption enabled")
        except ImportError:
            logger.warning("cryptography not installed, memory not encrypted")
        except Exception as e:
            logger.warning(f"Encryption init failed: {e}")

    def _encrypt(self, text: str) -> str:
        if self._fernet and text:
            return self._fernet.encrypt(text.encode()).decode()
        return text

    def _decrypt(self, text: str) -> str:
        if self._fernet and text:
            try:
                return self._fernet.decrypt(text.encode()).decode()
            except:
                return text
        return text

    def resolve_contact(self, nickname: str) -> Optional[Dict]:
        conn = sqlite3.connect(self.db_path)
        conn.row_factory = sqlite3.Row
        c = conn.cursor()
        c.execute("SELECT * FROM contacts WHERE nickname = ?", (nickname,))
        row = c.fetchone()
        conn.close()
        if row:
            d = dict(row)
            d["phone"] = self._decrypt(d.get("phone", ""))
            return d
        return None

    def find_ambiguous_contacts(self, nickname: str) -> List[Dict]:
        conn = sqlite3.connect(self.db_path)
        conn.row_factory = sqlite3.Row
        c = conn.cursor()
        c.execute("SELECT * FROM contacts WHERE nickname LIKE ?", (f"%{nickname}%",))
        rows = c.fetchall()
        conn.close()
        return [dict(r) for r in rows]

    def save_contact(self, nickname: str, real_name: str,
                     wechat_name: str = None, phone: str = None,
                     relation: str = None):
        encrypted_phone = self._encrypt(phone) if phone else None
        conn = sqlite3.connect(self.db_path)
        c = conn.cursor()
        c.execute("""INSERT OR REPLACE INTO contacts
                     (nickname, real_name, wechat_name, phone, relation, last_contact_time)
                     VALUES (?, ?, ?, ?, ?, datetime('now'))""",
                  (nickname, real_name, wechat_name, encrypted_phone, relation))
        conn.commit()
        conn.close()

    def get_cached_action(self, intent: str, target: str = "",
                          screen_hash: str = "") -> Optional[List[Dict]]:
        conn = sqlite3.connect(self.db_path)
        c = conn.cursor()
        if screen_hash:
            c.execute("SELECT actions FROM action_cache WHERE intent=? AND target=? AND screen_hash=?",
                      (intent, target, screen_hash))
        else:
            c.execute("SELECT actions FROM action_cache WHERE intent=? AND target=?",
                      (intent, target))
        row = c.fetchone()
        if row:
            c.execute("UPDATE action_cache SET hit_count=hit_count+1, last_used=datetime('now') WHERE intent=? AND target=?",
                      (intent, target))
            conn.commit()
            conn.close()
            return json.loads(row[0])
        conn.close()
        return None

    def cache_action(self, intent: str, target: str, actions: List[Dict],
                     screen_hash: str = ""):
        conn = sqlite3.connect(self.db_path)
        c = conn.cursor()
        c.execute("""INSERT OR REPLACE INTO action_cache
                     (intent, target, actions, screen_hash, hit_count, last_used)
                     VALUES (?, ?, ?, ?,
                       COALESCE((SELECT hit_count FROM action_cache WHERE intent=? AND target=?), 0) + 1,
                       datetime('now'))""",
                  (intent, target, json.dumps(actions, ensure_ascii=False),
                   screen_hash, intent, target))
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
                     DO UPDATE SET frequency=frequency+1, last_triggered=datetime('now')""",
                  (user_id, intent, target))
        conn.commit()
        conn.close()

    def get_habit_stats(self, user_id: str = "default") -> List[Dict]:
        conn = sqlite3.connect(self.db_path)
        conn.row_factory = sqlite3.Row
        c = conn.cursor()
        c.execute("SELECT * FROM habits WHERE user_id=? ORDER BY frequency DESC LIMIT 20",
                  (user_id,))
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


class OfflineIntentParser:
    def __init__(self, templates: List[Dict] = None):
        self.templates = templates or OFFLINE_INTENT_TEMPLATES
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
            m = re.search(r"([\u4e00-\u9fa5]{2,3})(?:的?天气)", text)
            if m:
                slots["city"] = m.group(1)
        if "time" in template["slots"]:
            m = re.search(r"(\d+点|早上|中午|下午|晚上|睡前|饭后)", text)
            if m:
                slots["time"] = m.group(1)
        if "medicine" in template["slots"]:
            m = re.search(r"([\u4e00-\u9fa5]{2,6})(?:的?提醒|该?吃)", text)
            if m:
                slots["medicine"] = m.group(1)
        if "app" in template["slots"]:
            m = re.search(r"打开(.+?)(?:$|，|。|！)", text)
            if m:
                slots["app"] = m.group(1).strip()
        if "query" in template["slots"]:
            m = re.search(r"(?:搜索|查一下|百度|搜)(.+?)(?:$|，|。|！)", text)
            if m:
                slots["query"] = m.group(1).strip()
        return slots


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


def three_level_parse(text: str, memory: MemoryStore,
                      offline_parser: OfflineIntentParser,
                      device=None) -> Dict:
    if device:
        screen_hash = ""
        try:
            import hashlib
            xml = device.dump_hierarchy()
            screen_hash = hashlib.md5(xml.encode()).hexdigest()
        except:
            pass
        cached = memory.get_cached_action(text, "", screen_hash)
        if cached:
            return {"source": "cache", "intent": "CACHED",
                    "actions": cached, "text": text}

    offline_result = offline_parser.parse(text)
    if offline_result:
        return offline_result

    return {"source": "cloud", "intent": "UNKNOWN",
            "action": "cloud_forward", "text": text}
