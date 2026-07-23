import secrets
from datetime import datetime, timedelta
from pathlib import Path
from typing import Optional

from fastapi import APIRouter, Depends, File, Form, HTTPException, Query, UploadFile
from fastapi.responses import FileResponse, RedirectResponse

from sqlalchemy import select
from sqlalchemy.orm import Session

from ..core.audit import audit
from ..core import storage
from ..core.db import db
from ..core.file_tokens import make_download_token, verify_download_token
from ..core.rbac import require_role
from ..core.security import current_user
from ..models import Claim, ClaimDocument, ClaimTimeline, InsuredPerson, Policy, User
from ..schemas import ClaimDocumentIn, ClaimDocumentReviewIn, ClaimIn, ClaimStatusIn, ClaimUpdate
from ..services import allowed_employer_ids, serialize
from ..services.claims import (
    CLAIM_REQUIRED_DOCS, CLAIM_REQUIRED_TYPES, CLAIM_TRANSITIONS, _INSURER_VISIBLE_CLAIM_STATUSES,
    claim_access, claim_payload, person_claim_access, prepare_claim_upload,
)
from ..services.insurer_scope import claim_insurer_id

router = APIRouter(prefix="/api", tags=["claims"])


def _document_dict(item: ClaimDocument) -> dict:
    token, expires = make_download_token(f"claim-document:{item.id}")
    return {**serialize(item), "url": f"/api/claims/{item.claim_id}/documents/{item.id}/download?token={token}&expires={expires}"}


@router.get("/claims")
def claims(q:str="",status_filter:Optional[str]=Query(default=None,alias='status'),risk:Optional[str]=None,enterprise_id:Optional[int]=None,user: User = Depends(current_user), session: Session = Depends(db)):
    stmt=select(Claim).order_by(Claim.id.desc())
    if user.role=='enterprise' and user.enterprise_id:
        stmt=stmt.where(Claim.enterprise_id==user.enterprise_id)
        allowed=allowed_employer_ids(session,user)
        if allowed is not None:
            from ..models import WorkPosition
            stmt=stmt.join(InsuredPerson,Claim.person_id==InsuredPerson.id).join(WorkPosition,InsuredPerson.position_id==WorkPosition.id).where(WorkPosition.actual_employer_id.in_(allowed))
    elif enterprise_id: stmt=stmt.where(Claim.enterprise_id==enterprise_id)
    if user.role not in {'admin','enterprise','insurer'}: raise HTTPException(403,'无权查看理赔案件')
    if status_filter: stmt=stmt.where(Claim.status==status_filter)
    rows=[claim_payload(item,session) for item in session.scalars(stmt)]
    if user.role=='insurer':
        rows=[row for row in rows if row['id'] in {
            item.id for item in session.scalars(select(Claim)) if item.status in _INSURER_VISIBLE_CLAIM_STATUSES and claim_insurer_id(item,session)==user.insurer_id
        }]
    if q:
        needle=q.lower();rows=[item for item in rows if needle in f"{item['claim_no']}{item['person_name']}{item['enterprise_name']}{item['actual_employer_name']}".lower()]
    if risk: rows=[item for item in rows if item['calculated_risk']==risk]
    return rows

@router.get("/claims/{item_id}")
def claim_detail(item_id:int,user:User=Depends(current_user),session:Session=Depends(db)):
    item=session.get(Claim,item_id)
    if not item: raise HTTPException(404,'理赔案件不存在')
    claim_access(item,user,session);return claim_payload(item,session)

