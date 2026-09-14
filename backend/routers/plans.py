import secrets
from pathlib import Path
from typing import Optional

from fastapi import APIRouter, Depends, File, HTTPException, Query, UploadFile
from fastapi.responses import FileResponse, RedirectResponse
from sqlalchemy import select
from sqlalchemy.orm import Session

from ..core import storage
from ..core.audit import audit
from ..core.db import db
from ..core.file_tokens import make_download_token, verify_download_token
from ..core.rbac import require_role
from ..core.security import current_user
from ..models import AgentCommission, InsurancePlan, PlanTier, Policy, User
from ..schemas import PlanIn, PlanTierIn, PlanUpdate
from ..services import enterprise_selectable_plan_ids, plan_dict, pricing_snapshot, serialize, strip_internal_pricing

router = APIRouter(prefix="/api", tags=["plans"])


@router.get("/plans")
def plans(user: User = Depends(current_user), session: Session = Depends(db)):
    stmt = select(InsurancePlan).order_by(InsurancePlan.id.desc())
    if user.role == "enterprise" and user.enterprise_id:
        allowed_ids = enterprise_selectable_plan_ids(session, user.enterprise_id)
        stmt = stmt.where(InsurancePlan.id.in_(allowed_ids)) if allowed_ids else stmt.where(InsurancePlan.id.is_(None))
        items = session.scalars(stmt).all()
        relations = {}
        for r in session.scalars(select(AgentCommission).where(AgentCommission.enterprise_id == user.enterprise_id, AgentCommission.status == "active").order_by(AgentCommission.id.asc())):
            relations[r.plan_id] = r
        return [strip_internal_pricing(plan_dict(x, relations.get(x.id)), user) for x in items]
    return [strip_internal_pricing(plan_dict(x), user) for x in session.scalars(stmt)]

@router.post("/plans")
def add_plan(data: PlanIn, user: User = Depends(current_user), session: Session = Depends(db)):
    # NOTE: role check stays inline (not a dependencies=[require_role(...)]) because
    # tests/system_smoke.py calls this function directly and asserts on the 403 it raises.
    if user.role != "admin": raise HTTPException(403,"仅总后台可新增保险方案")
    values=data.model_dump()
    if values['effective_mode']=='immediate': values['billing_mode']='daily'
    item = InsurancePlan(**values); session.add(item); session.commit(); session.refresh(item); audit(session, user, "create", "plan", str(item.id)); return plan_dict(item)

@router.get("/plan-tiers")
def plan_tiers(plan_id: Optional[int] = None, user: User = Depends(current_user), session: Session = Depends(db)):
    stmt=select(PlanTier).order_by(PlanTier.id.desc())
    relations = {}
    if user.role=='enterprise' and user.enterprise_id:
        allowed_ids = enterprise_selectable_plan_ids(session, user.enterprise_id)
        stmt = stmt.where(PlanTier.plan_id.in_(allowed_ids)) if allowed_ids else stmt.where(PlanTier.id.is_(None))
        for r in session.scalars(select(AgentCommission).where(AgentCommission.enterprise_id == user.enterprise_id, AgentCommission.status == "active").order_by(AgentCommission.id.asc())):
            relations[r.plan_id] = r
    if plan_id: stmt=stmt.where(PlanTier.plan_id==plan_id)
    items = session.scalars(stmt).all()
    plans_by_id = {p.id: p for p in session.scalars(select(InsurancePlan).where(InsurancePlan.id.in_({x.plan_id for x in items})))} if items else {}
    result = []
    for tier in items:
        plan = plans_by_id.get(tier.plan_id)
        pricing = pricing_snapshot(plan, relations.get(tier.plan_id), float(tier.price or 0)) if plan else {}
        result.append(strip_internal_pricing({**serialize(tier), **pricing}, user))
    return result

@router.post("/plan-tiers", dependencies=[Depends(require_role("admin", detail="仅总后台可维护类别价格"))])
def add_plan_tier(data: PlanTierIn, user: User = Depends(current_user), session: Session = Depends(db)):
    if not session.get(InsurancePlan,data.plan_id): raise HTTPException(404,"保险方案不存在")
    item=PlanTier(**data.model_dump());session.add(item);session.commit();session.refresh(item);audit(session,user,"create","plan_tier",str(item.id));return serialize(item)

@router.patch("/plans/{item_id}/status", dependencies=[Depends(require_role("admin", detail="仅总后台可维护保险方案"))])
def plan_status(item_id: int, status_value: str = Query(..., alias="status"), user: User = Depends(current_user), session: Session = Depends(db)):
    item = session.get(InsurancePlan, item_id)
    if not item: raise HTTPException(404, "方案不存在")
    if status_value not in {"active", "paused"}: raise HTTPException(400, "状态不合法")
    item.status = status_value; session.commit(); audit(session, user, "status_change", "plan", str(item.id), status_value); return serialize(item)

