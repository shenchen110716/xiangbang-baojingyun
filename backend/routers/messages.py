from datetime import datetime, timezone

from fastapi import APIRouter, Depends
from sqlalchemy import select
from sqlalchemy.orm import Session

from ..core.business_time import business_today
from ..core.db import db
from ..core.security import current_user
from ..models import Claim, Enterprise, InsuredPerson, User, WorkPosition
from ..services import usage_person_days

router = APIRouter(prefix="/api", tags=["messages"])


@router.get("/messages")
def messages(user:User=Depends(current_user),session:Session=Depends(db)):
    enterprise_ids=[user.enterprise_id] if user.role=='enterprise' and user.enterprise_id else [x for x in session.scalars(select(Enterprise.id))]
    now=datetime.now(timezone.utc);rows=[]
    for enterprise_id in enterprise_ids:
        enterprise=session.get(Enterprise,enterprise_id)
        if not enterprise: continue
        today=business_today();active_count=usage_person_days(session,enterprise_id,today,today)['active_people'];usage_daily=active_count*float(enterprise.usage_fee_daily or 0.1)
        if usage_daily>0 and enterprise.usage_balance/usage_daily<=int(enterprise.alert_days or 3): rows.append({'id':f'balance-{enterprise_id}','type':'warning','title':'使用费账户余额预警','content':f'{enterprise.name}余额预计可用 {enterprise.usage_balance/usage_daily:.1f} 天','created_at':now.isoformat(),'path':'/pages/billing/billing'})
        pending=session.query(InsuredPerson).filter(InsuredPerson.enterprise_id==enterprise_id,InsuredPerson.status=='pending').count()
        if pending: rows.append({'id':f'pending-{enterprise_id}','type':'todo','title':'员工待审核','content':f'{pending} 名员工正在等待参保审核','created_at':now.isoformat(),'path':'/pages/employees/employees'})
        supplements=session.query(Claim).filter(Claim.enterprise_id==enterprise_id,Claim.status=='supplement').count()
        if supplements: rows.append({'id':f'claim-{enterprise_id}','type':'danger','title':'理赔材料待补充','content':f'{supplements} 件理赔需要补充材料','created_at':now.isoformat(),'path':'/pages/claims/claims'})
        pending_positions=session.query(WorkPosition).filter(WorkPosition.enterprise_id==enterprise_id,WorkPosition.status.in_(['pending','supplement'])).count()
        if pending_positions: rows.append({'id':f'position-{enterprise_id}','type':'todo','title':'岗位定类进度','content':f'{pending_positions} 个岗位待审核或补充材料','created_at':now.isoformat(),'path':'/pages/positions/positions'})
    if not rows: rows.append({'id':'welcome','type':'success','title':'当前没有待办','content':'所有参保、账户和理赔业务运行正常','created_at':now.isoformat(),'path':'/pages/home/home'})
    return rows