@router.post("/claims")
def add_claim(data: ClaimIn, user: User = Depends(current_user), session: Session = Depends(db)):
    if user.role == "enterprise" and user.enterprise_id != data.enterprise_id: raise HTTPException(403,"无权提交该单位理赔")
    person=session.get(InsuredPerson,data.person_id)
    if not person or person.enterprise_id!=data.enterprise_id: raise HTTPException(400,'被保险人不属于该投保单位')
    person_claim_access(person,user,session)
    if person.status!='active': raise HTTPException(409,'只能为当前在保员工提交工伤报案')
    if person.policy_id:
        policy=session.get(Policy,person.policy_id)
        if not policy or policy.status!='active': raise HTTPException(409,'被保险人当前保单无效，请先核对保单')
    if data.policy_id is not None:
        chosen_policy=session.get(Policy,data.policy_id)
        if not chosen_policy or chosen_policy.enterprise_id!=data.enterprise_id: raise HTTPException(400,'保单号无效或不属于该投保单位')
    try: deadline=(datetime.strptime(data.accident_at[:10],'%Y-%m-%d')+timedelta(days=30)).strftime('%Y-%m-%d')
    except Exception: deadline=''
    sla_deadline=(datetime.now()+timedelta(days=2)).strftime('%Y-%m-%d %H:%M')
    item = Claim(**data.model_dump(),deadline=deadline,sla_deadline=sla_deadline,current_handler='企业经办人',claim_no=f"CLM-{datetime.now().strftime('%Y%m%d')}-{secrets.token_hex(3).upper()}"); session.add(item);session.flush();session.add(ClaimTimeline(claim_id=item.id,node='reported',action='提交工伤报案',note=data.description,operator=user.name));session.commit();session.refresh(item);audit(session, user, "create", "claim", str(item.id));return claim_payload(item,session)

@router.patch("/claims/{item_id}")
def update_claim(item_id:int,data:ClaimUpdate,user:User=Depends(current_user),session:Session=Depends(db)):
    item=session.get(Claim,item_id)
    if not item: raise HTTPException(404,'理赔案件不存在')
    claim_access(item,user,session);values=data.model_dump(exclude_unset=True)
    if user.role=='enterprise':
        if item.status not in {'reported','collecting','supplement'}: raise HTTPException(409,'当前节点不允许企业修改报案信息')
        allowed={'description','hospital','diagnosis','injury_part','payee_type','medical_cost','amount','contact_name','contact_phone'}
        if set(values)-allowed: raise HTTPException(403,'保司报案号、SLA、风险和审核意见仅平台可修改')
    for key,value in values.items():
        if value is not None:setattr(item,key,value)
    session.commit();audit(session,user,'update','claim',str(item.id));return serialize(item)

@router.patch("/claims/{item_id}/status")
def claim_status(item_id:int,data:ClaimStatusIn,user:User=Depends(current_user),session:Session=Depends(db)):
    item=session.get(Claim,item_id)
    if not item: raise HTTPException(404,"理赔案件不存在")
    claim_access(item,user,session)
    if data.status not in CLAIM_TRANSITIONS.get(item.status,set()): raise HTTPException(409,f'案件不能从 {item.status} 变更为 {data.status}')
    if user.role=='enterprise' and data.status!='submitted': raise HTTPException(403,'该节点需由平台理赔人员处理')
    if user.role=='insurer':
        if item.status!='insurer_review': raise HTTPException(403,'保司只能处理保司审核中的案件')
        if data.status not in {'approved','rejected','supplement'}: raise HTTPException(403,'保司只能核赔通过、拒赔或打回补件')
        if claim_insurer_id(item,session)!=user.insurer_id: raise HTTPException(403,'无权操作其他保险公司的理赔案件')
    if data.status=='submitted':
        uploaded={x.doc_type for x in session.scalars(select(ClaimDocument).where(ClaimDocument.claim_id==item.id,ClaimDocument.status.in_(['uploaded','accepted'])))};missing=CLAIM_REQUIRED_TYPES-uploaded
        if missing: raise HTTPException(409,f'材料未齐全，还缺少 {len(missing)} 项')
    if data.status=='insurer_review' and not (data.insurer_report_no or item.insurer_report_no): raise HTTPException(409,'请先登记保司报案号')
    if data.status=='approved' and data.approved_amount is None: raise HTTPException(409,'核赔通过时必须登记核赔金额')
    if data.status=='rejected' and not (data.rejection_reason or data.note): raise HTTPException(409,'拒赔时必须填写拒赔原因')
    item.status=data.status
    if data.approved_amount is not None:item.approved_amount=data.approved_amount
    if data.insurer_report_no is not None:item.insurer_report_no=data.insurer_report_no
    if data.rejection_reason is not None:item.rejection_reason=data.rejection_reason
    if data.sla_deadline is not None:item.sla_deadline=data.sla_deadline
    if data.note and user.role=='admin':item.review_note=data.note
    default_handlers={'submitted':'平台理赔专员','insurer_review':'保险公司理赔岗','supplement':'企业经办人','approved':'平台财务','paid':'平台理赔专员','rejected':'平台理赔专员','closed':'已归档'};item.current_handler=data.current_handler or default_handlers.get(data.status,item.current_handler)
    if data.status=='paid':item.paid_at=data.paid_at or datetime.now().strftime('%Y-%m-%d %H:%M')
    session.add(ClaimTimeline(claim_id=item.id,node=data.status,action='理赔状态变更',note=data.note,operator=user.name));session.commit();audit(session,user,"status_change","claim",str(item.id),data.status);return serialize(item)

