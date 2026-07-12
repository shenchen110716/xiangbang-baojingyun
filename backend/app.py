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
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from fastapi.staticfiles import StaticFiles
from passlib.context import CryptContext
from pydantic import BaseModel, ConfigDict, Field
from sqlalchemy import Boolean, DateTime, Float, ForeignKey, Integer, String, Text, or_, select
from sqlalchemy.orm import Mapped, Session, mapped_column
from .providers import insurer_provider, sms_provider, email_provider, payment_provider
from .core.config import ROOT, DATABASE_URL, SECRET_KEY, ALGORITHM
from .core.db import Base, engine, SessionLocal, db

pwd = CryptContext(schemes=["pbkdf2_sha256"], deprecated="auto")

class User(Base):
    __tablename__ = "users"
    id: Mapped[int] = mapped_column(primary_key=True)
    username: Mapped[str] = mapped_column(String(80), unique=True, index=True)
    password_hash: Mapped[str] = mapped_column(String(255))
    name: Mapped[str] = mapped_column(String(80), default="平台管理员")
    role: Mapped[str] = mapped_column(String(40), default="admin")
    enterprise_id: Mapped[Optional[int]] = mapped_column(Integer, nullable=True)
    phone: Mapped[str] = mapped_column(String(30), default="")
    status: Mapped[str] = mapped_column(String(30), default="active")
    active: Mapped[bool] = mapped_column(Boolean, default=True)
    is_owner: Mapped[bool] = mapped_column(Boolean, default=False)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=lambda: datetime.now(timezone.utc))

class Enterprise(Base):
    __tablename__ = "enterprises"
    id: Mapped[int] = mapped_column(primary_key=True)
    name: Mapped[str] = mapped_column(String(160), index=True)
    kind: Mapped[str] = mapped_column(String(30), default="企业")
    credit_code: Mapped[str] = mapped_column(String(40), default="")
    contact: Mapped[str] = mapped_column(String(80), default="")
    phone: Mapped[str] = mapped_column(String(30), default="")
    status: Mapped[str] = mapped_column(String(30), default="pending")
    agent_id: Mapped[Optional[int]] = mapped_column(Integer, nullable=True)
    premium_balance: Mapped[float] = mapped_column(Float, default=0)
    usage_balance: Mapped[float] = mapped_column(Float, default=0)
    usage_fee_daily: Mapped[float] = mapped_column(Float, default=0.1)
    alert_days: Mapped[int] = mapped_column(Integer, default=3)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=lambda: datetime.now(timezone.utc))

class ActualEmployer(Base):
    __tablename__ = "actual_employers"
    id: Mapped[int] = mapped_column(primary_key=True)
    enterprise_id: Mapped[int] = mapped_column(ForeignKey("enterprises.id"))
    name: Mapped[str] = mapped_column(String(160))
    credit_code: Mapped[str] = mapped_column(String(40), default="")
    contact: Mapped[str] = mapped_column(String(80), default="")
    phone: Mapped[str] = mapped_column(String(30), default="")
    status: Mapped[str] = mapped_column(String(30), default="active")
    created_at: Mapped[datetime] = mapped_column(DateTime, default=lambda: datetime.now(timezone.utc))

class WorkPosition(Base):
    __tablename__ = "work_positions"
    id: Mapped[int] = mapped_column(primary_key=True)
    enterprise_id: Mapped[int] = mapped_column(ForeignKey("enterprises.id"))
    actual_employer_id: Mapped[Optional[int]] = mapped_column(ForeignKey("actual_employers.id"), nullable=True)
    actual_employer: Mapped[str] = mapped_column(String(160), default="")
    name: Mapped[str] = mapped_column(String(100))
    occupation_class: Mapped[str] = mapped_column(String(30), default="待定")
    plan_id: Mapped[Optional[int]] = mapped_column(ForeignKey("insurance_plans.id"), nullable=True)
    status: Mapped[str] = mapped_column(String(30), default="pending")
    created_at: Mapped[datetime] = mapped_column(DateTime, default=lambda: datetime.now(timezone.utc))

class PositionVideo(Base):
    __tablename__ = "position_videos"
    id: Mapped[int] = mapped_column(primary_key=True)
    position_id: Mapped[int] = mapped_column(ForeignKey("work_positions.id"))
    name: Mapped[str] = mapped_column(String(160))
    url: Mapped[str] = mapped_column(Text, default="")
    status: Mapped[str] = mapped_column(String(30), default="pending")
    review_note: Mapped[str] = mapped_column(Text, default="")
    created_at: Mapped[datetime] = mapped_column(DateTime, default=lambda: datetime.now(timezone.utc))

class AgentCommission(Base):
    __tablename__ = "agent_commissions"
    id: Mapped[int] = mapped_column(primary_key=True)
    agent_id: Mapped[int] = mapped_column(ForeignKey("users.id"))
    enterprise_id: Mapped[int] = mapped_column(ForeignKey("enterprises.id"))
    plan_id: Mapped[int] = mapped_column(ForeignKey("insurance_plans.id"))
    rate: Mapped[float] = mapped_column(Float, default=.15)
    mode: Mapped[str] = mapped_column(String(20), default="rebate")
    markup_amount: Mapped[float] = mapped_column(Float, default=0)
    sale_price: Mapped[float] = mapped_column(Float, default=0)
    status: Mapped[str] = mapped_column(String(30), default="active")
    created_at: Mapped[datetime] = mapped_column(DateTime, default=lambda: datetime.now(timezone.utc))

class InsurancePlan(Base):
    __tablename__ = "insurance_plans"
    id: Mapped[int] = mapped_column(primary_key=True)
    insurer: Mapped[str] = mapped_column(String(100))
    insurer_email: Mapped[str] = mapped_column(String(160), default="")
    name: Mapped[str] = mapped_column(String(160))
    coverage: Mapped[str] = mapped_column(Text, default="")
    occupation_classes: Mapped[str] = mapped_column(String(100), default="1-4类")
    price: Mapped[float] = mapped_column(Float, default=0)
    commission_rate: Mapped[float] = mapped_column(Float, default=0)
    profit_amount: Mapped[float] = mapped_column(Float, default=0)
    payment_mode: Mapped[str] = mapped_column(String(30), default="企业直投")
    billing_mode: Mapped[str] = mapped_column(String(20), default="monthly")
    effective_mode: Mapped[str] = mapped_column(String(20), default="next_day")
    status: Mapped[str] = mapped_column(String(30), default="active")
    created_at: Mapped[datetime] = mapped_column(DateTime, default=lambda: datetime.now(timezone.utc))

class PlanTier(Base):
    __tablename__ = "plan_tiers"
    id: Mapped[int] = mapped_column(primary_key=True)
    plan_id: Mapped[int] = mapped_column(ForeignKey("insurance_plans.id"))
    occupation_class: Mapped[str] = mapped_column(String(30))
    price: Mapped[float] = mapped_column(Float, default=0)
    coverage: Mapped[str] = mapped_column(Text, default="")
    status: Mapped[str] = mapped_column(String(30), default="active")
    created_at: Mapped[datetime] = mapped_column(DateTime, default=lambda: datetime.now(timezone.utc))

class InsuredPerson(Base):
    __tablename__ = "insured_people"
    id: Mapped[int] = mapped_column(primary_key=True)
    enterprise_id: Mapped[int] = mapped_column(ForeignKey("enterprises.id"))
    name: Mapped[str] = mapped_column(String(80))
    phone: Mapped[str] = mapped_column(String(30), default="")
    id_number: Mapped[str] = mapped_column(String(40), default="")
    occupation: Mapped[str] = mapped_column(String(80), default="")
    occupation_class: Mapped[str] = mapped_column(String(20), default="3类")
    position_id: Mapped[Optional[int]] = mapped_column(ForeignKey("work_positions.id"), nullable=True)
    status: Mapped[str] = mapped_column(String(30), default="pending")
    policy_id: Mapped[Optional[int]] = mapped_column(ForeignKey("policies.id"), nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=lambda: datetime.now(timezone.utc))

class Policy(Base):
    __tablename__ = "policies"
    id: Mapped[int] = mapped_column(primary_key=True)
    policy_no: Mapped[str] = mapped_column(String(80), unique=True)
    enterprise_id: Mapped[int] = mapped_column(ForeignKey("enterprises.id"))
    plan_id: Mapped[int] = mapped_column(ForeignKey("insurance_plans.id"))
    premium: Mapped[float] = mapped_column(Float, default=0)
    status: Mapped[str] = mapped_column(String(30), default="active")
    start_date: Mapped[str] = mapped_column(String(20), default="")
    end_date: Mapped[str] = mapped_column(String(20), default="")
    created_at: Mapped[datetime] = mapped_column(DateTime, default=lambda: datetime.now(timezone.utc))

class Claim(Base):
    __tablename__ = "claims"
    id: Mapped[int] = mapped_column(primary_key=True)
    enterprise_id: Mapped[int] = mapped_column(ForeignKey("enterprises.id"))
    person_id: Mapped[int] = mapped_column(ForeignKey("insured_people.id"))
    claim_no: Mapped[str] = mapped_column(String(80), unique=True)
    description: Mapped[str] = mapped_column(Text, default="")
    status: Mapped[str] = mapped_column(String(30), default="reported")
    amount: Mapped[float] = mapped_column(Float, default=0)
    accident_at: Mapped[str] = mapped_column(String(30), default="")
    accident_place: Mapped[str] = mapped_column(String(200), default="")
    accident_type: Mapped[str] = mapped_column(String(60), default="工伤事故")
    hospital: Mapped[str] = mapped_column(String(160), default="")
    diagnosis: Mapped[str] = mapped_column(Text, default="")
    medical_cost: Mapped[float] = mapped_column(Float, default=0)
    contact_name: Mapped[str] = mapped_column(String(80), default="")
    contact_phone: Mapped[str] = mapped_column(String(30), default="")
    insurer_report_no: Mapped[str] = mapped_column(String(100), default="")
    current_handler: Mapped[str] = mapped_column(String(80), default="平台理赔专员")
    deadline: Mapped[str] = mapped_column(String(30), default="")
    approved_amount: Mapped[float] = mapped_column(Float, default=0)
    paid_at: Mapped[str] = mapped_column(String(30), default="")
    rejection_reason: Mapped[str] = mapped_column(Text, default="")
    review_note: Mapped[str] = mapped_column(Text, default="")
    sla_deadline: Mapped[str] = mapped_column(String(30), default="")
    risk_level: Mapped[str] = mapped_column(String(20), default="normal")
    created_at: Mapped[datetime] = mapped_column(DateTime, default=lambda: datetime.now(timezone.utc))

class ClaimTimeline(Base):
    __tablename__ = "claim_timelines"
    id: Mapped[int] = mapped_column(primary_key=True)
    claim_id: Mapped[int] = mapped_column(ForeignKey("claims.id"))
    node: Mapped[str] = mapped_column(String(40))
    action: Mapped[str] = mapped_column(String(100))
    note: Mapped[str] = mapped_column(Text, default="")
    operator: Mapped[str] = mapped_column(String(80), default="系统")
    created_at: Mapped[datetime] = mapped_column(DateTime, default=lambda: datetime.now(timezone.utc))

