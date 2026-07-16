"""Signed-request authentication for the external employment event API (§7.3).

§7.3 allows "API Key 或签名认证"; this is the signature variant, so the shared
secret never travels on the wire. The signature covers timestamp + nonce + body
digest, which means a captured request cannot be replayed against a different
body.

That choice dictates storage: recomputing an HMAC needs the secret back, so it
is kept Fernet-encrypted at rest under ID_ENCRYPTION_KEY rather than
password-hashed. (A one-way hash would make signature verification impossible —
it only works for the bearer-token variant, where the secret is sent every
request.)

Replay is refused by the nonce table's unique index, not by an "already seen?"
lookup in Python, which would race under concurrent delivery.

The principal carries the enterprise and the employers the key may write. The
handler derives scope from the principal and ignores any scope field in the
body: 认证身份固定绑定企业及允许的实际工作单位，Body 不能扩大范围（§7.3）。
"""
import hashlib
import hmac
import secrets
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from typing import Optional

from fastapi import HTTPException, Request
from sqlalchemy import select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from ..models import IntegrationApiKey, IntegrationNonce
from .id_number import decrypt_bytes, encrypt_bytes

# Outside this window a captured request is refused even with an unused nonce,
# which bounds how long a stolen request stays useful.
_MAX_CLOCK_SKEW = timedelta(seconds=300)


@dataclass(frozen=True)
class IntegrationPrincipal:
    key_id: str
    enterprise_id: int
    # None means every employer of the enterprise.
    allowed_employer_ids: Optional[frozenset[int]]

    def assert_employer(self, actual_employer_id: int) -> None:
        if (self.allowed_employer_ids is not None
                and actual_employer_id not in self.allowed_employer_ids):
            raise HTTPException(403, "该接入身份无权写入此实际工作单位")


def signing_payload(timestamp: str, nonce: str, body: bytes) -> str:
    return f"{timestamp}\n{nonce}\n{hashlib.sha256(body).hexdigest()}"


def sign_request(secret: str, timestamp: str, nonce: str, body: bytes) -> str:
    """Client-side signing; also used by tests to forge valid requests."""
    return hmac.new(secret.encode(), signing_payload(timestamp, nonce, body).encode(),
                    hashlib.sha256).hexdigest()


def issue_key_secret() -> tuple[str, str]:
    """Returns (plaintext_secret, stored_cipher). Plaintext is shown once."""
    secret = secrets.token_urlsafe(32)
    return secret, encrypt_bytes(secret.encode()).decode()


def _parse_employers(raw: str) -> Optional[frozenset[int]]:
    text = (raw or "").strip()
    if not text:
        return None
    return frozenset(int(part) for part in text.split(",") if part.strip())


async def authenticate_integration(session: Session, request: Request) -> IntegrationPrincipal:
    key_id = request.headers.get("X-Api-Key-Id", "")
    timestamp = request.headers.get("X-Timestamp", "")
    nonce = request.headers.get("X-Nonce", "")
    signature = request.headers.get("X-Signature", "")
    if not (key_id and timestamp and nonce and signature):
        raise HTTPException(401, "接入认证信息不完整")

    try:
        sent_at = datetime.fromtimestamp(int(timestamp), tz=timezone.utc)
    except (ValueError, OSError, OverflowError):
        raise HTTPException(401, "接入签名校验失败") from None
    if abs(datetime.now(timezone.utc) - sent_at) > _MAX_CLOCK_SKEW:
        raise HTTPException(401, "接入时间戳已过期")

    key = session.scalar(
        select(IntegrationApiKey).where(IntegrationApiKey.key_id == key_id,
                                        IntegrationApiKey.active.is_(True)))
    body = await request.body()
    # An unknown key gets the same answer as a bad signature: distinguishing
    # them would turn this endpoint into a key-id enumeration oracle.
    if not key:
        raise HTTPException(401, "接入签名校验失败")

    secret = decrypt_bytes(key.secret_cipher.encode()).decode()
    expected = sign_request(secret, timestamp, nonce, body)
    if not hmac.compare_digest(expected, signature):
        raise HTTPException(401, "接入签名校验失败")

    session.add(IntegrationNonce(key_id=key_id, nonce=nonce,
                                 seen_at=datetime.now(timezone.utc)))
    try:
        session.flush()
    except IntegrityError:
        session.rollback()
        raise HTTPException(409, "请求已被处理，请勿重放") from None

    return IntegrationPrincipal(
        key_id=key_id,
        enterprise_id=key.enterprise_id,
        allowed_employer_ids=_parse_employers(key.allowed_employer_ids),
    )
