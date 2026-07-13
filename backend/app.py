from __future__ import annotations

import os
import secrets
import base64
import csv
import io
from datetime import datetime, timedelta, timezone, date
import calendar
from pathlib import Path
from typing import Optional, Literal

import jwt
from fastapi import Depends, FastAPI, HTTPException, Query, status, UploadFile, File, Form
from fastapi.responses import StreamingResponse
from fastapi.middleware.cors import CORSMiddleware
from fastapi.staticfiles import StaticFiles
from sqlalchemy import or_, select
from sqlalchemy.orm import Session
from .providers import insurer_provider, sms_provider, email_provider, payment_provider
from .core.config import ROOT, DATABASE_URL, SECRET_KEY, ALGORITHM
from .core.db import Base, engine, SessionLocal, db
from .models import (
    User, Enterprise, ActualEmployer, WorkPosition, PositionVideo,
    AgentCommission, InsurancePlan, PlanTier, InsuredPerson, Policy,
    Claim, ClaimTimeline, ClaimDocument, AuditLog, PaymentRecord,
    Invoice, EnrollmentEmail,
)
from .schemas import (
    LoginIn, PasswordChangeIn, TokenOut, UserOut,
    OperatorIn, OperatorUpdate,
    EnterpriseIn, EnterpriseUpdate, RechargeIn,
    AgentIn, CommissionIn, CommissionUpdate,
    PositionIn, ActualEmployerIn, ActualEmployerUpdate,
    PositionVideoIn, PositionVideoReviewIn, PositionReviewIn,
    PlanTierIn, PlanIn, PlanUpdate,
    PersonIn, PersonUpdate, BulkPersonRow, BulkPersonIn,
    ClaimIn, ClaimUpdate, ClaimStatusIn, ClaimDocumentIn, ClaimDocumentReviewIn,
    PaymentIn, PaymentCallbackIn, InvoiceIn, InvoiceUpdate,
    NotificationIn,
)
from .services import (
    serialize, amount,
    plan_price_for_class, pricing_snapshot, plan_dict, validate_commission_price,
    commission_dict, agent_commission_rows, agent_commission_summary,
    policy_dict,
)

from .core.security import pwd, security, current_user
from .core.audit import audit
from .core.migrations import run_sqlite_bridge_migrations
from .core.seed import seed_default_accounts

