import secrets
import tempfile
from pathlib import Path
from typing import Literal

from fastapi import APIRouter, Depends, File, Form, HTTPException, Query, UploadFile
from fastapi.responses import FileResponse, RedirectResponse
from sqlalchemy import select
from sqlalchemy.orm import Session

from ..core import storage
from ..core.audit import audit
from ..core.db import db
from ..core.file_tokens import make_download_token, verify_download_token
from ..core.rbac import require_role
from ..core.security import current_user
from ..models import ActualEmployer, Enterprise, InsurancePlan, InsuredPerson, PositionVideo, User, WorkPosition
from ..schemas import (
    ActualEmployerIn, ActualEmployerUpdate, PositionIn,
    PositionReviewIn, PositionVideoIn, PositionVideoReviewIn,
)
from ..services import (
    allowed_employer_ids,
    assert_employer_access,
    is_enterprise_owner,
    serialize,
)

router = APIRouter(prefix="/api", tags=["positions"])

VIDEO_SUFFIXES = {'.mp4', '.mov', '.m4v'}
VIDEO_SUFFIX_BY_CONTENT_TYPE = {
    'video/mp4': '.mp4',
    'video/quicktime': '.mov',
    'video/x-m4v': '.m4v',
    'video/m4v': '.m4v',
}
MAX_VIDEO_BYTES = 100 * 1024 * 1024


def _video_dict(item: PositionVideo) -> dict:
    token, expires = make_download_token(f"position-video:{item.id}")
    return {**serialize(item), "url": f"/api/positions/{item.position_id}/videos/{item.id}/download?token={token}&expires={expires}"}


def _position_employer_access(
    session: Session, user: User, position: WorkPosition
) -> None:
    if position.actual_employer_id is not None:
        assert_employer_access(session, user, position.actual_employer_id)
        return
    if user.role == "enterprise":
        if position.enterprise_id != user.enterprise_id:
            raise HTTPException(403, "无权访问其他企业岗位")
        if not is_enterprise_owner(user):
            raise HTTPException(403, "历史岗位未关联实际工作单位，项目负责人无权访问")
    elif user.role != "admin":
        raise HTTPException(403, "无权访问岗位")


def _require_employer_master_manager(user: User, enterprise_id: int) -> None:
    if user.role == "admin":
        return
    if not is_enterprise_owner(user) or user.enterprise_id != enterprise_id:
        raise HTTPException(403, "仅企业主管可管理实际工作单位")


@router.get("/positions")
def positions(user: User = Depends(current_user), session: Session = Depends(db)):
    stmt=select(WorkPosition).order_by(WorkPosition.id.desc())
    if user.role == "enterprise" and user.enterprise_id:
        stmt=stmt.where(WorkPosition.enterprise_id==user.enterprise_id)
        allowed=allowed_employer_ids(session,user)
        if allowed is not None: stmt=stmt.where(WorkPosition.actual_employer_id.in_(allowed))
    elif user.role != "admin": raise HTTPException(403,"无权查看岗位")
    result=[]
    for x in session.scalars(stmt):
        item=serialize(x);em=session.get(ActualEmployer,x.actual_employer_id) if x.actual_employer_id else None;plan=session.get(InsurancePlan,x.plan_id) if x.plan_id else None;creator=session.get(User,x.created_by) if x.created_by else None;videos=session.scalars(select(PositionVideo).where(PositionVideo.position_id==x.id).order_by(PositionVideo.id.desc())).all();item['actual_employer_name']=em.name if em else x.actual_employer;item['plan_name']=plan.name if plan else '';item['creator_name']=creator.name if creator else '';item['video_count']=len(videos);item['latest_video_status']=videos[0].status if videos else 'missing';item['review_note']=videos[0].review_note if videos else '';result.append(item)
    return result

