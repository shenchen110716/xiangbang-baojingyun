from datetime import date, datetime

from fastapi import HTTPException
from sqlalchemy import select
from sqlalchemy.orm import Session

from ..models import (
    ActualEmployer, Claim, ClaimDocument, ClaimTimeline, Enterprise,
    InsurancePlan, InsuredPerson, Policy, User, WorkPosition,
)
from .serialization import serialize
from .employer_scopes import assert_employer_access, is_enterprise_owner

CLAIM_REQUIRED_DOCS=[('id_card','被保险人身份证明'),('labor_relation','劳动关系证明'),('diagnosis','医疗诊断证明'),('medical_record','病历或出院记录'),('invoice','医疗发票和费用清单'),('accident_proof','事故经过及证明'),('bank_card','收款银行卡信息')]
CLAIM_REQUIRED_TYPES={key for key,_ in CLAIM_REQUIRED_DOCS}
CLAIM_TRANSITIONS={'reported':{'collecting'},'collecting':{'submitted'},'submitted':{'insurer_review','supplement'},'insurer_review':{'supplement','approved','rejected'},'supplement':{'submitted','insurer_review'},'approved':{'paid'},'paid':{'closed'},'rejected':{'closed'},'closed':set()}


def person_claim_access(person:InsuredPerson,user:User,session:Session):
    if user.role not in {'admin','enterprise'}: raise HTTPException(403,'无权访问理赔案件')
    if user.role=='enterprise' and user.enterprise_id!=person.enterprise_id: raise HTTPException(403,'无权访问该理赔案件')
    position=session.get(WorkPosition,person.position_id) if person.position_id else None
    if position and position.actual_employer_id is not None:
        assert_employer_access(session,user,position.actual_employer_id)
    elif user.role=='enterprise' and not is_enterprise_owner(user):
        raise HTTPException(403,'理赔员工未关联实际工作单位，项目负责人无权访问')


def claim_access(item:Claim,user:User,session:Session):
    if user.role=='enterprise' and user.enterprise_id!=item.enterprise_id: raise HTTPException(403,'无权访问该理赔案件')
    if user.role not in {'admin','enterprise'}: raise HTTPException(403,'无权访问理赔案件')
    person=session.get(InsuredPerson,item.person_id)
    if not person: raise HTTPException(404,'理赔员工不存在')
    person_claim_access(person,user,session)

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

def prepare_claim_upload(item:Claim,user:User,session:Session):
    claim_access(item,user,session)
    if item.status=='closed': raise HTTPException(409,'已结案案件不能继续上传材料')
    if user.role=='enterprise' and item.status not in {'reported','collecting','supplement'}: raise HTTPException(409,'当前节点不允许企业上传材料')
    if item.status=='reported':
        item.status='collecting';item.current_handler='企业经办人';session.add(ClaimTimeline(claim_id=item.id,node='collecting',action='开始收集理赔材料',note='',operator=user.name))
