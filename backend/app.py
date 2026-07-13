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
from fastapi.staticfiles import StaticFiles
from sqlalchemy import or_, select
from sqlalchemy.orm import Session
from .providers import insurer_provider, sms_provider, email_provider, payment_provider
from .core.config import ROOT, DATABASE_URL, SECRET_KEY, ALGORITHM
from .core.db import Base, engine, SessionLocal, db
from .models import (
    User, Enterprise, ActualEmployer, WorkPosition, PositionVideo,
    AgentCommission, InsurancePlan, PlanTier, InsuredPerson, Policy,
    Claim, ClaimTimeline, ClaimDocument, AuditLog, PaymentRecord,
    Invoice, EnrollmentEmail,
)
from .schemas import (
    LoginIn, PasswordChangeIn, TokenOut, UserOut,
    OperatorIn, OperatorUpdate,
    EnterpriseIn, EnterpriseUpdate, RechargeIn,
    AgentIn, CommissionIn, CommissionUpdate,
    PositionIn, ActualEmployerIn, ActualEmployerUpdate,
    PositionVideoIn, PositionVideoReviewIn, PositionReviewIn,
    PlanTierIn, PlanIn, PlanUpdate,
    PersonIn, PersonUpdate, BulkPersonRow, BulkPersonIn,
    ClaimIn, ClaimUpdate, ClaimStatusIn, ClaimDocumentIn, ClaimDocumentReviewIn,
    PaymentIn, PaymentCallbackIn, InvoiceIn, InvoiceUpdate,
    NotificationIn,
)
from .services import (
    serialize, amount,
    plan_price_for_class, pricing_snapshot, plan_dict, validate_commission_price,
    commission_dict, agent_commission_rows, agent_commission_summary,
    policy_dict,
)

from .core.security import pwd, security, current_user
from .core.audit import audit
from .core.migrations import run_sqlite_bridge_migrations
from .core.seed import seed_default_accounts

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
        run_sqlite_bridge_migrations(s, DATABASE_URL)
        seed_default_accounts(s)

from .routers.auth import login, me, change_password  # noqa: F401 (temporary compat re-exports)
from .routers.audit_logs import audit_logs  # noqa: F401
from .routers.integrations import provider_status  # noqa: F401
from .routers.health import health  # noqa: F401

from .routers.agents import add_agent, add_agent_commission, update_agent_commission  # noqa: F401
from .routers.operators import add_operator  # noqa: F401
from .services.operators import operator_dict  # noqa: F401
from .routers.enterprises import add_enterprise  # noqa: F401
from .routers.dashboard import dashboard, screen_products  # noqa: F401
from .routers.positions import add_actual_employer, update_actual_employer, delete_actual_employer, add_position  # noqa: F401

from .routers.plans import add_plan  # noqa: F401
from .routers.reports import billing  # noqa: F401
from .routers.insured import add_person  # noqa: F401
from .routers.enrollment import enrollment_email  # noqa: F401

from .routers.payments import create_payment, payment_callback  # noqa: F401
from .routers.invoices import create_invoice, update_invoice  # noqa: F401

from .routers.health import router as health_router
from .routers.auth import router as auth_router
from .routers.audit_logs import router as audit_logs_router
from .routers.integrations import router as integrations_router
from .routers.agents import router as agents_router
from .routers.payments import router as payments_router
from .routers.invoices import router as invoices_router

from .routers.operators import router as operators_router
from .routers.dashboard import router as dashboard_router
from .routers.enterprises import router as enterprises_router

from .routers.positions import router as positions_router
from .routers.plans import router as plans_router
from .routers.reports import router as reports_router
from .routers.insured import router as insured_router
from .routers.enrollment import router as enrollment_router
from .routers.messages import router as messages_router
from .routers.notifications import router as notifications_router
from .routers.claims import router as claims_router

app.include_router(health_router)
app.include_router(auth_router)
app.include_router(audit_logs_router)
app.include_router(integrations_router)
app.include_router(agents_router)
app.include_router(payments_router)
app.include_router(invoices_router)
app.include_router(operators_router)
app.include_router(dashboard_router)
app.include_router(enterprises_router)
app.include_router(positions_router)
app.include_router(plans_router)
app.include_router(reports_router)
app.include_router(insured_router)
app.include_router(enrollment_router)
app.include_router(messages_router)
app.include_router(notifications_router)
app.include_router(claims_router)

app.mount("/", StaticFiles(directory=ROOT, html=True), name="frontend")
