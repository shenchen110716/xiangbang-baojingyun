"""微信支付配置纯逻辑单测：PaymentIn.channel 默认值/校验、SETTINGS_REGISTRY
新增分组的 key/secret 声明是否正确——需要隔离临时 SQLite 数据库。"""
import os
import sys
import tempfile
from pathlib import Path

# 在导入任何 backend 模块前，设置隔离的临时数据库
_tmp = tempfile.mkdtemp()
os.environ["DATABASE_URL"] = f"sqlite:///{_tmp}/wechat_config_test.db"
os.environ["ID_ENCRYPTION_KEY"] = "x" * 44

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from pydantic import ValidationError

from backend.core.db import Base, engine
from backend.models import SystemSetting  # noqa: F401
from backend.schemas import PaymentIn, WeChatBindOpenidIn
from backend.services import settings as S


def test_payment_in_channel_defaults_to_native_and_rejects_bad_values():
    data = PaymentIn(enterprise_id=1, account="usage", amount=10.0)
    assert data.channel == "native"
    assert PaymentIn(enterprise_id=1, account="usage", amount=10.0, channel="jsapi").channel == "jsapi"
    try:
        PaymentIn(enterprise_id=1, account="usage", amount=10.0, channel="bogus")
        raise AssertionError("invalid channel should be rejected")
    except ValidationError:
        pass


def test_wechat_bind_openid_in_requires_code():
    assert WeChatBindOpenidIn(code="abc").code == "abc"
    try:
        WeChatBindOpenidIn()
        raise AssertionError("missing code should be rejected")
    except ValidationError:
        pass


def test_settings_registry_declares_wechat_pay_group_with_correct_secrecy():
    by_key = {item["key"]: item for item in S.SETTINGS_REGISTRY}
    non_secret = ["WECHAT_PAY_MCH_ID", "WECHAT_PAY_APP_ID", "WECHAT_PAY_NOTIFY_URL", "WECHAT_PAY_CERT_SERIAL_NO"]
    secret = ["WECHAT_PAY_API_V3_KEY", "WECHAT_PAY_PRIVATE_KEY", "WECHAT_PAY_PLATFORM_CERT", "WECHAT_MINIPROGRAM_APP_SECRET"]
    for key in non_secret:
        assert by_key[key]["group"] == "微信支付" and by_key[key]["secret"] is False, key
    for key in secret:
        assert by_key[key]["group"] == "微信支付" and by_key[key]["secret"] is True, key
    assert by_key["USAGE_FEE_DEFAULT_METHOD"]["kind"] == "select"
    assert by_key["USAGE_FEE_DEFAULT_METHOD"]["options"] == ["wechat", "bank"]
    assert S.get("USAGE_FEE_DEFAULT_METHOD", "wechat") == "wechat"


if __name__ == "__main__":
    # 初始化隔离数据库表
    Base.metadata.create_all(bind=engine)

    for name, fn in sorted(globals().items()):
        if name.startswith("test_"):
            fn()
            print(f"  {name} ok")
    print("wechat pay config tests passed")
