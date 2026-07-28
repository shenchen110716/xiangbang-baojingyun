import secrets
from datetime import date
from pathlib import Path

from fastapi import APIRouter, Depends, File, HTTPException, Query, UploadFile
from fastapi.responses import FileResponse, RedirectResponse
from sqlalchemy import select
from sqlalchemy.orm import Session

from ..core.audit import audit
from ..core.business_time import business_today
from ..core import storage
from ..core.db import db
from ..core.file_tokens import make_download_token, verify_download_token
from ..core.rbac import assert_enterprise_scope, require_role
from ..core.security import current_user
from ..models import Enterprise, Invoice, Policy, User
from ..schemas import InvoiceIn, InvoiceUpdate
from ..services import amount, policy_dict, serialize, usage_person_days
from ..services.insurer_scope import insurer_plan_ids

router = APIRouter(prefix="/api", tags=["invoices"])


def _invoice_enterprise_ids_for_insurer(session: Session, insurer_id: int) -> set[int]:
    plan_ids = insurer_plan_ids(session, insurer_id)
    if not plan_ids:
        return set()
    return {x.enterprise_id for x in session.scalars(select(Policy).where(Policy.plan_id.in_(plan_ids)))}


def _assert_invoice_visible_to_insurer(session: Session, user: User, invoice: Invoice) -> None:
    """role=='insurer' 专用范围检查：只能操作名下投保单位（跟 GET /invoices
    列表口径一致，按投保单位有没有本保司的保单来判定，不按发票挂钩具体保单）。
    非 insurer 角色直接放行——这不是身份检查，是范围检查。"""
    if user.role != "insurer":
        return
    if invoice.enterprise_id not in _invoice_enterprise_ids_for_insurer(session, user.insurer_id):
        raise HTTPException(403, "无权操作其他保司名下单位的发票")


def _invoice_with_document(item: Invoice, session: Session) -> dict:
    enterprise = session.get(Enterprise, item.enterprise_id)
    payload = {**serialize(item), "enterprise_name": enterprise.name if enterprise else ""}
    if item.document_url:
        token, expires = make_download_token(f"invoice-document:{item.id}")
        payload["document_download_url"] = f"/api/invoices/{item.id}/document/download?token={token}&expires={expires}"
    return payload


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
    elif user.role=='insurer':
        # Invoice 没有直接的 plan_id，通过该单位在本保司名下有保单来判定可见性——
        # 与"财务管理"结算范围保持同一颗粒度（按投保单位，不按单张发票挂钩具体保单）。
        enterprise_ids=_invoice_enterprise_ids_for_insurer(session,user.insurer_id)
        stmt=stmt.where(Invoice.enterprise_id.in_(enterprise_ids)) if enterprise_ids else stmt.where(Invoice.id.is_(None))
    elif user.role!='admin': raise HTTPException(403,'无权查看发票')
    return [_invoice_with_document(item,session) for item in session.scalars(stmt)]

@router.post("/invoices", dependencies=[Depends(require_role("admin", "enterprise", detail="无权申请发票"))])
def create_invoice(data:InvoiceIn,user:User=Depends(current_user),session:Session=Depends(db)):
    assert_enterprise_scope(user, data.enterprise_id, "无权申请其他单位发票")
    if not session.get(Enterprise,data.enterprise_id): raise HTTPException(404,'投保单位不存在')
    item=Invoice(**data.model_dump());session.add(item);session.commit();session.refresh(item);audit(session,user,'create','invoice',str(item.id),f'{item.account}:{item.amount}');return _invoice_with_document(item,session)

@router.patch("/invoices/{item_id}", dependencies=[Depends(require_role("admin", detail="仅总后台可审核发票"))])
def update_invoice(item_id:int,data:InvoiceUpdate,user:User=Depends(current_user),session:Session=Depends(db)):
    item=session.get(Invoice,item_id)
    if not item: raise HTTPException(404,'发票申请不存在')
    item.status=data.status;session.commit();audit(session,user,'status_change','invoice',str(item.id),data.status);return _invoice_with_document(item,session)


@router.post("/invoices/{item_id}/document/upload", dependencies=[Depends(require_role("admin", "insurer", detail="仅平台或保司端可上传发票"))])
async def upload_invoice_document(item_id:int,file:UploadFile=File(...),user:User=Depends(current_user),session:Session=Depends(db)):
    item=session.get(Invoice,item_id)
    if not item: raise HTTPException(404,'发票申请不存在')
    _assert_invoice_visible_to_insurer(session,user,item)
    suffix=Path(file.filename or '').suffix.lower()
    if suffix not in {'.pdf','.jpg','.jpeg','.png'}: raise HTTPException(400,'仅支持 PDF 或图片格式')
    content=await file.read()
    if len(content)>20*1024*1024: raise HTTPException(400,'文件不能超过 20MB')
    stored=f'{secrets.token_hex(8)}{suffix}'
    item.document_url=storage.save_bytes(f'invoices/{item_id}/{stored}',content);item.document_name=file.filename or stored
    session.commit();audit(session,user,'upload','invoice_document',str(item_id))
    return _invoice_with_document(item,session)

@router.get("/invoices/{item_id}/document/download")
def download_invoice_document(item_id:int,token:str,expires:int,session:Session=Depends(db)):
    if not verify_download_token(f"invoice-document:{item_id}", expires, token): raise HTTPException(403,'下载链接无效或已过期')
    item=session.get(Invoice,item_id)
    if not item or not item.document_url: raise HTTPException(404,'发票文件不存在')
    resolved=storage.resolve(item.document_url,filename=item.document_name or None)
    if not resolved: raise HTTPException(404,'文件不存在')
    kind,ref=resolved
    return RedirectResponse(ref) if kind=='redirect' else FileResponse(ref,filename=item.document_name or None)
