from __future__ import annotations

import os
import secrets
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
from sqlalchemy import Boolean, DateTime, Float, ForeignKey, Integer, String, Text, create_engine, or_, select
from sqlalchemy.orm import DeclarativeBase, Mapped, Session, mapped_column, sessionmaker
from .providers import insurer_provider, sms_provider, email_provider, payment_provider

ROOT = Path(__file__).resolve().parents[1]
DATABASE_URL = os.getenv("DATABASE_URL", f"sqlite:///{ROOT / 'data.db'}")
if DATABASE_URL.startswith("postgres://"):
    DATABASE_URL = DATABASE_URL.replace("postgres://", "postgresql+psycopg://", 1)
elif DATABASE_URL.startswith("postgresql://"):
    DATABASE_URL = DATABASE_URL.replace("postgresql://", "postgresql+psycopg://", 1)
SECRET_KEY = os.getenv("JWT_SECRET", "dev-only-change-this-secret-at-least-32-bytes")
ALGORITHM = "HS256"
pwd = CryptContext(schemes=["pbkdf2_sha256"], deprecated="auto")

engine = create_engine(DATABASE_URL, connect_args={"check_same_thread": False} if DATABASE_URL.startswith("sqlite") else {})
SessionLocal = sessionmaker(bind=engine, autoflush=False, expire_on_commit=False)

class Base(DeclarativeBase): pass

class User(Base):
    __tablename__ = "users"
    id: Mapped[int] = mapped_column(primary_key=True)
    username: Mapped[str] = mapped_column(String(80), unique=True, index=True)
    password_hash: Mapped[str] = mapped_column(String(255))
    name: Mapped[str] = mapped_column(String(80), default="平台管理员")
    role: Mapped[str] = mapped_column(String(40), default="admin")
    enterprise_id: Mapped[Optional[int]] = mapped_column(Integer, nullable=True)
    phone: Mapped[str] = mapped_column(String(30), default="")
    commission_rate: Mapped[float] = mapped_column(Float, default=0.15)
    total_commission: Mapped[float] = mapped_column(Float, default=0)
    status: Mapped[str] = mapped_column(String(30), default="active")
    active: Mapped[bool] = mapped_column(Boolean, default=True)
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
    occupation_class: Mapped[str] = mapped_column(String(30), default="3类")
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
    contact_name: Mapped[str] = mapped_column(String(80), default="")
    contact_phone: Mapped[str] = mapped_column(String(30), default="")
    insurer_report_no: Mapped[str] = mapped_column(String(100), default="")
    current_handler: Mapped[str] = mapped_column(String(80), default="平台理赔专员")
    deadline: Mapped[str] = mapped_column(String(30), default="")
    approved_amount: Mapped[float] = mapped_column(Float, default=0)
    paid_at: Mapped[str] = mapped_column(String(30), default="")
    rejection_reason: Mapped[str] = mapped_column(Text, default="")
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

class LoginIn(BaseModel): username: str; password: str; portal: str = "admin"
class EnterpriseIn(BaseModel): name: str; kind: str = "企业"; contact: str = ""; phone: str = ""; credit_code: str = ""; agent_id: Optional[int] = None; usage_fee_daily: float = Field(default=0.1, ge=0); alert_days: int = Field(default=3, ge=3, le=7)
class EnterpriseUpdate(BaseModel): name: Optional[str] = None; kind: Optional[str] = None; contact: Optional[str] = None; phone: Optional[str] = None; credit_code: Optional[str] = None; agent_id: Optional[int] = None; usage_fee_daily: Optional[float] = Field(default=None, ge=0); alert_days: Optional[int] = Field(default=None, ge=3, le=7)
class PositionIn(BaseModel): enterprise_id: Optional[int] = None; actual_employer: str; actual_employer_id: Optional[int] = None; name: str; occupation_class: Literal["1-3类","4类","5类","超5类"] = "1-3类"; plan_id: Optional[int] = None
class ActualEmployerIn(BaseModel): enterprise_id: Optional[int] = None; name: str; credit_code: str = ""; contact: str = ""; phone: str = ""
class PositionVideoIn(BaseModel): name: str; url: str = ""
class PositionVideoReviewIn(BaseModel): status: Literal["pending","approved","rejected","supplement"]; review_note: str = ""
class RechargeIn(BaseModel): account: str = "premium"; amount: float = Field(gt=0)
class CommissionIn(BaseModel): agent_id: int; enterprise_id: int; plan_id: int; rate: float = Field(default=0, ge=0, le=1); mode: Literal["rebate","markup"] = "rebate"; markup_amount: float = Field(default=0, ge=0)
class CommissionUpdate(BaseModel): rate: Optional[float] = Field(default=None, ge=0, le=1); mode: Optional[Literal["rebate","markup"]] = None; markup_amount: Optional[float] = Field(default=None, ge=0); status: Optional[str] = None
class PlanTierIn(BaseModel): plan_id: int; occupation_class: Literal["1-3类","4类","5类","超5类"]; price: float = Field(ge=0); coverage: str = ""
class PlanIn(BaseModel): insurer: str; insurer_email: str = ""; name: str; coverage: str = ""; occupation_classes: str = "1-4类"; price: float = Field(ge=0); commission_rate: float = Field(default=0, ge=0, le=1); payment_mode: str = "企业直投"; billing_mode: Literal["monthly","daily"] = "monthly"; effective_mode: Literal["next_day","immediate"] = "next_day"
class PlanUpdate(BaseModel): insurer: Optional[str] = None; insurer_email: Optional[str] = None; name: Optional[str] = None; coverage: Optional[str] = None; occupation_classes: Optional[str] = None; price: Optional[float] = Field(default=None, ge=0); commission_rate: Optional[float] = Field(default=None, ge=0, le=1); payment_mode: Optional[str] = None; billing_mode: Optional[Literal["monthly","daily"]] = None; effective_mode: Optional[Literal["next_day","immediate"]] = None
class PersonIn(BaseModel): enterprise_id: int; name: str; phone: str = ""; id_number: str = Field(min_length=6); occupation: str = ""; occupation_class: str = "3类"; position_id: Optional[int] = None
class PersonUpdate(BaseModel): name: Optional[str] = None; phone: Optional[str] = None; id_number: Optional[str] = Field(default=None, min_length=6); position_id: Optional[int] = None
class BulkPersonRow(BaseModel): name: str; id_number: str = Field(min_length=6); phone: str = ""
class BulkPersonIn(BaseModel): enterprise_id: int; position_id: int; rows: list[BulkPersonRow] = Field(min_length=1, max_length=1000)
class ClaimIn(BaseModel): enterprise_id: int; person_id: int; description: str; amount: float = Field(default=0,ge=0); accident_at: str; accident_place: str; accident_type: str = "工伤事故"; hospital: str = ""; diagnosis: str = ""; contact_name: str = ""; contact_phone: str = ""
class ClaimUpdate(BaseModel): description: Optional[str]=None; hospital:Optional[str]=None; diagnosis:Optional[str]=None; insurer_report_no:Optional[str]=None; current_handler:Optional[str]=None; deadline:Optional[str]=None; rejection_reason:Optional[str]=None; risk_level:Optional[Literal['normal','attention','high']]=None
class ClaimStatusIn(BaseModel): status: Literal["reported","collecting","submitted","insurer_review","supplement","approved","paid","rejected","closed"]; note: str = ""; approved_amount: Optional[float] = Field(default=None,ge=0)
class ClaimDocumentIn(BaseModel): name: str; url: str = ""; doc_type: str = "other"
class PaymentIn(BaseModel): enterprise_id: int; account: Literal["premium","usage"] = "premium"; amount: float = Field(gt=0)
class PaymentCallbackIn(BaseModel): order_no: str; status: Literal["paid","failed","pending"]; provider_trade_no: str = ""
class NotificationIn(BaseModel): kind: Literal["sms","email"]; recipient: str; subject: str = "响帮帮保经云通知"; content: str; template: str = "general"
class AgentIn(BaseModel): username: str; password: str; name: str; phone: str = ""; commission_rate: float = Field(default=.15, ge=0, le=1)