class AuditLog(Base):
    __tablename__ = "audit_logs"
    id: Mapped[int] = mapped_column(primary_key=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id"))
    action: Mapped[str] = mapped_column(String(100))
    object_type: Mapped[str] = mapped_column(String(80))
    object_id: Mapped[str] = mapped_column(String(80), default="")
    detail: Mapped[str] = mapped_column(Text, default="")
    created_at: Mapped[datetime] = mapped_column(DateTime, default=lambda: datetime.now(timezone.utc))

class ClaimDocument(Base):
    __tablename__ = "claim_documents"
    id: Mapped[int] = mapped_column(primary_key=True)
    claim_id: Mapped[int] = mapped_column(ForeignKey("claims.id"))
    name: Mapped[str] = mapped_column(String(160))
    url: Mapped[str] = mapped_column(Text, default="")
    doc_type: Mapped[str] = mapped_column(String(40), default="other")
    status: Mapped[str] = mapped_column(String(30), default="uploaded")
    review_note: Mapped[str] = mapped_column(Text, default="")
    created_at: Mapped[datetime] = mapped_column(DateTime, default=lambda: datetime.now(timezone.utc))

class PaymentRecord(Base):
    __tablename__ = "payment_records"
    id: Mapped[int] = mapped_column(primary_key=True)
    order_no: Mapped[str] = mapped_column(String(100), unique=True)
    enterprise_id: Mapped[int] = mapped_column(ForeignKey("enterprises.id"))
    account: Mapped[str] = mapped_column(String(20), default="premium")
    amount: Mapped[float] = mapped_column(Float, default=0)
    status: Mapped[str] = mapped_column(String(30), default="pending")
    provider: Mapped[str] = mapped_column(String(60), default="payment")
    created_at: Mapped[datetime] = mapped_column(DateTime, default=lambda: datetime.now(timezone.utc))

class Invoice(Base):
    __tablename__ = "invoices"
    id: Mapped[int] = mapped_column(primary_key=True)
    enterprise_id: Mapped[int] = mapped_column(ForeignKey("enterprises.id"))
    account: Mapped[str] = mapped_column(String(20), default="premium")
    amount: Mapped[float] = mapped_column(Float, default=0)
    title: Mapped[str] = mapped_column(String(160), default="")
    tax_no: Mapped[str] = mapped_column(String(40), default="")
    email: Mapped[str] = mapped_column(String(160), default="")
    status: Mapped[str] = mapped_column(String(30), default="pending")
    created_at: Mapped[datetime] = mapped_column(DateTime, default=lambda: datetime.now(timezone.utc))

class EnrollmentEmail(Base):
    __tablename__ = "enrollment_emails"
    id: Mapped[int] = mapped_column(primary_key=True)
    enterprise_id: Mapped[int] = mapped_column(ForeignKey("enterprises.id"))
    plan_id: Mapped[int] = mapped_column(ForeignKey("insurance_plans.id"))
    kind: Mapped[str] = mapped_column(String(20))
    recipient: Mapped[str] = mapped_column(String(160))
    filename: Mapped[str] = mapped_column(String(160))
    people_count: Mapped[int] = mapped_column(Integer, default=0)
    request_id: Mapped[str] = mapped_column(String(100), default="")
    status: Mapped[str] = mapped_column(String(30), default="sent")
    created_at: Mapped[datetime] = mapped_column(DateTime, default=lambda: datetime.now(timezone.utc))

class LoginIn(BaseModel): username: str; password: str; portal: Literal["admin","enterprise"] = "admin"
class EnterpriseIn(BaseModel): name: str; kind: str = "企业"; contact: str = ""; phone: str = ""; credit_code: str = ""; agent_id: Optional[int] = None; usage_fee_daily: float = Field(default=0.1, ge=0); alert_days: int = Field(default=3, ge=3, le=7)
class EnterpriseUpdate(BaseModel): name: Optional[str] = None; kind: Optional[str] = None; contact: Optional[str] = None; phone: Optional[str] = None; credit_code: Optional[str] = None; agent_id: Optional[int] = None; usage_fee_daily: Optional[float] = Field(default=None, ge=0); alert_days: Optional[int] = Field(default=None, ge=3, le=7)
class PositionIn(BaseModel): enterprise_id: Optional[int] = None; actual_employer: str; actual_employer_id: Optional[int] = None; name: str; occupation_class: Literal["1-3类","4类","5类","超5类"] = "1-3类"; plan_id: Optional[int] = None
class ActualEmployerIn(BaseModel): enterprise_id: Optional[int] = None; name: str; credit_code: str = ""; contact: str = ""; phone: str = ""
class ActualEmployerUpdate(BaseModel): name: Optional[str] = Field(default=None,min_length=1); credit_code: Optional[str] = None; contact: Optional[str] = None; phone: Optional[str] = None
class PositionVideoIn(BaseModel): name: str; url: str = ""
class PositionVideoReviewIn(BaseModel): status: Literal["pending","approved","rejected","supplement"]; review_note: str = ""
class PositionReviewIn(BaseModel): occupation_class: Optional[Literal["1-3类","4类","5类","超5类"]] = None; status: Literal["approved","rejected","supplement"] = "approved"; plan_id: Optional[int] = None; review_note: str = ""
class RechargeIn(BaseModel): account: str = "premium"; amount: float = Field(gt=0)
class CommissionIn(BaseModel): agent_id: int; enterprise_id: int; plan_id: int; rate: float = Field(default=0, ge=0, le=1); mode: Literal["rebate","price","markup"] = "rebate"; markup_amount: float = Field(default=0, ge=0); sale_price: float = Field(default=0, ge=0)
class CommissionUpdate(BaseModel): rate: Optional[float] = Field(default=None, ge=0, le=1); mode: Optional[Literal["rebate","price","markup"]] = None; markup_amount: Optional[float] = Field(default=None, ge=0); sale_price: Optional[float] = Field(default=None, ge=0); status: Optional[str] = None
class PlanTierIn(BaseModel): plan_id: int; occupation_class: Literal["1-3类","4类","5类","超5类"]; price: float = Field(ge=0); coverage: str = ""
class PlanIn(BaseModel): insurer: str; insurer_email: str = ""; name: str; coverage: str = ""; occupation_classes: str = "1-4类"; price: float = Field(ge=0); commission_rate: float = Field(default=0, ge=0, le=1); profit_amount: float = Field(default=0,ge=0); payment_mode: str = "企业直投"; billing_mode: Literal["monthly","daily"] = "monthly"; effective_mode: Literal["next_day","immediate"] = "next_day"
class PlanUpdate(BaseModel): insurer: Optional[str] = None; insurer_email: Optional[str] = None; name: Optional[str] = None; coverage: Optional[str] = None; occupation_classes: Optional[str] = None; price: Optional[float] = Field(default=None, ge=0); commission_rate: Optional[float] = Field(default=None, ge=0, le=1); profit_amount: Optional[float] = Field(default=None,ge=0); payment_mode: Optional[str] = None; billing_mode: Optional[Literal["monthly","daily"]] = None; effective_mode: Optional[Literal["next_day","immediate"]] = None
class PersonIn(BaseModel): enterprise_id: int; name: str; phone: str = ""; id_number: str = Field(min_length=6); occupation: str = ""; occupation_class: str = "3类"; position_id: Optional[int] = None
class PersonUpdate(BaseModel): name: Optional[str] = None; phone: Optional[str] = None; id_number: Optional[str] = Field(default=None, min_length=6); position_id: Optional[int] = None
class BulkPersonRow(BaseModel): name: str; id_number: str = Field(min_length=6); phone: str = ""
class BulkPersonIn(BaseModel): enterprise_id: int; position_id: int; rows: list[BulkPersonRow] = Field(min_length=1, max_length=1000)
class ClaimIn(BaseModel): enterprise_id: int; person_id: int; description: str; amount: float = Field(default=0,ge=0); medical_cost: float = Field(default=0,ge=0); accident_at: str; accident_place: str; accident_type: str = "工伤事故"; hospital: str = ""; diagnosis: str = ""; contact_name: str = ""; contact_phone: str = ""
class ClaimUpdate(BaseModel): description: Optional[str]=None; hospital:Optional[str]=None; diagnosis:Optional[str]=None; medical_cost:Optional[float]=Field(default=None,ge=0); amount:Optional[float]=Field(default=None,ge=0); contact_name:Optional[str]=None; contact_phone:Optional[str]=None; insurer_report_no:Optional[str]=None; current_handler:Optional[str]=None; deadline:Optional[str]=None; sla_deadline:Optional[str]=None; rejection_reason:Optional[str]=None; review_note:Optional[str]=None; risk_level:Optional[Literal['normal','attention','high']]=None
class ClaimStatusIn(BaseModel): status: Literal["reported","collecting","submitted","insurer_review","supplement","approved","paid","rejected","closed"]; note: str = ""; approved_amount: Optional[float] = Field(default=None,ge=0); insurer_report_no: Optional[str] = None; rejection_reason: Optional[str] = None; paid_at: Optional[str] = None; current_handler: Optional[str] = None; sla_deadline: Optional[str] = None
class ClaimDocumentIn(BaseModel): name: str; url: str = ""; doc_type: str = "other"
class ClaimDocumentReviewIn(BaseModel): status: Literal["uploaded","accepted","rejected"]; review_note: str = ""
class PaymentIn(BaseModel): enterprise_id: int; account: Literal["premium","usage"] = "premium"; amount: float = Field(gt=0)
class PaymentCallbackIn(BaseModel): order_no: str; status: Literal["paid","failed","pending"]; provider_trade_no: str = ""
class InvoiceIn(BaseModel): enterprise_id: int; account: Literal["premium","usage"] = "premium"; amount: float = Field(gt=0); title: str = Field(min_length=1,max_length=160); tax_no: str = ""; email: str = ""
class InvoiceUpdate(BaseModel): status: Literal["pending","approved","issued","rejected"]
class NotificationIn(BaseModel): kind: Literal["sms","email"]; recipient: str; subject: str = "响帮帮保经云通知"; content: str; template: str = "general"
class AgentIn(BaseModel): username: str; password: str; name: str; phone: str = ""
class OperatorIn(BaseModel): username: str = Field(min_length=3, max_length=80); password: str = Field(min_length=6, max_length=128); name: str = Field(min_length=1, max_length=80); phone: str = ""; enterprise_id: Optional[int] = None
class OperatorUpdate(BaseModel): name: Optional[str] = Field(default=None, min_length=1, max_length=80); phone: Optional[str] = None; password: Optional[str] = Field(default=None, min_length=6, max_length=128); active: Optional[bool] = None
class PasswordChangeIn(BaseModel): current_password: str; new_password: str = Field(min_length=6,max_length=128)

class TokenOut(BaseModel): access_token: str; token_type: str = "bearer"
class UserOut(BaseModel): model_config = ConfigDict(from_attributes=True); id: int; username: str; name: str; role: str; enterprise_id: Optional[int] = None; phone: str = ""; is_owner: bool = False; active: bool = True

security = HTTPBearer(auto_error=False)

def current_user(creds: HTTPAuthorizationCredentials = Depends(security), session: Session = Depends(db)) -> User:
    if not creds: raise HTTPException(status_code=401, detail="请先登录")
    try: payload = jwt.decode(creds.credentials, SECRET_KEY, algorithms=[ALGORITHM]); uid = int(payload["sub"])
    except Exception: raise HTTPException(status_code=401, detail="登录已过期")
    user = session.get(User, uid)
    if not user or not user.active: raise HTTPException(status_code=401, detail="用户无效")
    if user.role not in {"admin","enterprise"}: raise HTTPException(status_code=403, detail="该账号暂未开通管理端权限")
    return user

def audit(session: Session, user: User, action: str, object_type: str, object_id: str, detail: str = ""):
    session.add(AuditLog(user_id=user.id, action=action, object_type=object_type, object_id=object_id, detail=detail)); session.commit()

def serialize(obj):
    return {c.name: getattr(obj, c.name) for c in obj.__table__.columns}

def amount(value:float) -> float:
    return round(float(value or 0),2)

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

def commission_dict(item:AgentCommission,session:Session) -> dict:
    agent=session.get(User,item.agent_id);enterprise=session.get(Enterprise,item.enterprise_id);plan=session.get(InsurancePlan,item.plan_id)
    return {**serialize(item),'mode':'price' if item.mode in {'price','markup'} else 'rebate','agent_name':agent.name if agent else '', 'enterprise_name':enterprise.name if enterprise else '', 'plan_name':plan.name if plan else '', 'insurer':plan.insurer if plan else '', **(pricing_snapshot(plan,item) if plan else {})}

def agent_commission_rows(session:Session, agent_id:int) -> list[dict]:
    rows=[]
    agent=session.get(User,agent_id)
    for rel in session.scalars(select(AgentCommission).where(AgentCommission.agent_id==agent_id).order_by(AgentCommission.id.desc())):
        plan=session.get(InsurancePlan,rel.plan_id); enterprise=session.get(Enterprise,rel.enterprise_id)
        if not plan or not enterprise: continue
        insured_count=session.query(InsuredPerson).join(WorkPosition,InsuredPerson.position_id==WorkPosition.id).filter(InsuredPerson.enterprise_id==rel.enterprise_id,WorkPosition.plan_id==rel.plan_id,InsuredPerson.status!='stopped').count()
        unit=pricing_snapshot(plan,rel)
        rows.append({**serialize(rel),'mode':unit['commission_mode'],'agent_name':agent.name if agent else '','enterprise_name':enterprise.name,'plan_name':plan.name,'insurer':plan.insurer,'insured_count':insured_count,'agent_commission_unit':unit['agent_commission_amount'],'agent_commission_total':amount(unit['agent_commission_amount']*insured_count)})
    return rows

def agent_commission_summary(session:Session, agent_id:int) -> dict:
    rows=agent_commission_rows(session,agent_id)
    active=[r for r in rows if r['status']=='active']
    return {'enterprise_count':len({r['enterprise_id'] for r in active}),'product_count':len(active),'insured_count':sum(r['insured_count'] for r in active),'total_commission':amount(sum(r['agent_commission_total'] for r in active))}

def validate_commission_price(data,plan:InsurancePlan):
    mode='price' if data.mode in {'price','markup'} else 'rebate'
    minimum=pricing_snapshot(plan)['minimum_sale_price']
    if mode=='rebate' and float(data.rate or 0)>float(plan.commission_rate or 0): raise HTTPException(400,'业务员返佣比例不能超过产品总返佣比例')
    sale=float(getattr(data,'sale_price',0) or 0)
    if mode=='price' and sale<=0: sale=minimum+float(getattr(data,'markup_amount',0) or 0)
    if mode=='price' and sale<minimum: raise HTTPException(400,f'销售价格不能低于销售最低价 ¥{minimum:.2f}')
    return mode,sale

def policy_dict(policy:Policy,session:Session) -> dict:
    enterprise=session.get(Enterprise,policy.enterprise_id);plan=session.get(InsurancePlan,policy.plan_id);relation=session.scalar(select(AgentCommission).where(AgentCommission.enterprise_id==policy.enterprise_id,AgentCommission.plan_id==policy.plan_id,AgentCommission.status=='active').order_by(AgentCommission.id.desc()))
    people=list(session.scalars(select(InsuredPerson).where(InsuredPerson.policy_id==policy.id)))
    snapshots=[]
    if plan:
        for person in people:
            snapshots.append(pricing_snapshot(plan,relation,plan_price_for_class(session,plan,person.occupation_class)))
        if not snapshots: snapshots=[pricing_snapshot(plan,relation)]
    total=lambda key:amount(sum(float(row.get(key,0)) for row in snapshots))
    calculated=total('sale_price') if people else float(policy.premium or 0)
    unit=snapshots[0] if snapshots else {}
    return {**serialize(policy),'premium_original':amount(policy.premium),'premium':amount(calculated),'calculated_premium':amount(calculated),'insured_count':len(people),'enterprise_name':enterprise.name if enterprise else '', 'insurer':plan.insurer if plan else '', 'plan_name':plan.name if plan else '', 'billing_mode':plan.billing_mode if plan else 'monthly','effective_mode':plan.effective_mode if plan else 'next_day',**unit,'insurance_base_total':total('insurance_base_price') if people else unit.get('insurance_base_price',0),'policy_floor_total':total('policy_floor_price') if people else unit.get('policy_floor_price',0),'minimum_sale_total':total('minimum_sale_price') if people else unit.get('minimum_sale_price',0),'sale_total':total('sale_price') if people else unit.get('sale_price',0),'total_commission_total':total('total_commission_amount') if people else unit.get('total_commission_amount',0),'agent_commission_total':total('agent_commission_amount') if people else unit.get('agent_commission_amount',0)}

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
        if DATABASE_URL.startswith("sqlite"):
            # 兼容旧版本地 SQLite 数据库；新建的 PostgreSQL 库由 create_all 建表。
            columns = {row[1] for row in s.connection().exec_driver_sql("PRAGMA table_info(users)")}
            if "enterprise_id" not in columns:
                s.connection().exec_driver_sql("ALTER TABLE users ADD COLUMN enterprise_id INTEGER")
            for column, definition in [("phone", "VARCHAR(30) DEFAULT ''"), ("status", "VARCHAR(30) DEFAULT 'active'"), ("is_owner", "BOOLEAN DEFAULT 0")]:
                if column not in columns: s.connection().exec_driver_sql(f"ALTER TABLE users ADD COLUMN {column} {definition}")
            enterprise_columns = {row[1] for row in s.connection().exec_driver_sql("PRAGMA table_info(enterprises)")}
            if "agent_id" not in enterprise_columns: s.connection().exec_driver_sql("ALTER TABLE enterprises ADD COLUMN agent_id INTEGER")
            if "usage_fee_daily" not in enterprise_columns: s.connection().exec_driver_sql("ALTER TABLE enterprises ADD COLUMN usage_fee_daily FLOAT DEFAULT 0.1")
            if "alert_days" not in enterprise_columns: s.connection().exec_driver_sql("ALTER TABLE enterprises ADD COLUMN alert_days INTEGER DEFAULT 3")
            commission_columns = {row[1] for row in s.connection().exec_driver_sql("PRAGMA table_info(agent_commissions)")}
            if "mode" not in commission_columns: s.connection().exec_driver_sql("ALTER TABLE agent_commissions ADD COLUMN mode VARCHAR(20) DEFAULT 'rebate'")
            if "markup_amount" not in commission_columns: s.connection().exec_driver_sql("ALTER TABLE agent_commissions ADD COLUMN markup_amount FLOAT DEFAULT 0")
            if "sale_price" not in commission_columns: s.connection().exec_driver_sql("ALTER TABLE agent_commissions ADD COLUMN sale_price FLOAT DEFAULT 0")
            plan_columns = {row[1] for row in s.connection().exec_driver_sql("PRAGMA table_info(insurance_plans)")}
            if "billing_mode" not in plan_columns: s.connection().exec_driver_sql("ALTER TABLE insurance_plans ADD COLUMN billing_mode VARCHAR(20) DEFAULT 'monthly'")
            if "effective_mode" not in plan_columns: s.connection().exec_driver_sql("ALTER TABLE insurance_plans ADD COLUMN effective_mode VARCHAR(20) DEFAULT 'next_day'")
            if "insurer_email" not in plan_columns: s.connection().exec_driver_sql("ALTER TABLE insurance_plans ADD COLUMN insurer_email VARCHAR(160) DEFAULT ''")
            if "profit_amount" not in plan_columns:
                s.connection().exec_driver_sql("ALTER TABLE insurance_plans ADD COLUMN profit_amount FLOAT DEFAULT 0")
                s.connection().exec_driver_sql("UPDATE insurance_plans SET profit_amount=price*commission_rate")
            s.connection().exec_driver_sql("UPDATE agent_commissions SET mode='price', sale_price=COALESCE((SELECT price*(1-commission_rate)+profit_amount FROM insurance_plans WHERE insurance_plans.id=agent_commissions.plan_id),0)+COALESCE(markup_amount,0) WHERE mode='markup'")
            insured_columns = {row[1] for row in s.connection().exec_driver_sql("PRAGMA table_info(insured_people)")}
            if "id_number" not in insured_columns: s.connection().exec_driver_sql("ALTER TABLE insured_people ADD COLUMN id_number VARCHAR(40) DEFAULT ''")
            if "position_id" not in insured_columns: s.connection().exec_driver_sql("ALTER TABLE insured_people ADD COLUMN position_id INTEGER")
            position_columns = {row[1] for row in s.connection().exec_driver_sql("PRAGMA table_info(work_positions)")}
            if "actual_employer_id" not in position_columns: s.connection().exec_driver_sql("ALTER TABLE work_positions ADD COLUMN actual_employer_id INTEGER")
            claim_columns = {row[1] for row in s.connection().exec_driver_sql("PRAGMA table_info(claims)")}
            for column, definition in [("accident_at","VARCHAR(30) DEFAULT ''"),("accident_place","VARCHAR(200) DEFAULT ''"),("accident_type","VARCHAR(60) DEFAULT '工伤事故'"),("hospital","VARCHAR(160) DEFAULT ''"),("diagnosis","TEXT DEFAULT ''"),("medical_cost","FLOAT DEFAULT 0"),("contact_name","VARCHAR(80) DEFAULT ''"),("contact_phone","VARCHAR(30) DEFAULT ''"),("insurer_report_no","VARCHAR(100) DEFAULT ''"),("current_handler","VARCHAR(80) DEFAULT '平台理赔专员'"),("deadline","VARCHAR(30) DEFAULT ''"),("sla_deadline","VARCHAR(30) DEFAULT ''"),("approved_amount","FLOAT DEFAULT 0"),("paid_at","VARCHAR(30) DEFAULT ''"),("rejection_reason","TEXT DEFAULT ''"),("review_note","TEXT DEFAULT ''"),("risk_level","VARCHAR(20) DEFAULT 'normal'")]:
                if column not in claim_columns: s.connection().exec_driver_sql(f"ALTER TABLE claims ADD COLUMN {column} {definition}")
            document_columns = {row[1] for row in s.connection().exec_driver_sql("PRAGMA table_info(claim_documents)")}
            if "review_note" not in document_columns: s.connection().exec_driver_sql("ALTER TABLE claim_documents ADD COLUMN review_note TEXT DEFAULT ''")
        if not s.scalar(select(User).where(User.username == "admin")):
            s.add(User(username="admin", password_hash=pwd.hash(os.getenv("ADMIN_PASSWORD", "admin123")), name="响帮帮管理员", role="admin"))
        s.commit()
        # 用户电脑端演示账号：仅在数据库尚未配置参保单位账号时创建
        if not s.scalar(select(User).where(User.username == "enterprise")):
            demo_enterprise = s.scalar(select(Enterprise).order_by(Enterprise.id.asc()))
            if not demo_enterprise:
                demo_enterprise = Enterprise(name="演示参保单位", kind="企业", contact="演示管理员", phone="", status="active")
                s.add(demo_enterprise); s.flush()
            s.add(User(username="enterprise", password_hash=pwd.hash(os.getenv("ENTERPRISE_PASSWORD", "enterprise123")), name=f"{demo_enterprise.name}管理员", role="enterprise", enterprise_id=demo_enterprise.id, is_owner=True))
            s.commit()
        # 旧数据升级：每个投保单位至少保留一个可管理操作员的单位主管。
        enterprise_ids={row[0] for row in s.execute(select(User.enterprise_id).where(User.role=="enterprise",User.enterprise_id.is_not(None))).all()}
        for enterprise_id in enterprise_ids:
            if not s.scalar(select(User).where(User.role=="enterprise",User.enterprise_id==enterprise_id,User.is_owner.is_(True))):
                owner=s.scalar(select(User).where(User.role=="enterprise",User.enterprise_id==enterprise_id).order_by(User.id.asc()))
                if owner: owner.is_owner=True
        s.commit()

@app.post("/api/auth/login", response_model=TokenOut)
def login(data: LoginIn, session: Session = Depends(db)):
    user = session.scalar(select(User).where(User.username == data.username))
    if not user or not pwd.verify(data.password, user.password_hash): raise HTTPException(401, "账号或密码错误")
    if not user.active: raise HTTPException(403, "该账号已停用，请联系单位主管")
    if data.portal == "admin" and user.role != "admin": raise HTTPException(403, "该账号不是总后台账号")
    if data.portal == "enterprise" and user.role != "enterprise": raise HTTPException(403, "该账号不是参保单位账号")
    token = jwt.encode({"sub": str(user.id), "exp": datetime.now(timezone.utc) + timedelta(hours=12)}, SECRET_KEY, algorithm=ALGORITHM)
    return TokenOut(access_token=token)

@app.get("/api/auth/me", response_model=UserOut)
def me(user: User = Depends(current_user)): return user

@app.patch("/api/auth/password")
def change_password(data:PasswordChangeIn,user:User=Depends(current_user),session:Session=Depends(db)):
    if not pwd.verify(data.current_password,user.password_hash): raise HTTPException(400,'当前密码不正确')
    if data.current_password==data.new_password: raise HTTPException(400,'新密码不能与当前密码相同')
    user.password_hash=pwd.hash(data.new_password);session.commit();audit(session,user,'password_change','user',str(user.id));return {'ok':True}

def operator_dict(item:User,session:Session):
    enterprise=session.get(Enterprise,item.enterprise_id) if item.enterprise_id else None
    return {"id":item.id,"username":item.username,"name":item.name,"phone":item.phone,"role":item.role,"enterprise_id":item.enterprise_id,"enterprise_name":enterprise.name if enterprise else "","is_owner":item.is_owner,"active":item.active,"created_at":item.created_at}

@app.get("/api/operators")
def operators(user:User=Depends(current_user),session:Session=Depends(db)):
    stmt=select(User).where(User.role=="enterprise").order_by(User.is_owner.desc(),User.id.asc())
    if user.role=="enterprise":
        if not user.enterprise_id: return []
        stmt=stmt.where(User.enterprise_id==user.enterprise_id)
    elif user.role!="admin": raise HTTPException(403,"无权查看操作员")
    return [operator_dict(item,session) for item in session.scalars(stmt)]

@app.post("/api/operators")
def add_operator(data:OperatorIn,user:User=Depends(current_user),session:Session=Depends(db)):
    if user.role=="enterprise" and not user.is_owner: raise HTTPException(403,"仅单位主管可管理操作员")
    if user.role not in {"admin","enterprise"}: raise HTTPException(403,"无权管理操作员")
    enterprise_id=user.enterprise_id if user.role=="enterprise" else data.enterprise_id
    if not enterprise_id or not session.get(Enterprise,enterprise_id): raise HTTPException(400,"请选择有效投保单位")
    if session.scalar(select(User).where(User.username==data.username)): raise HTTPException(409,"登录账号已存在")
    item=User(username=data.username.strip(),password_hash=pwd.hash(data.password),name=data.name.strip(),phone=data.phone.strip(),role="enterprise",enterprise_id=enterprise_id,is_owner=False,active=True,status="active")
    session.add(item);session.commit();session.refresh(item);audit(session,user,"create","operator",str(item.id));return operator_dict(item,session)

@app.patch("/api/operators/{item_id}")
def update_operator(item_id:int,data:OperatorUpdate,user:User=Depends(current_user),session:Session=Depends(db)):
    item=session.get(User,item_id)
    if not item or item.role!="enterprise": raise HTTPException(404,"操作员不存在")
    if user.role=="enterprise":
        if not user.is_owner: raise HTTPException(403,"仅单位主管可管理操作员")
        if item.enterprise_id!=user.enterprise_id: raise HTTPException(403,"无权管理其他单位操作员")
    elif user.role!="admin": raise HTTPException(403,"无权管理操作员")
    if item.id==user.id and data.active is False: raise HTTPException(400,"不能停用当前登录账号")
    if item.is_owner and data.active is False: raise HTTPException(400,"单位主管不能停用")
    values=data.model_dump(exclude_unset=True)
    if values.get("name") is not None: item.name=values["name"].strip()
    if values.get("phone") is not None: item.phone=values["phone"].strip()
    if values.get("password"): item.password_hash=pwd.hash(values["password"])
    if values.get("active") is not None: item.active=values["active"];item.status="active" if item.active else "inactive"
    session.commit();audit(session,user,"update","operator",str(item.id));return operator_dict(item,session)

@app.get("/api/agents")
def agents(user: User = Depends(current_user), session: Session = Depends(db)):
    if user.role != "admin": raise HTTPException(403, "仅总后台可管理业务员")
    return [{"id": x.id, "username": x.username, "name": x.name, "phone": x.phone, "role": x.role, "active": x.active, "status": x.status, "created_at": x.created_at, **agent_commission_summary(session, x.id)} for x in session.scalars(select(User).where(User.role == "salesperson").order_by(User.id.desc()))]

@app.get("/api/agents/{item_id}/commissions")
def agent_commissions_detail(item_id: int, user: User = Depends(current_user), session: Session = Depends(db)):
    if user.role != "admin": raise HTTPException(403, "仅总后台可查看业务员佣金")
    if not session.get(User, item_id): raise HTTPException(404, "业务员不存在")
    return agent_commission_rows(session, item_id)

@app.post("/api/agents")
def add_agent(data: AgentIn, user: User = Depends(current_user), session: Session = Depends(db)):
    if user.role != "admin": raise HTTPException(403, "仅总后台可管理业务员")
    if session.scalar(select(User).where(User.username == data.username)): raise HTTPException(409, "业务员账号已存在")
    item = User(username=data.username, password_hash=pwd.hash(data.password), name=data.name, phone=data.phone, role="salesperson")
    session.add(item); session.commit(); session.refresh(item); audit(session, user, "create", "salesperson", str(item.id)); return {"id": item.id, "username": item.username, "name": item.name, "role": item.role, "active": item.active}

@app.patch("/api/agents/{item_id}/status")
def agent_status(item_id: int, status_value: str = Query(..., alias="status"), user: User = Depends(current_user), session: Session = Depends(db)):
    if user.role != "admin": raise HTTPException(403, "仅总后台可管理业务员")
    item = session.get(User, item_id)
    if not item or item.role != "salesperson": raise HTTPException(404, "业务员不存在")
    item.status = status_value; item.active = status_value == "active"; session.commit(); audit(session, user, "status_change", "salesperson", str(item.id), status_value); return {"ok": True, "status": item.status}

@app.get("/api/agent-commissions")
def agent_commissions(user: User = Depends(current_user), session: Session = Depends(db)):
    if user.role != "admin": raise HTTPException(403, "仅总后台可查看业务员佣金")
    return [commission_dict(x,session) for x in session.scalars(select(AgentCommission).order_by(AgentCommission.id.desc()))]

@app.post("/api/agent-commissions")
def add_agent_commission(data: CommissionIn, user: User = Depends(current_user), session: Session = Depends(db)):
    if user.role != "admin": raise HTTPException(403, "仅总后台可配置佣金")
    agent=session.get(User,data.agent_id); enterprise=session.get(Enterprise,data.enterprise_id); plan=session.get(InsurancePlan,data.plan_id)
    if not agent or agent.role != "salesperson": raise HTTPException(404,"业务员不存在")
    if not enterprise or not plan: raise HTTPException(404,"投保单位或产品方案不存在")
    if enterprise.agent_id is not None and enterprise.agent_id != data.agent_id: raise HTTPException(409,"一个投保单位只能关联一个业务员；该单位已关联其他业务员")
    mode,sale_price=validate_commission_price(data,plan);values=data.model_dump();values['mode']=mode;values['sale_price']=sale_price;values['markup_amount']=max(0,sale_price-pricing_snapshot(plan)['minimum_sale_price']) if mode=='price' else 0
    if mode=='price': values['rate']=0
    item=AgentCommission(**values);session.add(item)
    if enterprise.agent_id is None: enterprise.agent_id = data.agent_id
    session.commit();session.refresh(item);audit(session,user,"create","agent_commission",str(item.id));return serialize(item)

@app.patch("/api/agent-commissions/{item_id}")
def update_agent_commission(item_id:int,data:CommissionUpdate,user:User=Depends(current_user),session:Session=Depends(db)):
    if user.role!="admin": raise HTTPException(403,"仅总后台可修改佣金关系")
    item=session.get(AgentCommission,item_id)
    if not item: raise HTTPException(404,"佣金关系不存在")
    values=data.model_dump(exclude_unset=True)
    for k,v in values.items():
        if v is not None: setattr(item,k,v)
    plan=session.get(InsurancePlan,item.plan_id);mode,sale_price=validate_commission_price(item,plan);item.mode=mode;item.sale_price=sale_price;item.markup_amount=max(0,sale_price-pricing_snapshot(plan)['minimum_sale_price']) if mode=='price' else 0
    if mode=='price': item.rate=0
    session.commit();audit(session,user,"update","agent_commission",str(item.id));return commission_dict(item,session)

@app.delete("/api/agent-commissions/{item_id}")
def delete_agent_commission(item_id:int,user:User=Depends(current_user),session:Session=Depends(db)):
    if user.role!="admin": raise HTTPException(403,"仅总后台可删除佣金关系")
    item=session.get(AgentCommission,item_id)
    if not item: raise HTTPException(404,"佣金关系不存在")
    session.delete(item); session.commit(); audit(session,user,"delete","agent_commission",str(item_id)); return {"ok":True}

@app.get("/api/dashboard")
def dashboard(user: User = Depends(current_user), session: Session = Depends(db)):
    enterprise_filter = [user.enterprise_id] if user.role == "enterprise" and user.enterprise_id else None
    enterprises = session.query(Enterprise).filter(Enterprise.id.in_(enterprise_filter)).all() if enterprise_filter else session.query(Enterprise).all()
    people = session.query(InsuredPerson).filter(InsuredPerson.enterprise_id.in_(enterprise_filter)).all() if enterprise_filter else session.query(InsuredPerson).all()
    active_people=[x for x in people if x.status in {'active','pending'}]
    alerts=[]
    for ent in enterprises:
        enterprise_active_count=session.query(InsuredPerson).filter(InsuredPerson.enterprise_id==ent.id,InsuredPerson.status.in_(['active','pending'])).count()
        daily_usage=enterprise_active_count*float(ent.usage_fee_daily or 0.1)
        daily_premium=sum(float(policy_dict(p,session)['premium'] or 0)/(1 if policy_dict(p,session)['billing_mode']=='daily' else 30) for p in session.scalars(select(Policy).where(Policy.enterprise_id==ent.id,Policy.status=='active')))
        for account,balance,daily in [('premium',ent.premium_balance,daily_premium),('usage',ent.usage_balance,daily_usage)]:
            days_left=999999 if daily<=0 else balance/daily
            if days_left <= int(ent.alert_days or 3): alerts.append({'enterprise_id':ent.id,'enterprise_name':ent.name,'account':account,'balance':balance,'daily_burn':daily,'days_left':round(days_left,1),'alert_days':ent.alert_days or 3,'level':'critical' if days_left<=1 else 'warning'})
    return {"portal": "enterprise" if user.role == "enterprise" else "admin", "enterprises": len(enterprises), "people": len(people), "active_people":len(active_people), "active_policies": session.query(Policy).filter(Policy.status == "active", Policy.enterprise_id.in_(enterprise_filter)).count() if enterprise_filter else session.query(Policy).filter(Policy.status == "active").count(), "pending_enterprises": session.query(Enterprise).filter(Enterprise.status == "pending").count() if not enterprise_filter else 0, "pending_people": len([x for x in people if x.status == "pending"]), "claims_open": session.query(Claim).filter(Claim.status.not_in(["paid", "closed"]), Claim.enterprise_id.in_(enterprise_filter)).count() if enterprise_filter else session.query(Claim).filter(Claim.status.not_in(["paid", "closed"])).count(), "premium_balance": sum(x.premium_balance for x in enterprises), "usage_balance": sum(x.usage_balance for x in enterprises), "balance_alerts": alerts}

@app.get("/api/screen/products")
def screen_products(user: User = Depends(current_user), session: Session = Depends(db)):
    result=[]
    for plan in session.scalars(select(InsurancePlan).order_by(InsurancePlan.id.desc())):
        policy_query=session.query(Policy).filter(Policy.plan_id==plan.id)
        if user.role=="enterprise" and user.enterprise_id: policy_query=policy_query.filter(Policy.enterprise_id==user.enterprise_id)
        policies=policy_query.all();insured_query=session.query(InsuredPerson).join(WorkPosition,InsuredPerson.position_id==WorkPosition.id).filter(WorkPosition.plan_id==plan.id,InsuredPerson.status.in_(['active','pending']))
        if user.role=="enterprise" and user.enterprise_id: insured_query=insured_query.filter(InsuredPerson.enterprise_id==user.enterprise_id)
        people=insured_query.all();enterprise_ids={x.enterprise_id for x in people}|{x.enterprise_id for x in policies};premium_total=sum(float(policy_dict(x,session)['premium'] or 0) for x in policies)
        result.append({"plan_id":plan.id,"insurer":plan.insurer,"product":plan.name,"insured_count":len(people),"enterprise_count":len(enterprise_ids),"premium_total":amount(premium_total),"policy_count":len(policies),**pricing_snapshot(plan)})
    return result

@app.get("/api/enterprises")
def enterprises(q: str = "", status_filter: Optional[str] = Query(None, alias="status"), user: User = Depends(current_user), session: Session = Depends(db)):
    stmt = select(Enterprise).order_by(Enterprise.id.desc())
    if user.role == "enterprise" and user.enterprise_id: stmt = stmt.where(Enterprise.id == user.enterprise_id)
    if q: stmt = stmt.where(or_(Enterprise.name.contains(q), Enterprise.contact.contains(q)))
    if status_filter: stmt = stmt.where(Enterprise.status == status_filter)
    result=[]
    for x in session.scalars(stmt):
        linked = session.scalar(select(AgentCommission).where(AgentCommission.enterprise_id == x.id).order_by(AgentCommission.id.asc())) if not x.agent_id else None
        agent_id = x.agent_id or (linked.agent_id if linked else None)
        item=serialize(x); agent=session.get(User,agent_id) if agent_id else None; item["agent_id"]=agent_id; item["agent_name"]=agent.name if agent else "未分配"; result.append(item)
    return result

@app.post("/api/enterprises")
def add_enterprise(data: EnterpriseIn, user: User = Depends(current_user), session: Session = Depends(db)):
    if user.role != "admin": raise HTTPException(403, "仅总后台可新增投保单位")
    if data.agent_id is not None:
        agent = session.get(User, data.agent_id)
        if not agent or agent.role != "salesperson": raise HTTPException(404, "业务员不存在")
    item = Enterprise(**data.model_dump()); session.add(item); session.commit(); session.refresh(item); audit(session, user, "create", "enterprise", str(item.id)); return serialize(item)

@app.patch("/api/enterprises/{item_id}/status")
def enterprise_status(item_id: int, status_value: str = Query(..., alias="status"), user: User = Depends(current_user), session: Session = Depends(db)):
    if user.role != "admin": raise HTTPException(403, "仅总后台可审核投保单位")
    item = session.get(Enterprise, item_id)
    if not item: raise HTTPException(404, "企业不存在")
    item.status = status_value; session.commit(); audit(session, user, "status_change", "enterprise", str(item.id), status_value); return serialize(item)

@app.patch("/api/enterprises/{item_id}")
def update_enterprise(item_id: int, data: EnterpriseUpdate, user: User = Depends(current_user), session: Session = Depends(db)):
    item = session.get(Enterprise, item_id)
    if not item: raise HTTPException(404, "投保单位不存在")
    if user.role not in {"admin","enterprise"}: raise HTTPException(403,"无权操作投保单位")
    if user.role == "enterprise" and user.enterprise_id != item_id: raise HTTPException(403, "无权操作该单位")
    if data.agent_id is not None:
        agent = session.get(User, data.agent_id)
        if not agent or agent.role != "salesperson": raise HTTPException(404, "业务员不存在")
        existing = session.scalars(select(AgentCommission).where(AgentCommission.enterprise_id == item_id)).all()
        if existing and any(x.agent_id != data.agent_id for x in existing):
            raise HTTPException(409, "一个投保单位只能关联一个业务员；该单位已关联其他业务员")
    for key, value in data.model_dump(exclude_unset=True).items():
        if value is not None: setattr(item, key, value)
    session.commit(); audit(session, user, "update", "enterprise", str(item.id)); return serialize(item)

@app.delete("/api/enterprises/{item_id}")
def delete_enterprise(item_id: int, user: User = Depends(current_user), session: Session = Depends(db)):
    if user.role != "admin": raise HTTPException(403, "仅总后台可删除投保单位")
    item = session.get(Enterprise, item_id)
    if not item: raise HTTPException(404, "投保单位不存在")
    if session.scalar(select(InsuredPerson.id).where(InsuredPerson.enterprise_id == item_id).limit(1)) or session.scalar(select(Policy.id).where(Policy.enterprise_id == item_id).limit(1)): raise HTTPException(409, "该单位已有参保人员或保单，不能删除；请先停保并归档")
    session.delete(item); session.commit(); audit(session, user, "delete", "enterprise", str(item_id)); return {"ok": True}

@app.post("/api/enterprises/{item_id}/recharge")
def recharge_enterprise(item_id: int, data: RechargeIn, user: User = Depends(current_user), session: Session = Depends(db)):
    item = session.get(Enterprise, item_id)
    if not item: raise HTTPException(404, "投保单位不存在")
    if user.role not in {"admin","enterprise"}: raise HTTPException(403,"无权为投保单位充值")
    if user.role == "enterprise" and user.enterprise_id != item_id: raise HTTPException(403, "无权为该单位充值")
    if data.account == "premium": item.premium_balance += data.amount
    elif data.account == "usage": item.usage_balance += data.amount
    else: raise HTTPException(400, "账户类型不合法")
    session.commit(); audit(session, user, "recharge", "enterprise", str(item_id), f"{data.account}:{data.amount}"); return serialize(item)

@app.get("/api/enterprises/{item_id}/admins")
def enterprise_admins(item_id: int, user: User = Depends(current_user), session: Session = Depends(db)):
    if user.role not in {"admin","enterprise"}: raise HTTPException(403,"无权查看单位管理员")
    if user.role == "enterprise" and user.enterprise_id != item_id: raise HTTPException(403, "无权查看该单位")
    return [{"id": x.id, "username": x.username, "name": x.name, "phone": x.phone, "active": x.active} for x in session.scalars(select(User).where(User.enterprise_id == item_id, User.role == "enterprise"))]

@app.post("/api/enterprises/{item_id}/admins")
def add_enterprise_admin(item_id: int, data: AgentIn, user: User = Depends(current_user), session: Session = Depends(db)):
    if user.role != "admin": raise HTTPException(403, "仅总后台可管理单位管理员")
    if not session.get(Enterprise, item_id): raise HTTPException(404, "投保单位不存在")
    if session.scalar(select(User).where(User.username == data.username)): raise HTTPException(409, "账号已存在")
    item=User(username=data.username,password_hash=pwd.hash(data.password),name=data.name,phone=data.phone,role="enterprise",enterprise_id=item_id);session.add(item);session.commit();session.refresh(item);audit(session,user,"create","enterprise_admin",str(item.id));return {"id":item.id,"username":item.username,"name":item.name,"phone":item.phone,"active":item.active}

@app.get("/api/enterprises/{item_id}/products")
def enterprise_products(item_id:int,user:User=Depends(current_user),session:Session=Depends(db)):
    if user.role=="enterprise" and user.enterprise_id!=item_id: raise HTTPException(403,"无权查看该单位")
    if not session.get(Enterprise,item_id): raise HTTPException(404,"投保单位不存在")
    rows=[]
    for x in session.scalars(select(AgentCommission).where(AgentCommission.enterprise_id==item_id).order_by(AgentCommission.id.desc())):
        plan=session.get(InsurancePlan,x.plan_id);agent=session.get(User,x.agent_id); people=session.query(InsuredPerson).join(WorkPosition,InsuredPerson.position_id==WorkPosition.id).filter(InsuredPerson.enterprise_id==item_id,WorkPosition.plan_id==x.plan_id,InsuredPerson.status!='stopped').count(); premium=session.query(Policy).filter(Policy.enterprise_id==item_id,Policy.plan_id==x.plan_id).with_entities(Policy.premium).all(); rows.append({"id":x.id,"product":plan.name if plan else "","insurer":plan.insurer if plan else "","agent":agent.name if agent else "","commission_rate":x.rate,"insured_count":people,"premium_total":sum(float(p[0] or 0) for p in premium),"status":x.status,**(pricing_snapshot(plan,x) if plan else {})})
    return rows

@app.get("/api/positions")
def positions(user: User = Depends(current_user), session: Session = Depends(db)):
    stmt=select(WorkPosition).order_by(WorkPosition.id.desc())
    if user.role == "enterprise" and user.enterprise_id: stmt=stmt.where(WorkPosition.enterprise_id==user.enterprise_id)
    result=[]
    for x in session.scalars(stmt):
        item=serialize(x);em=session.get(ActualEmployer,x.actual_employer_id) if x.actual_employer_id else None;videos=session.scalars(select(PositionVideo).where(PositionVideo.position_id==x.id).order_by(PositionVideo.id.desc())).all();item['actual_employer_name']=em.name if em else x.actual_employer;item['video_count']=len(videos);item['latest_video_status']=videos[0].status if videos else 'missing';item['review_note']=videos[0].review_note if videos else '';result.append(item)
    return result

@app.get("/api/actual-employers")
def actual_employers(user:User=Depends(current_user),session:Session=Depends(db)):
    stmt=select(ActualEmployer).order_by(ActualEmployer.id.desc())
    if user.role=='enterprise' and user.enterprise_id: stmt=stmt.where(ActualEmployer.enterprise_id==user.enterprise_id)
    return [serialize(x) for x in session.scalars(stmt)]

@app.post("/api/actual-employers")
def add_actual_employer(data:ActualEmployerIn,user:User=Depends(current_user),session:Session=Depends(db)):
    eid=user.enterprise_id if user.role=='enterprise' else data.enterprise_id
    if not eid or not session.get(Enterprise,eid): raise HTTPException(400,'请指定有效投保单位')
    item=ActualEmployer(enterprise_id=eid,name=data.name,credit_code=data.credit_code,contact=data.contact,phone=data.phone);session.add(item);session.commit();session.refresh(item);audit(session,user,'create','actual_employer',str(item.id));return serialize(item)

@app.patch("/api/actual-employers/{item_id}")
def update_actual_employer(item_id:int,data:ActualEmployerUpdate,user:User=Depends(current_user),session:Session=Depends(db)):
    item=session.get(ActualEmployer,item_id)
    if not item: raise HTTPException(404,'实际工作单位不存在')
    if user.role=='enterprise' and user.enterprise_id!=item.enterprise_id: raise HTTPException(403,'无权操作')
    if user.role not in {'admin','enterprise'}: raise HTTPException(403,'无权操作')
    for key,value in data.model_dump(exclude_unset=True).items():
        if value is not None: setattr(item,key,value.strip() if isinstance(value,str) else value)
    for position in session.scalars(select(WorkPosition).where(WorkPosition.actual_employer_id==item.id)):
        position.actual_employer=item.name
    session.commit();audit(session,user,'update','actual_employer',str(item.id));return serialize(item)

@app.delete("/api/actual-employers/{item_id}")
def delete_actual_employer(item_id:int,user:User=Depends(current_user),session:Session=Depends(db)):
    item=session.get(ActualEmployer,item_id)
    if not item: raise HTTPException(404,'实际工作单位不存在')
    if user.role=='enterprise' and user.enterprise_id!=item.enterprise_id: raise HTTPException(403,'无权操作')
    if user.role not in {'admin','enterprise'}: raise HTTPException(403,'无权操作')
    if session.scalar(select(WorkPosition.id).where(WorkPosition.actual_employer_id==item_id).limit(1)): raise HTTPException(409,'该工作单位已关联岗位，不能删除；可先暂停使用')
    session.delete(item);session.commit();audit(session,user,'delete','actual_employer',str(item_id));return {'ok':True}

@app.patch("/api/actual-employers/{item_id}/status")
def actual_employer_status(item_id:int,status_value:Literal['active','paused']=Query(...,alias='status'),user:User=Depends(current_user),session:Session=Depends(db)):
    item=session.get(ActualEmployer,item_id)
    if not item: raise HTTPException(404,'实际用工单位不存在')
    if user.role=='enterprise' and user.enterprise_id!=item.enterprise_id: raise HTTPException(403,'无权操作')
    item.status=status_value;session.commit();audit(session,user,'status_change','actual_employer',str(item.id),status_value);return serialize(item)

@app.get("/api/positions/{item_id}/videos")
def position_videos(item_id:int,user:User=Depends(current_user),session:Session=Depends(db)):
    pos=session.get(WorkPosition,item_id)
    if not pos: raise HTTPException(404,'岗位不存在')
    if user.role=='enterprise' and user.enterprise_id!=pos.enterprise_id: raise HTTPException(403,'无权查看')
    return [serialize(x) for x in session.scalars(select(PositionVideo).where(PositionVideo.position_id==item_id).order_by(PositionVideo.id.desc()))]

@app.post("/api/positions/{item_id}/videos")
def add_position_video(item_id:int,data:PositionVideoIn,user:User=Depends(current_user),session:Session=Depends(db)):
    pos=session.get(WorkPosition,item_id)
    if not pos: raise HTTPException(404,'岗位不存在')
    if user.role=='enterprise' and user.enterprise_id!=pos.enterprise_id: raise HTTPException(403,'无权上传')
    item=PositionVideo(position_id=item_id,**data.model_dump());session.add(item);pos.status='pending';pos.occupation_class='待定';pos.plan_id=None;session.commit();session.refresh(item);audit(session,user,'upload','position_video',str(item.id));return serialize(item)

@app.post("/api/positions/{item_id}/videos/upload")
async def upload_position_video(item_id:int,file:UploadFile=File(...),user:User=Depends(current_user),session:Session=Depends(db)):
    pos=session.get(WorkPosition,item_id)
    if not pos: raise HTTPException(404,'岗位不存在')
    if user.role=='enterprise' and user.enterprise_id!=pos.enterprise_id: raise HTTPException(403,'无权上传')
    suffix=Path(file.filename or '').suffix.lower()
    if suffix not in {'.mp4','.mov','.m4v'}: raise HTTPException(400,'仅支持 MP4、MOV 或 M4V 视频')
    content=await file.read()
    if len(content)>100*1024*1024: raise HTTPException(400,'岗位视频不能超过 100MB')
    folder=ROOT/'uploads'/'positions'/str(item_id);folder.mkdir(parents=True,exist_ok=True);stored=f'{secrets.token_hex(8)}{suffix}';(folder/stored).write_bytes(content)
    item=PositionVideo(position_id=item_id,name=file.filename or stored,url=f'/uploads/positions/{item_id}/{stored}',status='pending');session.add(item);pos.status='pending';pos.occupation_class='待定';pos.plan_id=None;session.commit();session.refresh(item);audit(session,user,'upload','position_video',str(item.id));return serialize(item)

@app.patch("/api/position-videos/{item_id}/review")
def review_position_video(item_id:int,data:PositionVideoReviewIn,user:User=Depends(current_user),session:Session=Depends(db)):
    if user.role!='admin': raise HTTPException(403,'仅平台端可审核岗位视频')
    item=session.get(PositionVideo,item_id)
    if not item: raise HTTPException(404,'岗位视频不存在')
    item.status=data.status;item.review_note=data.review_note;session.commit();audit(session,user,'review','position_video',str(item.id),data.status);return serialize(item)

@app.patch("/api/positions/{item_id}/review")
def review_position(item_id:int,data:PositionReviewIn,user:User=Depends(current_user),session:Session=Depends(db)):
    if user.role!='admin': raise HTTPException(403,'仅平台端可确定岗位职业类别')
    item=session.get(WorkPosition,item_id)
    if not item: raise HTTPException(404,'岗位不存在')
    videos=session.scalars(select(PositionVideo).where(PositionVideo.position_id==item_id).order_by(PositionVideo.id.desc())).all()
    if data.status=='approved' and not videos: raise HTTPException(400,'岗位视频上传后才能完成定类')
    if data.status=='approved' and not data.occupation_class: raise HTTPException(400,'请选择岗位职业类别')
    if data.status in {'supplement','rejected'} and not data.review_note.strip(): raise HTTPException(400,'补件或驳回时必须填写审核意见')
    if data.plan_id is not None and not session.get(InsurancePlan,data.plan_id): raise HTTPException(400,'投保方案不存在')
    if data.occupation_class: item.occupation_class=data.occupation_class
    item.plan_id=data.plan_id;item.status=data.status
    if videos:
        videos[0].status=data.status;videos[0].review_note=data.review_note
    session.commit();audit(session,user,'review','position',str(item.id),f'{data.status}:{item.occupation_class}');return serialize(item)

@app.post("/api/positions")
def add_position(data: PositionIn, user: User = Depends(current_user), session: Session = Depends(db)):
    target_enterprise = user.enterprise_id if user.role == "enterprise" else data.enterprise_id
    if not target_enterprise or not session.get(Enterprise, target_enterprise): raise HTTPException(400,"请先绑定有效投保单位")
    employer=session.get(ActualEmployer,data.actual_employer_id) if data.actual_employer_id else None
    if not employer or employer.enterprise_id!=target_enterprise: raise HTTPException(400,"请选择本企业添加的有效实际工作单位")
    if employer.status!='active': raise HTTPException(400,"该工作单位已暂停，不能新增岗位")
    item=WorkPosition(enterprise_id=target_enterprise,actual_employer_id=employer.id,actual_employer=employer.name,name=data.name,occupation_class='待定' if user.role=='enterprise' else data.occupation_class,plan_id=None if user.role=='enterprise' else data.plan_id,status='pending')
    session.add(item);session.commit();session.refresh(item);audit(session,user,"create","position",str(item.id));return serialize(item)

@app.patch("/api/positions/{item_id}")
def update_position(item_id:int,data:PositionIn,user:User=Depends(current_user),session:Session=Depends(db)):
    item=session.get(WorkPosition,item_id)
    if not item: raise HTTPException(404,"岗位不存在")
    if user.role=="enterprise" and item.enterprise_id!=user.enterprise_id: raise HTTPException(403,"无权操作")
    employer=session.get(ActualEmployer,data.actual_employer_id) if data.actual_employer_id else None
    if not employer or employer.enterprise_id!=item.enterprise_id: raise HTTPException(400,"请选择本企业添加的有效实际工作单位")
    item.actual_employer_id=employer.id;item.actual_employer=employer.name;item.name=data.name
    if user.role=='enterprise':
        item.occupation_class='待定';item.plan_id=None;item.status='pending'
    else:
        item.occupation_class=data.occupation_class;item.plan_id=data.plan_id
    session.commit();audit(session,user,"update","position",str(item.id));return serialize(item)

@app.delete("/api/positions/{item_id}")
def delete_position(item_id:int,user:User=Depends(current_user),session:Session=Depends(db)):
    item=session.get(WorkPosition,item_id)
    if not item: raise HTTPException(404,"岗位不存在")
    if user.role=="enterprise" and item.enterprise_id!=user.enterprise_id: raise HTTPException(403,"无权操作")
    if session.scalar(select(InsuredPerson.id).where(InsuredPerson.position_id==item_id).limit(1)): raise HTTPException(409,'该岗位已关联参保员工，不能删除')
    for video in session.scalars(select(PositionVideo).where(PositionVideo.position_id==item_id)):
        session.delete(video)
    session.delete(item);session.commit();audit(session,user,"delete","position",str(item_id));return {"ok":True}

@app.get("/api/plans")
def plans(user: User = Depends(current_user), session: Session = Depends(db)):
    stmt = select(InsurancePlan).order_by(InsurancePlan.id.desc())
    if user.role == "enterprise" and user.enterprise_id:
        allowed = select(AgentCommission.plan_id).where(AgentCommission.enterprise_id == user.enterprise_id)
        position_plans=select(WorkPosition.plan_id).where(WorkPosition.enterprise_id==user.enterprise_id,WorkPosition.plan_id.is_not(None))
        stmt = stmt.where(or_(InsurancePlan.id.in_(allowed),InsurancePlan.id.in_(position_plans)))
    return [plan_dict(x) for x in session.scalars(stmt)]

@app.get("/api/reports")
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

@app.get("/api/billing")
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

@app.get("/api/policies")
def policies(user: User = Depends(current_user), session: Session = Depends(db)):
    stmt=select(Policy).order_by(Policy.id.desc())
    if user.role=="enterprise" and user.enterprise_id: stmt=stmt.where(Policy.enterprise_id==user.enterprise_id)
    return [policy_dict(x,session) for x in session.scalars(stmt)]

@app.get("/api/policies/{item_id}/export")
def export_policy(item_id:int,user:User=Depends(current_user),session:Session=Depends(db)):
    policy=session.get(Policy,item_id)
    if not policy: raise HTTPException(404,'保单不存在')
    if user.role=='enterprise' and user.enterprise_id!=policy.enterprise_id: raise HTTPException(403,'无权导出该保单')
    enterprise=session.get(Enterprise,policy.enterprise_id);plan=session.get(InsurancePlan,policy.plan_id)
    import io,openpyxl
    relation=session.scalar(select(AgentCommission).where(AgentCommission.enterprise_id==policy.enterprise_id,AgentCommission.plan_id==policy.plan_id,AgentCommission.status=='active').order_by(AgentCommission.id.desc()))
    book=openpyxl.Workbook();sheet=book.active;sheet.title='保单人员明细';sheet.append(['保单号','投保单位','实际用工单位','岗位','职业类别','被保险人','身份证号','保险公司','保险方案','保险原价','总返佣比例','总返佣金额','保司结算底价','平台利润','销售最低价','实际销售价','业务员佣金','开始日期','结束日期','保单状态'])
    for person in session.scalars(select(InsuredPerson).where(InsuredPerson.policy_id==policy.id).order_by(InsuredPerson.id.asc())):
        position=session.get(WorkPosition,person.position_id) if person.position_id else None;employer=session.get(ActualEmployer,position.actual_employer_id) if position and position.actual_employer_id else None
        pricing=pricing_snapshot(plan,relation,plan_price_for_class(session,plan,person.occupation_class)) if plan else {}
        sheet.append([policy.policy_no,enterprise.name if enterprise else '',employer.name if employer else (position.actual_employer if position else ''),position.name if position else person.occupation,person.occupation_class,person.name,person.id_number,plan.insurer if plan else '',plan.name if plan else '',pricing.get('insurance_base_price',0),pricing.get('total_commission_rate',0),pricing.get('total_commission_amount',0),pricing.get('policy_floor_price',0),pricing.get('profit_amount',0),pricing.get('minimum_sale_price',0),pricing.get('sale_price',0),pricing.get('agent_commission_amount',0),policy.start_date,policy.end_date,policy.status])
    for column in sheet.columns: sheet.column_dimensions[column[0].column_letter].width=min(32,max(12,max(len(str(cell.value or '')) for cell in column)+2))
    output=io.BytesIO();book.save(output);output.seek(0)
    return StreamingResponse(output,media_type='application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',headers={'Content-Disposition':f'attachment; filename=policy-{policy.policy_no}.xlsx'})

@app.post("/api/plans")
def add_plan(data: PlanIn, user: User = Depends(current_user), session: Session = Depends(db)):
    if user.role != "admin": raise HTTPException(403,"仅总后台可新增保险方案")
    values=data.model_dump()
    if values['effective_mode']=='immediate': values['billing_mode']='daily'
    item = InsurancePlan(**values); session.add(item); session.commit(); session.refresh(item); audit(session, user, "create", "plan", str(item.id)); return plan_dict(item)

@app.get("/api/plan-tiers")
def plan_tiers(plan_id: Optional[int] = None, user: User = Depends(current_user), session: Session = Depends(db)):
    stmt=select(PlanTier).order_by(PlanTier.id.desc())
    if user.role=='enterprise' and user.enterprise_id:
        allowed=select(AgentCommission.plan_id).where(AgentCommission.enterprise_id==user.enterprise_id)
        stmt=stmt.where(PlanTier.plan_id.in_(allowed))
    if plan_id: stmt=stmt.where(PlanTier.plan_id==plan_id)
    return [serialize(x) for x in session.scalars(stmt)]

@app.post("/api/plan-tiers")
def add_plan_tier(data: PlanTierIn, user: User = Depends(current_user), session: Session = Depends(db)):
    if user.role != "admin": raise HTTPException(403,"仅总后台可维护类别价格")
    if not session.get(InsurancePlan,data.plan_id): raise HTTPException(404,"保险方案不存在")
    item=PlanTier(**data.model_dump());session.add(item);session.commit();session.refresh(item);audit(session,user,"create","plan_tier",str(item.id));return serialize(item)

@app.patch("/api/plans/{item_id}/status")
def plan_status(item_id: int, status_value: str = Query(..., alias="status"), user: User = Depends(current_user), session: Session = Depends(db)):
    if user.role != "admin": raise HTTPException(403,"仅总后台可维护保险方案")
    item = session.get(InsurancePlan, item_id)
    if not item: raise HTTPException(404, "方案不存在")
    if status_value not in {"active", "paused"}: raise HTTPException(400, "状态不合法")
    item.status = status_value; session.commit(); audit(session, user, "status_change", "plan", str(item.id), status_value); return serialize(item)

@app.patch("/api/plans/{item_id}")
def update_plan(item_id: int, data: PlanUpdate, user: User = Depends(current_user), session: Session = Depends(db)):
    if user.role != "admin": raise HTTPException(403,"仅总后台可维护保险方案")
    item = session.get(InsurancePlan, item_id)
    if not item: raise HTTPException(404, "方案不存在")
    values=data.model_dump(exclude_unset=True)
    if values.get('effective_mode')=='immediate' or (item.effective_mode=='immediate' and values.get('effective_mode') is None): values['billing_mode']='daily'
    for key, value in values.items():
        if value is not None: setattr(item, key, value)
    session.commit(); audit(session, user, "update", "plan", str(item.id)); return plan_dict(item)

@app.delete("/api/plans/{item_id}")
def delete_plan(item_id: int, user: User = Depends(current_user), session: Session = Depends(db)):
    if user.role != "admin": raise HTTPException(403,"仅总后台可删除保险方案")
    item = session.get(InsurancePlan, item_id)
    if not item: raise HTTPException(404, "方案不存在")
    used = session.scalar(select(Policy.id).where(Policy.plan_id == item_id).limit(1))
    if used: raise HTTPException(409, "该方案已有参保人员或保单使用，不能删除；请先暂停方案")
    session.delete(item); session.commit(); audit(session, user, "delete", "plan", str(item_id)); return {"ok": True, "deleted_id": item_id}

@app.get("/api/insured")
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

@app.post("/api/insured")
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

@app.patch("/api/insured/{item_id}")
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

@app.patch("/api/insured/{item_id}/status")
def insured_status(item_id:int,status_value:Literal["active","stopped","pending"]=Query(...,alias="status"),user:User=Depends(current_user),session:Session=Depends(db)):
    item=session.get(InsuredPerson,item_id)
    if not item: raise HTTPException(404,"参保员工不存在")
    if user.role=="enterprise" and user.enterprise_id!=item.enterprise_id: raise HTTPException(403,"无权操作该员工")
    item.status=status_value;session.commit();audit(session,user,"status_change","insured_person",str(item.id),status_value);return serialize(item)

@app.get("/api/insured/import-template")
def insured_import_template(user:User=Depends(current_user)):
    content='姓名,身份证号,手机号\n张三,340123199001011234,13800000000\n'
    return StreamingResponse(iter([content.encode('utf-8-sig')]),media_type='text/csv',headers={'Content-Disposition':'attachment; filename=insured-import-template.csv'})

@app.post("/api/insured/bulk")
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

@app.post("/api/insured/import-file")
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
            import io,openpyxl
            sheet=openpyxl.load_workbook(io.BytesIO(content),read_only=True,data_only=True).active
            raw=[[str(v or '').strip() for v in row] for row in sheet.iter_rows(values_only=True)]
        elif name.endswith('.csv'):
            import io,csv
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
        else: existing.status='stopped';item=existing
        affected.append(item)
    session.commit();audit(session,user,'bulk_enrollment' if kind=='enrollment' else 'bulk_termination','insured_person','',f'count={len(affected)};file={file.filename}')
    return {'ok':True,'kind':kind,'success':len(affected),'errors':[]}

@app.get("/api/enrollment/export")
def enrollment_export(kind:Literal["enrollment","termination"],date_value:str=Query(default="",alias="date"),plan_id:Optional[int]=None,user:User=Depends(current_user),session:Session=Depends(db)):
    target_date=date_value or datetime.now().strftime('%Y-%m-%d')
    stmt=select(InsuredPerson).order_by(InsuredPerson.id.asc())
    if user.role=="enterprise" and user.enterprise_id: stmt=stmt.where(InsuredPerson.enterprise_id==user.enterprise_id)
    if plan_id:
        stmt=stmt.join(WorkPosition,InsuredPerson.position_id==WorkPosition.id).where(WorkPosition.plan_id==plan_id)
    if kind=="termination": stmt=stmt.where(InsuredPerson.status=="stopped")
    else: stmt=stmt.where(InsuredPerson.created_at.like(f"{target_date}%"),InsuredPerson.status.in_(["active","pending"]))
    rows=[]
    for p in session.scalars(stmt):
        ent=session.get(Enterprise,p.enterprise_id);position=session.get(WorkPosition,p.position_id) if p.position_id else None;plan=session.get(InsurancePlan,position.plan_id) if position and position.plan_id else None;relation=session.scalar(select(AgentCommission).where(AgentCommission.enterprise_id==p.enterprise_id,AgentCommission.plan_id==plan.id,AgentCommission.status=='active').order_by(AgentCommission.id.desc())) if plan else None;pricing=pricing_snapshot(plan,relation,plan_price_for_class(session,plan,p.occupation_class)) if plan else {}
        rows.append([ent.name if ent else "",position.actual_employer if position else "",position.name if position else p.occupation,p.name,p.id_number,p.occupation_class,pricing.get('insurance_base_price',0),pricing.get('policy_floor_price',0),pricing.get('profit_amount',0),pricing.get('minimum_sale_price',0),pricing.get('sale_price',0),pricing.get('total_commission_amount',0),pricing.get('agent_commission_amount',0),p.status,p.created_at.strftime('%Y-%m-%d') if p.created_at else target_date])
    out=io.StringIO();csv.writer(out).writerows([["投保单位","实际工作单位","岗位","姓名","身份证号","职业类别","保险原价","保司结算底价","平台利润","销售最低价","实际销售价","总返佣金额","业务员佣金","状态","日期"],*rows]);out.seek(0)
    return StreamingResponse(iter([out.getvalue().encode('utf-8-sig')]),media_type='text/csv',headers={'Content-Disposition':f'attachment; filename={kind}-{target_date}.csv'})

@app.get("/api/enrollment/summary")
def enrollment_summary(date_value:str=Query(default="",alias="date"),user:User=Depends(current_user),session:Session=Depends(db)):
    target=date_value or datetime.now().strftime('%Y-%m-%d');result=[]
    for plan in session.scalars(select(InsurancePlan).order_by(InsurancePlan.id.desc())):
        stmt=select(InsuredPerson).join(WorkPosition,InsuredPerson.position_id==WorkPosition.id).where(WorkPosition.plan_id==plan.id)
        if user.role=='enterprise': stmt=stmt.where(InsuredPerson.enterprise_id==user.enterprise_id)
        people=list(session.scalars(stmt));new_count=sum(1 for x in people if str(x.created_at or '')[:10]==target and x.status!='stopped');stop_count=sum(1 for x in people if str(x.created_at or '')[:10]==target and x.status=='stopped')
        result.append({'plan_id':plan.id,'insurer':plan.insurer,'insurer_email':plan.insurer_email,'product':plan.name,'insured_count':len([x for x in people if x.status!='stopped']),'new_count':new_count,'stop_count':stop_count})
    return result

@app.get("/api/messages")
def messages(user:User=Depends(current_user),session:Session=Depends(db)):
    enterprise_ids=[user.enterprise_id] if user.role=='enterprise' and user.enterprise_id else [x for x in session.scalars(select(Enterprise.id))]
    now=datetime.now(timezone.utc);rows=[]
    for enterprise_id in enterprise_ids:
        enterprise=session.get(Enterprise,enterprise_id)
        if not enterprise: continue
        active_count=session.query(InsuredPerson).filter(InsuredPerson.enterprise_id==enterprise_id,InsuredPerson.status.in_(['active','pending'])).count();usage_daily=active_count*float(enterprise.usage_fee_daily or 0.1)
        if usage_daily>0 and enterprise.usage_balance/usage_daily<=int(enterprise.alert_days or 3): rows.append({'id':f'balance-{enterprise_id}','type':'warning','title':'使用费账户余额预警','content':f'{enterprise.name}余额预计可用 {enterprise.usage_balance/usage_daily:.1f} 天','created_at':now.isoformat(),'path':'/pages/billing/billing'})
        pending=session.query(InsuredPerson).filter(InsuredPerson.enterprise_id==enterprise_id,InsuredPerson.status=='pending').count()
        if pending: rows.append({'id':f'pending-{enterprise_id}','type':'todo','title':'员工待审核','content':f'{pending} 名员工正在等待参保审核','created_at':now.isoformat(),'path':'/pages/employees/employees'})
        supplements=session.query(Claim).filter(Claim.enterprise_id==enterprise_id,Claim.status=='supplement').count()
        if supplements: rows.append({'id':f'claim-{enterprise_id}','type':'danger','title':'理赔材料待补充','content':f'{supplements} 件理赔需要补充材料','created_at':now.isoformat(),'path':'/pages/claims/claims'})
        pending_positions=session.query(WorkPosition).filter(WorkPosition.enterprise_id==enterprise_id,WorkPosition.status.in_(['pending','supplement'])).count()
        if pending_positions: rows.append({'id':f'position-{enterprise_id}','type':'todo','title':'岗位定类进度','content':f'{pending_positions} 个岗位待审核或补充材料','created_at':now.isoformat(),'path':'/pages/positions/positions'})
    if not rows: rows.append({'id':'welcome','type':'success','title':'当前没有待办','content':'所有参保、账户和理赔业务运行正常','created_at':now.isoformat(),'path':'/pages/home/home'})
    return rows

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

@app.post("/api/notifications/send")
def send_notification(data:NotificationIn,user:User=Depends(current_user),session:Session=Depends(db)):
    result=sms_provider().send_sms(data.recipient,data.template,{"content":data.content}) if data.kind=="sms" else email_provider().send_email(data.recipient,data.subject,data.content)
    audit(session,user,"send",data.kind,data.recipient,result.message);return {"ok":result.ok,"provider":result.provider,"request_id":result.request_id,"message":result.message}

@app.post("/api/payments")
def create_payment(data:PaymentIn,user:User=Depends(current_user),session:Session=Depends(db)):
    if user.role not in {"admin","enterprise"}: raise HTTPException(403,"无权创建充值订单")
    if user.role=="enterprise" and user.enterprise_id!=data.enterprise_id: raise HTTPException(403,"无权为该单位充值")
    if not session.get(Enterprise,data.enterprise_id): raise HTTPException(404,"投保单位不存在")
    order=f"PAY-{datetime.now().strftime('%Y%m%d%H%M%S')}-{secrets.token_hex(3).upper()}"; result=payment_provider().create_payment(data.amount,order)
    row=PaymentRecord(order_no=order,enterprise_id=data.enterprise_id,account=data.account,amount=data.amount,status="pending",provider=result.provider);session.add(row);session.commit();return {"order_no":order,"status":row.status,"pay_url":result.data.get("pay_url",""),"request_id":result.request_id}

@app.post("/api/payments/callback")
def payment_callback(data:PaymentCallbackIn,session:Session=Depends(db)):
    row=session.scalar(select(PaymentRecord).where(PaymentRecord.order_no==data.order_no))
    if not row: raise HTTPException(404,"支付订单不存在")
    previous=row.status
    if previous=="paid": return {"ok":True,"order_no":row.order_no,"status":row.status,"idempotent":True}
    row.status=data.status
    if data.status=="paid":
        ent=session.get(Enterprise,row.enterprise_id)
        if row.account=="premium": ent.premium_balance += row.amount
        else: ent.usage_balance += row.amount
    session.commit();return {"ok":True,"order_no":row.order_no,"status":row.status,"idempotent":False}

@app.get("/api/payments/reconcile")
def payment_reconcile(user:User=Depends(current_user),session:Session=Depends(db)):
    if user.role!="admin": raise HTTPException(403,"仅总后台可对账")
    return {"pending":session.query(PaymentRecord).filter(PaymentRecord.status=="pending").count(),"paid":session.query(PaymentRecord).filter(PaymentRecord.status=="paid").count(),"failed":session.query(PaymentRecord).filter(PaymentRecord.status=="failed").count()}

@app.get("/api/invoices")
def invoices(user:User=Depends(current_user),session:Session=Depends(db)):
    stmt=select(Invoice).order_by(Invoice.id.desc())
    if user.role=='enterprise' and user.enterprise_id: stmt=stmt.where(Invoice.enterprise_id==user.enterprise_id)
    elif user.role!='admin': raise HTTPException(403,'无权查看发票')
    result=[]
    for item in session.scalars(stmt):
        enterprise=session.get(Enterprise,item.enterprise_id)
        result.append({**serialize(item),'enterprise_name':enterprise.name if enterprise else ''})
    return result

@app.post("/api/invoices")
def create_invoice(data:InvoiceIn,user:User=Depends(current_user),session:Session=Depends(db)):
    if user.role=='enterprise' and user.enterprise_id!=data.enterprise_id: raise HTTPException(403,'无权申请其他单位发票')
    if user.role not in {'admin','enterprise'}: raise HTTPException(403,'无权申请发票')
    if not session.get(Enterprise,data.enterprise_id): raise HTTPException(404,'投保单位不存在')
    item=Invoice(**data.model_dump());session.add(item);session.commit();session.refresh(item);audit(session,user,'create','invoice',str(item.id),f'{item.account}:{item.amount}');return serialize(item)

@app.patch("/api/invoices/{item_id}")
def update_invoice(item_id:int,data:InvoiceUpdate,user:User=Depends(current_user),session:Session=Depends(db)):
    if user.role!='admin': raise HTTPException(403,'仅总后台可审核发票')
    item=session.get(Invoice,item_id)
    if not item: raise HTTPException(404,'发票申请不存在')
    item.status=data.status;session.commit();audit(session,user,'status_change','invoice',str(item.id),data.status);return serialize(item)

@app.get("/api/audit-logs")
def audit_logs(limit:int=Query(100,ge=1,le=500),user:User=Depends(current_user),session:Session=Depends(db)):
    stmt=select(AuditLog).order_by(AuditLog.id.desc()).limit(limit)
    if user.role=='enterprise':
        operator_ids=select(User.id).where(User.enterprise_id==user.enterprise_id)
        stmt=select(AuditLog).where(AuditLog.user_id.in_(operator_ids)).order_by(AuditLog.id.desc()).limit(limit)
    elif user.role!='admin': raise HTTPException(403,'无权查看审计日志')
    result=[]
    for item in session.scalars(stmt):
        operator=session.get(User,item.user_id)
        result.append({**serialize(item),'operator':operator.name if operator else '系统'})
    return result

@app.get("/api/providers/status")
def provider_status(user: User = Depends(current_user)):
    return {"mode": os.getenv("INTEGRATION_MODE", "mock"), "insurer_api": bool(os.getenv("INSURER_API_BASE_URL")), "sms": bool(os.getenv("SMS_PROVIDER_URL")), "email": bool(os.getenv("SMTP_HOST")), "payment": bool(os.getenv("PAYMENT_PROVIDER_URL"))}

@app.post("/api/enrollment/send")
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

@app.post("/api/enrollment/email")
def enrollment_email(enterprise_id:int,plan_id:int,kind:Literal['enrollment','termination']='enrollment',date_value:str=Query(default="",alias="date"),user:User=Depends(current_user),session:Session=Depends(db)):
    if user.role=='enterprise' and user.enterprise_id!=enterprise_id: raise HTTPException(403,'无权发送该单位名单')
    ent=session.get(Enterprise,enterprise_id);plan=session.get(InsurancePlan,plan_id)
    if not ent or not plan: raise HTTPException(404,'投保单位或产品不存在')
    if not plan.insurer_email: raise HTTPException(400,'该保险公司方案尚未配置接收邮箱')
    target_date=date_value or datetime.now().strftime('%Y-%m-%d');stmt=select(InsuredPerson).join(WorkPosition,InsuredPerson.position_id==WorkPosition.id).where(InsuredPerson.enterprise_id==enterprise_id,WorkPosition.plan_id==plan_id)
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

@app.get('/api/enrollment/emails')
def enrollment_emails(user:User=Depends(current_user),session:Session=Depends(db)):
    stmt=select(EnrollmentEmail).order_by(EnrollmentEmail.id.desc())
    if user.role=='enterprise': stmt=stmt.where(EnrollmentEmail.enterprise_id==user.enterprise_id)
    result=[]
    for item in session.scalars(stmt):
        ent=session.get(Enterprise,item.enterprise_id);plan=session.get(InsurancePlan,item.plan_id);result.append({**serialize(item),'enterprise_name':ent.name if ent else '','plan_name':plan.name if plan else '','insurer':plan.insurer if plan else ''})
    return result

@app.get("/api/health")
def health(): return {"ok": True, "service": "xiangbangbaojingyun", "time": datetime.now(timezone.utc).isoformat()}

app.mount("/", StaticFiles(directory=ROOT, html=True), name="frontend")
