"""外部服务适配层。

默认使用 MockProvider。生产接入时按保司/供应商的签名规则实现对应 adapter，
并通过 .env 注入密钥；业务层不直接依赖第三方 SDK。
"""
from __future__ import annotations

import base64
import hashlib
import hmac
import json
import os
import time
import urllib.request
import uuid
from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Any, Optional

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

@dataclass
class ProviderResult:
    ok: bool
    provider: str
    request_id: str
    data: dict[str, Any]
    message: str = ""

class MockProvider:
    def __init__(self, name: str): self.name = name
    def submit_enrollment(self, payload: dict) -> ProviderResult:
        return ProviderResult(True, self.name, f"MOCK-{int(datetime.now(timezone.utc).timestamp())}", {"accepted": len(payload.get("people", [])), "mode": "mock"}, "模拟提交成功")
    def submit_termination(self, payload: dict) -> ProviderResult:
        return ProviderResult(True, self.name, f"MOCK-{int(datetime.now(timezone.utc).timestamp())}", {"accepted": len(payload.get("people", [])), "mode": "mock"}, "模拟停保成功")
    def send_sms(self, phone: str, template: str, params: dict) -> ProviderResult:
        return ProviderResult(True, self.name, "MOCK-SMS", {"phone": phone, "template": template}, "模拟短信已记录")
    def send_email(self, to: str, subject: str, body: str, attachments: list[dict] | None = None) -> ProviderResult:
        return ProviderResult(True, self.name, "MOCK-EMAIL", {"to": to, "subject": subject, "attachments": [item.get("filename","") for item in (attachments or [])]}, "模拟邮件及名单附件已记录")
    def create_payment(self, amount: float, order_no: str) -> ProviderResult:
        return ProviderResult(True, self.name, order_no, {"amount": amount, "pay_url": f"/mock-pay/{order_no}"}, "模拟支付单已创建")

class HttpProvider(MockProvider):
    """通用 JSON HTTP 适配器；不同保司/供应商可按环境变量替换专用 adapter。"""
    def __init__(self, name: str, url: str): super().__init__(name); self.url=url
    def _post(self, payload: dict, request_id: str) -> ProviderResult:
        try:
            body=json.dumps(payload,ensure_ascii=False).encode(); req=urllib.request.Request(self.url,body=body,headers={'Content-Type':'application/json'},method='POST')
            with urllib.request.urlopen(req,timeout=15) as res: data=json.loads(res.read() or '{}')
            return ProviderResult(True,self.name,data.get('request_id',request_id),data,'已发送至真实接口')
        except Exception as exc: return ProviderResult(False,self.name,request_id,{},f'接口发送失败：{exc}')
    def submit_enrollment(self,payload): return self._post(payload,'ENR-'+str(int(datetime.now(timezone.utc).timestamp())))
    def submit_termination(self,payload): return self._post(payload,'TER-'+str(int(datetime.now(timezone.utc).timestamp())))
    def send_sms(self,phone,template,params): return self._post({'phone':phone,'template':template,'params':params},'SMS-'+str(int(datetime.now(timezone.utc).timestamp())))
    def send_email(self,to,subject,body,attachments=None): return self._post({'to':to,'subject':subject,'body':body,'attachments':attachments or []},'MAIL-'+str(int(datetime.now(timezone.utc).timestamp())))
    def create_payment(self,amount,order_no): return self._post({'amount':amount,'order_no':order_no},order_no)

def provider_mode() -> str: return os.getenv("INTEGRATION_MODE", "mock")
def insurer_provider(name: str) -> MockProvider: return MockProvider(name) if provider_mode() == "mock" else HttpProvider(name, os.getenv("INSURER_API_BASE_URL", ""))
def sms_provider() -> MockProvider: return MockProvider("sms") if provider_mode()=="mock" else HttpProvider("sms", os.getenv("SMS_PROVIDER_URL", ""))
def email_provider() -> MockProvider: return MockProvider("smtp") if provider_mode()=="mock" else HttpProvider("smtp", os.getenv("EMAIL_PROVIDER_URL", ""))
def payment_provider() -> MockProvider: return MockProvider("payment") if provider_mode()=="mock" else HttpProvider("payment", os.getenv("PAYMENT_PROVIDER_URL", ""))


