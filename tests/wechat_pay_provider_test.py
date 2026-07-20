"""微信支付 provider 纯逻辑单测：mock 验签闭环、真实模式的 RSA 请求签名、
JSAPI 客户端参数签名与 AES-256-GCM 回调解密——全部用现场生成的测试密钥对，
不依赖数据库、不发真实网络请求。"""
import base64
import datetime
import hashlib
import hmac
import json
import os
import secrets
import sys
import tempfile
import time
import uuid

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

# RealWeChatPayProvider reads settings via backend.services.settings.get(),
# which checks the DB before falling back to os.environ. Point it at an
# isolated throwaway SQLite DB (with just the system_settings table) so this
# file stays self-contained and never touches the project's real data.db.
_test_db_dir = tempfile.mkdtemp(prefix="xbb-wechat-pay-provider-test-")
os.environ.setdefault("DATABASE_URL", f"sqlite:///{os.path.join(_test_db_dir, 'test.db')}")

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding, rsa
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.x509.oid import NameOID

from backend.core.db import engine
from backend.models import SystemSetting
from backend.providers import RealWeChatPayProvider, WeChatPayProvider

SystemSetting.__table__.create(bind=engine, checkfirst=True)


def _generate_rsa_keypair():
    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    pem = key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()).decode()
    return key, pem


def _generate_self_signed_cert(key):
    subject = issuer = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "mock-wechatpay-platform")])
    return (
        x509.CertificateBuilder()
        .subject_name(subject).issuer_name(issuer).public_key(key.public_key())
        .serial_number(x509.random_serial_number())
        .not_valid_before(datetime.datetime.utcnow())
        .not_valid_after(datetime.datetime.utcnow() + datetime.timedelta(days=1))
        .sign(key, hashes.SHA256())
    )


def test_mock_native_and_jsapi_order_return_ok():
    provider = WeChatPayProvider()
    native = provider.create_native_order(88.0, "PAY-TEST-1", "测试")
    assert native.ok and native.data["code_url"]
    jsapi = provider.create_jsapi_order(88.0, "PAY-TEST-2", "mock-openid-abc", "测试")
    assert jsapi.ok and jsapi.data["prepay_id"] and jsapi.data["package"].startswith("prepay_id=")
    for field in ("timeStamp", "nonceStr", "signType", "paySign"):
        assert jsapi.data[field]


def test_mock_code_to_openid_is_deterministic():
    provider = WeChatPayProvider()
    assert provider.code_to_openid("abc") == provider.code_to_openid("abc")
    assert provider.code_to_openid("abc") != provider.code_to_openid("def")
    assert provider.code_to_openid("") is None


def test_mock_verify_notify_accepts_correctly_signed_payload_and_rejects_others():
    provider = WeChatPayProvider()
    body = json.dumps({"out_trade_no": "PAY-TEST-3", "status": "paid", "transaction_id": "mock-txn-1"}).encode()
    signature = hmac.new(WeChatPayProvider.MOCK_NOTIFY_SECRET.encode(), body, hashlib.sha256).hexdigest()
    payload = provider.verify_notify({"X-Mock-Signature": signature}, body)
    assert payload == {"out_trade_no": "PAY-TEST-3", "status": "paid", "transaction_id": "mock-txn-1"}
    assert provider.verify_notify({}, body) is None
    assert provider.verify_notify({"X-Mock-Signature": "deadbeef"}, body) is None
    tampered = body.replace(b"paid", b"paix")
    assert provider.verify_notify({"X-Mock-Signature": signature}, tampered) is None


