import subprocess
import time
import threading
import logging

logger = logging.getLogger("HulaoEdgeAgent")


def ensure_connection():
    try:
        subprocess.run(["adb", "shell", "echo", "ok"], check=True, timeout=5, capture_output=True)
        return True
    except Exception:
        logger.warning("ADB connection lost, attempting reconnect...")
        try:
            subprocess.run(["adb", "tcpip", "5555"], check=True, timeout=5, capture_output=True)
            time.sleep(2)
            subprocess.run(["adb", "connect", "127.0.0.1:5555"], check=True, timeout=5, capture_output=True)
            time.sleep(1)
            logger.info("ADB reconnected successfully")
            return True
        except Exception as e:
            logger.error(f"ADB reconnect failed: {e}")
            return False


class ADBHeartbeat:
    def __init__(self, interval: int = 120):
        self.interval = interval
        self._running = False
        self._thread = None

    def start(self):
        if self._thread and self._thread.is_alive():
            return
        self._running = True
        self._thread = threading.Thread(target=self._loop, daemon=True)
        self._thread.start()
        logger.info(f"ADB heartbeat started (every {self.interval}s)")

    def stop(self):
        self._running = False

    def _loop(self):
        while self._running:
            ensure_connection()
            time.sleep(self.interval)
