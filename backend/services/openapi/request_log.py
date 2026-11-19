"""openapi_request_logs 落盘（AC8：每次调用含失败都要有一行）。

独立 SessionLocal 而不是请求的 db() 会话：认证/限流失败路径上请求会话
可能已 rollback 或带着未提交状态，审计行不能跟着陪葬；日志写失败也绝不
反过来打断响应（但要在服务端日志留痕）。
"""
import ipaddress
import logging
import os
from typing import Optional

from fastapi import Request

from ...core.db import SessionLocal
from ...models import OpenApiRequestLog

logger = logging.getLogger("backend.openapi")


def _trusted_proxy_networks() -> list:
    """可信反代网段（OPENAPI_TRUSTED_PROXIES，逗号分隔 IP/CIDR）。

    默认 127.0.0.1：生产形态是 nginx 与应用同机回环（bx.xbbzp.com）。
    """
    networks = []
    for part in os.getenv("OPENAPI_TRUSTED_PROXIES", "127.0.0.1").split(","):
        part = part.strip()
        if not part:
            continue
        try:
            networks.append(ipaddress.ip_network(part, strict=False))
        except ValueError:
            continue
    return networks


def client_ip(request: Request) -> str:
    """可信代理模型（review-p0 B1）。

    X-Forwarded-For 是调用方可以随意预置的头；nginx 的常见配置
    `$proxy_add_x_forwarded_for` 是**追加**语义——伪造链形如
    `<攻击者编造>, <真实IP>`，取第一跳等于直接信任攻击者。因此：
    - 直连来源（socket peer）不在可信代理名单 → 一律用直连 IP，XFF 忽略；
    - 直连来源是可信代理 → 取 XFF **最后一跳**（唯一一跳是由可信 nginx
      追加的、不可被调用方预置的值）。
    单级可信代理模型；如未来加 CDN 等多级代理，需按层数剥链（见交接文档）。
    """
    direct = request.client.host if request.client else ""
    forwarded = request.headers.get("X-Forwarded-For", "").strip()
    if not forwarded or not direct:
        return direct
    try:
        direct_addr = ipaddress.ip_address(direct)
    except ValueError:
        return direct
    if not any(direct_addr in net for net in _trusted_proxy_networks()):
        return direct
    return forwarded.split(",")[-1].strip()


def write_request_log(request: Request, request_id: str, status: int,
                      latency_ms: Optional[int]) -> None:
    principal = getattr(request.state, "openapi_principal", None)
    key_id = principal.key_id if principal else (
        request.headers.get("X-Api-Key-Id") or None)
    enterprise_id = principal.enterprise_id if principal else None
    try:
        with SessionLocal() as session:
            session.add(OpenApiRequestLog(
                request_id=request_id, key_id=key_id,
                enterprise_id=enterprise_id, method=request.method,
                path=request.url.path, status=status,
                latency_ms=latency_ms, ip=client_ip(request)))
            session.commit()
    except Exception:
        # 审计写入失败不打断业务响应，但服务端必须留痕（review-p0 M4）。
        logger.exception("openapi request log write failed request_id=%s path=%s",
                         request_id, request.url.path)
