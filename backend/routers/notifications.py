from fastapi import APIRouter, Depends
from sqlalchemy.orm import Session

from ..core.audit import audit
from ..core.db import db
from ..core.security import current_user
from ..models import User
from ..providers import email_provider, sms_provider
from ..schemas import NotificationIn

router = APIRouter(prefix="/api", tags=["notifications"])


@router.post("/notifications/send")
def send_notification(data:NotificationIn,user:User=Depends(current_user),session:Session=Depends(db)):
    result=sms_provider().send_sms(data.recipient,data.template,{"content":data.content}) if data.kind=="sms" else email_provider().send_email(data.recipient,data.subject,data.content)
    audit(session,user,"send",data.kind,data.recipient,result.message);return {"ok":result.ok,"provider":result.provider,"request_id":result.request_id,"message":result.message}
