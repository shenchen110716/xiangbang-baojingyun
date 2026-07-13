import csv
import io
from typing import Literal

import openpyxl
from fastapi import APIRouter, Depends, File, Form, HTTPException, Query, UploadFile
from fastapi.responses import StreamingResponse
from sqlalchemy import or_, select
from sqlalchemy.orm import Session

from ..core.audit import audit
from ..core.db import db
from ..core.security import current_user
from ..models import ActualEmployer, AgentCommission, Enterprise, InsurancePlan, InsuredPerson, Policy, User, WorkPosition
from ..schemas import BulkPersonIn, PersonIn, PersonUpdate
from ..services import activate_person_policy, plan_price_for_class, pricing_snapshot, serialize, terminate_person_policy

router = APIRouter(prefix="/api", tags=["insured"])


@router.get("/insured")
def insured(q: str = "", user: User = Depends(current_user), session: Session = Depends(db)):
    stmt = select(InsuredPerson).order_by(InsuredPerson.id.desc())
    if user.role=='enterprise' and user.enterprise_id: stmt=stmt.where(InsuredPerson.enterprise_id==user.enterprise_id)
    if q: stmt = stmt.where(or_(InsuredPerson.name.contains(q), InsuredPerson.phone.contains(q)))
    result=[]
    for x in session.scalars(stmt):
        item=serialize(x);enterprise=session.get(Enterprise,x.enterprise_id);position=session.get(WorkPosition,x.position_id) if x.position_id else None;employer=session.get(ActualEmployer,position.actual_employer_id) if position and position.actual_employer_id else None;plan=session.get(InsurancePlan,position.plan_id) if position and position.plan_id else None;policy=session.get(Policy,x.policy_id) if x.policy_id else None
        relation=session.scalar(select(AgentCommission).where(AgentCommission.enterprise_id==x.enterprise_id,AgentCommission.plan_id==plan.id,AgentCommission.status=='active').order_by(AgentCommission.id.desc())) if plan else None
        item.update(enterprise_name=enterprise.name if enterprise else '',position_name=position.name if position else x.occupation,actual_employer_name=employer.name if employer else (position.actual_employer if position else ''),plan_id=plan.id if plan else None,plan_name=plan.name if plan else '',insurer=plan.insurer if plan else '',policy_no=policy.policy_no if policy else '',policy_status=policy.status if policy else '',**(pricing_snapshot(plan,relation,plan_price_for_class(session,plan,x.occupation_class)) if plan else {}))
        result.append(item)
    return result

@router.post("/insured")
def add_person(data: PersonIn, user: User = Depends(current_user), session: Session = Depends(db)):
    if not session.get(Enterprise, data.enterprise_id): raise HTTPException(404, "企业不存在")
    if user.role=="enterprise" and user.enterprise_id!=data.enterprise_id: raise HTTPException(403,"无权操作该单位")
    if session.scalar(select(InsuredPerson.id).where(InsuredPerson.enterprise_id==data.enterprise_id,InsuredPerson.id_number==data.id_number).limit(1)): raise HTTPException(409,'该身份证号已存在')
    payload=data.model_dump()
    if data.position_id:
        position=session.get(WorkPosition,data.position_id)
        if not position or position.enterprise_id!=data.enterprise_id or position.status!='approved': raise HTTPException(400,"只能选择本单位已审核通过的有效岗位")
        payload['occupation']=position.name; payload['occupation_class']=position.occupation_class
    item = InsuredPerson(**payload); session.add(item); session.commit(); session.refresh(item); audit(session, user, "create", "insured_person", str(item.id)); return serialize(item)