class TokenOut(BaseModel): access_token: str; token_type: str = "bearer"
class UserOut(BaseModel): model_config = ConfigDict(from_attributes=True); id: int; username: str; name: str; role: str

security = HTTPBearer(auto_error=False)
def db():
    session = SessionLocal()
    try: yield session
    finally: session.close()

def current_user(creds: HTTPAuthorizationCredentials = Depends(security), session: Session = Depends(db)) -> User:
    if not creds: raise HTTPException(status_code=401, detail="请先登录")
    try: payload = jwt.decode(creds.credentials, SECRET_KEY, algorithms=[ALGORITHM]); uid = int(payload["sub"])
    except Exception: raise HTTPException(status_code=401, detail="登录已过期")
    user = session.get(User, uid)
    if not user or not user.active: raise HTTPException(status_code=401, detail="用户无效")
    return user

def audit(session: Session, user: User, action: str, object_type: str, object_id: str, detail: str = ""):
    session.add(AuditLog(user_id=user.id, action=action, object_type=object_type, object_id=object_id, detail=detail)); session.commit()

def serialize(obj):
    return {c.name: getattr(obj, c.name) for c in obj.__table__.columns}

app = FastAPI(title="响帮帮保经云 API", version="3.1.0")
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
            for column, definition in [("phone", "VARCHAR(30) DEFAULT ''"), ("commission_rate", "FLOAT DEFAULT 0.15"), ("total_commission", "FLOAT DEFAULT 0"), ("status", "VARCHAR(30) DEFAULT 'active'")]:
                if column not in columns: s.connection().exec_driver_sql(f"ALTER TABLE users ADD COLUMN {column} {definition}")
            enterprise_columns = {row[1] for row in s.connection().exec_driver_sql("PRAGMA table_info(enterprises)")}
            if "agent_id" not in enterprise_columns: s.connection().exec_driver_sql("ALTER TABLE enterprises ADD COLUMN agent_id INTEGER")
            if "usage_fee_daily" not in enterprise_columns: s.connection().exec_driver_sql("ALTER TABLE enterprises ADD COLUMN usage_fee_daily FLOAT DEFAULT 0.1")
            if "alert_days" not in enterprise_columns: s.connection().exec_driver_sql("ALTER TABLE enterprises ADD COLUMN alert_days INTEGER DEFAULT 3")
            commission_columns = {row[1] for row in s.connection().exec_driver_sql("PRAGMA table_info(agent_commissions)")}
            if "mode" not in commission_columns: s.connection().exec_driver_sql("ALTER TABLE agent_commissions ADD COLUMN mode VARCHAR(20) DEFAULT 'rebate'")
            if "markup_amount" not in commission_columns: s.connection().exec_driver_sql("ALTER TABLE agent_commissions ADD COLUMN markup_amount FLOAT DEFAULT 0")
            plan_columns = {row[1] for row in s.connection().exec_driver_sql("PRAGMA table_info(insurance_plans)")}
            if "billing_mode" not in plan_columns: s.connection().exec_driver_sql("ALTER TABLE insurance_plans ADD COLUMN billing_mode VARCHAR(20) DEFAULT 'monthly'")
            if "effective_mode" not in plan_columns: s.connection().exec_driver_sql("ALTER TABLE insurance_plans ADD COLUMN effective_mode VARCHAR(20) DEFAULT 'next_day'")
            if "insurer_email" not in plan_columns: s.connection().exec_driver_sql("ALTER TABLE insurance_plans ADD COLUMN insurer_email VARCHAR(160) DEFAULT ''")
            insured_columns = {row[1] for row in s.connection().exec_driver_sql("PRAGMA table_info(insured_people)")}
            if "id_number" not in insured_columns: s.connection().exec_driver_sql("ALTER TABLE insured_people ADD COLUMN id_number VARCHAR(40) DEFAULT ''")
            if "position_id" not in insured_columns: s.connection().exec_driver_sql("ALTER TABLE insured_people ADD COLUMN position_id INTEGER")
            position_columns = {row[1] for row in s.connection().exec_driver_sql("PRAGMA table_info(work_positions)")}
            if "actual_employer_id" not in position_columns: s.connection().exec_driver_sql("ALTER TABLE work_positions ADD COLUMN actual_employer_id INTEGER")
            claim_columns = {row[1] for row in s.connection().exec_driver_sql("PRAGMA table_info(claims)")}
            for column, definition in [("accident_at","VARCHAR(30) DEFAULT ''"),("accident_place","VARCHAR(200) DEFAULT ''"),("accident_type","VARCHAR(60) DEFAULT '工伤事故'"),("hospital","VARCHAR(160) DEFAULT ''"),("diagnosis","TEXT DEFAULT ''"),("contact_name","VARCHAR(80) DEFAULT ''"),("contact_phone","VARCHAR(30) DEFAULT ''"),("insurer_report_no","VARCHAR(100) DEFAULT ''"),("current_handler","VARCHAR(80) DEFAULT '平台理赔专员'"),("deadline","VARCHAR(30) DEFAULT ''"),("approved_amount","FLOAT DEFAULT 0"),("paid_at","VARCHAR(30) DEFAULT ''"),("rejection_reason","TEXT DEFAULT ''"),("risk_level","VARCHAR(20) DEFAULT 'normal'")]:
                if column not in claim_columns: s.connection().exec_driver_sql(f"ALTER TABLE claims ADD COLUMN {column} {definition}")
        if not s.scalar(select(User).where(User.username == "admin")):
            s.add(User(username="admin", password_hash=pwd.hash(os.getenv("ADMIN_PASSWORD", "admin123")), name="响帮帮管理员", role="admin"))
        s.commit()
        # 用户电脑端演示账号：仅在数据库尚未配置参保单位账号时创建
        if not s.scalar(select(User).where(User.username == "enterprise")):
            demo_enterprise = s.scalar(select(Enterprise).order_by(Enterprise.id.asc()))
            if not demo_enterprise:
                demo_enterprise = Enterprise(name="演示参保单位", kind="企业", contact="演示管理员", phone="", status="active")
                s.add(demo_enterprise); s.flush()
            s.add(User(username="enterprise", password_hash=pwd.hash(os.getenv("ENTERPRISE_PASSWORD", "enterprise123")), name=f"{demo_enterprise.name}管理员", role="enterprise", enterprise_id=demo_enterprise.id))
            s.commit()

