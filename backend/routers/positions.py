import secrets
from pathlib import Path
from typing import Literal

from fastapi import APIRouter, Depends, File, HTTPException, Query, UploadFile
from sqlalchemy import select
from sqlalchemy.orm import Session

from ..core.audit import audit
from ..core.config import ROOT
from ..core.db import db
from ..core.rbac import require_role
from ..core.security import current_user
from ..models import ActualEmployer, Enterprise, InsurancePlan, InsuredPerson, PositionVideo, User, WorkPosition
from ..schemas import (
    ActualEmployerIn, ActualEmployerUpdate, PositionIn,
    PositionReviewIn, PositionVideoIn, PositionVideoReviewIn,
)
from ..services import serialize

router = APIRouter(prefix="/api", tags=["positions"])


@router.get("/positions")
def positions(user: User = Depends(current_user), session: Session = Depends(db)):
    stmt=select(WorkPosition).order_by(WorkPosition.id.desc())
    if user.role == "enterprise" and user.enterprise_id: stmt=stmt.where(WorkPosition.enterprise_id==user.enterprise_id)
    result=[]
    for x in session.scalars(stmt):
        item=serialize(x);em=session.get(ActualEmployer,x.actual_employer_id) if x.actual_employer_id else None;videos=session.scalars(select(PositionVideo).where(PositionVideo.position_id==x.id).order_by(PositionVideo.id.desc())).all();item['actual_employer_name']=em.name if em else x.actual_employer;item['video_count']=len(videos);item['latest_video_status']=videos[0].status if videos else 'missing';item['review_note']=videos[0].review_note if videos else '';result.append(item)
    return result

@router.get("/actual-employers")
def actual_employers(user:User=Depends(current_user),session:Session=Depends(db)):
    stmt=select(ActualEmployer).order_by(ActualEmployer.id.desc())
    if user.role=='enterprise' and user.enterprise_id: stmt=stmt.where(ActualEmployer.enterprise_id==user.enterprise_id)
    return [serialize(x) for x in session.scalars(stmt)]

@router.post("/actual-employers")
def add_actual_employer(data:ActualEmployerIn,user:User=Depends(current_user),session:Session=Depends(db)):
    eid=user.enterprise_id if user.role=='enterprise' else data.enterprise_id
    if not eid or not session.get(Enterprise,eid): raise HTTPException(400,'请指定有效投保单位')
    item=ActualEmployer(enterprise_id=eid,name=data.name,credit_code=data.credit_code,contact=data.contact,phone=data.phone);session.add(item);session.commit();session.refresh(item);audit(session,user,'create','actual_employer',str(item.id));return serialize(item)

@router.patch("/actual-employers/{item_id}")
def update_actual_employer(item_id:int,data:ActualEmployerUpdate,user:User=Depends(current_user),session:Session=Depends(db)):
    item=session.get(ActualEmployer,item_id)
    if not item: raise HTTPException(404,'实际工作单位不存在')
    if user.role=='enterprise' and user.enterprise_id!=item.enterprise_id: raise HTTPException(403,'无权操作')
    if user.role not in {'admin','enterprise'}: raise HTTPException(403,'无权操作')
    for key,value in data.model_dump(exclude_unset=True).items():
        if value is not None: setattr(item,key,value.strip() if isinstance(value,str) else value)
    for position in session.scalars(select(WorkPosition).where(WorkPosition.actual_employer_id==item.id)):
        position.actual_employer=item.name
    session.commit();audit(session,user,'update','actual_employer',str(item.id));return serialize(item)

@router.delete("/actual-employers/{item_id}")
def delete_actual_employer(item_id:int,user:User=Depends(current_user),session:Session=Depends(db)):
    item=session.get(ActualEmployer,item_id)
    if not item: raise HTTPException(404,'实际工作单位不存在')
    if user.role=='enterprise' and user.enterprise_id!=item.enterprise_id: raise HTTPException(403,'无权操作')
    if user.role not in {'admin','enterprise'}: raise HTTPException(403,'无权操作')
    if session.scalar(select(WorkPosition.id).where(WorkPosition.actual_employer_id==item_id).limit(1)): raise HTTPException(409,'该工作单位已关联岗位，不能删除；可先暂停使用')
    session.delete(item);session.commit();audit(session,user,'delete','actual_employer',str(item_id));return {'ok':True}

@router.patch("/actual-employers/{item_id}/status")
def actual_employer_status(item_id:int,status_value:Literal['active','paused']=Query(...,alias='status'),user:User=Depends(current_user),session:Session=Depends(db)):
    item=session.get(ActualEmployer,item_id)
    if not item: raise HTTPException(404,'实际用工单位不存在')
    if user.role=='enterprise' and user.enterprise_id!=item.enterprise_id: raise HTTPException(403,'无权操作')
    item.status=status_value;session.commit();audit(session,user,'status_change','actual_employer',str(item.id),status_value);return serialize(item)