@router.get("/actual-employers")
def actual_employers(user:User=Depends(current_user),session:Session=Depends(db)):
    stmt=select(ActualEmployer).order_by(ActualEmployer.id.desc())
    if user.role=='enterprise' and user.enterprise_id:
        stmt=stmt.where(ActualEmployer.enterprise_id==user.enterprise_id)
        allowed=allowed_employer_ids(session,user)
        if allowed is not None: stmt=stmt.where(ActualEmployer.id.in_(allowed))
    elif user.role!='admin': raise HTTPException(403,'无权查看实际工作单位')
    return [serialize(x) for x in session.scalars(stmt)]

@router.post("/actual-employers")
def add_actual_employer(data:ActualEmployerIn,user:User=Depends(current_user),session:Session=Depends(db)):
    eid=user.enterprise_id if user.role=='enterprise' else data.enterprise_id
    if not eid or not session.get(Enterprise,eid): raise HTTPException(400,'请指定有效投保单位')
    _require_employer_master_manager(user,eid)
    item=ActualEmployer(enterprise_id=eid,name=data.name,credit_code=data.credit_code,contact=data.contact,phone=data.phone);session.add(item);session.commit();session.refresh(item);audit(session,user,'create','actual_employer',str(item.id));return serialize(item)

@router.patch("/actual-employers/{item_id}")
def update_actual_employer(item_id:int,data:ActualEmployerUpdate,user:User=Depends(current_user),session:Session=Depends(db)):
    item=session.get(ActualEmployer,item_id)
    if not item: raise HTTPException(404,'实际工作单位不存在')
    _require_employer_master_manager(user,item.enterprise_id)
    for key,value in data.model_dump(exclude_unset=True).items():
        if value is not None: setattr(item,key,value.strip() if isinstance(value,str) else value)
    for position in session.scalars(select(WorkPosition).where(WorkPosition.actual_employer_id==item.id)):
        position.actual_employer=item.name
    session.commit();audit(session,user,'update','actual_employer',str(item.id));return serialize(item)

@router.delete("/actual-employers/{item_id}")
def delete_actual_employer(item_id:int,user:User=Depends(current_user),session:Session=Depends(db)):
    item=session.get(ActualEmployer,item_id)
    if not item: raise HTTPException(404,'实际工作单位不存在')
    _require_employer_master_manager(user,item.enterprise_id)
    if session.scalar(select(WorkPosition.id).where(WorkPosition.actual_employer_id==item_id).limit(1)): raise HTTPException(409,'该工作单位已关联岗位，不能删除；可先暂停使用')
    session.delete(item);session.commit();audit(session,user,'delete','actual_employer',str(item_id));return {'ok':True}

@router.patch("/actual-employers/{item_id}/status")
def actual_employer_status(item_id:int,status_value:Literal['active','paused']=Query(...,alias='status'),user:User=Depends(current_user),session:Session=Depends(db)):
    item=session.get(ActualEmployer,item_id)
    if not item: raise HTTPException(404,'实际用工单位不存在')
    _require_employer_master_manager(user,item.enterprise_id)
    item.status=status_value;session.commit();audit(session,user,'status_change','actual_employer',str(item.id),status_value);return serialize(item)

@router.get("/positions/{item_id}/videos")
def position_videos(item_id:int,user:User=Depends(current_user),session:Session=Depends(db)):
    pos=session.get(WorkPosition,item_id)
    if not pos: raise HTTPException(404,'岗位不存在')
    _position_employer_access(session,user,pos)
    return [_video_dict(x) for x in session.scalars(select(PositionVideo).where(PositionVideo.position_id==item_id).order_by(PositionVideo.id.desc()))]

@router.post("/positions/{item_id}/videos")
def add_position_video(item_id:int,data:PositionVideoIn,user:User=Depends(current_user),session:Session=Depends(db)):
    pos=session.get(WorkPosition,item_id)
    if not pos: raise HTTPException(404,'岗位不存在')
    _position_employer_access(session,user,pos)
    item=PositionVideo(position_id=item_id,**data.model_dump());session.add(item);pos.status='pending';pos.occupation_class='待定';pos.plan_id=None;session.commit();session.refresh(item);audit(session,user,'upload','position_video',str(item.id));return _video_dict(item)