@app.post("/api/auth/login", response_model=TokenOut)
def login(data: LoginIn, session: Session = Depends(db)):
    user = session.scalar(select(User).where(User.username == data.username))
    if not user or not pwd.verify(data.password, user.password_hash): raise HTTPException(401, "账号或密码错误")
    if data.portal == "admin" and user.role != "admin": raise HTTPException(403, "该账号不是总后台账号")
    if data.portal == "enterprise" and user.role != "enterprise": raise HTTPException(403, "该账号不是参保单位账号")
    token = jwt.encode({"sub": str(user.id), "exp": datetime.now(timezone.utc) + timedelta(hours=12)}, SECRET_KEY, algorithm=ALGORITHM)
    return TokenOut(access_token=token)

@app.get("/api/auth/me", response_model=UserOut)
def me(user: User = Depends(current_user)): return user

@app.get("/api/agents")
def agents(user: User = Depends(current_user), session: Session = Depends(db)):
    if user.role != "admin": raise HTTPException(403, "仅总后台可管理业务员")
    return [{"id": x.id, "username": x.username, "name": x.name, "phone": x.phone, "commission_rate": x.commission_rate, "total_commission": x.total_commission, "role": x.role, "active": x.active, "status": x.status, "created_at": x.created_at} for x in session.scalars(select(User).where(User.role == "salesperson").order_by(User.id.desc()))]

@app.post("/api/agents")
def add_agent(data: AgentIn, user: User = Depends(current_user), session: Session = Depends(db)):
    if user.role != "admin": raise HTTPException(403, "仅总后台可管理业务员")
    if session.scalar(select(User).where(User.username == data.username)): raise HTTPException(409, "业务员账号已存在")
    item = User(username=data.username, password_hash=pwd.hash(data.password), name=data.name, phone=data.phone, commission_rate=data.commission_rate, role="salesperson")
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
    result=[]
    for x in session.scalars(select(AgentCommission).order_by(AgentCommission.id.desc())):
        item=serialize(x); agent=session.get(User,x.agent_id); enterprise=session.get(Enterprise,x.enterprise_id); plan=session.get(InsurancePlan,x.plan_id); item.update(agent_name=agent.name if agent else "",enterprise_name=enterprise.name if enterprise else "",plan_name=plan.name if plan else ""); result.append(item)
    return result

@app.post("/api/agent-commissions")
def add_agent_commission(data: CommissionIn, user: User = Depends(current_user), session: Session = Depends(db)):
    if user.role != "admin": raise HTTPException(403, "仅总后台可配置佣金")
    agent=session.get(User,data.agent_id); enterprise=session.get(Enterprise,data.enterprise_id); plan=session.get(InsurancePlan,data.plan_id)
    if not agent or agent.role != "salesperson": raise HTTPException(404,"业务员不存在")
    if not enterprise or not plan: raise HTTPException(404,"投保单位或产品方案不存在")
    existing=session.scalars(select(AgentCommission).where(AgentCommission.enterprise_id==data.enterprise_id)).all()
    if existing and any(x.agent_id != data.agent_id for x in existing): raise HTTPException(409,"一个投保单位只能关联一个业务员；该单位已关联其他业务员")
    item=AgentCommission(**data.model_dump());session.add(item)
    if enterprise.agent_id is None: enterprise.agent_id = data.agent_id
    session.commit();session.refresh(item);audit(session,user,"create","agent_commission",str(item.id));return serialize(item)

