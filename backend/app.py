from __future__ import annotations

import os

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from fastapi.staticfiles import StaticFiles

from .core.config import ROOT, DATABASE_URL
from .core.db import Base, engine, SessionLocal
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