@router.patch("/insured/{item_id}")
def update_person(item_id:int,data:PersonUpdate,user:User=Depends(current_user),session:Session=Depends(db)):
    item=session.get(InsuredPerson,item_id)
    if not item: raise HTTPException(404,'参保员工不存在')
    if user.role=='enterprise' and user.enterprise_id!=item.enterprise_id: raise HTTPException(403,'无权操作该员工')
    values=data.model_dump(exclude_unset=True)
    if 'id_number' in values and values['id_number']!=item.id_number and session.scalar(select(InsuredPerson.id).where(InsuredPerson.enterprise_id==item.enterprise_id,InsuredPerson.id_number==values['id_number'],InsuredPerson.id!=item.id).limit(1)): raise HTTPException(409,'该身份证号已存在')
    if 'position_id' in values:
        position=session.get(WorkPosition,values['position_id'])
        if not position or position.enterprise_id!=item.enterprise_id or position.status!='approved': raise HTTPException(400,'只能选择本单位已审核通过的有效岗位')
        item.position_id=position.id;item.occupation=position.name;item.occupation_class=position.occupation_class
    for key in ('name','phone','id_number'):
        if key in values and values[key] is not None: setattr(item,key,values[key])
    session.commit();audit(session,user,'update','insured_person',str(item.id));return serialize(item)

@router.patch("/insured/{item_id}/status")
def insured_status(item_id:int,status_value:Literal["active","stopped","pending"]=Query(...,alias="status"),user:User=Depends(current_user),session:Session=Depends(db)):
    item=session.get(InsuredPerson,item_id)
    if not item: raise HTTPException(404,"参保员工不存在")
    if user.role=="enterprise" and user.enterprise_id!=item.enterprise_id: raise HTTPException(403,"无权操作该员工")
    previous_status=item.status;item.status=status_value
    if status_value=="active" and previous_status!="active": activate_person_policy(session,item)
    elif previous_status=="active" and status_value!="active": terminate_person_policy(session,item)
    session.commit();audit(session,user,"status_change","insured_person",str(item.id),status_value);return serialize(item)

@router.get("/insured/import-template")
def insured_import_template(user:User=Depends(current_user)):
    content='姓名,身份证号,手机号\n张三,340123199001011234,13800000000\n'
    return StreamingResponse(iter([content.encode('utf-8-sig')]),media_type='text/csv',headers={'Content-Disposition':'attachment; filename=insured-import-template.csv'})

@router.post("/insured/bulk")
def bulk_add_people(data:BulkPersonIn,user:User=Depends(current_user),session:Session=Depends(db)):
    if user.role=='enterprise' and user.enterprise_id!=data.enterprise_id: raise HTTPException(403,'无权操作该单位')
    position=session.get(WorkPosition,data.position_id)
    if not position or position.enterprise_id!=data.enterprise_id or position.status!='approved': raise HTTPException(400,'只能选择本单位已审核通过的岗位')
    errors=[];created=[];seen=set()
    for index,row in enumerate(data.rows,start=2):
        identity=row.id_number.strip();name=row.name.strip()
        if not name or not identity: errors.append({'row':index,'message':'姓名和身份证号必填'});continue
        if identity in seen or session.scalar(select(InsuredPerson.id).where(InsuredPerson.id_number==identity).limit(1)): errors.append({'row':index,'message':'身份证号重复'});continue
        seen.add(identity);item=InsuredPerson(enterprise_id=data.enterprise_id,position_id=position.id,name=name,id_number=identity,phone=row.phone.strip(),occupation=position.name,occupation_class=position.occupation_class,status='pending');session.add(item);created.append(item)
    if errors: session.rollback();return {'ok':False,'created':0,'errors':errors}
    session.commit()
    for item in created: session.refresh(item)
    audit(session,user,'bulk_create','insured_person',','.join(str(x.id) for x in created),f'count={len(created)}')
    return {'ok':True,'created':len(created),'errors':[],'ids':[x.id for x in created]}

