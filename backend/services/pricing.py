from typing import Optional

from fastapi import HTTPException
from sqlalchemy import select
from sqlalchemy.orm import Session

from ..models import AgentCommission, InsurancePlan, PlanTier
from .serialization import amount, serialize


def plan_price_for_class(session:Session,plan:InsurancePlan,occupation_class:str="") -> float:
    if occupation_class:
        tier=session.scalar(select(PlanTier).where(PlanTier.plan_id==plan.id,PlanTier.occupation_class==occupation_class,PlanTier.status=='active').order_by(PlanTier.id.desc()))
        if tier: return float(tier.price or 0)
    return float(plan.price or 0)

def pricing_snapshot(plan:InsurancePlan,relation:Optional[AgentCommission]=None,base_price:Optional[float]=None) -> dict:
    insurance_base=float(plan.price if base_price is None else base_price)
    total_rate=float(plan.commission_rate or 0)
    total_commission=insurance_base*total_rate
    floor=insurance_base-total_commission
    profit=float(plan.profit_amount or 0)
    minimum=floor+profit
    mode='price' if relation and relation.mode in {'price','markup'} else 'rebate'
    ratio=float(relation.rate or 0) if relation else 0
    if mode=='price':
        configured=float(relation.sale_price or 0) if relation else 0
        if configured<=0 and relation: configured=minimum+float(relation.markup_amount or 0)
        sale=max(minimum,configured or minimum)
        agent_commission=max(0,sale-minimum)
    else:
        sale=minimum
        agent_commission=insurance_base*ratio
    return {
        'insurance_base_price':amount(insurance_base),
        'total_commission_rate':round(total_rate,6),
        'total_commission_amount':amount(total_commission),
        'policy_floor_price':amount(floor),
        'insurer_settlement_price':amount(floor),
        'profit_amount':amount(profit),
        'minimum_sale_price':amount(minimum),
        'commission_mode':mode,
        'agent_commission_rate':round(ratio,6) if mode=='rebate' else 0,
        'agent_commission_amount':amount(agent_commission),
        'sale_price':amount(sale),
        'platform_margin_amount':amount(max(0,profit-agent_commission) if mode=='rebate' else profit),
    }

def plan_dict(plan:InsurancePlan) -> dict:
    return {**serialize(plan),**pricing_snapshot(plan)}

def validate_commission_price(data,plan:InsurancePlan):
    mode='price' if data.mode in {'price','markup'} else 'rebate'
    minimum=pricing_snapshot(plan)['minimum_sale_price']
    if mode=='rebate' and float(data.rate or 0)>float(plan.commission_rate or 0): raise HTTPException(400,'业务员返佣比例不能超过产品总返佣比例')
    sale=float(getattr(data,'sale_price',0) or 0)
    if mode=='price' and sale<=0: sale=minimum+float(getattr(data,'markup_amount',0) or 0)
    if mode=='price' and sale<minimum: raise HTTPException(400,f'销售价格不能低于销售最低价 ¥{minimum:.2f}')
    return mode,sale
