import base64
import csv
import io
from datetime import datetime, timezone
from typing import Literal, Optional

from fastapi import APIRouter, Depends, HTTPException, Query
from fastapi.responses import StreamingResponse
from sqlalchemy import select
from sqlalchemy.orm import Session

from ..core.audit import audit
from ..core.db import db
from ..core.security import current_user
from ..models import AgentCommission, Enterprise, EnrollmentEmail, InsurancePlan, InsuredPerson, User, WorkPosition
from ..providers import email_provider, insurer_provider
from ..services import plan_price_for_class, pricing_snapshot, serialize

router = APIRouter(prefix="/api", tags=["enrollment"])


@router.get("/enrollment/export")
def enrollment_export(kind:Literal["enrollment","termination"],date_value:str=Query(default="",alias="date"),plan_id:Optional[int]=None,user:User=Depends(current_user),session:Session=Depends(db)):
    target_date=date_value or datetime.now(timezone.utc).strftime('%Y-%m-%d')
    stmt=select(InsuredPerson).order_by(InsuredPerson.id.asc())
    if user.role=="enterprise" and user.enterprise_id: stmt=stmt.where(InsuredPerson.enterprise_id==user.enterprise_id)
    if plan_id:
        stmt=stmt.join(WorkPosition,InsuredPerson.position_id==WorkPosition.id).where(WorkPosition.plan_id==plan_id)
    if kind=="termination": stmt=stmt.where(InsuredPerson.status=="stopped")
    else: stmt=stmt.where(InsuredPerson.created_at.like(f"{target_date}%"),InsuredPerson.status.in_(["active","pending"]))
    enterprise_export=user.role=='enterprise'
    rows=[]
    for p in session.scalars(stmt):
        ent=session.get(Enterprise,p.enterprise_id);position=session.get(WorkPosition,p.position_id) if p.position_id else None;plan=session.get(InsurancePlan,position.plan_id) if position and position.plan_id else None;relation=session.scalar(select(AgentCommission).where(AgentCommission.enterprise_id==p.enterprise_id,AgentCommission.plan_id==plan.id,AgentCommission.status=='active').order_by(AgentCommission.id.desc())) if plan else None;pricing=pricing_snapshot(plan,relation,plan_price_for_class(session,plan,p.occupation_class)) if plan else {}
        prefix=[ent.name if ent else "",position.actual_employer if position else "",position.name if position else p.occupation,p.name,p.id_number,p.occupation_class]
        if enterprise_export: rows.append(prefix+[pricing.get('sale_price',0),p.status,p.created_at.strftime('%Y-%m-%d') if p.created_at else target_date])
        else: rows.append(prefix+[pricing.get('insurance_base_price',0),pricing.get('policy_floor_price',0),pricing.get('profit_amount',0),pricing.get('minimum_sale_price',0),pricing.get('sale_price',0),pricing.get('total_commission_amount',0),pricing.get('agent_commission_amount',0),p.status,p.created_at.strftime('%Y-%m-%d') if p.created_at else target_date])
    header=["投保单位","实际工作单位","岗位","姓名","身份证号","职业类别","保费","状态","日期"] if enterprise_export else ["投保单位","实际工作单位","岗位","姓名","身份证号","职业类别","保险原价","保司结算底价","平台利润","销售最低价","实际销售价","总返佣金额","业务员佣金","状态","日期"]
    out=io.StringIO();csv.writer(out).writerows([header,*rows]);out.seek(0)
    return StreamingResponse(iter([out.getvalue().encode('utf-8-sig')]),media_type='text/csv',headers={'Content-Disposition':f'attachment; filename={kind}-{target_date}.csv'})

@router.get("/enrollment/summary")
def enrollment_summary(date_value:str=Query(default="",alias="date"),user:User=Depends(current_user),session:Session=Depends(db)):
    target=date_value or datetime.now(timezone.utc).strftime('%Y-%m-%d');result=[]
    for plan in session.scalars(select(InsurancePlan).order_by(InsurancePlan.id.desc())):
        stmt=select(InsuredPerson).join(WorkPosition,InsuredPerson.position_id==WorkPosition.id).where(WorkPosition.plan_id==plan.id)
        if user.role=='enterprise': stmt=stmt.where(InsuredPerson.enterprise_id==user.enterprise_id)
        people=list(session.scalars(stmt));new_count=sum(1 for x in people if str(x.created_at or '')[:10]==target and x.status!='stopped');stop_count=sum(1 for x in people if str(x.created_at or '')[:10]==target and x.status=='stopped')
        result.append({'plan_id':plan.id,'insurer':plan.insurer,'insurer_email':plan.insurer_email,'product':plan.name,'insured_count':len([x for x in people if x.status!='stopped']),'new_count':new_count,'stop_count':stop_count})
    return result

