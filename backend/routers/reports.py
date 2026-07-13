import io
import json
from datetime import date

import openpyxl
from fastapi import APIRouter, Depends, HTTPException, Query
from fastapi.responses import StreamingResponse
from sqlalchemy import select
from sqlalchemy.orm import Session

from ..core.business_time import business_today
from ..core.db import db
from ..core.security import current_user
from ..models import (
    ActualEmployer, AgentCommission, Claim, Enterprise, InsurancePlan,
    InsuredPerson, Policy, PolicyMember, User, WorkPosition,
)
from ..services import amount, billable_date_range, period_amount, plan_price_for_class, policy_dict, pricing_snapshot, usage_person_days

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
    return period_amount(unit_price, billing_mode, start, end)


def _member_prices(session: Session, member: PolicyMember, policy: Policy, person: InsuredPerson, plan: InsurancePlan | None) -> tuple[float, float, float, float]:
    try:
        snapshot = json.loads(member.rate_snapshot_json or "{}")
        required = {"sale_price", "policy_floor_price", "total_commission_amount", "agent_commission_amount"}
        if required.issubset(snapshot):
            return (
                float(snapshot["sale_price"] or 0),
                float(snapshot["policy_floor_price"] or 0),
                float(snapshot["total_commission_amount"] or 0),
                float(snapshot["agent_commission_amount"] or 0),
            )
    except (TypeError, ValueError, json.JSONDecodeError):
        pass
    if not plan:
        return float(policy.premium or 0), 0, 0, 0
    relation = session.scalar(select(AgentCommission).where(AgentCommission.enterprise_id == policy.enterprise_id, AgentCommission.plan_id == policy.plan_id, AgentCommission.status == "active").order_by(AgentCommission.id.desc()))
    pricing = pricing_snapshot(plan, relation, plan_price_for_class(session, plan, person.occupation_class))
    return (
        float(pricing.get("sale_price", 0)),
        float(pricing.get("policy_floor_price", 0)),
        float(pricing.get("total_commission_amount", 0)),
        float(pricing.get("agent_commission_amount", 0)),
    )


def _premium_detail_payload(
    start: date,
    end: date,
    user: User,
    session: Session,
    enterprise_id: int | None = None,
    insurer: str = "",
    agent_id: int | None = None,
) -> dict:
    rows = []
    if user.role == "enterprise":
        if enterprise_id is not None and enterprise_id != user.enterprise_id:
            raise HTTPException(403, "无权查询其他投保单位")
        if agent_id is not None:
            raise HTTPException(403, "企业端无权按业务员查询佣金")
        scoped_enterprise_id = user.enterprise_id
    else:
        scoped_enterprise_id = enterprise_id
        if agent_id is not None:
            agent = session.get(User, agent_id)
            if not agent or agent.role != "salesperson":
                raise HTTPException(404, "业务员不存在")
    insurer_filter = insurer.strip()
    members = session.scalars(select(PolicyMember).order_by(PolicyMember.effective_at.asc(), PolicyMember.id.asc())).all()
    for member in members:
        policy = session.get(Policy, member.policy_id)
        if not policy or (scoped_enterprise_id is not None and scoped_enterprise_id != policy.enterprise_id):
            continue
        person = session.get(InsuredPerson, member.person_id)
        if not person:
            continue
        plan = session.get(InsurancePlan, policy.plan_id)
        if insurer_filter and (not plan or plan.insurer != insurer_filter):
            continue
        billable = billable_date_range(member, start, end)
        if billable is None:
            continue
        period_start, period_end = billable
        enterprise = session.get(Enterprise, policy.enterprise_id)
        row_agent_id = enterprise.agent_id if enterprise else None
        if agent_id is not None and row_agent_id != agent_id:
            continue
        row_agent = session.get(User, row_agent_id) if row_agent_id else None
        position = session.get(WorkPosition, person.position_id) if person.position_id else None
        employer = session.get(ActualEmployer, position.actual_employer_id) if position and position.actual_employer_id else None
        billing_mode = plan.billing_mode if plan else "monthly"
        unit_price, unit_floor_price, unit_commission, unit_agent_commission = _member_prices(session, member, policy, person, plan)
        active_days = (period_end - period_start).days + 1
        premium = amount(_period_premium(unit_price, billing_mode, period_start, period_end))
        settlement = amount(_period_premium(unit_floor_price, billing_mode, period_start, period_end))
        commission = amount(_period_premium(unit_commission, billing_mode, period_start, period_end))
        agent_commission = amount(_period_premium(unit_agent_commission, billing_mode, period_start, period_end))
        rows.append({
            "member_id": member.id,
            "person_id": person.id,
            "person_name": person.name,
            "id_number": person.id_number,
            "enterprise_name": enterprise.name if enterprise else "",
            "agent_id": row_agent_id,
            "agent_name": row_agent.name if row_agent else "",
            "actual_employer_name": employer.name if employer else (position.actual_employer if position else ""),
            "position_name": position.name if position else person.occupation,
            "occupation_class": person.occupation_class,
            "policy_no": policy.policy_no,
            "insurer": plan.insurer if plan else "",
            "plan_name": plan.name if plan else "",
            "billing_mode": billing_mode,
            "unit_sale_price": amount(unit_price),
            "unit_policy_floor_price": amount(unit_floor_price),
            "unit_total_commission": amount(unit_commission),
            "unit_agent_commission": amount(unit_agent_commission),
            "coverage_start": member.effective_at,
            "coverage_end": member.terminated_at,
            "period_start": period_start.isoformat(),
            "period_end": period_end.isoformat(),
            "active_days": active_days,
            "premium_amount": premium,
            "settlement_amount": settlement,
            "commission_amount": commission,
            "agent_commission_amount": agent_commission,
        })
    platform_view = user.role == "admin"
    response_rows = rows if platform_view else [
        {key: value for key, value in row.items() if key not in {"agent_id", "agent_name", "unit_policy_floor_price", "settlement_amount", "unit_total_commission", "unit_agent_commission", "commission_amount", "agent_commission_amount"}}
        for row in rows
    ]
    return {
        "start_date": start.isoformat(),
        "end_date": end.isoformat(),
        "as_of_date": min(end, business_today()).isoformat(),
        "total_premium": amount(sum(row["premium_amount"] for row in rows)),
        "total_settlement": amount(sum(row["settlement_amount"] for row in rows)) if platform_view else 0,
        "total_commission": amount(sum(row["commission_amount"] for row in rows)) if platform_view else 0,
        "total_agent_commission": amount(sum(row["agent_commission_amount"] for row in rows)) if platform_view else 0,
        "detail_count": len(rows),
        "enterprise_id": scoped_enterprise_id,
        "insurer": insurer_filter,
        "agent_id": agent_id,
        "rows": response_rows,
    }


