"""
AI沪老 2.0 - Android App API 适配层

本模块在 Hermes 原生 API Server 基础上，提供专为老人助手场景优化的 API 端点：
- /hulao/v1/chat    : 简化版对话接口（自动注入老人助手人设）
- /hulao/v1/voice   : 语音指令接口（接收语音文本，返回操作指令）
- /hulao/v1/action  : 手机操作执行接口（调用无障碍服务）
- /hulao/v1/status  : 服务状态查询
- /hulao/v1/contacts: 联系人查询

安全机制：
- Bearer Token 认证
- 请求频率限制
- 输入内容安全扫描
"""

import asyncio
import json
import logging
import os
import time
import uuid
from typing import Any, Dict, List, Optional

logger = logging.getLogger(__name__)

# 老人助手系统提示词（注入到每次对话）
HULAO_SYSTEM_PROMPT = """你是"沪老"，一位温暖、耐心、贴心的AI老人助手。

核心原则：
1. 说短句，每句不超过15个字
2. 操作指导只说一步，等确认后再说下一步
3. 用生活化比喻解释技术概念
4. 多鼓励，"您真棒！""太厉害了！"
5. 涉及金钱操作必须反复确认
6. 紧急情况建议拨打120或联系家人

你可以帮助老人：打电话、发短信、微信聊天、导航、设闹钟、
查天气、提醒吃药、拍照等手机操作。"""

# 请求频率限制（每分钟最大请求数）
RATE_LIMIT_PER_MINUTE = 30

# 请求记录（用于频率限制）
_request_records: Dict[str, List[float]] = {}


def _check_rate_limit(client_id: str) -> bool:
    """检查请求频率是否超限"""
    now = time.time()
    if client_id not in _request_records:
        _request_records[client_id] = []

    # 清理超过60秒的记录
    _request_records[client_id] = [
        t for t in _request_records[client_id] if now - t < 60
    ]

    if len(_request_records[client_id]) >= RATE_LIMIT_PER_MINUTE:
        return False

    _request_records[client_id].append(now)
    return True


def _scan_safety(content: str) -> Optional[str]:
    """扫描输入内容的安全性，返回警告信息或None"""
    if not content:
        return None

    # 检测敏感信息
    sensitive_patterns = [
        ("身份证", "请勿在对话中输入身份证号码"),
        ("银行卡", "请勿在对话中输入银行卡号码"),
        ("密码", "请勿在对话中输入密码"),
        ("转账", "涉及转账操作，请务必与家人确认"),
    ]

    for keyword, warning in sensitive_patterns:
        if keyword in content:
            return warning

    return None


def _build_hulao_chat_request(
    user_message: str,
    session_id: Optional[str] = None,
    context: Optional[List[Dict]] = None,
) -> Dict[str, Any]:
    """构建 OpenAI 兼容的聊天请求体"""
    messages = [
        {"role": "system", "content": HULAO_SYSTEM_PROMPT}
    ]

    # 添加历史上下文
    if context:
        for msg in context:
            role = msg.get("role", "user")
            content = msg.get("content", "")
            if role in ("user", "assistant") and content:
                messages.append({"role": role, "content": content})

    # 添加当前用户消息
    messages.append({"role": "user", "content": user_message})

    return {
        "model": "hulao-agent",
        "messages": messages,
        "stream": False,
        "temperature": 0.7,
        "max_tokens": 1024,
        **({"X-Hermes-Session-Id": session_id} if session_id else {}),
    }


def _build_hulao_voice_request(
    voice_text: str,
    session_id: Optional[str] = None,
) -> Dict[str, Any]:
    """构建语音指令请求体"""
    enhanced_prompt = f"""用户通过语音说了以下内容，请理解意图并给出操作指导：
"{voice_text}"

注意：语音识别可能有误差，请根据上下文推断用户真实意图。
如果不确定，请用简单的方式确认。"""

    messages = [
        {"role": "system", "content": HULAO_SYSTEM_PROMPT + "\n\n用户通过语音输入，请特别注意：语音识别可能不准确，请灵活理解用户意图。回复要更简短，适合语音播报。"},
        {"role": "user", "content": enhanced_prompt},
    ]

    return {
        "model": "hulao-agent",
        "messages": messages,
        "stream": False,
        "temperature": 0.5,
        "max_tokens": 512,
        **({"X-Hermes-Session-Id": session_id} if session_id else {}),
    }


def _build_action_request(
    action_type: str,
    action_params: Dict[str, Any],
    session_id: Optional[str] = None,
) -> Dict[str, Any]:
    """构建手机操作执行请求体"""
    action_descriptions = {
        "call": "拨打电话",
        "sms": "发送短信",
        "wechat": "微信操作",
        "navigate": "地图导航",
        "alarm": "设置闹钟",
        "reminder": "设置提醒",
        "weather": "查看天气",
        "photo": "拍照",
        "contact": "查找联系人",
    }

    action_desc = action_descriptions.get(action_type, action_type)
    prompt = f"""用户请求执行手机操作：{action_desc}
操作参数：{json.dumps(action_params, ensure_ascii=False)}

请确认操作意图，并给出简短的操作确认信息。如果操作涉及金钱或隐私，请特别提醒。"""

    messages = [
        {"role": "system", "content": HULAO_SYSTEM_PROMPT + "\n\n用户请求执行手机操作，请确认操作并给出安全提示。"},
        {"role": "user", "content": prompt},
    ]

    return {
        "model": "hulao-agent",
        "messages": messages,
        "stream": False,
        "temperature": 0.3,
        "max_tokens": 256,
        **({"X-Hermes-Session-Id": session_id} if session_id else {}),
    }