@router.get("/positions/{item_id}/videos")
def position_videos(item_id:int,user:User=Depends(current_user),session:Session=Depends(db)):
    pos=session.get(WorkPosition,item_id)
    if not pos: raise HTTPException(404,'岗位不存在')
    if user.role=='enterprise' and user.enterprise_id!=pos.enterprise_id: raise HTTPException(403,'无权查看')
    return [serialize(x) for x in session.scalars(select(PositionVideo).where(PositionVideo.position_id==item_id).order_by(PositionVideo.id.desc()))]

@router.post("/positions/{item_id}/videos")
def add_position_video(item_id:int,data:PositionVideoIn,user:User=Depends(current_user),session:Session=Depends(db)):
    pos=session.get(WorkPosition,item_id)
    if not pos: raise HTTPException(404,'岗位不存在')
    if user.role=='enterprise' and user.enterprise_id!=pos.enterprise_id: raise HTTPException(403,'无权上传')
    item=PositionVideo(position_id=item_id,**data.model_dump());session.add(item);pos.status='pending';pos.occupation_class='待定';pos.plan_id=None;session.commit();session.refresh(item);audit(session,user,'upload','position_video',str(item.id));return serialize(item)

@router.post("/positions/{item_id}/videos/upload")
async def upload_position_video(item_id:int,file:UploadFile=File(...),user:User=Depends(current_user),session:Session=Depends(db)):
    pos=session.get(WorkPosition,item_id)
    if not pos: raise HTTPException(404,'岗位不存在')
    if user.role=='enterprise' and user.enterprise_id!=pos.enterprise_id: raise HTTPException(403,'无权上传')
    suffix=Path(file.filename or '').suffix.lower()
    if suffix not in {'.mp4','.mov','.m4v'}: raise HTTPException(400,'仅支持 MP4、MOV 或 M4V 视频')
    content=await file.read()
    if len(content)>100*1024*1024: raise HTTPException(400,'岗位视频不能超过 100MB')
    folder=ROOT/'uploads'/'positions'/str(item_id);folder.mkdir(parents=True,exist_ok=True);stored=f'{secrets.token_hex(8)}{suffix}';(folder/stored).write_bytes(content)
    item=PositionVideo(position_id=item_id,name=file.filename or stored,url=f'/uploads/positions/{item_id}/{stored}',status='pending');session.add(item);pos.status='pending';pos.occupation_class='待定';pos.plan_id=None;session.commit();session.refresh(item);audit(session,user,'upload','position_video',str(item.id));return serialize(item)

@router.patch("/position-videos/{item_id}/review", dependencies=[Depends(require_role("admin", detail="仅平台端可审核岗位视频"))])
def review_position_video(item_id:int,data:PositionVideoReviewIn,user:User=Depends(current_user),session:Session=Depends(db)):
    item=session.get(PositionVideo,item_id)
    if not item: raise HTTPException(404,'岗位视频不存在')
    item.status=data.status;item.review_note=data.review_note;session.commit();audit(session,user,'review','position_video',str(item.id),data.status);return serialize(item)

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
    if employer.status!='active': raise HTTPException(400,"该工作单位已暂停，不能新增岗位")
    item=WorkPosition(enterprise_id=target_enterprise,actual_employer_id=employer.id,actual_employer=employer.name,name=data.name,occupation_class='待定' if user.role=='enterprise' else data.occupation_class,plan_id=None if user.role=='enterprise' else data.plan_id,status='pending')
    session.add(item);session.commit();session.refresh(item);audit(session,user,"create","position",str(item.id));return serialize(item)

@router.patch("/positions/{item_id}")
def update_position(item_id:int,data:PositionIn,user:User=Depends(current_user),session:Session=Depends(db)):
    item=session.get(WorkPosition,item_id)
    if not item: raise HTTPException(404,"岗位不存在")
    if user.role=="enterprise" and item.enterprise_id!=user.enterprise_id: raise HTTPException(403,"无权操作")
    employer=session.get(ActualEmployer,data.actual_employer_id) if data.actual_employer_id else None
    if not employer or employer.enterprise_id!=item.enterprise_id: raise HTTPException(400,"请选择本企业添加的有效实际工作单位")
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
    if user.role=="enterprise" and item.enterprise_id!=user.enterprise_id: raise HTTPException(403,"无权操作")
    if session.scalar(select(InsuredPerson.id).where(InsuredPerson.position_id==item_id).limit(1)): raise HTTPException(409,'该岗位已关联参保员工，不能删除')
    for video in session.scalars(select(PositionVideo).where(PositionVideo.position_id==item_id)):
        session.delete(video)
    session.delete(item);session.commit();audit(session,user,"delete","position",str(item_id));return {"ok":True}