@router.patch("/plans/{item_id}", dependencies=[Depends(require_role("admin", detail="仅总后台可维护保险方案"))])
def update_plan(item_id: int, data: PlanUpdate, user: User = Depends(current_user), session: Session = Depends(db)):
    item = session.get(InsurancePlan, item_id)
    if not item: raise HTTPException(404, "方案不存在")
    values=data.model_dump(exclude_unset=True)
    if values.get('effective_mode')=='immediate' or (item.effective_mode=='immediate' and values.get('effective_mode') is None): values['billing_mode']='daily'
    for key, value in values.items():
        if value is not None: setattr(item, key, value)
    session.commit(); audit(session, user, "update", "plan", str(item.id)); return plan_dict(item)

@router.delete("/plans/{item_id}", dependencies=[Depends(require_role("admin", detail="仅总后台可删除保险方案"))])
def delete_plan(item_id: int, user: User = Depends(current_user), session: Session = Depends(db)):
    item = session.get(InsurancePlan, item_id)
    if not item: raise HTTPException(404, "方案不存在")
    used = session.scalar(select(Policy.id).where(Policy.plan_id == item_id).limit(1))
    if used: raise HTTPException(409, "该方案已有参保人员或保单使用，不能删除；请先暂停方案")
    if item.image_url: storage.delete(item.image_url)
    session.delete(item); session.commit(); audit(session, user, "delete", "plan", str(item_id)); return {"ok": True, "deleted_id": item_id}


# ---- 方案图片（保障彩页）----
# 上传仅平台；查看/下载走「登录换短时签名URL → 凭签名取文件」两跳，
# 跟发票/保单文件同一套姿势（SYSTEM-DESIGN §11.1，不静态挂载）。

_PLAN_IMAGE_EXTENSIONS = {".png", ".jpg", ".jpeg", ".webp"}
_PLAN_IMAGE_MAX_BYTES = 10 * 1024 * 1024
_PLAN_IMAGE_MEDIA = {".png": "image/png", ".jpg": "image/jpeg", ".jpeg": "image/jpeg", ".webp": "image/webp"}


@router.post("/plans/{item_id}/image", dependencies=[Depends(require_role("admin", detail="仅总后台可上传方案图片"))])
async def upload_plan_image(item_id: int, file: UploadFile = File(...), user: User = Depends(current_user), session: Session = Depends(db)):
    item = session.get(InsurancePlan, item_id)
    if not item: raise HTTPException(404, "方案不存在")
    suffix = Path(file.filename or "").suffix.lower()
    if suffix not in _PLAN_IMAGE_EXTENSIONS: raise HTTPException(400, "仅支持 png/jpg/webp 图片")
    content = await file.read()
    if len(content) > _PLAN_IMAGE_MAX_BYTES: raise HTTPException(400, "图片不能超过 10MB")
    if not content: raise HTTPException(400, "文件为空")
    old = item.image_url
    item.image_url = storage.save_bytes(f"plans/{item_id}/{secrets.token_hex(8)}{suffix}", content)
    item.image_name = file.filename or f"plan-{item_id}{suffix}"
    session.commit()
    if old: storage.delete(old)
    audit(session, user, "upload", "plan_image", str(item_id))
    return plan_dict(item)


@router.delete("/plans/{item_id}/image", dependencies=[Depends(require_role("admin", detail="仅总后台可删除方案图片"))])
def delete_plan_image(item_id: int, user: User = Depends(current_user), session: Session = Depends(db)):
    item = session.get(InsurancePlan, item_id)
    if not item: raise HTTPException(404, "方案不存在")
    if item.image_url: storage.delete(item.image_url)
    item.image_url = ""; item.image_name = ""
    session.commit(); audit(session, user, "delete", "plan_image", str(item_id))
    return plan_dict(item)


@router.get("/plans/{item_id}/image-link")
def plan_image_link(item_id: int, user: User = Depends(current_user), session: Session = Depends(db)):
    item = session.get(InsurancePlan, item_id)
    if not item or not item.image_url: raise HTTPException(404, "方案图片不存在")
    if user.role == "enterprise":
        if not user.enterprise_id: raise HTTPException(403, "无权查看该方案图片")
        allowed = enterprise_selectable_plan_ids(session, user.enterprise_id)
        if item_id not in (allowed or []): raise HTTPException(403, "无权查看该方案图片")
    token, expires = make_download_token(f"plan-image:{item_id}")
    return {
        "url": f"/api/plans/{item_id}/image/download?token={token}&expires={expires}",
        "image_name": item.image_name,
        "expires": expires,
    }


@router.get("/plans/{item_id}/image/download")
def download_plan_image(item_id: int, token: str, expires: int, download: int = 0, session: Session = Depends(db)):
    if not verify_download_token(f"plan-image:{item_id}", expires, token): raise HTTPException(403, "链接无效或已过期")
    item = session.get(InsurancePlan, item_id)
    if not item or not item.image_url: raise HTTPException(404, "方案图片不存在")
    resolved = storage.resolve(item.image_url, filename=item.image_name or None)
    if not resolved: raise HTTPException(404, "文件不存在")
    kind, ref = resolved
    if kind == "redirect": return RedirectResponse(ref)
    media_type = _PLAN_IMAGE_MEDIA.get(Path(str(ref)).suffix.lower(), "application/octet-stream")
    # 默认 inline 在线查看；download=1 时带文件名走附件下载
    if download: return FileResponse(ref, media_type=media_type, filename=item.image_name or None)
    return FileResponse(ref, media_type=media_type)