@router.post("/positions/{item_id}/videos/upload")
async def upload_position_video(item_id:int,file:UploadFile=File(...),file_ext:str=Form(''),user:User=Depends(current_user),session:Session=Depends(db)):
    pos=session.get(WorkPosition,item_id)
    if not pos: raise HTTPException(404,'岗位不存在')
    _position_employer_access(session,user,pos)
    suffix=Path(file.filename or '').suffix.lower()
    requested_suffix=f'.{file_ext.strip().lower().lstrip(".")}' if file_ext.strip() else ''
    if suffix not in VIDEO_SUFFIXES:
        suffix=VIDEO_SUFFIX_BY_CONTENT_TYPE.get((file.content_type or '').split(';',1)[0].lower(), requested_suffix)
    if suffix not in VIDEO_SUFFIXES: raise HTTPException(400,'无法识别视频格式，请选择 MP4、MOV 或 M4V 视频')
    stored=f'{secrets.token_hex(8)}{suffix}'
    size=0
    spool=tempfile.SpooledTemporaryFile(max_size=8*1024*1024)
    try:
        while chunk:=await file.read(1024*1024):
            size+=len(chunk)
            if size>MAX_VIDEO_BYTES: raise HTTPException(400,'岗位视频不能超过 100MB')
            spool.write(chunk)
        if size==0: raise HTTPException(400,'视频文件为空，请重新选择')
        url=storage.save_fileobj(f'positions/{item_id}/{stored}',spool)
    finally:
        spool.close()
    item=PositionVideo(position_id=item_id,name=file.filename or stored,url=url,status='pending');session.add(item);pos.status='pending';pos.occupation_class='待定';pos.plan_id=None;session.commit();session.refresh(item);audit(session,user,'upload','position_video',str(item.id));return _video_dict(item)

@router.get("/positions/{item_id}/videos/{video_id}/download")
def download_position_video(item_id:int,video_id:int,token:str,expires:int,session:Session=Depends(db)):
    # Short-lived signed link (see core/file_tokens.py) — intentionally not
    # behind Depends(current_user): the token itself, minted only for an
    # already-authenticated request to GET /positions/{id}/videos, is the
    # credential, so plain <video src> / wx.downloadFile work unchanged.
    if not verify_download_token(f"position-video:{video_id}", expires, token):
        raise HTTPException(403, "下载链接无效或已过期")
    video=session.get(PositionVideo,video_id)
    if not video or video.position_id!=item_id: raise HTTPException(404,'岗位视频不存在')
    resolved=storage.resolve(video.url)
    if not resolved: raise HTTPException(404,'文件不存在')
    kind,ref=resolved
    return RedirectResponse(ref) if kind=='redirect' else FileResponse(ref)

@router.patch("/position-videos/{item_id}/review", dependencies=[Depends(require_role("admin", detail="仅平台端可审核岗位视频"))])
def review_position_video(item_id:int,data:PositionVideoReviewIn,user:User=Depends(current_user),session:Session=Depends(db)):
    item=session.get(PositionVideo,item_id)
    if not item: raise HTTPException(404,'岗位视频不存在')
    item.status=data.status;item.review_note=data.review_note;session.commit();audit(session,user,'review','position_video',str(item.id),data.status);return serialize(item)

@router.delete("/position-videos/{item_id}", dependencies=[Depends(require_role("admin", detail="仅平台端可删除岗位视频"))])
def delete_position_video(item_id:int,user:User=Depends(current_user),session:Session=Depends(db)):
    item=session.get(PositionVideo,item_id)
    if not item: raise HTTPException(404,'岗位视频不存在')
    storage.delete(item.url)
    position=session.get(WorkPosition,item.position_id)
    session.delete(item);session.flush()
    remaining=session.scalar(select(PositionVideo.id).where(PositionVideo.position_id==item.position_id).limit(1))
    if position and not remaining:
        position.status='pending';position.occupation_class='待定';position.plan_id=None
    session.commit();audit(session,user,'delete','position_video',str(item_id));return {'ok':True}

