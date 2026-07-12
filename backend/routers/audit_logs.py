from fastapi import APIRouter, Depends, HTTPException, Query
from sqlalchemy import select
from sqlalchemy.orm import Session

from ..core.db import db
from ..core.security import current_user
from ..models import AuditLog, User
from ..services import serialize

router = APIRouter(prefix="/api", tags=["audit-logs"])


@router.get("/audit-logs")
def audit_logs(limit:int=Query(100,ge=1,le=500),user:User=Depends(current_user),session:Session=Depends(db)):
    stmt=select(AuditLog).order_by(AuditLog.id.desc()).limit(limit)
    if user.role=='enterprise':
        operator_ids=select(User.id).where(User.enterprise_id==user.enterprise_id)
        stmt=select(AuditLog).where(AuditLog.user_id.in_(operator_ids)).order_by(AuditLog.id.desc()).limit(limit)
    elif user.role!='admin': raise HTTPException(403,'无权查看审计日志')
    result=[]
    for item in session.scalars(stmt):
        operator=session.get(User,item.user_id)
        result.append({**serialize(item),'operator':operator.name if operator else '系统'})
    return result
