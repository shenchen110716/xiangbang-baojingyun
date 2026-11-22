"""/openapi/v1 签名 v2 认证 + scope/IP/限流依赖（AC2/AC3/AC5/AC6/AC7）。

主体隔离是红线（AC16）：OpenApiPrincipal 是独立 dataclass，不是 User、
不签 JWT、永远不进 core/security.current_user 的负向角色过滤体系；每个
openapi 查询都显式 WHERE enterprise_id = principal.enterprise_id。

校验顺序（require_scope 依赖内）：
1. 头完整性 + X-Sign-Version==2 + 时钟 ±300s（无 DB 开销的先挡掉）；
2. 加载 key、HMAC v2 验签（未知 key 与错签名同一回答，防 key_id 枚举；
   验签先于启停/过期判断，没有 secret 的人探不到 key 状态）；
3. 烧 nonce 并立即 commit——之后 IP/限流/scope 再失败也不"退回"nonce，
   否则被拒请求可以无限重放去探测；
4. IP 白名单（403）→ 固定窗口限流（429，超限也计数）→ scope（403）。
"""
import hmac
import ipaddress
import os
import secrets as _secrets
from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Optional

from fastapi import Depends, Request
from sqlalchemy import select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from ...core.api_keys import sign_request_v2
from ...core.db import db
from ...core.id_number import decrypt_bytes, encrypt_bytes
from ...models import IntegrationNonce, OpenApiClient
from ...routers.openapi.common import E_PERM, E_SIGN, OpenApiError
from .rate_limit import enforce_rate_limit
from .request_log import client_ip

MAX_CLOCK_SKEW_SECONDS = 300
# story-p3-compliance AC7：密钥轮换后旧 secret 的宽限期（7 天），
# 自 OpenApiClient.rotated_at 起算，超期旧 secret 验签一律 40100。
ROTATION_GRACE_SECONDS = 7 * 24 * 3600

# 设计方案 §4 的 scope 清单 + probe:read（联调/冒烟探针，无业务数据）。
KNOWN_SCOPES = frozenset({
    "insured:read", "insured:read_sensitive", "declaration:write",
    "account:read", "recharge:write", "policy:read", "webhook:manage",
    "probe:read",
})


@dataclass(frozen=True)
class OpenApiPrincipal:
    client_id: int
    key_id: str
    enterprise_id: int
    env: str                      # prod | sandbox
    scopes: frozenset[str]
    # None = 企业全部实际工作单位（沿用 v1 语义）。
    allowed_employer_ids: Optional[frozenset[int]]


def current_rate_limit() -> int:
    return int(os.getenv("OPENAPI_RATE_LIMIT_PER_SEC", "10"))


def current_rate_window() -> int:
    return int(os.getenv("OPENAPI_RATE_LIMIT_WINDOW_SECONDS", "1"))


def issue_client_secret() -> tuple[str, str]:
    """返回 (明文 secret, 存储密文)；明文只在签发响应里出现一次。"""
    secret = _secrets.token_urlsafe(32)
    return secret, encrypt_bytes(secret.encode()).decode()


def _aware(value: Optional[datetime]) -> Optional[datetime]:
    """SQLite 读回的 DateTime(timezone=True) 可能是 naive，按 UTC 补齐。"""
    if value is not None and value.tzinfo is None:
        return value.replace(tzinfo=timezone.utc)
    return value


def parse_csv_ints(raw: str) -> Optional[frozenset[int]]:
    text = (raw or "").strip()
    if not text:
        return None
    return frozenset(int(part) for part in text.split(",") if part.strip())


def parse_csv(raw: str) -> list[str]:
    return [part.strip() for part in (raw or "").split(",") if part.strip()]