@router.post("/insured/import-file")
async def import_insured_file(kind:Literal['enrollment','termination']=Form(...),enterprise_id:int=Form(...),position_id:int=Form(0),file:UploadFile=File(...),user:User=Depends(current_user),session:Session=Depends(db)):
    if user.role=='enterprise' and user.enterprise_id!=enterprise_id: raise HTTPException(403,'无权操作该单位')
    if not session.get(Enterprise,enterprise_id): raise HTTPException(404,'投保单位不存在')
    position=None
    if kind=='enrollment':
        position=session.get(WorkPosition,position_id)
        if not position or position.enterprise_id!=enterprise_id or position.status!='approved': raise HTTPException(400,'批量参保必须选择本单位已审核通过的岗位')
    content=await file.read();name=(file.filename or '').lower();raw=[]
    try:
        if name.endswith('.xlsx'):
            sheet=openpyxl.load_workbook(io.BytesIO(content),read_only=True,data_only=True).active
            raw=[[str(v or '').strip() for v in row] for row in sheet.iter_rows(values_only=True)]
        elif name.endswith('.csv'):
            raw=[[str(v).strip() for v in row] for row in csv.reader(io.StringIO(content.decode('utf-8-sig')))]
        else: raise HTTPException(400,'仅支持 CSV 或 XLSX 电子表格')
    except HTTPException: raise
    except Exception as exc: raise HTTPException(400,f'电子表格解析失败：{exc}')
    if len(raw)<2: raise HTTPException(400,'电子表格没有可导入的数据')
    headers={x.replace(' ',''):i for i,x in enumerate(raw[0])};name_col=headers.get('姓名');id_col=headers.get('身份证号');phone_col=headers.get('手机号')
    if id_col is None or (kind=='enrollment' and name_col is None): raise HTTPException(400,'模板必须包含姓名、身份证号；停保模板至少包含身份证号')
    errors=[];pending=[];seen=set()
    for row_no,row in enumerate(raw[1:],start=2):
        identity=row[id_col].strip() if id_col<len(row) else '';person_name=row[name_col].strip() if name_col is not None and name_col<len(row) else '';phone=row[phone_col].strip() if phone_col is not None and phone_col<len(row) else ''
        if not identity: errors.append({'row':row_no,'message':'身份证号必填'});continue
        if identity in seen: errors.append({'row':row_no,'message':'表格内身份证号重复'});continue
        seen.add(identity)
        existing=session.scalar(select(InsuredPerson).where(InsuredPerson.enterprise_id==enterprise_id,InsuredPerson.id_number==identity))
        if kind=='enrollment':
            if not person_name: errors.append({'row':row_no,'message':'姓名必填'});continue
            if existing and existing.status!='stopped': errors.append({'row':row_no,'message':'该员工已在保或待审核'});continue
            pending.append(('create',person_name,identity,phone,existing))
        else:
            if not existing: errors.append({'row':row_no,'message':'未找到该单位参保员工'});continue
            if existing.status=='stopped': errors.append({'row':row_no,'message':'该员工已停保'});continue
            pending.append(('stop',person_name,identity,phone,existing))
    if errors: return {'ok':False,'kind':kind,'success':0,'errors':errors}
    affected=[]
    for action,person_name,identity,phone,existing in pending:
        if action=='create':
            if existing:
                existing.name=person_name;existing.phone=phone;existing.position_id=position.id;existing.occupation=position.name;existing.occupation_class=position.occupation_class;existing.status='pending';item=existing
            else:
                item=InsuredPerson(enterprise_id=enterprise_id,position_id=position.id,name=person_name,id_number=identity,phone=phone,occupation=position.name,occupation_class=position.occupation_class,status='pending');session.add(item)
        else:
            if existing.status=='active': terminate_person_policy(session,existing)
            existing.status='stopped';item=existing
        affected.append(item)
    session.commit();audit(session,user,'bulk_enrollment' if kind=='enrollment' else 'bulk_termination','insured_person','',f'count={len(affected)};file={file.filename}')
    return {'ok':True,'kind':kind,'success':len(affected),'errors':[]}
