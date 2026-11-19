"""开放平台密钥管理（平台管理员，/api 面，AC10）。

走既有 /api + require_role("admin") 体系，和 /openapi/v1 的签名面完全分开。
secret 明文只在签发响应里出现一次，之后任何端点都不再返回（列表/详情
只有 key_id 等元数据）；签发与启停写入既有 audit_logs。
"""
import secrets
from datetime import datetime, timezone
from typing import Optional

from fastapi import APIRouter, Depends, HTTPException, Query
from pydantic import BaseModel
from sqlalchemy import select
from sqlalchemy.orm import Session

from ..core.audit import audit
from ..core.db import db
from ..core.rbac import require_role
from ..models import Enterprise, OpenApiClient, OpenApiRequestLog, User
from ..services.openapi.auth import KNOWN_SCOPES, issue_client_secret, parse_csv

router = APIRouter(prefix="/api", tags=["开放平台管理"])

SANDBOX_ENTERPRISE_KIND = "沙箱"


class OpenApiClientIssueIn(BaseModel):
    enterprise_id: int
    name: str = ""
    scopes: list[str] = []
    env: str = "prod"                    # prod | sandbox
    ip_allowlist: list[str] = []
    allowed_employer_ids: list[int] = []
    expires_at: Optional[str] = None     # ISO8601；None = 不过期


class OpenApiClientUpdateIn(BaseModel):
    name: Optional[str] = None
    status: Optional[str] = None         # active | disabled
    scopes: Optional[list[str]] = None
    ip_allowlist: Optional[list[str]] = None
    expires_at: Optional[str] = None     # "" = 清除过期时间


def _validate_scopes(scopes: list[str]) -> str:
    unknown = [s for s in scopes if s not in KNOWN_SCOPES]
    if unknown:
        raise HTTPException(422, f"未知 scope：{', '.join(unknown)}")
    return ",".join(dict.fromkeys(scopes))


def _validate_ip_allowlist(entries: list[str]) -> str:
    import ipaddress
    for entry in entries:
        try:
            ipaddress.ip_network(entry.strip(), strict=False)
        except ValueError:
            raise HTTPException(422, f"无效的 IP/CIDR：{entry}") from None
    return ",".join(entry.strip() for entry in entries if entry.strip())


def _parse_expires(raw: Optional[str]) -> Optional[datetime]:
    if not raw:
        return None
    try:
        value = datetime.fromisoformat(raw)
    except ValueError:
        raise HTTPException(422, "无效的过期时间格式（需 ISO8601）") from None
    if value.tzinfo is None:
        value = value.replace(tzinfo=timezone.utc)
    return value


def _client_dict(client: OpenApiClient) -> dict:
    """对外序列化；永不包含任何 secret 字段。"""
    return {
        "id": client.id,
        "enterprise_id": client.enterprise_id,
        "name": client.name,
        "key_id": client.key_id,
        "scopes": parse_csv(client.scopes),
        "allowed_employer_ids": [int(x) for x in parse_csv(client.allowed_employer_ids)],
        "ip_allowlist": parse_csv(client.ip_allowlist),
        "status": client.status,
        "env": client.env,
        "expires_at": client.expires_at.isoformat() if client.expires_at else None,
        "rotated_at": client.rotated_at.isoformat() if client.rotated_at else None,
        "created_at": client.created_at.isoformat() if client.created_at else None,
    }


@router.post("/openapi-clients")
def issue_openapi_client(payload: OpenApiClientIssueIn,
                         user: User = Depends(require_role("admin")),
                         session: Session = Depends(db)):
    enterprise = session.get(Enterprise, payload.enterprise_id)
    if not enterprise:
        raise HTTPException(404, "企业不存在")
    if payload.env not in ("prod", "sandbox"):
        raise HTTPException(422, "env 只能是 prod 或 sandbox")
    # AC15：sandbox key 必须绑定沙箱标记企业，真实企业数据不进沙箱通道。
    if payload.env == "sandbox" and enterprise.kind != SANDBOX_ENTERPRISE_KIND:
        raise HTTPException(422, f"沙箱密钥只能绑定「{SANDBOX_ENTERPRISE_KIND}」类型的企业")

    secret, cipher = issue_client_secret()
    client = OpenApiClient(
        enterprise_id=payload.enterprise_id,
        name=payload.name,
        key_id=f"ok{secrets.token_hex(12)}",
        secret_encrypted=cipher,
        scopes=_validate_scopes(payload.scopes),
        allowed_employer_ids=",".join(str(i) for i in payload.allowed_employer_ids),
        ip_allowlist=_validate_ip_allowlist(payload.ip_allowlist),
        status="active",
        env=payload.env,
        expires_at=_parse_expires(payload.expires_at),
    )
    session.add(client)
    session.flush()
    audit(session, user, "openapi_client_issue", "openapi_client", str(client.id),
          f"企业 {enterprise.name} 签发开放平台密钥 {client.key_id}（env={client.env}）")
    result = _client_dict(client)
    result["secret"] = secret   # 明文仅此一次
    return result


@router.get("/openapi-clients")
def list_openapi_clients(enterprise_id: int,
                         user: User = Depends(require_role("admin")),
                         session: Session = Depends(db)):
    clients = session.scalars(
        select(OpenApiClient)
        .where(OpenApiClient.enterprise_id == enterprise_id)
        .order_by(OpenApiClient.id.desc())).all()
    return [_client_dict(c) for c in clients]


@router.patch("/openapi-clients/{client_id}")
def update_openapi_client(client_id: int, payload: OpenApiClientUpdateIn,
                          user: User = Depends(require_role("admin")),
                          session: Session = Depends(db)):
    client = session.get(OpenApiClient, client_id)
    if not client:
        raise HTTPException(404, "密钥不存在")

    audit_action = "openapi_client_update"
    if payload.status is not None:
        if payload.status not in ("active", "disabled"):
            raise HTTPException(422, "status 只能是 active 或 disabled")
        if payload.status != client.status:
            audit_action = ("openapi_client_disable" if payload.status == "disabled"
                            else "openapi_client_enable")
        client.status = payload.status
    if payload.name is not None:
        client.name = payload.name
    if payload.scopes is not None:
        client.scopes = _validate_scopes(payload.scopes)
    if payload.ip_allowlist is not None:
        client.ip_allowlist = _validate_ip_allowlist(payload.ip_allowlist)
    if payload.expires_at is not None:
        client.expires_at = _parse_expires(payload.expires_at)  # "" 清除

    audit(session, user, audit_action, "openapi_client", str(client.id),
          f"更新开放平台密钥 {client.key_id}")
    return _client_dict(client)


@router.get("/openapi-request-logs")
def list_openapi_request_logs(key_id: Optional[str] = None,
                              enterprise_id: Optional[int] = None,
                              limit: int = Query(100, ge=1, le=500),
                              user: User = Depends(require_role("admin")),
                              session: Session = Depends(db)):
    query = select(OpenApiRequestLog).order_by(OpenApiRequestLog.id.desc())
    if key_id:
        query = query.where(OpenApiRequestLog.key_id == key_id)
    if enterprise_id is not None:
        query = query.where(OpenApiRequestLog.enterprise_id == enterprise_id)
    rows = session.scalars(query.limit(limit)).all()
    return [{
        "id": row.id,
        "request_id": row.request_id,
        "key_id": row.key_id,
        "enterprise_id": row.enterprise_id,
        "method": row.method,
        "path": row.path,
        "status": row.status,
        "latency_ms": row.latency_ms,
        "ip": row.ip,
        "created_at": row.created_at.isoformat() if row.created_at else None,
    } for row in rows]
