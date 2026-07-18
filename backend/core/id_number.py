"""GB 11643-1999 18-digit resident ID number format/checksum validation.

This is a local, offline format check only — it confirms the number is
*structurally* well-formed (birth date, region code, checksum digit), not
that it belongs to the person whose name was entered. Verifying a
name-to-ID match requires a real-name verification provider (e.g. a
government or third-party KYC API), which this system has no integration
for (INTEGRATION_MODE is mock-only for insurer/SMS/email/payment; there is
no identity-verification adapter), so that part of the check is out of
scope here.
"""
import base64
import hashlib
import hmac
import re
from datetime import date

from cryptography.fernet import Fernet

from .config import ID_ENCRYPTION_KEY

_WEIGHTS = [7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2]
_CHECK_CODES = "10X98765432"


def _normalise(value: str) -> str:
    return (value or "").strip().upper()


def _fernet() -> Fernet:
    # Fernet demands a 32-byte urlsafe-base64 key; the configured secret is
    # free-form, so derive a fixed-length key from it rather than constraining
    # how operators generate the env var.
    key = base64.urlsafe_b64encode(hashlib.sha256(ID_ENCRYPTION_KEY.encode()).digest())
    return Fernet(key)


def id_hash(value: str) -> str:
    """确定性哈希，仅用于匹配与唯一性判断，不可逆。

    Keyed with the same secret as encryption so the digests are useless to
    anyone who exfiltrates the table without the key.
    """
    return hmac.new(
        ID_ENCRYPTION_KEY.encode(), _normalise(value).encode(), hashlib.sha256
    ).hexdigest()


def id_encrypt(value: str) -> str:
    """可逆密文，用于存储。含随机 IV，故同一号码每次密文不同。"""
    return _fernet().encrypt(_normalise(value).encode()).decode()


def id_decrypt(token: str) -> str:
    return _fernet().decrypt(token.encode()).decode()


def encrypt_bytes(raw: bytes) -> bytes:
    """For files at rest (§6.4: 原始上传文件必须私有、加密)."""
    return _fernet().encrypt(raw)


def decrypt_bytes(token: bytes) -> bytes:
    return _fernet().decrypt(token)


def mask_id_number(value: str) -> str:
    """响应、日志与审计中唯一允许出现的形式（§6.4）。"""
    raw = _normalise(value)
    if len(raw) < 10:
        return "*" * len(raw)
    return f'{raw[:6]}{"*" * (len(raw) - 10)}{raw[-4:]}'


def is_valid_id_number(value: str) -> bool:
    value = _normalise(value)
    if not re.fullmatch(r"\d{17}[\dX]", value):
        return False
    try:
        birth = date(int(value[6:10]), int(value[10:12]), int(value[12:14]))
    except ValueError:
        return False
    if birth > date.today():
        return False
    total = sum(int(d) * w for d, w in zip(value[:17], _WEIGHTS))
    return _CHECK_CODES[total % 11] == value[17]


def birth_date_from_id(value: str) -> date | None:
    """从 18 位身份证号解析出生日期，非法则 None。"""
    value = _normalise(value)
    if not re.fullmatch(r"\d{17}[\dX]", value):
        return None
    try:
        return date(int(value[6:10]), int(value[10:12]), int(value[12:14]))
    except ValueError:
        return None


def age_on(birth: date, on: date | None = None) -> int:
    """周岁：截至 on（默认今天）已满的整岁数。"""
    on = on or date.today()
    return on.year - birth.year - ((on.month, on.day) < (birth.month, birth.day))
