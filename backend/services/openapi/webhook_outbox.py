"""Webhook 事件 outbox（story-p2-write AC13–AC15）。

- emit_event 只 session.add、不 commit：事件行与业务写同一事务落库——业务
  回滚事件就不存在，业务提交事件必达（至少会被投递循环看见）。
- 投递走 P0 的后台循环（register_periodic_job）：urllib POST、5s 超时、
  2xx 即 delivered；失败按 1m/5m/30m/2h/12h 退避，共 1+5 次后置 failed，
  admin 可手动重发（resend_delivery）。
- 投递签名（AC14）：v2 同款 HMAC——用 endpoint 登记时那把 key 的
  AppSecret（Fernet 解密还原），对方用自己手里的 secret 验签；另带
  X-Event-Id 供消费端幂等去重。payload 只含 ID 与关键字段，绝不带明文
  证件号（对外明文只走 insured:read_sensitive 的拉取面）。
- URL 要求 https 且必须指向公网地址（AC12 + review-p2 M6）：https 下私网/
  内网/链路本地等非公网 IP 字面量与 localhost 一律拒绝（纯代码判断 IP 段，
  不做 DNS 解析——域名解析到内网属于部署侧网络策略问题）；http 仅限
  loopback 且仅当 OPENAPI_WEBHOOK_ALLOW_INSECURE=1（默认关，测试/本地
  联调开启——否则 smoke 根本无法起 https 接收端）。
- balance.low（AC15）：扫描设置了 usage_alert_threshold 的企业，可用服务
  费（usage_account_view 口径）跌破阈值投递一次；低位周期内不重发，回升
  后再跌破再发。周期状态持久化在 audit_logs（action=openapi_balance_low，
  detail=low/recovered，最新一行即当前状态）——不为一个布尔位加表。
"""
import ipaddress
import json
import logging
import os
import urllib.error
import urllib.request
from datetime import datetime, timedelta, timezone
from typing import Optional
from urllib.parse import urlsplit
from uuid import uuid4

from sqlalchemy import select
from sqlalchemy.orm import Session

from ...core.api_keys import sign_request_v2
from ...core.id_number import decrypt_bytes
from ...models import (
    AuditLog, Enterprise, OpenApiClient, OpenApiWebhookDelivery,
    OpenApiWebhookEndpoint,
)
from ..accruals import usage_account_view
from .background import register_periodic_job

logger = logging.getLogger("backend.openapi")

# 退避序列（秒）：1m / 5m / 30m / 2h / 12h；用完置 failed（AC13）。
BACKOFF_SECONDS = [60, 300, 1800, 7200, 43200]
MAX_DELIVERIES_PER_TICK = 20
DELIVER_TIMEOUT_SECONDS = 5
_LOOPBACK_HOSTS = {"127.0.0.1", "localhost", "::1"}
BALANCE_ALERT_ACTION = "openapi_balance_low"


def allow_insecure_webhook_urls() -> bool:
    """review-p2 M6：http loopback 豁免挂环境开关，默认关闭（生产姿态）。"""
    return os.getenv("OPENAPI_WEBHOOK_ALLOW_INSECURE", "") == "1"


def _is_internal_host(hostname: str) -> bool:
    """IP 字面量非公网段（10/172.16-31/192.168/127/169.254/::1/fc00::…）或
    loopback 主机名 → 内网。域名不做 DNS 解析，按公网放行。"""
    if hostname.lower() in _LOOPBACK_HOSTS:
        return True
    try:
        addr = ipaddress.ip_address(hostname)
    except ValueError:
        return False   # 域名：不解析，视为公网
    return not addr.is_global


def is_acceptable_webhook_url(url: str) -> bool:
    """https 强制 + 公网限定（review-p2 M6）。

    - https：主机不得是 loopback 名或非公网 IP 字面量，防 SSRF 打内网。
    - http：仅 loopback，且仅当 OPENAPI_WEBHOOK_ALLOW_INSECURE=1（本地
      联调/smoke），默认拒绝。
    """
    try:
        parts = urlsplit(url)
    except ValueError:
        return False
    hostname = parts.hostname
    if not hostname:
        return False
    if parts.scheme == "https":
        return not _is_internal_host(hostname)
    if parts.scheme == "http":
        return allow_insecure_webhook_urls() and hostname.lower() in _LOOPBACK_HOSTS
    return False


