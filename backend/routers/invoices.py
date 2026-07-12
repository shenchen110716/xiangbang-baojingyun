from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy import select
from sqlalchemy.orm import Session

from ..core.audit import audit
from ..core.db import db
from ..core.rbac import assert_enterprise_scope, require_role
from ..core.security import current_user
from ..models import Enterprise, Invoice, User
from ..schemas import InvoiceIn, InvoiceUpdate
from ..services import serialize

router = APIRouter(prefix="/api", tags=["invoices"])


@router.get("/invoices")
def invoices(user:User=Depends(current_user),session:Session=Depends(db)):
    stmt=select(Invoice).order_by(Invoice.id.desc())
    if user.role=='enterprise' and user.enterprise_id: stmt=stmt.where(Invoice.enterprise_id==user.enterprise_id)
    elif user.role!='admin': raise HTTPException(403,'无权查看发票')
    result=[]
    for item in session.scalars(stmt):
        enterprise=session.get(Enterprise,item.enterprise_id)
        result.append({**serialize(item),'enterprise_name':enterprise.name if enterprise else ''})
    return result

@router.post("/invoices", dependencies=[Depends(require_role("admin", "enterprise", detail="无权申请发票"))])
def create_invoice(data:InvoiceIn,user:User=Depends(current_user),session:Session=Depends(db)):
    assert_enterprise_scope(user, data.enterprise_id, "无权申请其他单位发票")
    if not session.get(Enterprise,data.enterprise_id): raise HTTPException(404,'投保单位不存在')
    item=Invoice(**data.model_dump());session.add(item);session.commit();session.refresh(item);audit(session,user,'create','invoice',str(item.id),f'{item.account}:{item.amount}');return serialize(item)

@router.patch("/invoices/{item_id}", dependencies=[Depends(require_role("admin", detail="仅总后台可审核发票"))])
def update_invoice(item_id:int,data:InvoiceUpdate,user:User=Depends(current_user),session:Session=Depends(db)):
    item=session.get(Invoice,item_id)
    if not item: raise HTTPException(404,'发票申请不存在')
    item.status=data.status;session.commit();audit(session,user,'status_change','invoice',str(item.id),data.status);return serialize(item)