@app.patch("/api/agent-commissions/{item_id}")
def update_agent_commission(item_id:int,data:CommissionUpdate,user:User=Depends(current_user),session:Session=Depends(db)):
    if user.role!="admin": raise HTTPException(403,"仅总后台可修改佣金关系")
    item=session.get(AgentCommission,item_id)
    if not item: raise HTTPException(404,"佣金关系不存在")
    for k,v in data.model_dump(exclude_unset=True).items():
        if v is not None: setattr(item,k,v)
    if item.mode=="markup": item.rate=0
    session.commit();audit(session,user,"update","agent_commission",str(item.id));return serialize(item)

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
    alerts=[]
    for ent in enterprises:
        active_people=session.query(InsuredPerson).filter(InsuredPerson.enterprise_id==ent.id,InsuredPerson.status.in_(['active','pending'])).count()
        daily_usage=active_people*float(ent.usage_fee_daily or 0.1)
        daily_premium=sum(float(p.premium or 0)/30 for p in session.scalars(select(Policy).where(Policy.enterprise_id==ent.id,Policy.status=='active')))
        for account,balance,daily in [('premium',ent.premium_balance,daily_premium),('usage',ent.usage_balance,daily_usage)]:
            days_left=999999 if daily<=0 else balance/daily
            if days_left <= int(ent.alert_days or 3): alerts.append({'enterprise_id':ent.id,'enterprise_name':ent.name,'account':account,'balance':balance,'daily_burn':daily,'days_left':round(days_left,1),'alert_days':ent.alert_days or 3,'level':'critical' if days_left<=1 else 'warning'})
    return {"portal": "enterprise" if user.role == "enterprise" else "admin", "enterprises": len(enterprises), "people": len(people), "active_policies": session.query(Policy).filter(Policy.status == "active", Policy.enterprise_id.in_(enterprise_filter)).count() if enterprise_filter else session.query(Policy).filter(Policy.status == "active").count(), "pending_enterprises": session.query(Enterprise).filter(Enterprise.status == "pending").count() if not enterprise_filter else 0, "pending_people": len([x for x in people if x.status == "pending"]), "claims_open": session.query(Claim).filter(Claim.status.not_in(["paid", "closed"]), Claim.enterprise_id.in_(enterprise_filter)).count() if enterprise_filter else session.query(Claim).filter(Claim.status.not_in(["paid", "closed"])).count(), "premium_balance": sum(x.premium_balance for x in enterprises), "usage_balance": sum(x.usage_balance for x in enterprises), "balance_alerts": alerts}

@app.get("/api/screen/products")
def screen_products(user: User = Depends(current_user), session: Session = Depends(db)):
    result=[]
    for plan in session.scalars(select(InsurancePlan).order_by(InsurancePlan.id.desc())):
        policy_query=session.query(Policy).filter(Policy.plan_id==plan.id)
        if user.role=="enterprise" and user.enterprise_id: policy_query=policy_query.filter(Policy.enterprise_id==user.enterprise_id)
        policies=policy_query.all(); enterprise_ids={x.enterprise_id for x in policies}; insured_count=session.query(InsuredPerson).join(Policy,InsuredPerson.policy_id==Policy.id).filter(Policy.plan_id==plan.id).count()
        result.append({"plan_id":plan.id,"insurer":plan.insurer,"product":plan.name,"insured_count":insured_count,"enterprise_count":len(enterprise_ids),"premium_total":sum(float(x.premium or 0) for x in policies),"policy_count":len(policies)})
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
    if data.agent_id is not None:
        agent = session.get(User, data.agent_id)
        if not agent or agent.role != "salesperson": raise HTTPException(404, "业务员不存在")
    item = Enterprise(**data.model_dump()); session.add(item); session.commit(); session.refresh(item); audit(session, user, "create", "enterprise", str(item.id)); return serialize(item)

@app.patch("/api/enterprises/{item_id}/status")
def enterprise_status(item_id: int, status_value: str = Query(..., alias="status"), user: User = Depends(current_user), session: Session = Depends(db)):
    item = session.get(Enterprise, item_id)
    if not item: raise HTTPException(404, "企业不存在")
    item.status = status_value; session.commit(); audit(session, user, "status_change", "enterprise", str(item.id), status_value); return serialize(item)

@app.patch("/api/enterprises/{item_id}")
def update_enterprise(item_id: int, data: EnterpriseUpdate, user: User = Depends(current_user), session: Session = Depends(db)):
    item = session.get(Enterprise, item_id)
    if not item: raise HTTPException(404, "投保单位不存在")
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
    if user.role == "enterprise" and user.enterprise_id != item_id: raise HTTPException(403, "无权为该单位充值")
    if data.account == "premium": item.premium_balance += data.amount
    elif data.account == "usage": item.usage_balance += data.amount
    else: raise HTTPException(400, "账户类型不合法")
    session.commit(); audit(session, user, "recharge", "enterprise", str(item_id), f"{data.account}:{data.amount}"); return serialize(item)

@app.get("/api/enterprises/{item_id}/admins")
def enterprise_admins(item_id: int, user: User = Depends(current_user), session: Session = Depends(db)):
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
        plan=session.get(InsurancePlan,x.plan_id);agent=session.get(User,x.agent_id); people=session.query(InsuredPerson).filter(InsuredPerson.enterprise_id==item_id).count(); premium=session.query(Policy).filter(Policy.enterprise_id==item_id,Policy.plan_id==x.plan_id).with_entities(Policy.premium).all(); rows.append({"id":x.id,"product":plan.name if plan else "","insurer":plan.insurer if plan else "","agent":agent.name if agent else "","commission_rate":x.rate,"insured_count":people,"premium_total":sum(float(p[0] or 0) for p in premium),"status":x.status})
    return rows

@app.get("/api/positions")
def positions(user: User = Depends(current_user), session: Session = Depends(db)):
    stmt=select(WorkPosition).order_by(WorkPosition.id.desc())
    if user.role == "enterprise" and user.enterprise_id: stmt=stmt.where(WorkPosition.enterprise_id==user.enterprise_id)
    result=[]
    for x in session.scalars(stmt):
        item=serialize(x);em=session.get(ActualEmployer,x.actual_employer_id) if x.actual_employer_id else None;item['actual_employer_name']=em.name if em else x.actual_employer;item['video_count']=session.query(PositionVideo).filter(PositionVideo.position_id==x.id).count();result.append(item)
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
    item=PositionVideo(position_id=item_id,**data.model_dump());session.add(item);session.commit();session.refresh(item);audit(session,user,'upload','position_video',str(item.id));return serialize(item)

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
    item=PositionVideo(position_id=item_id,name=file.filename or stored,url=f'/uploads/positions/{item_id}/{stored}',status='pending');session.add(item);session.commit();session.refresh(item);audit(session,user,'upload','position_video',str(item.id));return serialize(item)

