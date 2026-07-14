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
import re
from datetime import date

_WEIGHTS = [7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2]
_CHECK_CODES = "10X98765432"


def is_valid_id_number(value: str) -> bool:
    value = (value or "").strip().upper()
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