def emit_event(db: Session, enterprise_id: int, event_type: str,
               payload: dict) -> Optional[OpenApiWebhookDelivery]:
    """按企业把事件扇出到所有 active endpoint；同一事件共享 event_id。

    只 add 不 commit（outbox 红线）；无 endpoint 时返回 None——事件静默
    丢弃是注册缺失的正常语义，不是错误。
    """
    endpoints = db.scalars(
        select(OpenApiWebhookEndpoint).where(
            OpenApiWebhookEndpoint.enterprise_id == enterprise_id,
            OpenApiWebhookEndpoint.status == "active")).all()
    event_id = uuid4().hex
    first = None
    for endpoint in endpoints:
        delivery = OpenApiWebhookDelivery(
            enterprise_id=enterprise_id, endpoint_id=endpoint.id,
            event_id=event_id, event_type=event_type,
            payload=json.dumps(payload, ensure_ascii=False))
        db.add(delivery)
        if first is None:
            first = delivery
    return first


def emit_test_event(db: Session, endpoint: OpenApiWebhookEndpoint) -> OpenApiWebhookDelivery:
    """webhook.test 只投给这一个 endpoint（AC12 的连通性自检）。"""
    delivery = OpenApiWebhookDelivery(
        enterprise_id=endpoint.enterprise_id, endpoint_id=endpoint.id,
        event_id=uuid4().hex, event_type="webhook.test",
        payload=json.dumps({"endpoint_id": endpoint.id}, ensure_ascii=False))
    db.add(delivery)
    return delivery


def resend_delivery(delivery: OpenApiWebhookDelivery) -> None:
    """admin 手动重发（AC13）：重置为 pending、清退避，下个 tick 立即投。"""
    delivery.status = "pending"
    delivery.attempts = 0
    delivery.next_retry_at = None
    delivery.last_error = ""


def _delivery_secret(session: Session, endpoint: OpenApiWebhookEndpoint) -> Optional[str]:
    client = session.scalar(select(OpenApiClient).where(
        OpenApiClient.key_id == endpoint.key_id))
    if client is None:
        return None
    try:
        return decrypt_bytes(client.secret_encrypted.encode()).decode()
    except Exception:
        logger.exception("webhook secret decrypt failed key=%s", endpoint.key_id)
        return None


def _post_signed(url: str, key_id: str, secret: str, event_id: str,
                 body: bytes) -> int:
    parts = urlsplit(url)
    timestamp = str(int(datetime.now(timezone.utc).timestamp()))
    nonce = uuid4().hex
    signature = sign_request_v2(secret, "POST", parts.path or "/",
                                parts.query, timestamp, nonce, body)
    request = urllib.request.Request(url, data=body, method="POST", headers={
        "Content-Type": "application/json",
        "X-Api-Key-Id": key_id,
        "X-Timestamp": timestamp,
        "X-Nonce": nonce,
        "X-Signature": signature,
        "X-Sign-Version": "2",
        "X-Event-Id": event_id,
    })
    with urllib.request.urlopen(request, timeout=DELIVER_TIMEOUT_SECONDS) as resp:
        return resp.status


def _fail(delivery: OpenApiWebhookDelivery, error: str, now: datetime) -> None:
    delivery.attempts += 1
    delivery.last_error = error[:500]
    if delivery.attempts > len(BACKOFF_SECONDS):
        delivery.status = "failed"
        delivery.next_retry_at = None
    else:
        delivery.next_retry_at = now + timedelta(
            seconds=BACKOFF_SECONDS[delivery.attempts - 1])


