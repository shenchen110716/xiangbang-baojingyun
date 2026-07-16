from sqlalchemy import select
from sqlalchemy.orm import Session

from ..core.audit import audit
from ..models import User
from ..providers import sms_provider


def notify_enterprise(session: Session, enterprise_id: int, template: str, params: dict) -> None:
    """Send an SMS to each active enterprise account without interrupting the caller."""
    recipients = session.scalars(
        select(User).where(
            User.role == "enterprise",
            User.enterprise_id == enterprise_id,
            User.active.is_(True),
        )
    ).all()

    for user in recipients:
        if not (user.phone or "").strip():
            continue

        try:
            result = sms_provider().send_sms(user.phone, template, params)
            if result.ok:
                continue
            failure = "provider returned ok=False"
        except Exception as exc:
            failure = f"provider raised {type(exc).__name__}"

        try:
            audit(
                session,
                user,
                "sms_failed",
                "enterprise_notification",
                str(enterprise_id),
                f"template={template};recipient_user_id={user.id};{failure}",
            )
        except Exception:
            # Notification and audit failures must not affect the completed
            # business operation that triggered this best-effort helper.
            session.rollback()
