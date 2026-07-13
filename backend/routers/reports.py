import calendar
import io
from datetime import date, datetime

import openpyxl
from fastapi import APIRouter, Depends, HTTPException
from fastapi.responses import StreamingResponse
from sqlalchemy import select
from sqlalchemy.orm import Session

from ..core.db import db
from ..core.security import current_user
from ..models import (
    ActualEmployer, AgentCommission, Claim, Enterprise, InsurancePlan,
    InsuredPerson, Policy, User, WorkPosition,
)
from ..services import plan_price_for_class, policy_dict, pricing_snapshot

router = APIRouter(prefix="/api", tags=["reports"])


@router.get("/reports")
def reports(user: User = Depends(current_user), session: Session = Depends(db)):
    enterprise_id = user.enterprise_id if user.role == "enterprise" else None
    policy_rows = session.scalars(select(Policy).where(Policy.enterprise_id == enterprise_id) if enterprise_id else select(Policy)).all();policies=[policy_dict(x,session) for x in policy_rows]
    people = session.query(InsuredPerson).filter(InsuredPerson.enterprise_id == enterprise_id).count() if enterprise_id else session.query(InsuredPerson).count()
    claims = session.query(Claim).filter(Claim.enterprise_id == enterprise_id).count() if enterprise_id else session.query(Claim).count()
    now=date.today(); days=calendar.monthrange(now.year,now.month)[1]
    def prorated(policy):
        try:
            start=datetime.strptime(policy['start_date'],'%Y-%m-%d').date() if policy['start_date'] else now.replace(day=1)
            end=datetime.strptime(policy['end_date'],'%Y-%m-%d').date() if policy['end_date'] else now.replace(day=days)
            active=max(0,(min(end,now.replace(day=days))-max(start,now.replace(day=1))).days+1)
            return float(policy['premium'] or 0)*active/days
        except Exception: return float(policy['premium'] or 0)
    premium = sum(prorated(x) for x in policies)
    settlement=sum(float(x.get('policy_floor_total',0)) for x in policies);commission=sum(float(x.get('total_commission_total',0)) for x in policies)
    return [{"id":"premium","name":"销售保费汇总","period":f"{now.year}-{now.month:02d}按实际天数","value":premium,"detail":f"{len(policies)} 张保单，统一按销售价格计算"},{"id":"settlement","name":"保司结算底价","period":"当前","value":settlement,"detail":"保险原价 ×（1-总返佣比例）"},{"id":"commission","name":"总返佣金额","period":"当前","value":commission,"detail":"保险原价 × 总返佣比例"},{"id":"people","name":"参保人员报表","period":"当前","value":people,"detail":"在册参保人员"},{"id":"claims","name":"理赔统计报表","period":"累计","value":claims,"detail":"理赔案件"}]

@router.get("/billing")
def billing(user: User = Depends(current_user), session: Session = Depends(db)):
    stmt = select(Enterprise).where(Enterprise.id == user.enterprise_id) if user.role == "enterprise" and user.enterprise_id else select(Enterprise)
    rows=[]
    for x in session.scalars(stmt):
        people = session.query(InsuredPerson).filter(InsuredPerson.enterprise_id==x.id, InsuredPerson.status.in_(['active','pending'])).count()
        days = calendar.monthrange(date.today().year,date.today().month)[1]
        daily_usage = people * float(x.usage_fee_daily or 0.1)
        rows.append({"id":x.id,"enterprise_name":x.name,"account":"保费账户","balance":x.premium_balance,"status":"正常","daily_rate":0,"estimated_daily":0})
        rows.append({"id":x.id,"enterprise_name":x.name,"account":"平台使用费账户","balance":x.usage_balance,"status":"正常","daily_rate":x.usage_fee_daily or 0.1,"estimated_daily":daily_usage,"monthly_estimate":daily_usage*days})
    return rows

@router.get("/policies")
def policies(user: User = Depends(current_user), session: Session = Depends(db)):
    stmt=select(Policy).order_by(Policy.id.desc())
    if user.role=="enterprise" and user.enterprise_id: stmt=stmt.where(Policy.enterprise_id==user.enterprise_id)
    return [policy_dict(x,session) for x in session.scalars(stmt)]

@router.get("/policies/{item_id}/export")
def export_policy(item_id:int,user:User=Depends(current_user),session:Session=Depends(db)):
    policy=session.get(Policy,item_id)
    if not policy: raise HTTPException(404,'保单不存在')
    if user.role=='enterprise' and user.enterprise_id!=policy.enterprise_id: raise HTTPException(403,'无权导出该保单')
    enterprise=session.get(Enterprise,policy.enterprise_id);plan=session.get(InsurancePlan,policy.plan_id)
    relation=session.scalar(select(AgentCommission).where(AgentCommission.enterprise_id==policy.enterprise_id,AgentCommission.plan_id==policy.plan_id,AgentCommission.status=='active').order_by(AgentCommission.id.desc()))
    book=openpyxl.Workbook();sheet=book.active;sheet.title='保单人员明细';sheet.append(['保单号','投保单位','实际用工单位','岗位','职业类别','被保险人','身份证号','保险公司','保险方案','保险原价','总返佣比例','总返佣金额','保司结算底价','平台利润','销售最低价','实际销售价','业务员佣金','开始日期','结束日期','保单状态'])
    for person in session.scalars(select(InsuredPerson).where(InsuredPerson.policy_id==policy.id).order_by(InsuredPerson.id.asc())):
        position=session.get(WorkPosition,person.position_id) if person.position_id else None;employer=session.get(ActualEmployer,position.actual_employer_id) if position and position.actual_employer_id else None
        pricing=pricing_snapshot(plan,relation,plan_price_for_class(session,plan,person.occupation_class)) if plan else {}
        sheet.append([policy.policy_no,enterprise.name if enterprise else '',employer.name if employer else (position.actual_employer if position else ''),position.name if position else person.occupation,person.occupation_class,person.name,person.id_number,plan.insurer if plan else '',plan.name if plan else '',pricing.get('insurance_base_price',0),pricing.get('total_commission_rate',0),pricing.get('total_commission_amount',0),pricing.get('policy_floor_price',0),pricing.get('profit_amount',0),pricing.get('minimum_sale_price',0),pricing.get('sale_price',0),pricing.get('agent_commission_amount',0),policy.start_date,policy.end_date,policy.status])
    for column in sheet.columns: sheet.column_dimensions[column[0].column_letter].width=min(32,max(12,max(len(str(cell.value or '')) for cell in column)+2))
    output=io.BytesIO();book.save(output);output.seek(0)
    return StreamingResponse(output,media_type='application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',headers={'Content-Disposition':f'attachment; filename=policy-{policy.policy_no}.xlsx'})