@router.post("/enrollment/send")
def enrollment_send(enterprise_id:int, plan_id:int, kind:Literal["enrollment","termination"]="enrollment", user:User=Depends(current_user), session:Session=Depends(db)):
    if user.role=="enterprise" and user.enterprise_id!=enterprise_id: raise HTTPException(403,"无权发送该单位名单")
    ent=session.get(Enterprise,enterprise_id);plan=session.get(InsurancePlan,plan_id)
    if not ent or not plan: raise HTTPException(404,"投保单位或方案不存在")
    stmt=select(InsuredPerson).join(WorkPosition,InsuredPerson.position_id==WorkPosition.id).where(InsuredPerson.enterprise_id==enterprise_id,WorkPosition.plan_id==plan_id)
    stmt=stmt.where(InsuredPerson.status=='stopped') if kind=='termination' else stmt.where(InsuredPerson.status.in_(['active','pending']))
    people=[serialize(x) for x in session.scalars(stmt)]
    payload={"enterprise":{"id":ent.id,"name":ent.name},"plan":serialize(plan),"people":people,"sent_at":datetime.now(timezone.utc).isoformat()}
    result=insurer_provider(plan.insurer).submit_enrollment(payload) if kind=="enrollment" else insurer_provider(plan.insurer).submit_termination(payload)
    audit(session,user,"send",kind,str(enterprise_id),result.request_id);return {"ok":result.ok,"kind":kind,"request_id":result.request_id,"accepted":result.data.get("accepted",0),"message":result.message}

@router.post("/enrollment/email")
def enrollment_email(enterprise_id:int,plan_id:int,kind:Literal['enrollment','termination']='enrollment',date_value:str=Query(default="",alias="date"),user:User=Depends(current_user),session:Session=Depends(db)):
    if user.role=='enterprise' and user.enterprise_id!=enterprise_id: raise HTTPException(403,'无权发送该单位名单')
    ent=session.get(Enterprise,enterprise_id);plan=session.get(InsurancePlan,plan_id)
    if not ent or not plan: raise HTTPException(404,'投保单位或产品不存在')
    if not plan.insurer_email: raise HTTPException(400,'该保险公司方案尚未配置接收邮箱')
    target_date=date_value or datetime.now(timezone.utc).strftime('%Y-%m-%d');stmt=select(InsuredPerson).join(WorkPosition,InsuredPerson.position_id==WorkPosition.id).where(InsuredPerson.enterprise_id==enterprise_id,WorkPosition.plan_id==plan_id)
    if kind=='termination': stmt=stmt.where(InsuredPerson.status=='stopped')
    else: stmt=stmt.where(InsuredPerson.created_at.like(f'{target_date}%'),InsuredPerson.status.in_(['active','pending']))
    rows=[]
    for person in session.scalars(stmt):
        position=session.get(WorkPosition,person.position_id) if person.position_id else None;relation=session.scalar(select(AgentCommission).where(AgentCommission.enterprise_id==enterprise_id,AgentCommission.plan_id==plan_id,AgentCommission.status=='active').order_by(AgentCommission.id.desc()));pricing=pricing_snapshot(plan,relation,plan_price_for_class(session,plan,person.occupation_class))
        rows.append([ent.name,position.actual_employer if position else '',position.name if position else person.occupation,person.name,person.id_number,person.occupation_class,pricing['insurance_base_price'],pricing['policy_floor_price'],pricing['profit_amount'],pricing['minimum_sale_price'],pricing['sale_price'],pricing['total_commission_amount'],pricing['agent_commission_amount'],person.status,target_date])
    output=io.StringIO();csv.writer(output).writerows([['投保单位','实际工作单位','岗位','姓名','身份证号','职业类别','保险原价','保司结算底价','平台利润','销售最低价','实际销售价','总返佣金额','业务员佣金','状态','日期'],*rows]);filename=f'{kind}-{target_date}.csv';encoded=base64.b64encode(output.getvalue().encode('utf-8-sig')).decode()
    subject=f'{plan.insurer} {plan.name} {"新参" if kind=="enrollment" else "停保"}名单 {target_date}';body=f'投保单位：{ent.name}\n业务类型：{"新参" if kind=="enrollment" else "停保"}\n人数：{len(rows)}\n请查收附件名单。'
    result=email_provider().send_email(plan.insurer_email,subject,body,[{'filename':filename,'content_base64':encoded,'content_type':'text/csv'}]);record=EnrollmentEmail(enterprise_id=enterprise_id,plan_id=plan_id,kind=kind,recipient=plan.insurer_email,filename=filename,people_count=len(rows),request_id=result.request_id,status='sent' if result.ok else 'failed');session.add(record);session.commit()
    audit(session,user,'send_email',kind,str(enterprise_id),f'{result.request_id};count={len(rows)};to={plan.insurer_email}');return {'ok':result.ok,'email':plan.insurer_email,'request_id':result.request_id,'message':result.message,'people_count':len(rows),'filename':filename,'kind':kind}

@router.get('/enrollment/emails')
def enrollment_emails(user:User=Depends(current_user),session:Session=Depends(db)):
    stmt=select(EnrollmentEmail).order_by(EnrollmentEmail.id.desc())
    if user.role=='enterprise': stmt=stmt.where(EnrollmentEmail.enterprise_id==user.enterprise_id)
    result=[]
    for item in session.scalars(stmt):
        ent=session.get(Enterprise,item.enterprise_id);plan=session.get(InsurancePlan,item.plan_id);result.append({**serialize(item),'enterprise_name':ent.name if ent else '','plan_name':plan.name if plan else '','insurer':plan.insurer if plan else ''})
    return result