@app.patch("/api/position-videos/{item_id}/review")
def review_position_video(item_id:int,data:PositionVideoReviewIn,user:User=Depends(current_user),session:Session=Depends(db)):
    if user.role!='admin': raise HTTPException(403,'仅平台端可审核岗位视频')
    item=session.get(PositionVideo,item_id)
    if not item: raise HTTPException(404,'岗位视频不存在')
    item.status=data.status;item.review_note=data.review_note;session.commit();audit(session,user,'review','position_video',str(item.id),data.status);return serialize(item)

@app.post("/api/positions")
def add_position(data: PositionIn, user: User = Depends(current_user), session: Session = Depends(db)):
    target_enterprise = user.enterprise_id if user.role == "enterprise" else data.enterprise_id
    if not target_enterprise or not session.get(Enterprise, target_enterprise): raise HTTPException(400,"请先绑定有效投保单位")
    if data.actual_employer_id and (not session.get(ActualEmployer,data.actual_employer_id) or session.get(ActualEmployer,data.actual_employer_id).enterprise_id!=target_enterprise): raise HTTPException(400,"实际用工单位不属于该投保单位")
    payload=data.model_dump(exclude={"enterprise_id"}); item=WorkPosition(**payload,enterprise_id=target_enterprise)
    session.add(item);session.commit();session.refresh(item);audit(session,user,"create","position",str(item.id));return serialize(item)

@app.patch("/api/positions/{item_id}")
def update_position(item_id:int,data:PositionIn,user:User=Depends(current_user),session:Session=Depends(db)):
    item=session.get(WorkPosition,item_id)
    if not item: raise HTTPException(404,"岗位不存在")
    if user.role=="enterprise" and item.enterprise_id!=user.enterprise_id: raise HTTPException(403,"无权操作")
    for k,v in data.model_dump(exclude_unset=True).items():
        if k != "enterprise_id" and v is not None: setattr(item,k,v)
    session.commit();audit(session,user,"update","position",str(item.id));return serialize(item)

@app.delete("/api/positions/{item_id}")
def delete_position(item_id:int,user:User=Depends(current_user),session:Session=Depends(db)):
    item=session.get(WorkPosition,item_id)
    if not item: raise HTTPException(404,"岗位不存在")
    if user.role=="enterprise" and item.enterprise_id!=user.enterprise_id: raise HTTPException(403,"无权操作")
    session.delete(item);session.commit();audit(session,user,"delete","position",str(item_id));return {"ok":True}

@app.get("/api/plans")
def plans(user: User = Depends(current_user), session: Session = Depends(db)):
    stmt = select(InsurancePlan).order_by(InsurancePlan.id.desc())
    if user.role == "enterprise" and user.enterprise_id:
        allowed = select(AgentCommission.plan_id).where(AgentCommission.enterprise_id == user.enterprise_id)
        stmt = stmt.where(InsurancePlan.id.in_(allowed))
    return [serialize(x) for x in session.scalars(stmt)]

@app.get("/api/reports")
def reports(user: User = Depends(current_user), session: Session = Depends(db)):
    enterprise_id = user.enterprise_id if user.role == "enterprise" else None
    policies = session.scalars(select(Policy).where(Policy.enterprise_id == enterprise_id) if enterprise_id else select(Policy)).all()
    people = session.query(InsuredPerson).filter(InsuredPerson.enterprise_id == enterprise_id).count() if enterprise_id else session.query(InsuredPerson).count()
    claims = session.query(Claim).filter(Claim.enterprise_id == enterprise_id).count() if enterprise_id else session.query(Claim).count()
    now=date.today(); days=calendar.monthrange(now.year,now.month)[1]
    def prorated(policy):
        try:
            start=datetime.strptime(policy.start_date,'%Y-%m-%d').date() if policy.start_date else now.replace(day=1)
            end=datetime.strptime(policy.end_date,'%Y-%m-%d').date() if policy.end_date else now.replace(day=days)
            active=max(0,(min(end,now.replace(day=days))-max(start,now.replace(day=1))).days+1)
            return float(policy.premium or 0)*active/days
        except Exception: return float(policy.premium or 0)
    premium = sum(prorated(x) for x in policies)
    return [{"id":"premium","name":"保费汇总报表","period":f"{now.year}-{now.month:02d}按实际天数","value":premium,"detail":f"{len(policies)} 张保单，按当月 {days} 天折算"},{"id":"people","name":"参保人员报表","period":"当前","value":people,"detail":"在册参保人员"},{"id":"claims","name":"理赔统计报表","period":"累计","value":claims,"detail":"理赔案件"}]

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
    result=[]
    for x in session.scalars(stmt):
        enterprise=session.get(Enterprise,x.enterprise_id);plan=session.get(InsurancePlan,x.plan_id);result.append({**serialize(x),"enterprise_name":enterprise.name if enterprise else "","insurer":plan.insurer if plan else "","plan_name":plan.name if plan else "","billing_mode":plan.billing_mode if plan else "monthly","effective_mode":plan.effective_mode if plan else "next_day"})
    return result

@app.get("/api/policies/{item_id}/export")
def export_policy(item_id:int,user:User=Depends(current_user),session:Session=Depends(db)):
    policy=session.get(Policy,item_id)
    if not policy: raise HTTPException(404,'保单不存在')
    if user.role=='enterprise' and user.enterprise_id!=policy.enterprise_id: raise HTTPException(403,'无权导出该保单')
    enterprise=session.get(Enterprise,policy.enterprise_id);plan=session.get(InsurancePlan,policy.plan_id)
    import io,openpyxl
    book=openpyxl.Workbook();sheet=book.active;sheet.title='保单人员明细';sheet.append(['保单号','投保单位','实际用工单位','岗位','职业类别','被保险人','身份证号','保险公司','保险方案','开始日期','结束日期','保单状态'])
    for person in session.scalars(select(InsuredPerson).where(InsuredPerson.policy_id==policy.id).order_by(InsuredPerson.id.asc())):
        position=session.get(WorkPosition,person.position_id) if person.position_id else None;employer=session.get(ActualEmployer,position.actual_employer_id) if position and position.actual_employer_id else None
        sheet.append([policy.policy_no,enterprise.name if enterprise else '',employer.name if employer else (position.actual_employer if position else ''),position.name if position else person.occupation,person.occupation_class,person.name,person.id_number,plan.insurer if plan else '',plan.name if plan else '',policy.start_date,policy.end_date,policy.status])
    for column in sheet.columns: sheet.column_dimensions[column[0].column_letter].width=min(32,max(12,max(len(str(cell.value or '')) for cell in column)+2))
    output=io.BytesIO();book.save(output);output.seek(0)
    return StreamingResponse(output,media_type='application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',headers={'Content-Disposition':f'attachment; filename=policy-{policy.policy_no}.xlsx'})