async def _authenticate_v2(session: Session, request: Request) -> OpenApiPrincipal:
    key_id = request.headers.get("X-Api-Key-Id", "")
    timestamp = request.headers.get("X-Timestamp", "")
    nonce = request.headers.get("X-Nonce", "")
    signature = request.headers.get("X-Signature", "")
    version = request.headers.get("X-Sign-Version", "")
    if not (key_id and timestamp and nonce and signature):
        raise OpenApiError(401, E_SIGN, "接入认证信息不完整")
    if version != "2":
        raise OpenApiError(401, E_SIGN, "开放平台仅接受 X-Sign-Version: 2 签名")

    try:
        sent_at = datetime.fromtimestamp(int(timestamp), tz=timezone.utc)
    except (ValueError, OSError, OverflowError):
        raise OpenApiError(401, E_SIGN, "接入签名校验失败") from None
    if abs((datetime.now(timezone.utc) - sent_at).total_seconds()) > MAX_CLOCK_SKEW_SECONDS:
        raise OpenApiError(401, E_SIGN, "接入时间戳已过期")

    key = session.scalar(select(OpenApiClient).where(OpenApiClient.key_id == key_id))
    body = await request.body()
    method, path, query = request.method, request.url.path, request.url.query
    # 未知 key 与错签名同一回答，避免 key_id 枚举 oracle（沿用 v1 姿态）；
    # 并对假 secret 跑一次等价 HMAC，对齐与"已知 key 错签名"路径的耗时，
    # 堵住响应时延枚举 key_id 的侧信道（review-p0 minor-6）。
    if not key:
        dummy_secret = _secrets.token_urlsafe(32)
        sign_request_v2(dummy_secret, method, path, query, timestamp, nonce, body)
        raise OpenApiError(401, E_SIGN, "接入签名校验失败")

    candidates = [key.secret_encrypted]
    # story-p3-compliance AC7：轮换后的旧密钥只在宽限期内可验签，宽限自
    # rotated_at 起算；超期即废止（与错签名同一回答，不泄漏废止状态）。
    rotated_at = _aware(key.rotated_at)
    if key.secondary_secret_encrypted and rotated_at is not None and (
            (datetime.now(timezone.utc) - rotated_at).total_seconds()
            <= ROTATION_GRACE_SECONDS):
        candidates.append(key.secondary_secret_encrypted)  # 轮换宽限期旧密钥
    for cipher in candidates:
        secret = decrypt_bytes(cipher.encode()).decode()
        expected = sign_request_v2(secret, method, path, query, timestamp, nonce, body)
        if hmac.compare_digest(expected, signature):
            break
    else:
        raise OpenApiError(401, E_SIGN, "接入签名校验失败")

    # 验签之后才暴露 key 状态：没有 secret 的人探不到启停/过期。
    if key.status != "active":
        raise OpenApiError(401, E_SIGN, "接入身份已停用")
    expires_at = _aware(key.expires_at)
    if expires_at is not None and expires_at <= datetime.now(timezone.utc):
        raise OpenApiError(401, E_SIGN, "接入身份已过期")

    # 烧 nonce 并立刻落库；唯一索引 ux_nonce_per_key 是防重放的权威。
    session.add(IntegrationNonce(key_id=key_id, nonce=nonce,
                                 seen_at=datetime.now(timezone.utc)))
    try:
        session.commit()
    except IntegrityError:
        session.rollback()
        raise OpenApiError(401, E_SIGN, "请求已被处理，请勿重放") from None

    return OpenApiPrincipal(
        client_id=key.id, key_id=key.key_id, enterprise_id=key.enterprise_id,
        env=key.env, scopes=frozenset(parse_csv(key.scopes)),
        allowed_employer_ids=parse_csv_ints(key.allowed_employer_ids),
    )


def _check_ip_allowlist(session: Session, principal: OpenApiPrincipal,
                        request: Request) -> None:
    key = session.get(OpenApiClient, principal.client_id)
    entries = parse_csv(key.ip_allowlist if key else "")
    if not entries:
        return
    try:
        source = ipaddress.ip_address(client_ip(request))
    except ValueError:
        raise OpenApiError(403, E_PERM, "来源 IP 不在白名单内") from None
    for entry in entries:
        try:
            if source in ipaddress.ip_network(entry, strict=False):
                return
        except ValueError:
            continue
    raise OpenApiError(403, E_PERM, "来源 IP 不在白名单内")


def require_scope(scope: Optional[str] = None):
    """依赖工厂：认证 + IP + 限流 + scope，返回 OpenApiPrincipal。

    scope=None 表示任何有效 key 都可调用（如 ping）。
    """
    if scope is not None and scope not in KNOWN_SCOPES:
        raise ValueError(f"unknown openapi scope: {scope}")

    async def dependency(request: Request,
                         session: Session = Depends(db)) -> OpenApiPrincipal:
        principal = await _authenticate_v2(session, request)
        # 先挂到 request.state：IP/限流/scope 失败时请求日志也要带上
        # key/enterprise（AC8）。
        request.state.openapi_principal = principal
        _check_ip_allowlist(session, principal, request)
        enforce_rate_limit(session, principal.key_id, "default",
                           current_rate_limit(), current_rate_window())
        if scope is not None and scope not in principal.scopes:
            raise OpenApiError(403, E_PERM, f"缺少所需 scope：{scope}")
        return principal

    return dependency
