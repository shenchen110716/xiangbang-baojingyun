import calendar
import io
import json
from datetime import date, timedelta

import openpyxl
from fastapi import APIRouter, Depends, HTTPException, Query
from fastapi.responses import StreamingResponse
from sqlalchemy import select
from sqlalchemy.orm import Session

from ..core.db import db
from ..core.security import current_user
from ..models import (
    ActualEmployer, AgentCommission, Claim, Enterprise, InsurancePlan,
    InsuredPerson, Policy, PolicyMember, User, WorkPosition,
)
from ..services import amount, plan_price_for_class, policy_dict, pricing_snapshot

router = APIRouter(prefix="/api", tags=["reports"])


def _parse_report_range(start_value: str, end_value: str) -> tuple[date, date]:
    try:
        start = date.fromisoformat(start_value)
        end = date.fromisoformat(end_value)
    except ValueError as exc:
        raise HTTPException(400, "统计日期格式不正确，应为 yyyy-MM-dd") from exc
    if start > end:
        raise HTTPException(400, "开始日期不能晚于结束日期")
    if (end - start).days > 730:
        raise HTTPException(400, "单次统计时间段不能超过两年")
    return start, end


def _period_premium(unit_price: float, billing_mode: str, start: date, end: date) -> float:
    if billing_mode == "daily":
        return unit_price * ((end - start).days + 1)
    total = 0.0
    cursor = start
    while cursor <= end:
        month_days = calendar.monthrange(cursor.year, cursor.month)[1]
        month_end = date(cursor.year, cursor.month, month_days)
        segment_end = min(end, month_end)
        active_days = (segment_end - cursor).days + 1
        total += unit_price * active_days / month_days
        cursor = segment_end + timedelta(days=1)
    return total


def _member_sale_price(session: Session, member: PolicyMember, policy: Policy, person: InsuredPerson, plan: InsurancePlan | None) -> float:
    try:
        snapshot = json.loads(member.rate_snapshot_json or "{}")
        if "sale_price" in snapshot:
            return float(snapshot["sale_price"] or 0)
    except (TypeError, ValueError, json.JSONDecodeError):
        pass
    if not plan:
        return float(policy.premium or 0)
    relation = session.scalar(select(AgentCommission).where(AgentCommission.enterprise_id == policy.enterprise_id, AgentCommission.plan_id == policy.plan_id, AgentCommission.status == "active").order_by(AgentCommission.id.desc()))
    return float(pricing_snapshot(plan, relation, plan_price_for_class(session, plan, person.occupation_class)).get("sale_price", 0))


def _premium_detail_payload(start: date, end: date, user: User, session: Session) -> dict:
    rows = []
    members = session.scalars(select(PolicyMember).order_by(PolicyMember.effective_at.asc(), PolicyMember.id.asc())).all()
    for member in members:
        policy = session.get(Policy, member.policy_id)
        if not policy or (user.role == "enterprise" and user.enterprise_id != policy.enterprise_id):
            continue
        person = session.get(InsuredPerson, member.person_id)
        if not person:
            continue
        effective = member.effective_at.date()
        terminated = member.terminated_at.date() if member.terminated_at else None
        period_start = max(start, effective)
        period_end = min(end, terminated or end)
        if period_start > period_end:
            continue
        enterprise = session.get(Enterprise, policy.enterprise_id)
        plan = session.get(InsurancePlan, policy.plan_id)
        position = session.get(WorkPosition, person.position_id) if person.position_id else None
        employer = session.get(ActualEmployer, position.actual_employer_id) if position and position.actual_employer_id else None
        billing_mode = plan.billing_mode if plan else "monthly"
        unit_price = _member_sale_price(session, member, policy, person, plan)
        active_days = (period_end - period_start).days + 1
        premium = amount(_period_premium(unit_price, billing_mode, period_start, period_end))
        rows.append({
            "member_id": member.id,
            "person_id": person.id,
            "person_name": person.name,
            "id_number": person.id_number,
            "enterprise_name": enterprise.name if enterprise else "",
            "actual_employer_name": employer.name if employer else (position.actual_employer if position else ""),
            "position_name": position.name if position else person.occupation,
            "occupation_class": person.occupation_class,
            "policy_no": policy.policy_no,
            "insurer": plan.insurer if plan else "",
            "plan_name": plan.name if plan else "",
            "billing_mode": billing_mode,
            "unit_sale_price": amount(unit_price),
            "coverage_start": member.effective_at,
            "coverage_end": member.terminated_at,
            "period_start": period_start.isoformat(),
            "period_end": period_end.isoformat(),
            "active_days": active_days,
            "premium_amount": premium,
        })
    return {
        "start_date": start.isoformat(),
        "end_date": end.isoformat(),
        "total_premium": amount(sum(row["premium_amount"] for row in rows)),
        "detail_count": len(rows),
        "rows": rows,
    }