@app.post("/api/plans")
def add_plan(data: PlanIn, user: User = Depends(current_user), session: Session = Depends(db)):
    item = InsurancePlan(**data.model_dump()); session.add(item); session.commit(); session.refresh(item); audit(session, user, "create", "plan", str(item.id)); return serialize(item)

@app.get("/api/plan-tiers")
def plan_tiers(plan_id: Optional[int] = None, user: User = Depends(current_user), session: Session = Depends(db)):
    stmt=select(PlanTier).order_by(PlanTier.id.desc())
    if plan_id: stmt=stmt.where(PlanTier.plan_id==plan_id)
    return [serialize(x) for x in session.scalars(stmt)]

@app.post("/api/plan-tiers")
def add_plan_tier(data: PlanTierIn, user: User = Depends(current_user), session: Session = Depends(db)):
    if user.role != "admin": raise HTTPException(403,"仅总后台可维护类别价格")
    if not session.get(InsurancePlan,data.plan_id): raise HTTPException(404,"保险方案不存在")
    item=PlanTier(**data.model_dump());session.add(item);session.commit();session.refresh(item);audit(session,user,"create","plan_tier",str(item.id));return serialize(item)

@app.patch("/api/plans/{item_id}/status")
def plan_status(item_id: int, status_value: str = Query(..., alias="status"), user: User = Depends(current_user), session: Session = Depends(db)):
    item = session.get(InsurancePlan, item_id)
    if not item: raise HTTPException(404, "方案不存在")
    if status_value not in {"active", "paused"}: raise HTTPException(400, "状态不合法")
    item.status = status_value; session.commit(); audit(session, user, "status_change", "plan", str(item.id), status_value); return serialize(item)

@app.patch("/api/plans/{item_id}")
def update_plan(item_id: int, data: PlanUpdate, user: User = Depends(current_user), session: Session = Depends(db)):
    item = session.get(InsurancePlan, item_id)
    if not item: raise HTTPException(404, "方案不存在")
    for key, value in data.model_dump(exclude_unset=True).items():
        if value is not None: setattr(item, key, value)
    session.commit(); audit(session, user, "update", "plan", str(item.id)); return serialize(item)

@app.delete("/api/plans/{item_id}")
def delete_plan(item_id: int, user: User = Depends(current_user), session: Session = Depends(db)):
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
        item.update(enterprise_name=enterprise.name if enterprise else '',position_name=position.name if position else x.occupation,actual_employer_name=employer.name if employer else (position.actual_employer if position else ''),plan_id=plan.id if plan else None,plan_name=plan.name if plan else '',insurer=plan.insurer if plan else '',policy_no=policy.policy_no if policy else '',policy_status=policy.status if policy else '')
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
        ent=session.get(Enterprise,p.enterprise_id);position=session.get(WorkPosition,p.position_id) if p.position_id else None
        rows.append([ent.name if ent else "",position.actual_employer if position else "",position.name if position else p.occupation,p.name,p.id_number,p.occupation_class,p.status,p.created_at.strftime('%Y-%m-%d') if p.created_at else target_date])
    import io,csv
    out=io.StringIO();csv.writer(out).writerows([["投保单位","实际用工单位","岗位","姓名","身份证号","职业类别","状态","日期"],*rows]);out.seek(0)
    return StreamingResponse(iter([out.getvalue().encode('utf-8-sig')]),media_type='text/csv',headers={'Content-Disposition':f'attachment; filename={kind}-{target_date}.csv'})

@app.get("/api/enrollment/summary")
def enrollment_summary(date_value:str=Query(default="",alias="date"),user:User=Depends(current_user),session:Session=Depends(db)):
    target=date_value or datetime.now().strftime('%Y-%m-%d');result=[]
    for plan in session.scalars(select(InsurancePlan).order_by(InsurancePlan.id.desc())):
        policies=session.scalars(select(Policy).where(Policy.plan_id==plan.id)).all();policy_ids={p.id for p in policies};people=session.scalars(select(InsuredPerson).where(InsuredPerson.policy_id.in_(policy_ids))) if policy_ids else []
        if user.role=='enterprise': people=[x for x in people if x.enterprise_id==user.enterprise_id]
        people=list(people);new_count=sum(1 for x in people if str(x.created_at or '')[:10]==target and x.status!='stopped');stop_count=sum(1 for x in people if str(x.created_at or '')[:10]==target and x.status=='stopped')
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

@app.get("/api/claims")
def claims(user: User = Depends(current_user), session: Session = Depends(db)):
    stmt=select(Claim).order_by(Claim.id.desc())
    if user.role=='enterprise' and user.enterprise_id: stmt=stmt.where(Claim.enterprise_id==user.enterprise_id)
    result=[]
    for x in session.scalars(stmt):
        item=serialize(x);ent=session.get(Enterprise,x.enterprise_id);person=session.get(InsuredPerson,x.person_id);position=session.get(WorkPosition,person.position_id) if person and person.position_id else None;employer=session.get(ActualEmployer,position.actual_employer_id) if position and position.actual_employer_id else None
        docs=session.scalars(select(ClaimDocument).where(ClaimDocument.claim_id==x.id)).all();item.update(enterprise_name=ent.name if ent else '',person_name=person.name if person else '',id_number=person.id_number if person else '',position_name=position.name if position else '',actual_employer_name=employer.name if employer else (position.actual_employer if position else ''),document_count=len(docs),missing_count=max(0,7-len({d.doc_type for d in docs})))
        result.append(item)
    return result