class WeChatPayProvider(MockProvider):
    """mock 模式微信支付：不发真实请求，用确定性假数据 + 简化 HMAC 验签，
    使 /api/payments/wechat-notify 端点本身（含验签失败分支）也能被冒烟测试
    完整覆盖，而不必依赖真实微信证书。"""
    MOCK_NOTIFY_SECRET = "mock-wechat-notify-secret"

    def __init__(self, name: str = "wechat"):
        super().__init__(name)

    def create_native_order(self, amount: float, order_no: str, description: str) -> ProviderResult:
        return ProviderResult(True, self.name, order_no, {"code_url": f"weixin://wxpay/bizpayurl?mock={order_no}"}, "模拟微信 Native 下单成功")

    def create_jsapi_order(self, amount: float, order_no: str, openid: str, description: str) -> ProviderResult:
        prepay_id = f"mock-prepay-{order_no}"
        return ProviderResult(True, self.name, order_no, {
            "prepay_id": prepay_id,
            "timeStamp": str(int(datetime.now(timezone.utc).timestamp())),
            "nonceStr": order_no,
            "package": f"prepay_id={prepay_id}",
            "signType": "RSA",
            "paySign": "mock-pay-sign",
        }, "模拟微信 JSAPI 下单成功")

    def code_to_openid(self, code: str) -> Optional[str]:
        return f"mock-openid-{code}" if code else None

    def verify_notify(self, headers: dict, raw_body: bytes) -> Optional[dict]:
        signature = headers.get("X-Mock-Signature") or headers.get("x-mock-signature")
        if not signature:
            return None
        expected = hmac.new(self.MOCK_NOTIFY_SECRET.encode(), raw_body, hashlib.sha256).hexdigest()
        if not hmac.compare_digest(signature, expected):
            return None
        try:
            return json.loads(raw_body.decode())
        except Exception:
            return None


