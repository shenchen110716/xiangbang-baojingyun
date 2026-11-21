"""开放平台·充值（story-p2-write AC10/AC11）。

- wechat_native（仅 usage）：薄适配 /api/payments 的下单姿态（OPAY- 前缀
  订单号 + providers.create_native_order），**回调沿用 /api/payments/
  wechat-notify**——微信商户号的 notify_url 只有一个，绝不另起第二个回调
  端点；入账走既有 _apply_paid（唯一入账代码路径，code guard）。
- bank_transfer：复用 RechargeRequest 审核流（admin confirm/reject 入账，
  金额变动只发生在既有 /api/recharge-requests/{id}/confirm 里），响应带
  收款账户信息（recharge_payment_account_view），回单经 POST
  /recharges/{id}/receipt 原始字节补传（core/storage，不静态挂载）。
- recharge_id 对外统一口径：bank-{RechargeRequest.id} / wechat-{order_no}
  （两类底层行，不为对外视图另建表）。
- RechargeRequest.created_by NOT NULL 且外键 users.id：机器调用没有 users
  行，回填该企业首个 enterprise 账号（没有则平台 admin）——不改表结构，
  语义为「代表该企业提交」。
"""
import secrets
from datetime import datetime
from pathlib import Path
from typing import Literal, Optional

from fastapi import APIRouter, Depends, Request
from pydantic import BaseModel
from sqlalchemy import select
from sqlalchemy.orm import Session

from ...core import storage
from ...core.db import db
from ...models import PaymentRecord, RechargeRequest, User
from ...providers import wechat_pay_provider
from ...services.openapi.auth import OpenApiPrincipal, require_scope
from ...services.openapi.idempotency import require_idempotency
from ...services.recharge import (
    recharge_payment_account_view, resolve_account_for_insurer,
)
from .common import E_BIZ, OpenApiError, OpenApiRoute, ok

router = APIRouter(prefix="/openapi/v1", tags=["开放平台·充值"],
                   route_class=OpenApiRoute)

RECEIPT_SUFFIXES = {".pdf", ".jpg", ".jpeg", ".png"}
RECEIPT_MAX_BYTES = 20 * 1024 * 1024
# 支付单状态 → 对外统一语义（AC11）。
_BANK_STATUS = {"pending": "pending", "confirmed": "succeeded", "rejected": "rejected"}
_WECHAT_STATUS = {"pending": "pending", "paid": "succeeded"}


class RechargeIn(BaseModel):
    account: Literal["premium", "usage"]
    insurer: str = ""
    amount: float
    channel: Literal["wechat_native", "bank_transfer"]


def _fallback_created_by(session: Session, enterprise_id: int) -> int:
    """created_by NOT NULL 的回填（见模块 docstring）。"""
    user_id = session.scalar(
        select(User.id).where(User.role == "enterprise",
                              User.enterprise_id == enterprise_id)
        .order_by(User.id.asc()).limit(1))
    if user_id is None:
        user_id = session.scalar(
            select(User.id).where(User.role == "admin")
            .order_by(User.id.asc()).limit(1))
    if user_id is None:   # 系统连 admin 都没有：部署态损坏
        raise OpenApiError(500, 50000, "系统账号缺失，请联系平台")
    return user_id


@router.post("/recharges")
async def create_recharge(
        request: Request, payload: RechargeIn,
        principal: OpenApiPrincipal = Depends(require_scope("recharge:write")),
        session: Session = Depends(db)):
    ctx = await require_idempotency(principal, request)
    if ctx.replayed:
        return ok(ctx.snapshot, request.state.openapi_request_id)
    try:
        data = _create_recharge(session, principal, payload)
        ctx.save(session, data)
    except Exception:
        # review-p2 M3：service 只 flush——失败先回滚业务事务（放掉写锁），
        # 再释放幂等占位，允许同 key 重试。
        session.rollback()
        ctx.release()
        raise
    return ok(data, request.state.openapi_request_id)


