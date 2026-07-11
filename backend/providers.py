"""外部服务适配层。

默认使用 MockProvider。生产接入时按保司/供应商的签名规则实现对应 adapter，
并通过 .env 注入密钥；业务层不直接依赖第三方 SDK。
"""
from __future__ import annotations

import os
import json
import urllib.request
from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Any

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
    def send_email(self, to: str, subject: str, body: str) -> ProviderResult:
        return ProviderResult(True, self.name, "MOCK-EMAIL", {"to": to, "subject": subject}, "模拟邮件已记录")
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
    def send_email(self,to,subject,body): return self._post({'to':to,'subject':subject,'body':body},'MAIL-'+str(int(datetime.now(timezone.utc).timestamp())))
    def create_payment(self,amount,order_no): return self._post({'amount':amount,'order_no':order_no},order_no)

def provider_mode() -> str: return os.getenv("INTEGRATION_MODE", "mock")
def insurer_provider(name: str) -> MockProvider: return MockProvider(name) if provider_mode() == "mock" else HttpProvider(name, os.getenv("INSURER_API_BASE_URL", ""))
def sms_provider() -> MockProvider: return MockProvider("sms") if provider_mode()=="mock" else HttpProvider("sms", os.getenv("SMS_PROVIDER_URL", ""))
def email_provider() -> MockProvider: return MockProvider("smtp") if provider_mode()=="mock" else HttpProvider("smtp", os.getenv("EMAIL_PROVIDER_URL", ""))
def payment_provider() -> MockProvider: return MockProvider("payment") if provider_mode()=="mock" else HttpProvider("payment", os.getenv("PAYMENT_PROVIDER_URL", ""))