@app.post("/api/claims")
def add_claim(data: ClaimIn, user: User = Depends(current_user), session: Session = Depends(db)):
    if user.role == "enterprise" and user.enterprise_id != data.enterprise_id: raise HTTPException(403,"无权提交该单位理赔")
    person=session.get(InsuredPerson,data.person_id)
    if not person or person.enterprise_id!=data.enterprise_id: raise HTTPException(400,'被保险人不属于该投保单位')
    try: deadline=(datetime.strptime(data.accident_at[:10],'%Y-%m-%d')+timedelta(days=30)).strftime('%Y-%m-%d')
    except Exception: deadline=''
    item = Claim(**data.model_dump(),deadline=deadline,claim_no=f"CLM-{datetime.now().strftime('%Y%m%d')}-{secrets.token_hex(3).upper()}"); session.add(item);session.flush();session.add(ClaimTimeline(claim_id=item.id,node='reported',action='提交工伤报案',note=data.description,operator=user.name));session.commit();session.refresh(item);audit(session, user, "create", "claim", str(item.id));return serialize(item)

@app.patch("/api/claims/{item_id}")
def update_claim(item_id:int,data:ClaimUpdate,user:User=Depends(current_user),session:Session=Depends(db)):
    item=session.get(Claim,item_id)
    if not item: raise HTTPException(404,'理赔案件不存在')
    if user.role=='enterprise' and user.enterprise_id!=item.enterprise_id: raise HTTPException(403,'无权操作该案件')
    for k,v in data.model_dump(exclude_unset=True).items():
        if v is not None:setattr(item,k,v)
    session.commit();audit(session,user,'update','claim',str(item.id));return serialize(item)

@app.patch("/api/claims/{item_id}/status")
def claim_status(item_id:int,data:ClaimStatusIn,user:User=Depends(current_user),session:Session=Depends(db)):
    item=session.get(Claim,item_id)
    if not item: raise HTTPException(404,"理赔案件不存在")
    if user.role=="enterprise" and user.enterprise_id!=item.enterprise_id: raise HTTPException(403,"无权操作该案件")
    transitions={'reported':{'collecting','submitted'},'collecting':{'submitted','supplement'},'submitted':{'insurer_review','supplement'},'insurer_review':{'supplement','approved','rejected'},'supplement':{'submitted','insurer_review'},'approved':{'paid'},'paid':{'closed'},'rejected':{'closed'},'closed':set()}
    if data.status not in transitions.get(item.status,set()): raise HTTPException(409,f'案件不能从 {item.status} 变更为 {data.status}')
    if user.role=='enterprise' and data.status not in {'collecting','submitted'}: raise HTTPException(403,'该节点需由平台理赔人员处理')
    if data.status=='submitted':
        required={'id_card','labor_relation','diagnosis','medical_record','invoice','accident_proof','bank_card'};uploaded={x.doc_type for x in session.scalars(select(ClaimDocument).where(ClaimDocument.claim_id==item.id))};missing=required-uploaded
        if missing: raise HTTPException(409,f'材料未齐全，还缺少 {len(missing)} 项')
    item.status=data.status
    if data.approved_amount is not None:item.approved_amount=data.approved_amount
    if data.status=='paid':item.paid_at=datetime.now().strftime('%Y-%m-%d %H:%M')
    session.add(ClaimTimeline(claim_id=item.id,node=data.status,action='理赔状态变更',note=data.note,operator=user.name));session.commit();audit(session,user,"status_change","claim",str(item.id),data.status);return serialize(item)

@app.get("/api/claims/{item_id}/documents")
def claim_documents(item_id:int,user:User=Depends(current_user),session:Session=Depends(db)):
    item=session.get(Claim,item_id)
    if not item: raise HTTPException(404,"理赔案件不存在")
    if user.role=="enterprise" and user.enterprise_id!=item.enterprise_id: raise HTTPException(403,"无权查看该案件")
    return [serialize(x) for x in session.scalars(select(ClaimDocument).where(ClaimDocument.claim_id==item_id).order_by(ClaimDocument.id.desc()))]

@app.post("/api/claims/{item_id}/documents")
def add_claim_document(item_id:int,data:ClaimDocumentIn,user:User=Depends(current_user),session:Session=Depends(db)):
    item=session.get(Claim,item_id)
    if not item: raise HTTPException(404,"理赔案件不存在")
    if user.role=="enterprise" and user.enterprise_id!=item.enterprise_id: raise HTTPException(403,"无权上传材料")
    doc=ClaimDocument(claim_id=item_id,**data.model_dump());session.add(doc);session.flush();session.add(ClaimTimeline(claim_id=item_id,node=item.status,action=f'上传材料：{data.name}',note=data.doc_type,operator=user.name));session.commit();session.refresh(doc);audit(session,user,"upload","claim_document",str(doc.id));return serialize(doc)

@app.post("/api/claims/{item_id}/documents/upload")
async def upload_claim_document(item_id:int,doc_type:str=Form('other'),file:UploadFile=File(...),user:User=Depends(current_user),session:Session=Depends(db)):
    item=session.get(Claim,item_id)
    if not item: raise HTTPException(404,'理赔案件不存在')
    if user.role=='enterprise' and user.enterprise_id!=item.enterprise_id: raise HTTPException(403,'无权上传材料')
    suffix=Path(file.filename or '').suffix.lower()
    if suffix not in {'.jpg','.jpeg','.png','.pdf','.doc','.docx','.xls','.xlsx'}: raise HTTPException(400,'仅支持图片、PDF、Word、Excel材料')
    content=await file.read()
    if len(content)>20*1024*1024: raise HTTPException(400,'单个材料不能超过20MB')
    folder=ROOT/'uploads'/'claims'/str(item_id);folder.mkdir(parents=True,exist_ok=True);stored=f'{secrets.token_hex(8)}{suffix}';(folder/stored).write_bytes(content);url=f'/uploads/claims/{item_id}/{stored}'
    doc=ClaimDocument(claim_id=item_id,name=file.filename or stored,url=url,doc_type=doc_type);session.add(doc);session.flush();session.add(ClaimTimeline(claim_id=item_id,node=item.status,action=f'上传材料：{doc.name}',note=doc_type,operator=user.name));session.commit();session.refresh(doc);audit(session,user,'upload','claim_document',str(doc.id));return serialize(doc)

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
    required=[('id_card','身份证明'),('labor_relation','劳动关系证明'),('diagnosis','医疗诊断证明'),('medical_record','病历/出院记录'),('invoice','医疗发票及费用清单'),('accident_proof','事故经过及证明'),('bank_card','收款银行卡信息')];docs=session.scalars(select(ClaimDocument).where(ClaimDocument.claim_id==item_id)).all();uploaded={x.doc_type for x in docs}
    return [{'doc_type':k,'name':n,'required':True,'uploaded':k in uploaded} for k,n in required]

