import hashlib
import hmac
import time

from .config import SECRET_KEY

# SYSTEM-DESIGN-V4.md section 11.1: "下载 URL 有效期不超过 5 分钟" — short-lived
# signed download links for private files (position videos / claim documents)
# instead of a permanently-browsable anonymous static mount. The signature
# ties the link to one specific resource id and an expiry timestamp, so a
# leaked/logged link stops working on its own after DEFAULT_TTL_SECONDS.
DEFAULT_TTL_SECONDS = 300


def _sign(resource: str, expires: int) -> str:
    message = f"{resource}:{expires}".encode()
    return hmac.new(SECRET_KEY.encode(), message, hashlib.sha256).hexdigest()


def make_download_token(resource: str, ttl_seconds: int = DEFAULT_TTL_SECONDS) -> tuple[str, int]:
    expires = int(time.time()) + ttl_seconds
    return _sign(resource, expires), expires


def verify_download_token(resource: str, expires: int, token: str) -> bool:
    if int(time.time()) > expires:
        return False
    expected = _sign(resource, expires)
    return hmac.compare_digest(expected, token)
