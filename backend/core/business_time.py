import os
from datetime import date, datetime
from zoneinfo import ZoneInfo


BUSINESS_TIMEZONE = ZoneInfo(os.getenv("BUSINESS_TIMEZONE", "Australia/Melbourne"))


def business_now() -> datetime:
    """Return a timezone-normalized naive value suitable for existing DateTime columns."""
    return datetime.now(BUSINESS_TIMEZONE).replace(tzinfo=None)


def business_today() -> date:
    return business_now().date()


def as_business_time(value: datetime) -> datetime:
    if value.tzinfo is None:
        return value
    return value.astimezone(BUSINESS_TIMEZONE).replace(tzinfo=None)
