import uiautomator2 as u2
import time
import logging
import threading
import hashlib
import json
from typing import Optional, Dict, Any, List, Callable
from enum import Enum

logger = logging.getLogger("HulaoEdgeAgent")


class ClickStrategy(Enum):
    TEXT = "text"
    DESC = "desc"
    RESOURCE_ID = "resource_id"
    COORD = "coord"
    AUTOGLM = "autoglm"


class ActionExecutor:
    def __init__(self, device: u2.Device, memory_store=None):
        self.device = device
        self.memory = memory_store
        self._cancel_flag = threading.Event()
        self._current_task_id: Optional[str] = None
        self._task_status: Dict[str, str] = {}
        self._last_ui_snapshot: Optional[str] = None

    def cancel(self):
        self._cancel_flag.set()
        logger.info(f"Task {self._current_task_id} cancel requested")

    def is_cancelled(self) -> bool:
        return self._cancel_flag.is_set()

    def _reset_cancel(self):
        self._cancel_flag.clear()

    def execute_action_chain(self, task_id: str, actions: List[Dict],
                             on_step_complete: Callable = None) -> Dict:
        self._current_task_id = task_id
        self._task_status[task_id] = "RUNNING"
        self._reset_cancel()
        self._last_ui_snapshot = self.device.dump_hierarchy()

        executed = []
        for i, action in enumerate(actions):
            if self.is_cancelled():
                self._task_status[task_id] = "CANCELLED"
                logger.info(f"Task {task_id} cancelled at step {i}")
                return {"success": False, "status": "CANCELLED", "executed": executed}

            result = self._execute_single_action(action)
            executed.append({"step": i, "action": action, "result": result})

            if on_step_complete:
                on_step_complete(task_id, i, result)

            if not result.get("success", False):
                retry_ok = self._retry(action, i)
                if not retry_ok:
                    self._task_status[task_id] = "FAILED"
                    self._rollback()
                    return {"success": False, "status": "FAILED",
                            "failed_step": i, "executed": executed}

            time.sleep(0.5)

        self._task_status[task_id] = "COMPLETED"

        if self.memory:
            screen_hash = self._get_screen_hash()
            self.memory.cache_action(
                task_id, "", actions, screen_hash
            )

        return {"success": True, "status": "COMPLETED", "executed": executed}

    def _execute_single_action(self, action: Dict) -> Dict:
        action_type = action.get("type", "")
        try:
            if action_type == "click":
                return self._click(action)
            elif action_type == "long_click":
                return self._long_click(action)
            elif action_type == "type":
                return self._type_text(action)
            elif action_type == "press":
                return self._press_key(action)
            elif action_type == "swipe":
                return self._swipe(action)
            elif action_type == "launch":
                return self._launch_app(action)
            elif action_type == "scroll":
                return self._scroll(action)
            elif action_type == "wait":
                return self._wait(action)
            else:
                return {"success": False, "error": f"Unknown action type: {action_type}"}
        except Exception as e:
            return {"success": False, "error": str(e)}

    def _click(self, action: Dict) -> Dict:
        strategy = action.get("strategy", ClickStrategy.TEXT.value)

        if strategy == ClickStrategy.TEXT.value:
            text = action.get("text", "")
            try:
                self.device(text=text).click()
                return {"success": True, "strategy": "text", "target": text}
            except:
                pass

        if strategy in [ClickStrategy.TEXT.value, ClickStrategy.AUTOGLM.value]:
            desc = action.get("desc", action.get("text", ""))
            if desc:
                try:
                    self.device(description=desc).click()
                    return {"success": True, "strategy": "desc", "target": desc}
                except:
                    pass

        if strategy in [ClickStrategy.TEXT.value, ClickStrategy.DESC.value, ClickStrategy.AUTOGLM.value]:
            rid = action.get("resource_id", "")
            if rid:
                try:
                    self.device(resourceId=rid).click()
                    return {"success": True, "strategy": "resource_id", "target": rid}
                except:
                    pass

        if "x" in action and "y" in action:
            try:
                self.device.click(action["x"], action["y"])
                return {"success": True, "strategy": "coord",
                        "target": f"({action['x']},{action['y']})"}
            except:
                pass

        return {"success": False, "error": "All click strategies failed"}

    def _long_click(self, action: Dict) -> Dict:
        try:
            if "text" in action:
                self.device(text=action["text"]).long_click()
            elif "x" in action and "y" in action:
                self.device.long_click(action["x"], action["y"])
            return {"success": True}
        except Exception as e:
            return {"success": False, "error": str(e)}

    def _type_text(self, action: Dict) -> Dict:
        try:
            text = action.get("text", "")
            clear = action.get("clear", True)
            self.device.send_keys(text, clear=clear)
            return {"success": True, "text": text}
        except Exception as e:
            return {"success": False, "error": str(e)}

    def _press_key(self, action: Dict) -> Dict:
        try:
            key = action.get("key", "back")
            self.device.press(key)
            return {"success": True, "key": key}
        except Exception as e:
            return {"success": False, "error": str(e)}

    def _swipe(self, action: Dict) -> Dict:
        try:
            direction = action.get("direction", "up")
            scale = action.get("scale", 0.9)
            self.device.swipe_ext(direction, scale=scale)
            return {"success": True, "direction": direction}
        except Exception as e:
            return {"success": False, "error": str(e)}

    def _launch_app(self, action: Dict) -> Dict:
        try:
            package = action.get("package", "")
            self.device.app_start(package)
            return {"success": True, "package": package}
        except Exception as e:
            return {"success": False, "error": str(e)}

    def _scroll(self, action: Dict) -> Dict:
        try:
            direction = action.get("direction", "down")
            if direction == "down":
                self.device(scrollable=True).fling.forward()
            else:
                self.device(scrollable=True).fling.backward()
            return {"success": True, "direction": direction}
        except Exception as e:
            return {"success": False, "error": str(e)}

    def _wait(self, action: Dict) -> Dict:
        seconds = action.get("seconds", 1)
        time.sleep(seconds)
        return {"success": True, "waited": seconds}

    def _retry(self, action: Dict, step: int, max_retries: int = 2) -> bool:
        for attempt in range(max_retries):
            logger.info(f"Retrying step {step}, attempt {attempt + 1}/{max_retries}")
            time.sleep(2)
            result = self._execute_single_action(action)
            if result.get("success", False):
                return True
            self._rollback()
        return False

    def _rollback(self):
        try:
            current = self.device.dump_hierarchy()
            if current == self._last_ui_snapshot:
                logger.info("Screen unchanged, pressing back to rollback")
                self.device.press("back")
                time.sleep(0.5)
        except Exception as e:
            logger.warning(f"Rollback failed: {e}")

    def _get_screen_hash(self) -> str:
        try:
            xml = self.device.dump_hierarchy()
            return hashlib.md5(xml.encode()).hexdigest()
        except:
            return ""

    def get_task_status(self, task_id: str) -> str:
        return self._task_status.get(task_id, "UNKNOWN")