def _create_recharge(session: Session, principal: OpenApiPrincipal,
                     payload: RechargeIn) -> dict:
    if payload.amount <= 0:
        raise OpenApiError(422, E_BIZ, "充值金额必须大于 0")
    account_id = None
    insurer = payload.insurer.strip()
    if payload.account == "premium":
        if not insurer:
            raise OpenApiError(422, E_BIZ, "保费充值必须指定保司（insurer）")
        account = resolve_account_for_insurer(session, insurer)
        if account is None:
            raise OpenApiError(422, E_BIZ, "该保司尚未配置收款账户，请联系平台")
        account_id = account.id

    if payload.channel == "wechat_native":
        if payload.account == "premium":
            # 与 /api/payments 同一条业务规则：保费充值必须走审核流程。
            raise OpenApiError(422, E_BIZ,
                               "保费账户充值仅支持 bank_transfer（需上传回单走审核流程）")
        order_no = (f"OPAY-{datetime.now().strftime('%Y%m%d%H%M%S')}-"
                    f"{secrets.token_hex(3).upper()}")
        result = wechat_pay_provider().create_native_order(
            payload.amount, order_no, "响帮帮无忧保-平台服务费")
        if not result.ok:
            raise OpenApiError(502, 50000, result.message or "微信支付下单失败")
        session.add(PaymentRecord(
            order_no=order_no, enterprise_id=principal.enterprise_id,
            account=payload.account, amount=payload.amount, status="pending",
            provider=result.provider, channel="native"))
        session.flush()   # review-p2 M3：不 commit，由 ctx.save 与幂等快照单次提交
        return {
            "recharge_id": f"wechat-{order_no}",
            "status": "pending",
            "channel": "wechat_native",
            "amount": payload.amount,
            "code_url": (result.data or {}).get("code_url"),
        }

    # bank_transfer：pending 申请 + 收款账户信息，回单后补。
    item = RechargeRequest(
        enterprise_id=principal.enterprise_id, account_type=payload.account,
        insurer=insurer if payload.account == "premium" else None,
        account_id=account_id, amount=payload.amount, receipt_file_url="",
        status="pending",
        created_by=_fallback_created_by(session, principal.enterprise_id))
    session.add(item)
    session.flush()   # review-p2 M3：不 commit，由 ctx.save 与幂等快照单次提交
    # flush 后 item.id 已可用（下面的 recharge_id 依赖它）。
    return {
        "recharge_id": f"bank-{item.id}",
        "status": "pending",
        "channel": "bank_transfer",
        "amount": payload.amount,
        # 往哪转账（AC11）：与企业端页面同一套解析，未配置为 null。
        "payment_account": recharge_payment_account_view(
            session, payload.account, insurer),
        "receipt_upload": f"/openapi/v1/recharges/bank-{item.id}/receipt",
    }


def _get_own_bank_recharge(session: Session, principal: OpenApiPrincipal,
                           recharge_id: str) -> RechargeRequest:
    if not recharge_id.startswith("bank-"):
        raise OpenApiError(404, E_BIZ, "资源不存在")
    try:
        row_id = int(recharge_id[len("bank-"):])
    except ValueError:
        raise OpenApiError(404, E_BIZ, "资源不存在") from None
    item = session.get(RechargeRequest, row_id)
    if item is None or item.enterprise_id != principal.enterprise_id:
        raise OpenApiError(404, E_BIZ, "资源不存在")
    return item


@router.post("/recharges/{recharge_id}/receipt")
async def upload_recharge_receipt(
        recharge_id: str, request: Request,
        filename: Optional[str] = None,
        principal: OpenApiPrincipal = Depends(require_scope("recharge:write")),
        session: Session = Depends(db)):
    """银行转账回单补传：原始字节 body（v2 签名天然覆盖 body，不走 multipart
    ——multipart 的 boundary 每次随机，同一文件两次上传签名串都不同，
    对接方没法预生成签名用例）。"""
    item = _get_own_bank_recharge(session, principal, recharge_id)
    if item.status != "pending":
        raise OpenApiError(422, E_BIZ, "该充值申请已处理，不能再上传回单")
    suffix = Path(filename or "").suffix.lower()
    if suffix not in RECEIPT_SUFFIXES:
        raise OpenApiError(422, E_BIZ, "仅支持 PDF 或图片格式（filename 需带扩展名）")
    content = await request.body()
    if not content:
        raise OpenApiError(422, E_BIZ, "回单内容不能为空")
    if len(content) > RECEIPT_MAX_BYTES:
        raise OpenApiError(422, E_BIZ, "文件不能超过 20MB")
    stored = f"{secrets.token_hex(8)}{suffix}"
    item.receipt_file_url = storage.save_bytes(
        f"recharge-receipts/{item.enterprise_id}/{stored}", content)
    session.commit()
    return ok({"recharge_id": recharge_id, "receipt_uploaded": True},
              request.state.openapi_request_id)


@router.get("/recharges/{recharge_id}")
def get_recharge(
        recharge_id: str, request: Request,
        principal: OpenApiPrincipal = Depends(require_scope("recharge:write")),
        session: Session = Depends(db)):
    rid = request.state.openapi_request_id
    if recharge_id.startswith("bank-"):
        item = _get_own_bank_recharge(session, principal, recharge_id)
        return ok({
            "recharge_id": recharge_id,
            "channel": "bank_transfer",
            "account": item.account_type,
            "amount": item.amount,
            "status": _BANK_STATUS.get(item.status, item.status),
            "reject_reason": item.reject_reason or None,
            "receipt_uploaded": bool(item.receipt_file_url),
            "created_at": item.created_at.isoformat() if item.created_at else None,
        }, rid)
    if recharge_id.startswith("wechat-"):
        order_no = recharge_id[len("wechat-"):]
        row = session.scalar(select(PaymentRecord).where(
            PaymentRecord.order_no == order_no,
            # 显式企业过滤（红线）：他企业订单与不存在同答 404。
            PaymentRecord.enterprise_id == principal.enterprise_id))
        if row is None:
            raise OpenApiError(404, E_BIZ, "资源不存在")
        return ok({
            "recharge_id": recharge_id,
            "channel": "wechat_native",
            "account": row.account,
            "amount": row.amount,
            "status": _WECHAT_STATUS.get(row.status, row.status),
            "paid_at": row.paid_at.isoformat() if row.paid_at else None,
            "created_at": row.created_at.isoformat() if row.created_at else None,
        }, rid)
    raise OpenApiError(404, E_BIZ, "资源不存在")
