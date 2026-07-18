"""services/settings 冒烟：加密存库、掩码、env 回落、读取优先级。

自带隔离临时 SQLite，建表后验证：
- 非敏感项明文读写；
- 敏感项加密入库（DB 里不是明文）、读取解密、admin_view 只给掩码；
- 未设置的项回落到环境变量；
- 写入掩码表示“不改动”。
"""
import os
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

_tmp = tempfile.mkdtemp()
os.environ["DATABASE_URL"] = f"sqlite:///{_tmp}/settings.db"
os.environ["ID_ENCRYPTION_KEY"] = "test-enc-key-please-change"

from backend.core.db import Base, engine
from backend.models import SystemSetting  # noqa: F401
from backend.services import settings as S


def run():
    Base.metadata.create_all(bind=engine)

    # 非敏感项明文读写
    S.set_many({"S3_BUCKET": "my-bucket", "STORAGE_BACKEND": "s3"}, user_id=1)
    assert S.get("S3_BUCKET") == "my-bucket"
    assert S.get("STORAGE_BACKEND") == "s3"

    # 敏感项：入库密文 ≠ 明文，读取解密
    S.set_many({"S3_SECRET_ACCESS_KEY": "super-secret-123"}, user_id=1)
    from backend.core.db import SessionLocal
    with SessionLocal() as s:
        raw = s.get(SystemSetting, "S3_SECRET_ACCESS_KEY").value
    assert raw != "super-secret-123", "密钥必须加密入库，不能明文"
    assert S.get("S3_SECRET_ACCESS_KEY") == "super-secret-123", "读取应解密"
    print("secret encrypted-at-rest + decrypt OK")

    # admin_view：密钥只给掩码 + configured，绝不回明文
    view = S.admin_view()
    flat = {it["key"]: it for g in view for it in g["items"]}
    assert flat["S3_SECRET_ACCESS_KEY"]["configured"] is True
    assert flat["S3_SECRET_ACCESS_KEY"]["value"] == S.MASK
    assert "super-secret-123" not in str(view)
    assert flat["S3_BUCKET"]["value"] == "my-bucket"  # 非敏感项明文回显
    print("admin_view masks secrets OK")

    # 写入掩码 = 不改动（保持原密钥）
    S.set_many({"S3_SECRET_ACCESS_KEY": S.MASK}, user_id=2)
    assert S.get("S3_SECRET_ACCESS_KEY") == "super-secret-123"
    print("mask-on-write keeps existing secret OK")

    # env 回落：未在 DB 设置的 key 读环境变量
    os.environ["SMS_SIGN_NAME"] = "响帮帮"
    S.invalidate()
    assert S.get("SMS_SIGN_NAME") == "响帮帮"
    # DB 覆盖 env
    S.set_many({"SMS_SIGN_NAME": "保经云"}, user_id=1)
    assert S.get("SMS_SIGN_NAME") == "保经云"
    print("env fallback + DB override OK")

    # get_bool
    S.set_many({"OCR_ENABLED": "1"}, user_id=1)
    assert S.get_bool("OCR_ENABLED") is True
    print("get_bool OK")

    print("settings_smoke: ALL GREEN")


if __name__ == "__main__":
    run()
