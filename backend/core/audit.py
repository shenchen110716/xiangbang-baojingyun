from sqlalchemy.orm import Session

from ..models import AuditLog, User


def audit(session: Session, user: User, action: str, object_type: str, object_id: str, detail: str = ""):
    session.add(AuditLog(user_id=user.id, action=action, object_type=object_type, object_id=object_id, detail=detail)); session.commit()
