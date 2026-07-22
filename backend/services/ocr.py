"""身份证 OCR 识别、转账回单/票据金额识别（保经云问题 7.18 第 4 条 / 7.18-3）。

对接百度智能云文字识别（OCR）：
- 身份证识别：百度「身份证识别」API（POST /rest/2.0/ocr/v1/idcard），返回结构化的
  姓名/公民身份号码/性别/出生字段。
- 回单/票据金额识别：百度没有专门针对"银行转账回单"的结构化产品（回单格式五花八门，
  不像发票有统一版式），改用「通用票据识别」API（POST /rest/2.0/ocr/v1/receipt）拿到
  逐行文字，再按"金额/合计/总计/实付"关键字启发式定位金额所在行，找不到关键字行则退
  而求其次抓第一个金额格式的数字。识别值仅作预填，充值到账仍由平台人工确认。
- 鉴权：OAuth2 client_credentials 换取 access_token（有效期约 30 天），进程内缓存到
  过期前 5 分钟重新换取，避免每次识别都换一次 token。
- 配置全部来自「系统设置」：OCR_ENABLED、BAIDU_OCR_API_KEY、BAIDU_OCR_SECRET_KEY。
- mock 优先：未启用/未配置密钥/INTEGRATION_MODE!=real 时返回模拟样例，前端会标注
  "模拟识别，请核对"。

⚠️ 百度 OCR 各接口的字段名/端点以官方文档为准，这里的实现基于文档整理、未接入真实密钥
实测；真正接入百度密钥、切到 INTEGRATION_MODE=real 后，请先用一张真实证件/回单跑一次
人工验证，确认字段解析无误再对用户开放。
"""
from __future__ import annotations

import base64
import json
import re
import time
import urllib.parse
import urllib.request
from typing import Optional

from ..providers import provider_mode
from . import settings

# 模拟样例：真实校验位、可通过 is_valid_id_number（1990-05-21 生）。
_MOCK_ID = {"name": "李明", "id_number": "340104199005213241", "gender": "男", "birth": "1990-05-21"}
_MOCK_AMOUNT = 5000.0

_TOKEN_URL = "https://aip.baidubce.com/oauth/2.0/token"
_IDCARD_URL = "https://aip.baidubce.com/rest/2.0/ocr/v1/idcard"
_RECEIPT_URL = "https://aip.baidubce.com/rest/2.0/ocr/v1/receipt"

# 进程内缓存 access_token；{"token": str, "expires_at": float(epoch秒)}。
_token_cache: dict[str, object] = {}

_AMOUNT_PATTERN = re.compile(r"[¥￥]?\s*(\d{1,3}(?:,\d{3})*(?:\.\d{1,2})?)\s*元?")
_AMOUNT_KEYWORDS = ("金额", "合计", "总计", "实付")


class OcrError(Exception):
    pass


def _fetch_access_token() -> dict:
    api_key = settings.get("BAIDU_OCR_API_KEY")
    secret_key = settings.get("BAIDU_OCR_SECRET_KEY")
    if not (api_key and secret_key):
        raise OcrError("百度 OCR 密钥未配置，请联系平台在「系统设置」中填写")
    query = urllib.parse.urlencode({"grant_type": "client_credentials", "client_id": api_key, "client_secret": secret_key})
    req = urllib.request.Request(f"{_TOKEN_URL}?{query}", method="POST")
    with urllib.request.urlopen(req, timeout=15) as res:
        return json.loads(res.read() or "{}")


def _get_access_token() -> str:
    """OAuth2 client_credentials 换取 access_token，有效期内复用缓存。"""
    cached_token = _token_cache.get("token")
    expires_at = _token_cache.get("expires_at", 0)
    if cached_token and time.time() < float(expires_at) - 300:
        return str(cached_token)
    data = _fetch_access_token()
    token = data.get("access_token")
    if not token:
        raise OcrError(f"百度 OCR 鉴权失败：{data.get('error_description') or data.get('error') or '未知错误'}")
    _token_cache["token"] = token
    _token_cache["expires_at"] = time.time() + int(data.get("expires_in", 2592000))
    return str(token)