@app.post("/api/notifications/send")
def send_notification(data:NotificationIn,user:User=Depends(current_user),session:Session=Depends(db)):
    result=sms_provider().send_sms(data.recipient,data.template,{"content":data.content}) if data.kind=="sms" else email_provider().send_email(data.recipient,data.subject,data.content)
    audit(session,user,"send",data.kind,data.recipient,result.message);return {"ok":result.ok,"provider":result.provider,"request_id":result.request_id,"message":result.message}

@app.post("/api/payments")
def create_payment(data:PaymentIn,user:User=Depends(current_user),session:Session=Depends(db)):
    if user.role=="enterprise" and user.enterprise_id!=data.enterprise_id: raise HTTPException(403,"无权为该单位充值")
    if not session.get(Enterprise,data.enterprise_id): raise HTTPException(404,"投保单位不存在")
    order=f"PAY-{datetime.now().strftime('%Y%m%d%H%M%S')}-{secrets.token_hex(3).upper()}"; result=payment_provider().create_payment(data.amount,order)
    row=PaymentRecord(order_no=order,enterprise_id=data.enterprise_id,account=data.account,amount=data.amount,status="pending",provider=result.provider);session.add(row);session.commit();return {"order_no":order,"status":row.status,"pay_url":result.data.get("pay_url",""),"request_id":result.request_id}

@app.post("/api/payments/callback")
def payment_callback(data:PaymentCallbackIn,session:Session=Depends(db)):
    row=session.scalar(select(PaymentRecord).where(PaymentRecord.order_no==data.order_no))
    if not row: raise HTTPException(404,"支付订单不存在")
    row.status=data.status
    if data.status=="paid":
        ent=session.get(Enterprise,row.enterprise_id)
        if row.account=="premium": ent.premium_balance += row.amount
        else: ent.usage_balance += row.amount
    session.commit();return {"ok":True,"order_no":row.order_no,"status":row.status}

@app.get("/api/payments/reconcile")
def payment_reconcile(user:User=Depends(current_user),session:Session=Depends(db)):
    if user.role!="admin": raise HTTPException(403,"仅总后台可对账")
    return {"pending":session.query(PaymentRecord).filter(PaymentRecord.status=="pending").count(),"paid":session.query(PaymentRecord).filter(PaymentRecord.status=="paid").count(),"failed":session.query(PaymentRecord).filter(PaymentRecord.status=="failed").count()}

@app.get("/api/providers/status")
def provider_status(user: User = Depends(current_user)):
    return {"mode": os.getenv("INTEGRATION_MODE", "mock"), "insurer_api": bool(os.getenv("INSURER_API_BASE_URL")), "sms": bool(os.getenv("SMS_PROVIDER_URL")), "email": bool(os.getenv("SMTP_HOST")), "payment": bool(os.getenv("PAYMENT_PROVIDER_URL"))}

@app.post("/api/enrollment/send")
def enrollment_send(enterprise_id:int, plan_id:int, kind:Literal["enrollment","termination"]="enrollment", user:User=Depends(current_user), session:Session=Depends(db)):
    if user.role=="enterprise" and user.enterprise_id!=enterprise_id: raise HTTPException(403,"无权发送该单位名单")
    ent=session.get(Enterprise,enterprise_id);plan=session.get(InsurancePlan,plan_id)
    if not ent or not plan: raise HTTPException(404,"投保单位或方案不存在")
    people=[serialize(x) for x in session.scalars(select(InsuredPerson).where(InsuredPerson.enterprise_id==enterprise_id))]
    payload={"enterprise":{"id":ent.id,"name":ent.name},"plan":serialize(plan),"people":people,"sent_at":datetime.now(timezone.utc).isoformat()}
    result=insurer_provider(plan.insurer).submit_enrollment(payload) if kind=="enrollment" else insurer_provider(plan.insurer).submit_termination(payload)
    audit(session,user,"send",kind,str(enterprise_id),result.request_id);return {"ok":result.ok,"kind":kind,"request_id":result.request_id,"accepted":result.data.get("accepted",0),"message":result.message}

@app.post("/api/enrollment/email")
def enrollment_email(enterprise_id:int,plan_id:int,kind:Literal['enrollment','termination']='enrollment',user:User=Depends(current_user),session:Session=Depends(db)):
    if user.role=='enterprise' and user.enterprise_id!=enterprise_id: raise HTTPException(403,'无权发送该单位名单')
    ent=session.get(Enterprise,enterprise_id);plan=session.get(InsurancePlan,plan_id)
    if not ent or not plan: raise HTTPException(404,'投保单位或产品不存在')
    if not plan.insurer_email: raise HTTPException(400,'该保险公司方案尚未配置接收邮箱')
    result=email_provider().send_email(plan.insurer_email,f'{plan.insurer} {plan.name} {"参保" if kind=="enrollment" else "停保"}名单',f'投保单位：{ent.name}\n请查收系统生成的{kind}名单。')
    audit(session,user,'send_email','enrollment',str(enterprise_id),result.request_id);return {'ok':result.ok,'email':plan.insurer_email,'request_id':result.request_id,'message':result.message}

@app.get("/api/health")
def health(): return {"ok": True, "service": "xiangbangbaojingyun", "time": datetime.now(timezone.utc).isoformat()}

app.mount("/", StaticFiles(directory=ROOT, html=True), name="frontend")
