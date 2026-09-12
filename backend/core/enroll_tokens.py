import hashlib
import hmac

from .config import SECRET_KEY

# 扫码参保的二维码链接是无状态签名 token，不建表存——跟 file_tokens.py 的
# 短时下载链接同一套思路，区别是这里没有时间过期，而是靠 WorkPosition.
# enroll_token_version 失效：HR"重新生成二维码"把版本号加一，旧签名立刻
# 对不上，不用去清理/作废任何数据库行。


def _sign(position_id: int, payment_mode: str, token_version: int) -> str:
    message = f"{position_id}:{payment_mode}:{token_version}".encode()
    return hmac.new(SECRET_KEY.encode(), message, hashlib.sha256).hexdigest()[:32]


def make_enroll_token(position_id: int, payment_mode: str, token_version: int) -> str:
    return _sign(position_id, payment_mode, token_version)


def verify_enroll_token(position_id: int, payment_mode: str, token_version: int, token: str) -> bool:
    expected = _sign(position_id, payment_mode, token_version)
    return hmac.compare_digest(expected, token)
