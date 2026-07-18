import os
from datetime import date, datetime
from zoneinfo import ZoneInfo


# 中国保险系统，业务时钟统一用北京时间（UTC+8）。之前默认 Australia/Melbourne
# （UTC+10/+11）导致 business_now() 与 UTC 存的 created_at 相差约 10 小时，表现为
# “添加时间与生效时间不一致”（保经云问题 7.15 第 1 条）。
BUSINESS_TIMEZONE = ZoneInfo(os.getenv("BUSINESS_TIMEZONE", "Asia/Shanghai"))


def business_default() -> datetime:
    """DateTime 列的默认值统一用业务时钟（北京时间的 naive 值），与 effective_at /
    business_now() 同源，避免 created_at 用 UTC、业务时间用本地时区导致的错位。"""
    return business_now()


def business_now() -> datetime:
    """Return a timezone-normalized naive value suitable for existing DateTime columns."""
    return datetime.now(BUSINESS_TIMEZONE).replace(tzinfo=None)


def business_today() -> date:
    return business_now().date()


def as_business_time(value: datetime) -> datetime:
    if value.tzinfo is None:
        return value
    return value.astimezone(BUSINESS_TIMEZONE).replace(tzinfo=None)
