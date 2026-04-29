import cv2
import numpy as np
import base64
import re
import logging
from typing import Optional, Dict, List

logger = logging.getLogger("HulaoEdgeAgent")

SENSITIVE_PATTERNS = {
    "phone": re.compile(r'1[3-9]\d{9}'),
    "bank_card": re.compile(r'\d{16,19}'),
    "id_card": re.compile(r'\d{17}[\dXx]'),
    "money": re.compile(r'\d+\.?\d{0,2}\s*元'),
    "verification_code": re.compile(r'\b\d{4,6}\b'),
}

ocr = None


def _init_ocr():
    global ocr
    if ocr is not None:
        return
    try:
        from paddleocr import PaddleOCR
        ocr = PaddleOCR(lang='ch', use_angle_cls=False, use_gpu=False,
                        det_db_thresh=0.3, rec_batch_num=1)
        logger.info("PaddleOCR initialized for privacy filter")
    except ImportError:
        logger.warning("PaddleOCR not installed. pip install paddleocr")
    except Exception as e:
        logger.warning(f"PaddleOCR init failed: {e}")


def desensitize_image(img: np.ndarray) -> np.ndarray:
    _init_ocr()
    img_copy = img.copy()
    h, w = img_copy.shape[:2]

    if ocr is None:
        return _fallback_desensitize(img_copy)

    try:
        results = ocr.ocr(img_copy, cls=False)
        if not results or not results[0]:
            return img_copy

        for line in results[0]:
            box = line[0]
            text = line[1][0]

            is_sensitive = False
            for pattern_name, pattern in SENSITIVE_PATTERNS.items():
                if pattern.search(text):
                    is_sensitive = True
                    break

            if is_sensitive:
                pts = np.array(box, np.int32)
                x_min = max(0, int(pts[:, 0].min()))
                y_min = max(0, int(pts[:, 1].min()))
                x_max = min(w, int(pts[:, 0].max()))
                y_max = min(h, int(pts[:, 1].max()))
                img_copy[y_min:y_max, x_min:x_max] = 0

        return img_copy
    except Exception as e:
        logger.warning(f"PaddleOCR desensitize failed: {e}")
        return _fallback_desensitize(img_copy)


def _fallback_desensitize(img: np.ndarray) -> np.ndarray:
    try:
        gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
        _, thresh = cv2.threshold(gray, 180, 255, cv2.THRESH_BINARY_INV)
        contours, _ = cv2.findContours(thresh, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
        for cnt in contours:
            x, y, w_r, h_r = cv2.boundingRect(cnt)
            if w_r > 30 and h_r > 8 and h_r < 60 and w_r < 500:
                img[y:y + h_r, x:x + w_r] = 0
        return img
    except Exception as e:
        logger.warning(f"Fallback desensitize failed: {e}")
        return img


def capture_screen_numpy(device) -> Optional[np.ndarray]:
    try:
        pil_img = device.screenshot(format="pillow")
        opencv_img = cv2.cvtColor(np.array(pil_img), cv2.COLOR_RGB2BGR)
        return opencv_img
    except Exception as e:
        logger.warning(f"Capture screen failed: {e}")
        return None


def capture_screen_base64(device) -> Optional[str]:
    try:
        pil_img = device.screenshot(format="pillow")
        import io
        buffer = io.BytesIO()
        pil_img.save(buffer, format="JPEG", quality=75)
        return base64.b64encode(buffer.getvalue()).decode("utf-8")
    except Exception as e:
        logger.warning(f"Capture screen base64 failed: {e}")
        return None


def get_ui_summary(device) -> Dict:
    try:
        import xml.etree.ElementTree as ET
        xml_str = device.dump_hierarchy()
        root = ET.fromstring(xml_str)

        elements = []
        for node in root.iter():
            if node.attrib.get("clickable") == "true" or \
               node.attrib.get("long-clickable") == "true" or \
               node.attrib.get("scrollable") == "true":
                bounds = node.attrib.get("bounds", "")
                text = node.attrib.get("text", "")
                content_desc = node.attrib.get("content-desc", "")
                resource_id = node.attrib.get("resource-id", "")
                class_name = node.attrib.get("class", "")
                elements.append({
                    "text": text,
                    "content_desc": content_desc,
                    "resource_id": resource_id.split("/")[-1] if resource_id else "",
                    "class": class_name,
                    "bounds": bounds
                })

        app_info = device.app_current()
        return {
            "app_package": app_info.get("package", ""),
            "app_activity": app_info.get("activity", ""),
            "interactive_elements": elements[:50]
        }
    except Exception as e:
        logger.warning(f"Get UI summary failed: {e}")
        return {"app_package": "", "app_activity": "", "interactive_elements": []}


def prepare_upload_payload(device, target_description: str) -> Optional[Dict]:
    img = capture_screen_numpy(device)
    if img is None:
        return None

    clean_img = desensitize_image(img)
    _, buffer = cv2.imencode('.jpg', clean_img, [cv2.IMWRITE_JPEG_QUALITY, 75])
    img_base64 = base64.b64encode(buffer).decode('utf-8')
    ui_summary = get_ui_summary(device)

    return {
        "image_base64": img_base64,
        "target": target_description,
        "ui_summary": ui_summary,
        "device_resolution": {
            "width": img.shape[1],
            "height": img.shape[0]
        }
    }


def match_template_on_screen(device, template_name: str, threshold: float = 0.8) -> Optional[Dict]:
    try:
        screen = capture_screen_numpy(device)
        if screen is None:
            return None
        template_dir = os.path.expanduser("~/.hermes/templates/")
        template_path = os.path.join(template_dir, f"{template_name}.png")
        if not os.path.exists(template_path):
            return None

        screen_gray = cv2.cvtColor(screen, cv2.COLOR_BGR2GRAY)
        template = cv2.imread(template_path, cv2.IMREAD_GRAYSCALE)

        scales = [0.8, 0.9, 1.0, 1.1, 1.2]
        best_match = None
        best_val = 0

        for scale in scales:
            resized = cv2.resize(template, None, fx=scale, fy=scale)
            if resized.shape[0] > screen_gray.shape[0] or \
               resized.shape[1] > screen_gray.shape[1]:
                continue
            result = cv2.matchTemplate(screen_gray, resized, cv2.TM_CCOEFF_NORMED)
            min_val, max_val, min_loc, max_loc = cv2.minMaxLoc(result)
            if max_val > best_val:
                best_val = max_val
                h_t, w_t = resized.shape
                best_match = {
                    "x": int(max_loc[0] + w_t // 2),
                    "y": int(max_loc[1] + h_t // 2),
                    "confidence": float(max_val),
                    "width": w_t,
                    "height": h_t
                }

        if best_val >= threshold and best_match:
            return best_match
        return None
    except Exception as e:
        logger.warning(f"Template match failed: {e}")
        return None


import os