@router.get("/reports")
def reports(user: User = Depends(current_user), session: Session = Depends(db)):
    enterprise_id = user.enterprise_id if user.role == "enterprise" else None
    policy_rows = session.scalars(select(Policy).where(Policy.enterprise_id == enterprise_id) if enterprise_id else select(Policy)).all();policies=[policy_dict(x,session) for x in policy_rows]
    people = session.query(InsuredPerson).filter(InsuredPerson.enterprise_id == enterprise_id).count() if enterprise_id else session.query(InsuredPerson).count()
    claims = session.query(Claim).filter(Claim.enterprise_id == enterprise_id).count() if enterprise_id else session.query(Claim).count()
    now=business_today()
    current_detail = _premium_detail_payload(now.replace(day=1), now, user, session)
    premium = current_detail["total_premium"]
    settlement=current_detail["total_settlement"];commission=current_detail["total_commission"]
    period_label=f"{now.year}-{now.month:02d}截至{now.day}日"
    premium_row={"id":"premium","name":"销售保费汇总","period":period_label,"value":premium,"detail":f"{len(policies)} 张保单，按日折算并累计至当前日期"}
    people_row={"id":"people","name":"参保人员报表","period":"当前","value":people,"detail":"在册参保人员"}
    claims_row={"id":"claims","name":"理赔统计报表","period":"累计","value":claims,"detail":"理赔案件"}
    usage_enterprises = [session.get(Enterprise, enterprise_id)] if enterprise_id else session.scalars(select(Enterprise)).all()
    usage_fee = amount(sum(usage_person_days(session, item.id, now.replace(day=1), now)["person_days"] * float(item.usage_fee_daily or 0.1) for item in usage_enterprises if item))
    usage_row={"id":"usage_fee","name":"平台使用费","period":period_label,"value":usage_fee,"detail":"每人日费率 × 本月有效参保人天"}
    if user.role == "enterprise": return [premium_row,usage_row,people_row,claims_row]
    return [premium_row,{"id":"settlement","name":"保司结算底价","period":period_label,"value":settlement,"detail":"结算底价按日折算并累计至当前日期"},{"id":"commission","name":"总返佣金额","period":period_label,"value":commission,"detail":"返佣单价按日折算并累计至当前日期"},usage_row,people_row,claims_row]