@router.get("/claims/{item_id}/documents")
def claim_documents(item_id:int,user:User=Depends(current_user),session:Session=Depends(db)):
    item=session.get(Claim,item_id)
    if not item: raise HTTPException(404,"理赔案件不存在")
    claim_access(item,user,session)
    return [_document_dict(x) for x in session.scalars(select(ClaimDocument).where(ClaimDocument.claim_id==item_id).order_by(ClaimDocument.id.desc()))]

@router.post("/claims/{item_id}/documents")
def add_claim_document(item_id:int,data:ClaimDocumentIn,user:User=Depends(current_user),session:Session=Depends(db)):
    item=session.get(Claim,item_id)
    if not item: raise HTTPException(404,"理赔案件不存在")
    prepare_claim_upload(item,user,session)
    doc=ClaimDocument(claim_id=item_id,**data.model_dump());session.add(doc);session.flush();session.add(ClaimTimeline(claim_id=item_id,node=item.status,action=f'上传材料：{data.name}',note=data.doc_type,operator=user.name));session.commit();session.refresh(doc);audit(session,user,"upload","claim_document",str(doc.id));return _document_dict(doc)

@router.post("/claims/{item_id}/documents/upload")
async def upload_claim_document(item_id:int,doc_type:str=Form('other'),file:UploadFile=File(...),user:User=Depends(current_user),session:Session=Depends(db)):
    item=session.get(Claim,item_id)
    if not item: raise HTTPException(404,'理赔案件不存在')
    prepare_claim_upload(item,user,session)
    suffix=Path(file.filename or '').suffix.lower()
    if suffix not in {'.jpg','.jpeg','.png','.heic','.pdf','.doc','.docx','.xls','.xlsx'}: raise HTTPException(400,'仅支持图片、PDF、Word、Excel材料')
    content=await file.read()
    if len(content)>20*1024*1024: raise HTTPException(400,'单个材料不能超过20MB')
    stored=f'{secrets.token_hex(8)}{suffix}';url=storage.save_bytes(f'claims/{item_id}/{stored}',content)
    doc=ClaimDocument(claim_id=item_id,name=file.filename or stored,url=url,doc_type=doc_type);session.add(doc);session.flush();session.add(ClaimTimeline(claim_id=item_id,node=item.status,action=f'上传材料：{doc.name}',note=doc_type,operator=user.name));session.commit();session.refresh(doc);audit(session,user,'upload','claim_document',str(doc.id));return _document_dict(doc)