@router.get("/reports")
def reports(user: User = Depends(current_user), session: Session = Depends(db)):
    enterprise_id = user.enterprise_id if user.role == "enterprise" else None
    policy_rows = session.scalars(select(Policy).where(Policy.enterprise_id == enterprise_id) if enterprise_id else select(Policy)).all();policies=[policy_dict(x,session) for x in policy_rows]
    people = session.query(InsuredPerson).filter(InsuredPerson.enterprise_id == enterprise_id).count() if enterprise_id else session.query(InsuredPerson).count()
    claims = session.query(Claim).filter(Claim.enterprise_id == enterprise_id).count() if enterprise_id else session.query(Claim).count()
    now=date.today(); days=calendar.monthrange(now.year,now.month)[1]
    premium = _premium_detail_payload(now.replace(day=1), now.replace(day=days), user, session)["total_premium"]
    settlement=sum(float(x.get('policy_floor_total',0)) for x in policies);commission=sum(float(x.get('total_commission_total',0)) for x in policies)
    premium_row={"id":"premium","name":"销售保费汇总","period":f"{now.year}-{now.month:02d}按实际天数","value":premium,"detail":f"{len(policies)} 张保单，统一按销售价格计算"}
    people_row={"id":"people","name":"参保人员报表","period":"当前","value":people,"detail":"在册参保人员"}
    claims_row={"id":"claims","name":"理赔统计报表","period":"累计","value":claims,"detail":"理赔案件"}
    if user.role == "enterprise": return [premium_row,people_row,claims_row]
    return [premium_row,{"id":"settlement","name":"保司结算底价","period":"当前","value":settlement,"detail":"保险原价 ×（1-总返佣比例）"},{"id":"commission","name":"总返佣金额","period":"当前","value":commission,"detail":"保险原价 × 总返佣比例"},people_row,claims_row]


@router.get("/reports/premium-details")
def premium_details(start_date: str = Query(...), end_date: str = Query(...), user: User = Depends(current_user), session: Session = Depends(db)):
    start, end = _parse_report_range(start_date, end_date)
    return _premium_detail_payload(start, end, user, session)


@router.get("/reports/premium-details/export")
def export_premium_details(start_date: str = Query(...), end_date: str = Query(...), user: User = Depends(current_user), session: Session = Depends(db)):
    start, end = _parse_report_range(start_date, end_date)
    payload = _premium_detail_payload(start, end, user, session)
    book = openpyxl.Workbook(); sheet = book.active; sheet.title = "销售保费明细"
    sheet.append(["统计开始", "统计结束", "被保险人", "身份证号", "投保单位", "实际用工单位", "岗位", "职业类别", "保单号", "保险公司", "保险方案", "计费方式", "实际销售价", "本期开始", "本期结束", "计费天数", "保费金额"])
    for row in payload["rows"]:
        sheet.append([payload["start_date"], payload["end_date"], row["person_name"], row["id_number"], row["enterprise_name"], row["actual_employer_name"], row["position_name"], row["occupation_class"], row["policy_no"], row["insurer"], row["plan_name"], "按天" if row["billing_mode"] == "daily" else "按月", row["unit_sale_price"], row["period_start"], row["period_end"], row["active_days"], row["premium_amount"]])
    sheet.append([]); sheet.append(["保费总额", payload["total_premium"]])
    for column in sheet.columns: sheet.column_dimensions[column[0].column_letter].width = min(32, max(12, max(len(str(cell.value or "")) for cell in column) + 2))
    output = io.BytesIO(); book.save(output); output.seek(0)
    filename = f"premium-details-{start.isoformat()}-{end.isoformat()}.xlsx"
    return StreamingResponse(output, media_type="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", headers={"Content-Disposition": f"attachment; filename={filename}"})

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
    book=openpyxl.Workbook();sheet=book.active;sheet.title='保单人员明细'
    enterprise_export = user.role == 'enterprise'
    if enterprise_export: sheet.append(['保单号','投保单位','实际用工单位','岗位','职业类别','被保险人','身份证号','保险公司','保险方案','实际销售价','开始日期','结束日期','保单状态'])
    else: sheet.append(['保单号','投保单位','实际用工单位','岗位','职业类别','被保险人','身份证号','保险公司','保险方案','保险原价','总返佣比例','总返佣金额','保司结算底价','平台利润','销售最低价','实际销售价','业务员佣金','开始日期','结束日期','保单状态'])
    for person in session.scalars(select(InsuredPerson).where(InsuredPerson.policy_id==policy.id).order_by(InsuredPerson.id.asc())):
        position=session.get(WorkPosition,person.position_id) if person.position_id else None;employer=session.get(ActualEmployer,position.actual_employer_id) if position and position.actual_employer_id else None
        pricing=pricing_snapshot(plan,relation,plan_price_for_class(session,plan,person.occupation_class)) if plan else {}
        prefix=[policy.policy_no,enterprise.name if enterprise else '',employer.name if employer else (position.actual_employer if position else ''),position.name if position else person.occupation,person.occupation_class,person.name,person.id_number,plan.insurer if plan else '',plan.name if plan else '']
        if enterprise_export: sheet.append(prefix+[pricing.get('sale_price',0),policy.start_date,policy.end_date,policy.status])
        else: sheet.append(prefix+[pricing.get('insurance_base_price',0),pricing.get('total_commission_rate',0),pricing.get('total_commission_amount',0),pricing.get('policy_floor_price',0),pricing.get('profit_amount',0),pricing.get('minimum_sale_price',0),pricing.get('sale_price',0),pricing.get('agent_commission_amount',0),policy.start_date,policy.end_date,policy.status])
    for column in sheet.columns: sheet.column_dimensions[column[0].column_letter].width=min(32,max(12,max(len(str(cell.value or '')) for cell in column)+2))
    output=io.BytesIO();book.save(output);output.seek(0)
    return StreamingResponse(output,media_type='application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',headers={'Content-Disposition':f'attachment; filename=policy-{policy.policy_no}.xlsx'})