def _baidu_post(url: str, form: dict) -> dict:
    token = _get_access_token()
    body = urllib.parse.urlencode(form).encode()
    req = urllib.request.Request(f"{url}?access_token={token}", data=body,
                                  headers={"Content-Type": "application/x-www-form-urlencoded"}, method="POST")
    with urllib.request.urlopen(req, timeout=15) as res:
        return json.loads(res.read() or "{}")


def _call_real_id_card(image: bytes) -> dict:
    data = _baidu_post(_IDCARD_URL, {"id_card_side": "front", "image": base64.b64encode(image).decode()})
    if "error_code" in data:
        raise OcrError(f"百度身份证识别失败：{data.get('error_msg', data['error_code'])}")
    words = data.get("words_result", {})
    name = words.get("姓名", {}).get("words", "")
    id_number = words.get("公民身份号码", {}).get("words", "")
    gender = words.get("性别", {}).get("words", "")
    birth_raw = words.get("出生", {}).get("words", "")  # 形如 "1990年5月21日"
    birth = re.sub(r"[年月]", "-", birth_raw).rstrip("日") if birth_raw else ""
    if not (name and id_number):
        raise OcrError("未能从证件识别出姓名或身份证号，请换清晰的证件照或手动填写")
    return {"name": name, "id_number": id_number, "gender": gender, "birth": birth, "mock": False}


def _extract_amount(words_list: list[str]) -> Optional[str]:
    for text in words_list:
        if any(k in text for k in _AMOUNT_KEYWORDS):
            m = _AMOUNT_PATTERN.search(text)
            if m:
                return m.group(1)
    for text in words_list:
        m = _AMOUNT_PATTERN.search(text)
        if m:
            return m.group(1)
    return None


def _call_real_amount(image: bytes) -> float:
    data = _baidu_post(_RECEIPT_URL, {"image": base64.b64encode(image).decode(), "recognize_granularity": "big"})
    if "error_code" in data:
        raise OcrError(f"百度票据识别失败：{data.get('error_msg', data['error_code'])}")
    words_list = [item.get("words", "") for item in data.get("words_result", [])]
    candidate = _extract_amount(words_list)
    if not candidate:
        raise OcrError("未能从票据识别出金额，请手动填写")
    try:
        return round(float(candidate.replace(",", "")), 2)
    except ValueError:
        raise OcrError("未能从票据识别出金额，请手动填写")


def _baidu_configured() -> bool:
    return provider_mode() == "real" and bool(settings.get("BAIDU_OCR_API_KEY"))


def recognize_id_card(image: bytes) -> dict:
    """识别身份证正面，返回 {name, id_number, gender, birth, mock, message}。"""
    if not settings.get_bool("OCR_ENABLED"):
        raise OcrError("身份证识别未启用，请联系平台在「系统设置」中开启 OCR")
    if not image:
        raise OcrError("未收到图片")
    if _baidu_configured():
        result = _call_real_id_card(image)
        result.setdefault("message", "")
        return result
    return {**_MOCK_ID, "mock": True, "message": "模拟识别结果，请核对后再提交（配置真实 OCR 后自动识别）"}


def recognize_receipt_amount(image: bytes) -> dict:
    """识别转账回单/发票金额，返回 {amount, mock, message}，供充值时自动带出金额。
    识别值仅作预填，充值到账仍由平台人工确认（自动入账不绕过审核）。"""
    if not settings.get_bool("OCR_ENABLED"):
        raise OcrError("票据识别未启用，请联系平台在「系统设置」中开启 OCR")
    if not image:
        raise OcrError("未收到图片")
    if _baidu_configured():
        return {"amount": _call_real_amount(image), "mock": False, "message": ""}
    return {"amount": _MOCK_AMOUNT, "mock": True, "message": "模拟识别金额，请核对后再提交（配置真实 OCR 后自动识别）"}