@router.patch("/positions/{item_id}/review", dependencies=[Depends(require_role("admin", detail="仅平台端可确定岗位职业类别"))])
def review_position(item_id:int,data:PositionReviewIn,user:User=Depends(current_user),session:Session=Depends(db)):
    item=session.get(WorkPosition,item_id)
    if not item: raise HTTPException(404,'岗位不存在')
    videos=session.scalars(select(PositionVideo).where(PositionVideo.position_id==item_id).order_by(PositionVideo.id.desc())).all()
    if data.status=='approved' and not videos: raise HTTPException(400,'岗位视频上传后才能完成定类')
    if data.status=='approved' and not data.occupation_class: raise HTTPException(400,'请选择岗位职业类别')
    if data.status in {'supplement','rejected'} and not data.review_note.strip(): raise HTTPException(400,'补件或驳回时必须填写审核意见')
    if data.plan_id is not None and not session.get(InsurancePlan,data.plan_id): raise HTTPException(400,'投保方案不存在')
    if data.occupation_class: item.occupation_class=data.occupation_class
    item.plan_id=data.plan_id;item.status=data.status
    if videos:
        videos[0].status=data.status;videos[0].review_note=data.review_note
    session.commit();audit(session,user,'review','position',str(item.id),f'{data.status}:{item.occupation_class}');return serialize(item)

@router.post("/positions")
def add_position(data: PositionIn, user: User = Depends(current_user), session: Session = Depends(db)):
    target_enterprise = user.enterprise_id if user.role == "enterprise" else data.enterprise_id
    if not target_enterprise or not session.get(Enterprise, target_enterprise): raise HTTPException(400,"请先绑定有效投保单位")
    employer=session.get(ActualEmployer,data.actual_employer_id) if data.actual_employer_id else None
    if not employer or employer.enterprise_id!=target_enterprise: raise HTTPException(400,"请选择本企业添加的有效实际工作单位")
    assert_employer_access(session,user,employer.id)
    if employer.status!='active': raise HTTPException(400,"该工作单位已暂停，不能新增岗位")
    item=WorkPosition(enterprise_id=target_enterprise,actual_employer_id=employer.id,actual_employer=employer.name,name=data.name,occupation_class='待定' if user.role=='enterprise' else data.occupation_class,plan_id=None if user.role=='enterprise' else data.plan_id,status='pending',created_by=user.id)
    session.add(item);session.commit();session.refresh(item);audit(session,user,"create","position",str(item.id));return serialize(item)

@router.patch("/positions/{item_id}")
def update_position(item_id:int,data:PositionIn,user:User=Depends(current_user),session:Session=Depends(db)):
    item=session.get(WorkPosition,item_id)
    if not item: raise HTTPException(404,"岗位不存在")
    _position_employer_access(session,user,item)
    employer=session.get(ActualEmployer,data.actual_employer_id) if data.actual_employer_id else None
    if not employer or employer.enterprise_id!=item.enterprise_id: raise HTTPException(400,"请选择本企业添加的有效实际工作单位")
    assert_employer_access(session,user,employer.id)
    item.actual_employer_id=employer.id;item.actual_employer=employer.name;item.name=data.name
    if user.role=='enterprise':
        item.occupation_class='待定';item.plan_id=None;item.status='pending'
    else:
        item.occupation_class=data.occupation_class;item.plan_id=data.plan_id
    session.commit();audit(session,user,"update","position",str(item.id));return serialize(item)

@router.delete("/positions/{item_id}")
def delete_position(item_id:int,user:User=Depends(current_user),session:Session=Depends(db)):
    item=session.get(WorkPosition,item_id)
    if not item: raise HTTPException(404,"岗位不存在")
    _position_employer_access(session,user,item)
    if session.scalar(select(InsuredPerson.id).where(InsuredPerson.position_id==item_id).limit(1)): raise HTTPException(409,'该岗位已关联参保员工，不能删除')
    for video in session.scalars(select(PositionVideo).where(PositionVideo.position_id==item_id)):
        session.delete(video)
    session.delete(item);session.commit();audit(session,user,"delete","position",str(item_id));return {"ok":True}
