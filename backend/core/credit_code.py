"""GB 32100-2015 18-character 统一社会信用代码 format/checksum validation.

Same scope note as id_number.py: this is a local, offline format check only —
it confirms the code is *structurally* well-formed (character set, checksum
digit), not that it belongs to a real registered entity. Verifying that
requires a lookup against 国家企业信用信息公示系统 or an equivalent registry
API, which this system has no integration for.
"""
import re

# 31 个字符（不含 I、O、S、V、Z，避免跟数字/相近字母混淆），下标即字符值。
_CHARSET = "0123456789ABCDEFGHJKLMNPQRTUWXY"
_WEIGHTS = [1, 3, 9, 27, 19, 26, 16, 17, 20, 29, 25, 13, 8, 24, 10, 30, 28]


def _normalise(value: str) -> str:
    return (value or "").strip().upper()


def is_valid_credit_code(value: str) -> bool:
    code = _normalise(value)
    if not re.fullmatch(r"[0-9A-Z]{18}", code):
        return False
    if any(ch not in _CHARSET for ch in code):
        return False
    total = sum(_CHARSET.index(ch) * w for ch, w in zip(code[:17], _WEIGHTS))
    check = (31 - total % 31) % 31
    return _CHARSET[check] == code[17]
