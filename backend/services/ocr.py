"""身份证 OCR 识别（保经云问题 7.18 第 4 条）。

设计与项目一贯的 providers 抽象一致——mock 优先、真实接口按需接入：
- 配置全部来自平台端「系统设置」（services.settings，env 回落）：OCR_ENABLED、
  OCR_PROVIDER_URL、OCR_APP_ID、OCR_APP_KEY。
- 真实模式（配了 OCR_PROVIDER_URL 且 INTEGRATION_MODE=real）：把图片 base64 POST 给
  通用 JSON 接口，解析 {name, id_number}；不同厂商（腾讯/百度/阿里）只需换 URL 与解析。
- 否则返回**模拟识别**样例，便于在未接入真实 OCR 时联调「拍照→自动填充」闭环，前端会
  标注“模拟识别、请核对”。识别结果一律由用户在提交前复核（保存仍走身份证校验与最小年龄）。
"""
from __future__ import annotations

import base64
import json
import urllib.request

from ..providers import provider_mode
from . import settings

# 模拟样例：真实校验位、可通过 is_valid_id_number（1990-05-21 生）。
_MOCK = {"name": "李明", "id_number": "340104199005213241", "gender": "男", "birth": "1990-05-21"}


class OcrError(Exception):
    pass


def _call_real(url: str, image: bytes) -> dict:
    payload = {
        "app_id": settings.get("OCR_APP_ID"),
        "app_key": settings.get("OCR_APP_KEY"),
        "image_base64": base64.b64encode(image).decode(),
        "card_side": "front",
    }
    body = json.dumps(payload, ensure_ascii=False).encode()
    req = urllib.request.Request(url, data=body, headers={"Content-Type": "application/json"}, method="POST")
    with urllib.request.urlopen(req, timeout=15) as res:
        data = json.loads(res.read() or "{}")
    # 兼容 {name,id_number} 或 {data:{...}} 两种常见返回结构
    src = data.get("data", data)
    name = src.get("name") or src.get("Name") or ""
    id_number = src.get("id_number") or src.get("idNumber") or src.get("IdNum") or src.get("id") or ""
    if not (name and id_number):
        raise OcrError("未能从证件识别出姓名或身份证号，请换清晰的证件照或手动填写")
    return {"name": name, "id_number": id_number, "gender": src.get("gender", ""), "birth": src.get("birth", ""), "mock": False}


def recognize_id_card(image: bytes) -> dict:
    """识别身份证正面，返回 {name, id_number, gender, birth, mock, message}。"""
    if not settings.get_bool("OCR_ENABLED"):
        raise OcrError("身份证识别未启用，请联系平台在「系统设置」中开启 OCR")
    if not image:
        raise OcrError("未收到图片")
    url = settings.get("OCR_PROVIDER_URL")
    if url and provider_mode() == "real":
        result = _call_real(url, image)
        result.setdefault("message", "")
        return result
    return {**_MOCK, "mock": True, "message": "模拟识别结果，请核对后再提交（配置真实 OCR 后自动识别）"}
