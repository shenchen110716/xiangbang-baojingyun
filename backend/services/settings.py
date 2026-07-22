"""平台端系统设置：存储/短信/OCR 等运营配置的读写与缓存。

设计要点（见 CLAUDE.md 安全姿态）：
- 敏感项（secret=True）用项目现有 Fernet（ID_ENCRYPTION_KEY 派生）加密后入库，
  对外只返回掩码；加密主密钥本身只在环境变量。
- 读取优先级：数据库 → 环境变量（同名 key）→ 默认值，兼容已有 env 配置。
- 进程内缓存，set 后失效；消费方（storage/notify/ocr）用 get() 即可，无需传 session。
"""
from __future__ import annotations

import os
from typing import Optional

from ..core.db import SessionLocal
from ..core.id_number import decrypt_bytes, encrypt_bytes
from ..models import SystemSetting

MASK = "••••••"  # 掩码：读取时代替密文返回；写入时收到它表示“不改动”

# 设置注册表：分组渲染 + 判定是否加密。key 与环境变量同名，便于 env 回落。
SETTINGS_REGISTRY = [
    # 对象存储（上传文件持久化）
    {"key": "STORAGE_BACKEND", "group": "对象存储", "label": "存储后端", "secret": False,
     "kind": "select", "options": ["local", "s3"], "hint": "生产选 s3（Cloudflare R2 / AWS S3）；local 仅开发用"},
    {"key": "S3_BUCKET", "group": "对象存储", "label": "存储桶名", "secret": False, "kind": "text"},
    {"key": "S3_ENDPOINT_URL", "group": "对象存储", "label": "Endpoint", "secret": False, "kind": "text",
     "hint": "R2 形如 https://<AccountID>.r2.cloudflarestorage.com"},
    {"key": "S3_REGION", "group": "对象存储", "label": "区域", "secret": False, "kind": "text", "hint": "R2 用 auto"},
    {"key": "S3_ACCESS_KEY_ID", "group": "对象存储", "label": "Access Key ID", "secret": True, "kind": "password"},
    {"key": "S3_SECRET_ACCESS_KEY", "group": "对象存储", "label": "Secret Access Key", "secret": True, "kind": "password"},
    # 短信
    {"key": "SMS_PROVIDER_URL", "group": "短信", "label": "短信网关地址", "secret": False, "kind": "text"},
    {"key": "SMS_SIGN_NAME", "group": "短信", "label": "短信签名", "secret": False, "kind": "text"},
    {"key": "SMS_API_KEY", "group": "短信", "label": "短信 API 密钥", "secret": True, "kind": "password"},
    # 身份证 OCR（百度智能云文字识别）
    {"key": "OCR_ENABLED", "group": "身份证OCR", "label": "启用 OCR", "secret": False, "kind": "bool"},
    {"key": "BAIDU_OCR_API_KEY", "group": "身份证OCR", "label": "百度智能云 API Key", "secret": False, "kind": "text"},
    {"key": "BAIDU_OCR_SECRET_KEY", "group": "身份证OCR", "label": "百度智能云 Secret Key", "secret": True, "kind": "password"},
    # 微信支付（平台服务费收款商户号）
    {"key": "WECHAT_PAY_MCH_ID", "group": "微信支付", "label": "商户号", "secret": False, "kind": "text"},
    {"key": "WECHAT_PAY_APP_ID", "group": "微信支付", "label": "AppID", "secret": False, "kind": "text", "hint": "公众号或小程序 AppID"},
    {"key": "WECHAT_PAY_NOTIFY_URL", "group": "微信支付", "label": "支付结果通知地址", "secret": False, "kind": "text", "hint": "形如 https://your-domain/api/payments/wechat-notify"},
    {"key": "WECHAT_PAY_CERT_SERIAL_NO", "group": "微信支付", "label": "商户证书序列号", "secret": False, "kind": "text"},
    {"key": "WECHAT_PAY_API_V3_KEY", "group": "微信支付", "label": "APIv3 密钥", "secret": True, "kind": "password"},
    {"key": "WECHAT_PAY_PRIVATE_KEY", "group": "微信支付", "label": "商户 API 私钥（PEM）", "secret": True, "kind": "password"},
    {"key": "WECHAT_PAY_PLATFORM_CERT", "group": "微信支付", "label": "微信支付平台证书（PEM）", "secret": True, "kind": "password"},
    {"key": "WECHAT_MINIPROGRAM_APP_SECRET", "group": "微信支付", "label": "小程序 AppSecret", "secret": True, "kind": "password", "hint": "用于 wx.login() 换取 openid"},
    # 使用费收款
    {"key": "USAGE_FEE_DEFAULT_METHOD", "group": "使用费收款", "label": "默认收款方式", "secret": False, "kind": "select", "options": ["wechat", "bank"], "hint": "使用费缴纳页默认选中的收款方式"},
]

_BY_KEY = {item["key"]: item for item in SETTINGS_REGISTRY}
_cache: Optional[dict[str, str]] = None


def is_secret(key: str) -> bool:
    return bool(_BY_KEY.get(key, {}).get("secret"))


def _load() -> None:
    global _cache
    data: dict[str, str] = {}
    with SessionLocal() as s:
        for row in s.query(SystemSetting).all():
            data[row.key] = row.value
    _cache = data


def invalidate() -> None:
    global _cache
    _cache = None


def get(key: str, default: str = "") -> str:
    """DB（密文自动解密）→ 环境变量 → 默认值。"""
    if _cache is None:
        _load()
    raw = (_cache or {}).get(key)
    if raw not in (None, ""):
        if is_secret(key):
            try:
                return decrypt_bytes(raw.encode()).decode()
            except Exception:
                return default
        return raw
    return os.getenv(key, default)


def get_bool(key: str, default: bool = False) -> bool:
    val = get(key, "1" if default else "0").strip().lower()
    return val in ("1", "true", "yes", "on")


def configured(key: str) -> bool:
    """该项是否已有有效值（DB 或 env）。"""
    return bool(get(key))


def admin_view() -> list[dict]:
    """按分组返回设置项，密钥项只返回 configured 布尔 + 掩码，绝不回传明文。"""
    groups: dict[str, dict] = {}
    for item in SETTINGS_REGISTRY:
        g = groups.setdefault(item["group"], {"group": item["group"], "items": []})
        entry = {
            "key": item["key"], "label": item["label"], "secret": item["secret"],
            "kind": item["kind"], "options": item.get("options"), "hint": item.get("hint", ""),
            "configured": configured(item["key"]),
        }
        entry["value"] = MASK if (item["secret"] and entry["configured"]) else ("" if item["secret"] else get(item["key"]))
        g["items"].append(entry)
    return list(groups.values())


def set_many(values: dict[str, str], user_id: Optional[int]) -> None:
    """批量写入。仅接受注册表内的 key；密钥项加密入库；收到掩码则跳过（保持原值）。"""
    from datetime import datetime
    with SessionLocal() as s:
        for key, value in values.items():
            item = _BY_KEY.get(key)
            if not item:
                continue
            if item["secret"] and value == MASK:
                continue  # 未改动
            stored = encrypt_bytes(value.encode()).decode() if (item["secret"] and value) else value
            row = s.get(SystemSetting, key)
            if row:
                row.value = stored
                row.updated_by = user_id
                row.updated_at = datetime.utcnow()
            else:
                s.add(SystemSetting(key=key, value=stored, updated_by=user_id, updated_at=datetime.utcnow()))
        s.commit()
    invalidate()