app = FastAPI(title="响帮帮保经云 API", version="3.6.0")
cors_origins = [origin.strip() for origin in os.getenv("CORS_ORIGINS", "*").split(",") if origin.strip()]
app.add_middleware(
    CORSMiddleware,
    allow_origins=cors_origins,
    allow_credentials=cors_origins != ["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)

@app.on_event("startup")
def startup():
    Base.metadata.create_all(engine)
    with SessionLocal() as s:
        run_sqlite_bridge_migrations(s, DATABASE_URL)
        seed_default_accounts(s)

from .routers.auth import login, me, change_password  # noqa: F401 (temporary compat re-exports)
from .routers.audit_logs import audit_logs  # noqa: F401
from .routers.integrations import provider_status  # noqa: F401
from .routers.health import health  # noqa: F401

from .routers.agents import add_agent, add_agent_commission, update_agent_commission  # noqa: F401
from .routers.operators import add_operator  # noqa: F401
from .services.operators import operator_dict  # noqa: F401
from .routers.enterprises import add_enterprise  # noqa: F401
from .routers.dashboard import dashboard, screen_products  # noqa: F401
from .routers.positions import add_actual_employer, update_actual_employer, delete_actual_employer, add_position  # noqa: F401

from .routers.plans import add_plan  # noqa: F401
from .routers.reports import billing  # noqa: F401
from .routers.insured import add_person  # noqa: F401
from .routers.enrollment import enrollment_email  # noqa: F401

CLAIM_REQUIRED_DOCS=[('id_card','被保险人身份证明'),('labor_relation','劳动关系证明'),('diagnosis','医疗诊断证明'),('medical_record','病历或出院记录'),('invoice','医疗发票和费用清单'),('accident_proof','事故经过及证明'),('bank_card','收款银行卡信息')]
CLAIM_REQUIRED_TYPES={key for key,_ in CLAIM_REQUIRED_DOCS}
CLAIM_TRANSITIONS={'reported':{'collecting'},'collecting':{'submitted'},'submitted':{'insurer_review','supplement'},'insurer_review':{'supplement','approved','rejected'},'supplement':{'submitted','insurer_review'},'approved':{'paid'},'paid':{'closed'},'rejected':{'closed'},'closed':set()}

def claim_access(item:Claim,user:User):
    if user.role=='enterprise' and user.enterprise_id!=item.enterprise_id: raise HTTPException(403,'无权访问该理赔案件')
    if user.role not in {'admin','enterprise'}: raise HTTPException(403,'无权访问理赔案件')

def claim_payload(item:Claim,session:Session):
    result=serialize(item);enterprise=session.get(Enterprise,item.enterprise_id);person=session.get(InsuredPerson,item.person_id);position=session.get(WorkPosition,person.position_id) if person and person.position_id else None;employer=session.get(ActualEmployer,position.actual_employer_id) if position and position.actual_employer_id else None;policy=session.get(Policy,person.policy_id) if person and person.policy_id else None;plan=session.get(InsurancePlan,policy.plan_id) if policy else None
    docs=session.scalars(select(ClaimDocument).where(ClaimDocument.claim_id==item.id)).all();valid_types={doc.doc_type for doc in docs if doc.status in {'uploaded','accepted'}}&CLAIM_REQUIRED_TYPES;missing=CLAIM_REQUIRED_TYPES-valid_types
    deadline_days=None
    try: deadline_days=(datetime.strptime(item.deadline[:10],'%Y-%m-%d').date()-date.today()).days if item.deadline else None
    except Exception: pass
    sla_overdue=False
    try: sla_overdue=bool(item.sla_deadline and datetime.strptime(item.sla_deadline[:16],'%Y-%m-%d %H:%M')<datetime.now())
    except Exception: pass
    calculated_risk='high' if (deadline_days is not None and deadline_days<0) or sla_overdue else 'attention' if item.status=='supplement' or (deadline_days is not None and deadline_days<=5) else item.risk_level
    result.update(enterprise_name=enterprise.name if enterprise else '',person_name=person.name if person else '',id_number=person.id_number if person else '',position_name=position.name if position else '',actual_employer_name=employer.name if employer else (position.actual_employer if position else ''),policy_no=policy.policy_no if policy else '',plan_name=plan.name if plan else '',insurer=plan.insurer if plan else '',document_count=len(docs),missing_count=len(missing),missing_types=sorted(missing),complete_percent=round((len(CLAIM_REQUIRED_TYPES)-len(missing))/len(CLAIM_REQUIRED_TYPES)*100),deadline_days=deadline_days,deadline_overdue=deadline_days is not None and deadline_days<0,sla_overdue=sla_overdue,calculated_risk=calculated_risk)
    return result

@app.get("/api/claims")
def claims(q:str="",status_filter:Optional[str]=Query(default=None,alias='status'),risk:Optional[str]=None,enterprise_id:Optional[int]=None,user: User = Depends(current_user), session: Session = Depends(db)):
    stmt=select(Claim).order_by(Claim.id.desc())
    if user.role=='enterprise' and user.enterprise_id: stmt=stmt.where(Claim.enterprise_id==user.enterprise_id)
    elif enterprise_id: stmt=stmt.where(Claim.enterprise_id==enterprise_id)
    if status_filter: stmt=stmt.where(Claim.status==status_filter)
    rows=[claim_payload(item,session) for item in session.scalars(stmt)]
    if q:
        needle=q.lower();rows=[item for item in rows if needle in f"{item['claim_no']}{item['person_name']}{item['enterprise_name']}{item['actual_employer_name']}".lower()]
    if risk: rows=[item for item in rows if item['calculated_risk']==risk]
    return rows

@app.get("/api/claims/{item_id}")
def claim_detail(item_id:int,user:User=Depends(current_user),session:Session=Depends(db)):
    item=session.get(Claim,item_id)
    if not item: raise HTTPException(404,'理赔案件不存在')
    claim_access(item,user);return claim_payload(item,session)

@app.post("/api/claims")
def add_claim(data: ClaimIn, user: User = Depends(current_user), session: Session = Depends(db)):
    if user.role == "enterprise" and user.enterprise_id != data.enterprise_id: raise HTTPException(403,"无权提交该单位理赔")
    person=session.get(InsuredPerson,data.person_id)
    if not person or person.enterprise_id!=data.enterprise_id: raise HTTPException(400,'被保险人不属于该投保单位')
    if person.status!='active': raise HTTPException(409,'只能为当前在保员工提交工伤报案')
    if person.policy_id:
        policy=session.get(Policy,person.policy_id)
        if not policy or policy.status!='active': raise HTTPException(409,'被保险人当前保单无效，请先核对保单')
    try: deadline=(datetime.strptime(data.accident_at[:10],'%Y-%m-%d')+timedelta(days=30)).strftime('%Y-%m-%d')
    except Exception: deadline=''
    sla_deadline=(datetime.now()+timedelta(days=2)).strftime('%Y-%m-%d %H:%M')
    item = Claim(**data.model_dump(),deadline=deadline,sla_deadline=sla_deadline,current_handler='企业经办人',claim_no=f"CLM-{datetime.now().strftime('%Y%m%d')}-{secrets.token_hex(3).upper()}"); session.add(item);session.flush();session.add(ClaimTimeline(claim_id=item.id,node='reported',action='提交工伤报案',note=data.description,operator=user.name));session.commit();session.refresh(item);audit(session, user, "create", "claim", str(item.id));return claim_payload(item,session)

@app.patch("/api/claims/{item_id}")
def update_claim(item_id:int,data:ClaimUpdate,user:User=Depends(current_user),session:Session=Depends(db)):
    item=session.get(Claim,item_id)
    if not item: raise HTTPException(404,'理赔案件不存在')
    claim_access(item,user);values=data.model_dump(exclude_unset=True)
    if user.role=='enterprise':
        if item.status not in {'reported','collecting','supplement'}: raise HTTPException(409,'当前节点不允许企业修改报案信息')
        allowed={'description','hospital','diagnosis','medical_cost','amount','contact_name','contact_phone'}
        if set(values)-allowed: raise HTTPException(403,'保司报案号、SLA、风险和审核意见仅平台可修改')
    for key,value in values.items():
        if value is not None:setattr(item,key,value)
    session.commit();audit(session,user,'update','claim',str(item.id));return serialize(item)

@app.patch("/api/claims/{item_id}/status")
def claim_status(item_id:int,data:ClaimStatusIn,user:User=Depends(current_user),session:Session=Depends(db)):
    item=session.get(Claim,item_id)
    if not item: raise HTTPException(404,"理赔案件不存在")
    claim_access(item,user)
    if data.status not in CLAIM_TRANSITIONS.get(item.status,set()): raise HTTPException(409,f'案件不能从 {item.status} 变更为 {data.status}')
    if user.role=='enterprise' and data.status!='submitted': raise HTTPException(403,'该节点需由平台理赔人员处理')
    if data.status=='submitted':
        uploaded={x.doc_type for x in session.scalars(select(ClaimDocument).where(ClaimDocument.claim_id==item.id,x.status.in_(['uploaded','accepted'])))};missing=CLAIM_REQUIRED_TYPES-uploaded
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

@app.get("/api/claims/{item_id}/documents")
def claim_documents(item_id:int,user:User=Depends(current_user),session:Session=Depends(db)):
    item=session.get(Claim,item_id)
    if not item: raise HTTPException(404,"理赔案件不存在")
    if user.role=="enterprise" and user.enterprise_id!=item.enterprise_id: raise HTTPException(403,"无权查看该案件")
    return [serialize(x) for x in session.scalars(select(ClaimDocument).where(ClaimDocument.claim_id==item_id).order_by(ClaimDocument.id.desc()))]

def prepare_claim_upload(item:Claim,user:User,session:Session):
    claim_access(item,user)
    if item.status=='closed': raise HTTPException(409,'已结案案件不能继续上传材料')
    if user.role=='enterprise' and item.status not in {'reported','collecting','supplement'}: raise HTTPException(409,'当前节点不允许企业上传材料')
    if item.status=='reported':
        item.status='collecting';item.current_handler='企业经办人';session.add(ClaimTimeline(claim_id=item.id,node='collecting',action='开始收集理赔材料',note='',operator=user.name))

@app.post("/api/claims/{item_id}/documents")
def add_claim_document(item_id:int,data:ClaimDocumentIn,user:User=Depends(current_user),session:Session=Depends(db)):
    item=session.get(Claim,item_id)
    if not item: raise HTTPException(404,"理赔案件不存在")
    prepare_claim_upload(item,user,session)
    doc=ClaimDocument(claim_id=item_id,**data.model_dump());session.add(doc);session.flush();session.add(ClaimTimeline(claim_id=item_id,node=item.status,action=f'上传材料：{data.name}',note=data.doc_type,operator=user.name));session.commit();session.refresh(doc);audit(session,user,"upload","claim_document",str(doc.id));return serialize(doc)

@app.post("/api/claims/{item_id}/documents/upload")
async def upload_claim_document(item_id:int,doc_type:str=Form('other'),file:UploadFile=File(...),user:User=Depends(current_user),session:Session=Depends(db)):
    item=session.get(Claim,item_id)
    if not item: raise HTTPException(404,'理赔案件不存在')
    prepare_claim_upload(item,user,session)
    suffix=Path(file.filename or '').suffix.lower()
    if suffix not in {'.jpg','.jpeg','.png','.heic','.pdf','.doc','.docx','.xls','.xlsx'}: raise HTTPException(400,'仅支持图片、PDF、Word、Excel材料')
    content=await file.read()
    if len(content)>20*1024*1024: raise HTTPException(400,'单个材料不能超过20MB')
    folder=ROOT/'uploads'/'claims'/str(item_id);folder.mkdir(parents=True,exist_ok=True);stored=f'{secrets.token_hex(8)}{suffix}';(folder/stored).write_bytes(content);url=f'/uploads/claims/{item_id}/{stored}'
    doc=ClaimDocument(claim_id=item_id,name=file.filename or stored,url=url,doc_type=doc_type);session.add(doc);session.flush();session.add(ClaimTimeline(claim_id=item_id,node=item.status,action=f'上传材料：{doc.name}',note=doc_type,operator=user.name));session.commit();session.refresh(doc);audit(session,user,'upload','claim_document',str(doc.id));return serialize(doc)

@app.patch("/api/claims/{item_id}/documents/{document_id}")
def review_claim_document(item_id:int,document_id:int,data:ClaimDocumentReviewIn,user:User=Depends(current_user),session:Session=Depends(db)):
    if user.role!='admin': raise HTTPException(403,'仅平台理赔人员可审核材料')
    item=session.get(Claim,item_id);document=session.get(ClaimDocument,document_id)
    if not item or not document or document.claim_id!=item_id: raise HTTPException(404,'理赔材料不存在')
    document.status=data.status;document.review_note=data.review_note
    if data.status=='rejected' and item.status not in {'paid','rejected','closed'}:
        item.status='supplement';item.current_handler='企业经办人';session.add(ClaimTimeline(claim_id=item.id,node='supplement',action=f'材料驳回：{document.name}',note=data.review_note,operator=user.name))
    else: session.add(ClaimTimeline(claim_id=item.id,node=item.status,action=f'材料审核：{document.name}',note=data.review_note or data.status,operator=user.name))
    session.commit();audit(session,user,'review','claim_document',str(document.id),data.status);return serialize(document)

@app.delete("/api/claims/{item_id}/documents/{document_id}")
def delete_claim_document(item_id:int,document_id:int,user:User=Depends(current_user),session:Session=Depends(db)):
    item=session.get(Claim,item_id);document=session.get(ClaimDocument,document_id)
    if not item or not document or document.claim_id!=item_id: raise HTTPException(404,'理赔材料不存在')
    claim_access(item,user)
    if user.role=='enterprise' and item.status not in {'reported','collecting','supplement'}: raise HTTPException(409,'当前节点不允许删除材料')
    if item.status=='closed': raise HTTPException(409,'已结案材料不能删除')
    session.add(ClaimTimeline(claim_id=item.id,node=item.status,action=f'删除材料：{document.name}',note=document.doc_type,operator=user.name));session.delete(document);session.commit();audit(session,user,'delete','claim_document',str(document_id));return {'ok':True}

@app.get("/api/claims/{item_id}/timeline")
def claim_timeline(item_id:int,user:User=Depends(current_user),session:Session=Depends(db)):
    item=session.get(Claim,item_id)
    if not item: raise HTTPException(404,'理赔案件不存在')
    if user.role=='enterprise' and user.enterprise_id!=item.enterprise_id: raise HTTPException(403,'无权查看该案件')
    return [serialize(x) for x in session.scalars(select(ClaimTimeline).where(ClaimTimeline.claim_id==item_id).order_by(ClaimTimeline.id.asc()))]

@app.get("/api/claims/{item_id}/checklist")
def claim_checklist(item_id:int,user:User=Depends(current_user),session:Session=Depends(db)):
    item=session.get(Claim,item_id)
    if not item: raise HTTPException(404,'理赔案件不存在')
    if user.role=='enterprise' and user.enterprise_id!=item.enterprise_id: raise HTTPException(403,'无权查看该案件')
    docs=session.scalars(select(ClaimDocument).where(ClaimDocument.claim_id==item_id).order_by(ClaimDocument.id.desc())).all();valid={x.doc_type for x in docs if x.status in {'uploaded','accepted'}};latest={}
    for document in docs:
        if document.doc_type not in latest: latest[document.doc_type]=document
    return [{'doc_type':key,'name':name,'required':True,'uploaded':key in valid,'status':latest[key].status if key in latest else 'missing','review_note':latest[key].review_note if key in latest else ''} for key,name in CLAIM_REQUIRED_DOCS]

from .routers.payments import create_payment, payment_callback  # noqa: F401
from .routers.invoices import create_invoice, update_invoice  # noqa: F401

from .routers.health import router as health_router
from .routers.auth import router as auth_router
from .routers.audit_logs import router as audit_logs_router
from .routers.integrations import router as integrations_router
from .routers.agents import router as agents_router
from .routers.payments import router as payments_router
from .routers.invoices import router as invoices_router

from .routers.operators import router as operators_router
from .routers.dashboard import router as dashboard_router
from .routers.enterprises import router as enterprises_router

from .routers.positions import router as positions_router
from .routers.plans import router as plans_router
from .routers.reports import router as reports_router
from .routers.insured import router as insured_router
from .routers.enrollment import router as enrollment_router
from .routers.messages import router as messages_router
from .routers.notifications import router as notifications_router

app.include_router(health_router)
app.include_router(auth_router)
app.include_router(audit_logs_router)
app.include_router(integrations_router)
app.include_router(agents_router)
app.include_router(payments_router)
app.include_router(invoices_router)
app.include_router(operators_router)
app.include_router(dashboard_router)
app.include_router(enterprises_router)
app.include_router(positions_router)
app.include_router(plans_router)
app.include_router(reports_router)
app.include_router(insured_router)
app.include_router(enrollment_router)
app.include_router(messages_router)
app.include_router(notifications_router)

app.mount("/", StaticFiles(directory=ROOT, html=True), name="frontend")
