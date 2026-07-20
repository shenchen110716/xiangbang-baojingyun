"""微信支付配置纯逻辑单测：PaymentIn.channel 默认值/校验、SETTINGS_REGISTRY
新增分组的 key/secret 声明是否正确——不涉及数据库。"""
import os
import sys

os.environ.setdefault("ID_ENCRYPTION_KEY", "x" * 44)
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from pydantic import ValidationError

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
    for name, fn in sorted(globals().items()):
        if name.startswith("test_"):
            fn()
            print(f"  {name} ok")
    print("wechat pay config tests passed")