@router.get("/reports/premium-details")
def premium_details(start_date: str = Query(...), end_date: str = Query(...), enterprise_id: int | None = Query(default=None), insurer: str = Query(default=""), agent_id: int | None = None, user: User = Depends(current_user), session: Session = Depends(db)):
    start, end = _parse_report_range(start_date, end_date)
    return _premium_detail_payload(start, end, user, session, enterprise_id, insurer, agent_id)


@router.get("/reports/premium-details/export")
def export_premium_details(start_date: str = Query(...), end_date: str = Query(...), enterprise_id: int | None = Query(default=None), insurer: str = Query(default=""), agent_id: int | None = None, user: User = Depends(current_user), session: Session = Depends(db)):
    start, end = _parse_report_range(start_date, end_date)
    payload = _premium_detail_payload(start, end, user, session, enterprise_id, insurer, agent_id)
    book = openpyxl.Workbook(); sheet = book.active; sheet.title = "销售保费明细"
    platform_export = user.role == "admin"
    headers = ["统计开始", "统计结束", "被保险人", "身份证号", "投保单位", "实际用工单位", "岗位", "职业类别", "保单号", "保险公司", "保险方案", "计费方式", "实际销售价", "本期开始", "本期结束", "计费天数", "保费金额"]
    if platform_export:
        headers.insert(5, "业务员")
        headers += ["保司结算底价", "保司结算金额", "总返佣单价", "总返佣金额", "业务员佣金单价", "业务员佣金金额"]
    sheet.append(headers)
    for row in payload["rows"]:
        values = [payload["start_date"], payload["end_date"], row["person_name"], row["id_number"], row["enterprise_name"], row["actual_employer_name"], row["position_name"], row["occupation_class"], row["policy_no"], row["insurer"], row["plan_name"], "按天" if row["billing_mode"] == "daily" else "按月", row["unit_sale_price"], row["period_start"], row["period_end"], row["active_days"], row["premium_amount"]]
        if platform_export:
            values.insert(5, row["agent_name"])
            values += [row["unit_policy_floor_price"], row["settlement_amount"], row["unit_total_commission"], row["commission_amount"], row["unit_agent_commission"], row["agent_commission_amount"]]
        sheet.append(values)
    sheet.append([]); sheet.append(["销售保费总额", payload["total_premium"]])
    if platform_export: sheet.append(["保司结算总额", payload["total_settlement"]])
    if platform_export: sheet.append(["总返佣金额", payload["total_commission"]])
    if platform_export: sheet.append(["业务员佣金金额", payload["total_agent_commission"]])
    for column in sheet.columns: sheet.column_dimensions[column[0].column_letter].width = min(32, max(12, max(len(str(cell.value or "")) for cell in column) + 2))
    output = io.BytesIO(); book.save(output); output.seek(0)
    filename = f"premium-details-{start.isoformat()}-{end.isoformat()}.xlsx"
    return StreamingResponse(output, media_type="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", headers={"Content-Disposition": f"attachment; filename={filename}"})

@router.get("/billing")
def billing(user: User = Depends(current_user), session: Session = Depends(db)):
    stmt = select(Enterprise).where(Enterprise.id == user.enterprise_id) if user.role == "enterprise" and user.enterprise_id else select(Enterprise)
    rows=[]
    for x in session.scalars(stmt):
        today = business_today(); rate = float(x.usage_fee_daily or 0.1)
        month = usage_person_days(session, x.id, today.replace(day=1), today)
        lifetime = usage_person_days(session, x.id, requested_end=today)
        common={"active_people":month["active_people"],"month_person_days":month["person_days"],"month_accrued":amount(month["person_days"]*rate),"total_person_days":lifetime["person_days"],"total_accrued":amount(lifetime["person_days"]*rate),"as_of_date":today.isoformat()}
        rows.append({"id":x.id,"enterprise_name":x.name,"account":"保费账户","balance":x.premium_balance,"status":"正常","daily_rate":0,"estimated_daily":0,"monthly_estimate":0,**{key:0 if key != "as_of_date" else today.isoformat() for key in common}})
        rows.append({"id":x.id,"enterprise_name":x.name,"account":"平台使用费账户","balance":x.usage_balance,"status":"正常","daily_rate":rate,"estimated_daily":amount(month["active_people"]*rate),"monthly_estimate":amount(month["person_days"]*rate),**common})
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