class RealWeChatPayProvider(WeChatPayProvider):
    """微信支付 v3 API：商户私钥 RSA-SHA256 签名请求（PKCS1v15），APIv3 密钥
    AES-256-GCM 解密回调 resource，微信支付平台证书 RSA-SHA256 验签回调头。
    密钥全部来自 services.settings（系统设置，Fernet 加密入库）。"""
    API_BASE = "https://api.mch.weixin.qq.com"

    def __init__(self, name: str = "wechat"):
        super().__init__(name)

    def _settings(self):
        from .services import settings as settings_service
        return settings_service

    def _sign(self, method: str, url_path: str, body: str) -> dict:
        S = self._settings()
        mch_id = S.get("WECHAT_PAY_MCH_ID")
        serial_no = S.get("WECHAT_PAY_CERT_SERIAL_NO")
        private_key_pem = S.get("WECHAT_PAY_PRIVATE_KEY")
        timestamp = str(int(time.time()))
        nonce = uuid.uuid4().hex
        message = f"{method}\n{url_path}\n{timestamp}\n{nonce}\n{body}\n".encode()
        private_key = serialization.load_pem_private_key(private_key_pem.encode(), password=None)
        signature = base64.b64encode(private_key.sign(message, padding.PKCS1v15(), hashes.SHA256())).decode()
        authorization = (
            f'WECHATPAY2-SHA256-RSA2048 mchid="{mch_id}",nonce_str="{nonce}",'
            f'timestamp="{timestamp}",serial_no="{serial_no}",signature="{signature}"'
        )
        return {"Authorization": authorization, "Content-Type": "application/json", "Accept": "application/json"}

    def _post(self, url_path: str, payload: dict) -> ProviderResult:
        body = json.dumps(payload, ensure_ascii=False)
        try:
            headers = self._sign("POST", url_path, body)
            req = urllib.request.Request(f"{self.API_BASE}{url_path}", data=body.encode(), headers=headers, method="POST")
            with urllib.request.urlopen(req, timeout=15) as res:
                data = json.loads(res.read() or "{}")
            return ProviderResult(True, self.name, payload.get("out_trade_no", ""), data, "微信支付下单成功")
        except Exception as exc:
            return ProviderResult(False, self.name, payload.get("out_trade_no", ""), {}, f"微信支付下单失败：{exc}")

    def create_native_order(self, amount: float, order_no: str, description: str) -> ProviderResult:
        S = self._settings()
        payload = {
            "appid": S.get("WECHAT_PAY_APP_ID"), "mchid": S.get("WECHAT_PAY_MCH_ID"),
            "description": description, "out_trade_no": order_no,
            "notify_url": S.get("WECHAT_PAY_NOTIFY_URL"),
            "amount": {"total": round(amount * 100), "currency": "CNY"},
        }
        return self._post("/v3/pay/transactions/native", payload)

    def _jsapi_client_params(self, prepay_id: str) -> dict:
        S = self._settings()
        app_id = S.get("WECHAT_PAY_APP_ID")
        timestamp = str(int(time.time()))
        nonce = uuid.uuid4().hex
        package = f"prepay_id={prepay_id}"
        message = f"{app_id}\n{timestamp}\n{nonce}\n{package}\n".encode()
        private_key = serialization.load_pem_private_key(S.get("WECHAT_PAY_PRIVATE_KEY").encode(), password=None)
        pay_sign = base64.b64encode(private_key.sign(message, padding.PKCS1v15(), hashes.SHA256())).decode()
        return {"prepay_id": prepay_id, "timeStamp": timestamp, "nonceStr": nonce, "package": package, "signType": "RSA", "paySign": pay_sign}

    def create_jsapi_order(self, amount: float, order_no: str, openid: str, description: str) -> ProviderResult:
        S = self._settings()
        payload = {
            "appid": S.get("WECHAT_PAY_APP_ID"), "mchid": S.get("WECHAT_PAY_MCH_ID"),
            "description": description, "out_trade_no": order_no,
            "notify_url": S.get("WECHAT_PAY_NOTIFY_URL"),
            "amount": {"total": round(amount * 100), "currency": "CNY"},
            "payer": {"openid": openid},
        }
        order_result = self._post("/v3/pay/transactions/jsapi", payload)
        if not order_result.ok:
            return order_result
        prepay_id = order_result.data.get("prepay_id", "")
        return ProviderResult(True, self.name, order_no, self._jsapi_client_params(prepay_id), "微信支付下单成功")

    def code_to_openid(self, code: str) -> Optional[str]:
        S = self._settings()
        app_id = S.get("WECHAT_PAY_APP_ID")
        app_secret = S.get("WECHAT_MINIPROGRAM_APP_SECRET")
        url = f"https://api.weixin.qq.com/sns/jscode2session?appid={app_id}&secret={app_secret}&js_code={code}&grant_type=authorization_code"
        try:
            with urllib.request.urlopen(url, timeout=10) as res:
                data = json.loads(res.read() or "{}")
            return data.get("openid")
        except Exception:
            return None

    def verify_notify(self, headers: dict, raw_body: bytes) -> Optional[dict]:
        S = self._settings()
        try:
            timestamp = headers.get("Wechatpay-Timestamp") or headers.get("wechatpay-timestamp")
            nonce = headers.get("Wechatpay-Nonce") or headers.get("wechatpay-nonce")
            signature = headers.get("Wechatpay-Signature") or headers.get("wechatpay-signature")
            if not (timestamp and nonce and signature):
                return None
            message = f"{timestamp}\n{nonce}\n{raw_body.decode()}\n".encode()
            cert = x509.load_pem_x509_certificate(S.get("WECHAT_PAY_PLATFORM_CERT").encode())
            cert.public_key().verify(base64.b64decode(signature), message, padding.PKCS1v15(), hashes.SHA256())
        except Exception:
            return None
        try:
            envelope = json.loads(raw_body.decode())
            resource = envelope["resource"]
            api_v3_key = S.get("WECHAT_PAY_API_V3_KEY").encode()
            resource_nonce = resource["nonce"].encode()
            associated_data = resource.get("associated_data", "").encode()
            ciphertext = base64.b64decode(resource["ciphertext"])
            decrypted = json.loads(AESGCM(api_v3_key).decrypt(resource_nonce, ciphertext, associated_data).decode())
        except Exception:
            return None
        trade_state = decrypted.get("trade_state", "")
        return {
            "out_trade_no": decrypted.get("out_trade_no", ""),
            "status": "paid" if trade_state == "SUCCESS" else ("failed" if trade_state else "pending"),
            "transaction_id": decrypted.get("transaction_id", ""),
        }


def wechat_pay_provider() -> WeChatPayProvider:
    return WeChatPayProvider("wechat") if provider_mode() == "mock" else RealWeChatPayProvider("wechat")