@router.get("/claims/{item_id}/documents/{document_id}/download")
def download_claim_document(item_id:int,document_id:int,token:str,expires:int,session:Session=Depends(db)):
    # Short-lived signed link, same pattern as download_position_video in
    # routers/positions.py — see core/file_tokens.py for the rationale.
    if not verify_download_token(f"claim-document:{document_id}", expires, token):
        raise HTTPException(403, "下载链接无效或已过期")
    document=session.get(ClaimDocument,document_id)
    if not document or document.claim_id!=item_id: raise HTTPException(404,'理赔材料不存在')
    resolved=storage.resolve(document.url)
    if not resolved: raise HTTPException(404,'文件不存在')
    kind,ref=resolved
    return RedirectResponse(ref) if kind=='redirect' else FileResponse(ref)

@router.patch("/claims/{item_id}/documents/{document_id}", dependencies=[Depends(require_role("admin", detail="仅平台理赔人员可审核材料"))])
def review_claim_document(item_id:int,document_id:int,data:ClaimDocumentReviewIn,user:User=Depends(current_user),session:Session=Depends(db)):
    item=session.get(Claim,item_id);document=session.get(ClaimDocument,document_id)
    if not item or not document or document.claim_id!=item_id: raise HTTPException(404,'理赔材料不存在')
    document.status=data.status;document.review_note=data.review_note
    if data.status=='rejected' and item.status not in {'paid','rejected','closed'}:
        item.status='supplement';item.current_handler='企业经办人';session.add(ClaimTimeline(claim_id=item.id,node='supplement',action=f'材料驳回：{document.name}',note=data.review_note,operator=user.name))
    else: session.add(ClaimTimeline(claim_id=item.id,node=item.status,action=f'材料审核：{document.name}',note=data.review_note or data.status,operator=user.name))
    session.commit();audit(session,user,'review','claim_document',str(document.id),data.status);return _document_dict(document)

@router.delete("/claims/{item_id}/documents/{document_id}")
def delete_claim_document(item_id:int,document_id:int,user:User=Depends(current_user),session:Session=Depends(db)):
    item=session.get(Claim,item_id);document=session.get(ClaimDocument,document_id)
    if not item or not document or document.claim_id!=item_id: raise HTTPException(404,'理赔材料不存在')
    claim_access(item,user,session)
    if user.role=='enterprise' and item.status not in {'reported','collecting','supplement'}: raise HTTPException(409,'当前节点不允许删除材料')
    if item.status=='closed': raise HTTPException(409,'已结案材料不能删除')
    session.add(ClaimTimeline(claim_id=item.id,node=item.status,action=f'删除材料：{document.name}',note=document.doc_type,operator=user.name));session.delete(document);session.commit();audit(session,user,'delete','claim_document',str(document_id));return {'ok':True}

@router.get("/claims/{item_id}/timeline")
def claim_timeline(item_id:int,user:User=Depends(current_user),session:Session=Depends(db)):
    item=session.get(Claim,item_id)
    if not item: raise HTTPException(404,'理赔案件不存在')
    claim_access(item,user,session)
    return [serialize(x) for x in session.scalars(select(ClaimTimeline).where(ClaimTimeline.claim_id==item_id).order_by(ClaimTimeline.id.asc()))]

@router.get("/claims/{item_id}/checklist")
def claim_checklist(item_id:int,user:User=Depends(current_user),session:Session=Depends(db)):
    item=session.get(Claim,item_id)
    if not item: raise HTTPException(404,'理赔案件不存在')
    claim_access(item,user,session)
    docs=session.scalars(select(ClaimDocument).where(ClaimDocument.claim_id==item_id).order_by(ClaimDocument.id.desc())).all();valid={x.doc_type for x in docs if x.status in {'uploaded','accepted'}};latest={}
    for document in docs:
        if document.doc_type not in latest: latest[document.doc_type]=document
    return [{'doc_type':key,'name':name,'required':True,'uploaded':key in valid,'status':latest[key].status if key in latest else 'missing','review_note':latest[key].review_note if key in latest else ''} for key,name in CLAIM_REQUIRED_DOCS]