class HermesVisualExecutor:
    def __init__(self, executor: ActionExecutor, autoglm_analyzer=None,
                 privacy_module=None):
        self.executor = executor
        self.autoglm = autoglm_analyzer
        self.privacy = privacy_module

    def execute_visual_command(self, command: str, task_id: str = "") -> Dict:
        if not task_id:
            task_id = f"vis_{int(time.time())}"

        if self.executor.is_cancelled():
            return {"success": False, "status": "CANCELLED"}

        payload = None
        if self.privacy:
            payload = self.privacy.prepare_upload_payload(
                self.executor.device, command
            )

        if self.autoglm and payload:
            result = self.autoglm.analyze_screen(
                command, image_base64=payload.get("image_base64", ""),
                ui_summary=payload.get("ui_summary", {})
            )
            if result and result.get("actions"):
                return self.executor.execute_action_chain(
                    task_id, result["actions"]
                )

        if self.privacy:
            template_result = self.privacy.match_template_on_screen(
                self.executor.device, command
            )
            if template_result:
                action = {
                    "type": "click", "strategy": "coord",
                    "x": template_result["x"], "y": template_result["y"]
                }
                return self.executor.execute_action_chain(
                    task_id, [action]
                )

        return {"success": False, "status": "NO_ACTION_FOUND",
                "message": "Neither AutoGLM nor template match found target"}
