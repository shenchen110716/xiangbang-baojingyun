import secrets
from datetime import datetime

from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy import select
from sqlalchemy.orm import Session

from ..core.db import db
from ..core.rbac import assert_enterprise_scope, require_role
from ..core.security import current_user
from ..models import Enterprise, PaymentRecord, User
from ..providers import payment_provider
from ..schemas import PaymentCallbackIn, PaymentIn

router = APIRouter(prefix="/api", tags=["payments"])


@router.post("/payments", dependencies=[Depends(require_role("admin", "enterprise", detail="无权创建充值订单"))])
def create_payment(data:PaymentIn,user:User=Depends(current_user),session:Session=Depends(db)):
    assert_enterprise_scope(user, data.enterprise_id, "无权为该单位充值")
    if not session.get(Enterprise,data.enterprise_id): raise HTTPException(404,"投保单位不存在")
    order=f"PAY-{datetime.now().strftime('%Y%m%d%H%M%S')}-{secrets.token_hex(3).upper()}"; result=payment_provider().create_payment(data.amount,order)
    row=PaymentRecord(order_no=order,enterprise_id=data.enterprise_id,account=data.account,amount=data.amount,status="pending",provider=result.provider);session.add(row);session.commit();return {"order_no":order,"status":row.status,"pay_url":result.data.get("pay_url",""),"request_id":result.request_id}

@router.post("/payments/callback")
def payment_callback(data:PaymentCallbackIn,session:Session=Depends(db)):
    row=session.scalar(select(PaymentRecord).where(PaymentRecord.order_no==data.order_no))
    if not row: raise HTTPException(404,"支付订单不存在")
    previous=row.status
    if previous=="paid": return {"ok":True,"order_no":row.order_no,"status":row.status,"idempotent":True}
    row.status=data.status
    if data.status=="paid":
        ent=session.get(Enterprise,row.enterprise_id)
        if row.account=="premium": ent.premium_balance += row.amount
        else: ent.usage_balance += row.amount
    session.commit();return {"ok":True,"order_no":row.order_no,"status":row.status,"idempotent":False}

@router.get("/payments/reconcile", dependencies=[Depends(require_role("admin", detail="仅总后台可对账"))])
def payment_reconcile(session:Session=Depends(db)):
    return {"pending":session.query(PaymentRecord).filter(PaymentRecord.status=="pending").count(),"paid":session.query(PaymentRecord).filter(PaymentRecord.status=="paid").count(),"failed":session.query(PaymentRecord).filter(PaymentRecord.status=="failed").count()}
