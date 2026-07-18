import secrets
from pathlib import Path
from typing import Literal

from fastapi import APIRouter, Depends, File, Form, HTTPException, Query, UploadFile
from fastapi.responses import FileResponse
from sqlalchemy import select
from sqlalchemy.orm import Session

from ..core.audit import audit
from ..core.business_time import business_now
from ..core.config import ROOT
from ..core.db import db
from ..core.file_tokens import make_download_token, verify_download_token
from ..core.rbac import require_role
from ..core.security import current_user
from ..models import Enterprise, RechargeRequest, User
from ..services import get_or_create_premium_account, notify_enterprise, post_ledger_entry, premium_payment_options, recharge_payment_account_view, resolve_account_for_insurer, serialize

router = APIRouter(prefix="/api", tags=["recharge-requests"])


@router.get("/recharge/payment-account", dependencies=[Depends(require_role("admin", "enterprise", detail="无权查看收款账户"))])
def recharge_payment_account(account_type: str = Query(...), insurer: str = Query(""), session: Session = Depends(db)):
    """发起充值时展示"往哪个账户转账"：保费按保司、使用费按平台使用费收款账户解析。
    企业端也可访问（现有收款账户管理接口仅限管理员），未配置返回 null。"""
    return recharge_payment_account_view(session, account_type, insurer)


@router.get("/recharge/payment-accounts", dependencies=[Depends(require_role("admin", "enterprise", detail="无权查看收款账户"))])
def recharge_payment_accounts(session: Session = Depends(db)):
    """已配置收款账户的保费保司列表，供企业端充值时下拉选择。"""
    return premium_payment_options(session)


def _recharge_dict(item: RechargeRequest, session: Session) -> dict:
    enterprise = session.get(Enterprise, item.enterprise_id)
    payload = serialize(item)
    payload["enterprise_name"] = enterprise.name if enterprise else ""
    if item.receipt_file_url:
        token, expires = make_download_token(f"recharge-receipt:{item.id}")
        payload["receipt_download_url"] = f"/api/recharge-requests/{item.id}/receipt?token={token}&expires={expires}"
    return payload


@router.post("/recharge-requests", dependencies=[Depends(require_role("admin", "enterprise", detail="无权发起充值申请"))])
async def create_recharge_request(
    enterprise_id: int = Form(...),
    account_type: Literal["premium", "usage"] = Form(...),
    insurer: str = Form(""),
    amount: float = Form(...),
    file: UploadFile = File(...),
    user: User = Depends(current_user),
    session: Session = Depends(db),
):
    if user.role == "enterprise" and user.enterprise_id != enterprise_id: raise HTTPException(403, "无权为其他单位发起充值")
    if not session.get(Enterprise, enterprise_id): raise HTTPException(404, "投保单位不存在")
    if amount <= 0: raise HTTPException(400, "充值金额必须大于 0")
    account_id = None
    if account_type == "premium":
        if not insurer.strip(): raise HTTPException(400, "请选择保司")
        account = resolve_account_for_insurer(session, insurer.strip())
        if not account: raise HTTPException(400, "该保司尚未配置收款账户，请联系平台")
        account_id = account.id
    suffix = Path(file.filename or "").suffix.lower()
    if suffix not in {".pdf", ".jpg", ".jpeg", ".png"}: raise HTTPException(400, "仅支持 PDF 或图片格式")
    content = await file.read()
    if len(content) > 20 * 1024 * 1024: raise HTTPException(400, "文件不能超过 20MB")
    folder = ROOT / "uploads" / "recharge-receipts" / str(enterprise_id)
    folder.mkdir(parents=True, exist_ok=True)
    stored = f"{secrets.token_hex(8)}{suffix}"
    (folder / stored).write_bytes(content)
    item = RechargeRequest(
        enterprise_id=enterprise_id, account_type=account_type,
        insurer=insurer.strip() if account_type == "premium" else None, account_id=account_id,
        amount=amount, receipt_file_url=f"/uploads/recharge-receipts/{enterprise_id}/{stored}",
        status="pending", created_by=user.id,
    )
    session.add(item); session.commit(); session.refresh(item)
    audit(session, user, "create", "recharge_request", str(item.id), f"{account_type}:{amount}")
    return _recharge_dict(item, session)


@router.get("/recharge-requests")
def list_recharge_requests(status_value: str = Query("", alias="status"), user: User = Depends(current_user), session: Session = Depends(db)):
    stmt = select(RechargeRequest).order_by(RechargeRequest.id.desc())
    if user.role == "enterprise" and user.enterprise_id: stmt = stmt.where(RechargeRequest.enterprise_id == user.enterprise_id)
    elif user.role != "admin": raise HTTPException(403, "无权查看充值记录")
    if status_value: stmt = stmt.where(RechargeRequest.status == status_value)
    return [_recharge_dict(x, session) for x in session.scalars(stmt)]


@router.patch("/recharge-requests/{item_id}/confirm", dependencies=[Depends(require_role("admin", detail="仅总后台可确认充值"))])
def confirm_recharge_request(item_id: int, user: User = Depends(current_user), session: Session = Depends(db)):
    item = session.get(RechargeRequest, item_id)
    if not item: raise HTTPException(404, "充值申请不存在")
    if item.status != "pending": raise HTTPException(400, "该申请已处理，不能重复确认")
    enterprise = session.get(Enterprise, item.enterprise_id)
    if item.account_type == "premium":
        premium_account = get_or_create_premium_account(session, item.enterprise_id, item.account_id)
        premium_account.balance += item.amount
        post_ledger_entry(session, enterprise, "premium", "credit", item.amount, "recharge_request", str(item.id), user, account_id=item.account_id)
    else:
        enterprise.usage_balance += item.amount
        post_ledger_entry(session, enterprise, "usage", "credit", item.amount, "recharge_request", str(item.id), user)
    item.status = "confirmed"; item.confirmed_by = user.id; item.confirmed_at = business_now()
    session.commit(); audit(session, user, "confirm", "recharge_request", str(item.id))
    notify_enterprise(session, item.enterprise_id, "recharge_confirmed", {"amount": item.amount, "account_type": item.account_type})
    return _recharge_dict(item, session)


@router.patch("/recharge-requests/{item_id}/reject", dependencies=[Depends(require_role("admin", detail="仅总后台可驳回充值"))])
def reject_recharge_request(item_id: int, reason: str = Query(...), user: User = Depends(current_user), session: Session = Depends(db)):
    item = session.get(RechargeRequest, item_id)
    if not item: raise HTTPException(404, "充值申请不存在")
    if item.status != "pending": raise HTTPException(400, "该申请已处理，不能重复驳回")
    if not reason.strip(): raise HTTPException(400, "驳回时必须填写原因")
    item.status = "rejected"; item.reject_reason = reason.strip(); item.confirmed_by = user.id; item.confirmed_at = business_now()
    session.commit(); audit(session, user, "reject", "recharge_request", str(item.id), reason)
    notify_enterprise(session, item.enterprise_id, "recharge_rejected", {"amount": item.amount, "reason": reason.strip()})
    return _recharge_dict(item, session)


@router.get("/recharge-requests/{item_id}/receipt")
def download_recharge_receipt(item_id: int, token: str, expires: int, session: Session = Depends(db)):
    if not verify_download_token(f"recharge-receipt:{item_id}", expires, token): raise HTTPException(403, "下载链接无效或已过期")
    item = session.get(RechargeRequest, item_id)
    if not item or not item.receipt_file_url: raise HTTPException(404, "回单不存在")
    return FileResponse(ROOT / item.receipt_file_url.lstrip("/"))
