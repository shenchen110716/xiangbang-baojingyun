from datetime import date

from fastapi import APIRouter, Depends, HTTPException, Query
from sqlalchemy import select
from sqlalchemy.orm import Session

from ..core.audit import audit
from ..core.business_time import business_today
from ..core.db import db
from ..core.rbac import assert_enterprise_scope, require_role
from ..core.security import current_user
from ..models import Enterprise, InsurancePlan, Invoice, Policy, User
from ..schemas import InvoiceIn, InvoiceUpdate
from ..services import amount, policy_dict, serialize, usage_person_days

router = APIRouter(prefix="/api", tags=["invoices"])


@router.get("/invoices/monthly-summary")
def invoice_monthly_summary(enterprise_id: int = Query(...), user: User = Depends(current_user), session: Session = Depends(db)):
    """按自然月统计当月应开票费用（保费、系统服务费），并标记本月是否已开票，
    供申请发票时自动带出开票金额（保经云问题 7.18 第 6 条）。"""
    assert_enterprise_scope(user, enterprise_id, "无权查看其他单位")
    enterprise = session.get(Enterprise, enterprise_id)
    if not enterprise:
        raise HTTPException(404, "投保单位不存在")
    today = business_today()
    month_start = date(today.year, today.month, 1)
    month_key = today.strftime("%Y-%m")
    rate = float(enterprise.usage_fee_daily or 0.1)
    usage_amount = amount(usage_person_days(session, enterprise_id, month_start, today)["person_days"] * rate)
    active_policies = session.scalars(select(Policy).where(Policy.enterprise_id == enterprise_id, Policy.status == "active"))
    premium_amount = amount(sum(float(policy_dict(p, session)["premium"] or 0) for p in active_policies))

    def invoiced(account: str) -> bool:
        for inv in session.scalars(select(Invoice).where(Invoice.enterprise_id == enterprise_id, Invoice.account == account, Invoice.status != "rejected")):
            if inv.created_at and inv.created_at.strftime("%Y-%m") == month_key:
                return True
        return False

    return {
        "month": month_key,
        "premium": {"amount": premium_amount, "invoiced": invoiced("premium")},
        "usage": {"amount": usage_amount, "invoiced": invoiced("usage")},
    }


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
