import secrets
from datetime import datetime, timezone

from fastapi import APIRouter, Depends, HTTPException, Query, Request
from sqlalchemy import select
from sqlalchemy.orm import Session

from ..core.db import db
from ..core.rbac import assert_enterprise_scope, require_role
from ..core.security import current_user
from ..models import Enterprise, PaymentRecord, User
from ..providers import provider_mode, wechat_pay_provider
from ..schemas import PaymentCallbackIn, PaymentIn
from ..services import post_ledger_entry
from ..services import settings as settings_service

router = APIRouter(prefix="/api", tags=["payments"])


def _apply_paid(session: Session, row: PaymentRecord) -> None:
    row.status = "paid"
    ent = session.get(Enterprise, row.enterprise_id)
    if row.account == "premium": ent.premium_balance += row.amount
    else: ent.usage_balance += row.amount
    post_ledger_entry(session, ent, row.account, "credit", row.amount, "payment", row.order_no, idempotency_key=row.order_no)
    session.commit()


@router.post("/payments", dependencies=[Depends(require_role("admin", "enterprise", detail="无权创建充值订单"))])
def create_payment(data:PaymentIn,user:User=Depends(current_user),session:Session=Depends(db)):
    assert_enterprise_scope(user, data.enterprise_id, "无权为该单位充值")
    if not session.get(Enterprise,data.enterprise_id): raise HTTPException(404,"投保单位不存在")
    if data.account == "premium": raise HTTPException(400, "保费账户充值请使用「账户充值」页面提交充值申请，走审核流程")
    order=f"PAY-{datetime.now().strftime('%Y%m%d%H%M%S')}-{secrets.token_hex(3).upper()}"
    if data.channel == "jsapi":
        if not user.wx_openid: raise HTTPException(400, "请先在小程序内完成微信授权绑定")
        result = wechat_pay_provider().create_jsapi_order(data.amount, order, user.wx_openid, "响帮帮无忧保-平台服务费")
    else:
        result = wechat_pay_provider().create_native_order(data.amount, order, "响帮帮无忧保-平台服务费")
    if not result.ok: raise HTTPException(502, result.message or "微信支付下单失败")
    row=PaymentRecord(order_no=order,enterprise_id=data.enterprise_id,account=data.account,amount=data.amount,status="pending",provider=result.provider,channel=data.channel,openid=user.wx_openid if data.channel=="jsapi" else None)
    session.add(row);session.commit()
    return {**result.data,"order_no":order,"status":row.status,"channel":row.channel,"request_id":result.request_id}

@router.post("/payments/callback", dependencies=[Depends(require_role("admin", detail="仅总后台可手动触发支付回调"))])
def payment_callback(data:PaymentCallbackIn,session:Session=Depends(db)):
    # with_for_update() 锁定订单行：防止并发重复通知在下面的幂等判断之间双倍入账。
    # SQLite（本地/测试）静默忽略该子句；PostgreSQL（生产）真正加行锁。
    row=session.scalar(select(PaymentRecord).where(PaymentRecord.order_no==data.order_no).with_for_update())
    if not row: raise HTTPException(404,"支付订单不存在")
    if row.status=="paid": return {"ok":True,"order_no":row.order_no,"status":row.status,"idempotent":True}
    if data.status=="paid":
        row.provider_trade_no=data.provider_trade_no
        row.paid_at=datetime.now(timezone.utc)
        _apply_paid(session,row)
    else:
        row.status=data.status; session.commit()
    return {"ok":True,"order_no":row.order_no,"status":row.status,"idempotent":False}

@router.post("/payments/wechat-notify")
async def wechat_notify(request:Request,session:Session=Depends(db)):
    if provider_mode()=="mock" and settings_service.configured("WECHAT_PAY_MCH_ID"):
        raise HTTPException(503,"系统配置异常：已配置微信商户号但仍处于 mock 模式，请联系平台管理员")
    payload=wechat_pay_provider().verify_notify(dict(request.headers), await request.body())
    if not payload: raise HTTPException(400,"签名校验失败")
    # with_for_update() 同上：锁定订单行，防止微信网关并发重试导致双倍入账。
    row=session.scalar(select(PaymentRecord).where(PaymentRecord.order_no==payload.get("out_trade_no","")).with_for_update())
    if not row: raise HTTPException(404,"支付订单不存在")
    if row.status=="paid": return {"ok":True,"order_no":row.order_no,"status":row.status,"idempotent":True}
    if payload.get("status")=="paid":
        row.provider_trade_no=payload.get("transaction_id","")
        row.paid_at=datetime.now(timezone.utc)
        _apply_paid(session,row)
    else:
        row.status=payload.get("status",row.status); session.commit()
    return {"ok":True,"order_no":row.order_no,"status":row.status,"idempotent":False}

@router.get("/payments", dependencies=[Depends(require_role("admin", detail="仅总后台可查看支付记录"))])
def list_payments(enterprise_id:int|None=Query(None), status_value:str=Query("",alias="status"), channel:str=Query(""), session:Session=Depends(db)):
    stmt=select(PaymentRecord).order_by(PaymentRecord.created_at.desc())
    if enterprise_id: stmt=stmt.where(PaymentRecord.enterprise_id==enterprise_id)
    if status_value: stmt=stmt.where(PaymentRecord.status==status_value)
    if channel: stmt=stmt.where(PaymentRecord.channel==channel)
    rows=session.scalars(stmt).all()
    enterprise_names={e.id:e.name for e in session.query(Enterprise).all()}
    return [{"order_no":r.order_no,"enterprise_id":r.enterprise_id,"enterprise_name":enterprise_names.get(r.enterprise_id,""),"account":r.account,"amount":r.amount,"status":r.status,"provider":r.provider,"channel":r.channel,"provider_trade_no":r.provider_trade_no,"created_at":r.created_at,"paid_at":r.paid_at} for r in rows]

@router.get("/payments/reconcile", dependencies=[Depends(require_role("admin", detail="仅总后台可对账"))])
def payment_reconcile(session:Session=Depends(db)):
    return {"pending":session.query(PaymentRecord).filter(PaymentRecord.status=="pending").count(),"paid":session.query(PaymentRecord).filter(PaymentRecord.status=="paid").count(),"failed":session.query(PaymentRecord).filter(PaymentRecord.status=="failed").count()}

@router.get("/payments/{order_no}")
def get_payment(order_no:str,user:User=Depends(current_user),session:Session=Depends(db)):
    row=session.scalar(select(PaymentRecord).where(PaymentRecord.order_no==order_no))
    if not row: raise HTTPException(404,"支付订单不存在")
    assert_enterprise_scope(user,row.enterprise_id,"无权查看该订单")
    return {"order_no":row.order_no,"status":row.status,"amount":row.amount,"account":row.account,"channel":row.channel,"paid_at":row.paid_at}