def deliver_pending_webhooks(session: Session) -> None:
    """投递循环（后台 job）：取到期的 pending 投递逐条 POST。

    每条独立 commit——一条投出去就立刻固化 delivered，进程崩溃最多造成
    「已投未记」的重复投递（at-least-once，消费端按 X-Event-Id 去重），
    绝不会丢投。
    """
    now = datetime.now(timezone.utc)
    deliveries = session.scalars(
        select(OpenApiWebhookDelivery).where(
            OpenApiWebhookDelivery.status == "pending",
            (OpenApiWebhookDelivery.next_retry_at.is_(None))
            | (OpenApiWebhookDelivery.next_retry_at <= now))
        .order_by(OpenApiWebhookDelivery.id.asc())
        .limit(MAX_DELIVERIES_PER_TICK)).all()
    for delivery in deliveries:
        endpoint = session.get(OpenApiWebhookEndpoint, delivery.endpoint_id)
        if endpoint is None or endpoint.status != "active":
            delivery.status = "failed"
            delivery.last_error = "endpoint 已删除或停用"
            session.commit()
            continue
        secret = _delivery_secret(session, endpoint)
        if secret is None:
            delivery.status = "failed"
            delivery.last_error = "投递签名密钥不可用（key 已删除或解密失败）"
            session.commit()
            continue
        body = json.dumps({
            "event_id": delivery.event_id,
            "event_type": delivery.event_type,
            "occurred_at": (delivery.created_at.isoformat()
                            if delivery.created_at else now.isoformat()),
            "data": json.loads(delivery.payload or "{}"),
        }, ensure_ascii=False).encode()
        try:
            status = _post_signed(endpoint.url, endpoint.key_id, secret,
                                  delivery.event_id, body)
            if 200 <= status < 300:
                delivery.status = "delivered"
                delivery.delivered_at = datetime.now(timezone.utc)
                delivery.last_error = ""
            else:
                _fail(delivery, f"HTTP {status}", now)
        except urllib.error.HTTPError as exc:  # 4xx/5xx 带响应体的失败
            _fail(delivery, f"HTTP {exc.code}", now)
        except Exception as exc:
            _fail(delivery, f"{type(exc).__name__}: {exc}", now)
        session.commit()


def _latest_alert_state(session: Session, enterprise_id: int) -> str:
    row = session.scalar(
        select(AuditLog.detail).where(
            AuditLog.action == BALANCE_ALERT_ACTION,
            AuditLog.object_type == "enterprise",
            AuditLog.object_id == str(enterprise_id))
        .order_by(AuditLog.id.desc()).limit(1))
    return row or "recovered"


def scan_balance_alerts(session: Session) -> None:
    """AC15：阈值扫描。跌破投递一次 balance.low；低位周期不重发；回升记
    recovered（重新武装），再跌破再发。commit 由 run_tick_once 做。"""
    enterprises = session.scalars(
        select(Enterprise).where(Enterprise.usage_alert_threshold.isnot(None))).all()
    for enterprise in enterprises:
        threshold = enterprise.usage_alert_threshold
        available = usage_account_view(session, enterprise)["available"]
        state = _latest_alert_state(session, enterprise.id)
        if available < threshold and state != "low":
            first = emit_event(session, enterprise.id, "balance.low", {
                "enterprise_id": enterprise.id,
                "available": available,
                "threshold": threshold,
            })
            if first is None:
                # review-p2 minor：没有 active endpoint 时不落 low 状态——
                # 否则「先跌破后注册」的企业会错过本轮低位周期的唯一一次预警。
                continue
            session.add(AuditLog(user_id=None, action=BALANCE_ALERT_ACTION,
                                 object_type="enterprise",
                                 object_id=str(enterprise.id), detail="low"))
        elif available >= threshold and state == "low":
            session.add(AuditLog(user_id=None, action=BALANCE_ALERT_ACTION,
                                 object_type="enterprise",
                                 object_id=str(enterprise.id),
                                 detail="recovered"))


register_periodic_job("openapi_webhook_deliver", deliver_pending_webhooks)
register_periodic_job("openapi_balance_alerts", scan_balance_alerts)