def test_real_provider_sign_produces_verifiable_authorization_header():
    key, pem = _generate_rsa_keypair()
    os.environ.update({
        "WECHAT_PAY_MCH_ID": "1900000001",
        "WECHAT_PAY_CERT_SERIAL_NO": "TESTSERIAL01",
        "WECHAT_PAY_PRIVATE_KEY": pem,
    })
    provider = RealWeChatPayProvider()
    body = '{"out_trade_no":"PAY-TEST-4"}'
    headers = provider._sign("POST", "/v3/pay/transactions/native", body)
    auth = headers["Authorization"]
    assert auth.startswith("WECHATPAY2-SHA256-RSA2048 ")
    fields = dict(item.split("=", 1) for item in auth[len("WECHATPAY2-SHA256-RSA2048 "):].split(","))
    for name in ("mchid", "nonce_str", "timestamp", "serial_no", "signature"):
        assert name in fields, f"missing {name}"
    signature = base64.b64decode(fields["signature"].strip('"'))
    message = f'POST\n/v3/pay/transactions/native\n{fields["timestamp"].strip(chr(34))}\n{fields["nonce_str"].strip(chr(34))}\n{body}\n'.encode()
    key.public_key().verify(signature, message, padding.PKCS1v15(), hashes.SHA256())  # 不抛异常即验签通过


def test_real_provider_jsapi_client_params_are_verifiably_signed():
    key, pem = _generate_rsa_keypair()
    os.environ.update({"WECHAT_PAY_APP_ID": "wx1234567890", "WECHAT_PAY_PRIVATE_KEY": pem})
    provider = RealWeChatPayProvider()
    params = provider._jsapi_client_params("mock-prepay-xyz")
    assert params["package"] == "prepay_id=mock-prepay-xyz"
    message = f'wx1234567890\n{params["timeStamp"]}\n{params["nonceStr"]}\n{params["package"]}\n'.encode()
    signature = base64.b64decode(params["paySign"])
    key.public_key().verify(signature, message, padding.PKCS1v15(), hashes.SHA256())


def test_real_provider_verify_notify_decrypts_and_normalises_trade_state():
    platform_key, _ = _generate_rsa_keypair()
    cert = _generate_self_signed_cert(platform_key)
    cert_pem = cert.public_bytes(serialization.Encoding.PEM).decode()
    api_v3_key_str = secrets.token_hex(16)

    resource_plaintext = json.dumps({"out_trade_no": "PAY-TEST-5", "transaction_id": "wx-txn-5", "trade_state": "SUCCESS"}).encode()
    nonce_str = uuid.uuid4().hex[:12]
    associated_data_str = "transaction"
    ciphertext = AESGCM(api_v3_key_str.encode()).encrypt(nonce_str.encode(), resource_plaintext, associated_data_str.encode())
    envelope = json.dumps({"resource": {
        "algorithm": "AEAD_AES_256_GCM",
        "ciphertext": base64.b64encode(ciphertext).decode(),
        "associated_data": associated_data_str,
        "nonce": nonce_str,
    }}).encode()

    sig_nonce = uuid.uuid4().hex
    sig_timestamp = str(int(time.time()))
    message = f"{sig_timestamp}\n{sig_nonce}\n{envelope.decode()}\n".encode()
    signature = platform_key.sign(message, padding.PKCS1v15(), hashes.SHA256())

    os.environ["WECHAT_PAY_PLATFORM_CERT"] = cert_pem
    os.environ["WECHAT_PAY_API_V3_KEY"] = api_v3_key_str
    headers = {
        "Wechatpay-Timestamp": sig_timestamp,
        "Wechatpay-Nonce": sig_nonce,
        "Wechatpay-Signature": base64.b64encode(signature).decode(),
    }
    provider = RealWeChatPayProvider()
    payload = provider.verify_notify(headers, envelope)
    assert payload == {"out_trade_no": "PAY-TEST-5", "status": "paid", "transaction_id": "wx-txn-5"}

    bad_headers = {**headers, "Wechatpay-Signature": base64.b64encode(b"not-a-signature").decode()}
    assert provider.verify_notify(bad_headers, envelope) is None


if __name__ == "__main__":
    for name, fn in sorted(globals().items()):
        if name.startswith("test_"):
            fn()
            print(f"  {name} ok")
    print("wechat pay provider tests passed")
