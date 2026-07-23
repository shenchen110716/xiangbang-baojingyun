from __future__ import annotations

import os

from fastapi import FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse
from fastapi.staticfiles import StaticFiles

from .core.config import ROOT, DATABASE_URL
from .core.db import Base, engine, SessionLocal
from .core.migrations import run_sqlite_bridge_migrations, migrate_premium_balances
from .core.seed import seed_default_accounts

app = FastAPI(title="响帮帮无忧保 API", version="3.6.0")
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
        migrate_premium_balances(s)


from .routers.health import router as health_router
from .routers.auth import router as auth_router
from .routers.audit_logs import router as audit_logs_router
from .routers.integrations import router as integrations_router
from .routers.agents import router as agents_router
from .routers.payments import router as payments_router
from .routers.wechat import router as wechat_router
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
from .routers.insurer_accounts import router as insurer_accounts_router
from .routers.recharge_requests import router as recharge_requests_router
from .routers.pending_terminations import router as pending_terminations_router
from .routers.employer_scopes import router as employer_scopes_router
from .routers.employment_facts import router as employment_facts_router
from .routers.timeliness import router as timeliness_router
from .routers.agent_portal import router as agent_portal_router
from .routers.settings_admin import router as settings_admin_router
from .routers.ocr import router as ocr_router

app.include_router(health_router)
app.include_router(auth_router)
app.include_router(audit_logs_router)
app.include_router(integrations_router)
app.include_router(agents_router)
app.include_router(payments_router)
app.include_router(wechat_router)
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
app.include_router(insurer_accounts_router)
app.include_router(recharge_requests_router)
app.include_router(pending_terminations_router)
app.include_router(employer_scopes_router)
app.include_router(employment_facts_router)
app.include_router(timeliness_router)
app.include_router(agent_portal_router)
app.include_router(settings_admin_router)
app.include_router(ocr_router)

# SYSTEM-DESIGN-V4.md Phase 0 stop-loss item #1: the project root (source
# code, data.db, .env, requirements.txt, ...) must never be reachable over
# HTTP. The Vue admin build lives entirely under web/dist/ (a clean build
# output with no secrets), so mounting StaticFiles there is safe, but the
# SPA fallback route below stays an explicit allowlist of known client
# routes rather than a wildcard "serve index.html for anything unmatched" —
# that would make paths like /data.db or /backend/app.py return 200
# (index.html) instead of 404, which is a worse information-hygiene
# posture than what Phase 0 established.
WEB_DIST = ROOT / "web" / "dist"
app.mount("/assets", StaticFiles(directory=WEB_DIST / "assets"), name="web-assets")

_WEB_ROOT_FILES = {"favicon.svg", "icons.svg", "xbbzp.html"}

_FRONTEND_ROUTES = {
    "/", "/home", "/screen", "/team", "/dispatch", "/workers", "/work-relations",
    "/agents", "/insurance", "/policy", "/claims", "/insurers", "/exports",
    "/report", "/billing", "/recharge", "/pending-terminations", "/promotion",
    "/operators", "/message", "/settings", "/login", "/agent-portal",
    "/timeliness", "/system-settings",
}


@app.get("/{path:path}", include_in_schema=False)
def serve_frontend(path: str):
    if path in _WEB_ROOT_FILES:
        return FileResponse(WEB_DIST / path)
    if f"/{path}" in _FRONTEND_ROUTES or path.startswith("certificate/"):
        return FileResponse(
            WEB_DIST / "index.html",
            media_type="text/html",
            headers={"Cache-Control": "no-store, max-age=0"},
        )
    raise HTTPException(404)


# Uploaded position videos / claim documents are no longer served through a
# static mount at all — see download_position_video (routers/positions.py)
# and download_claim_document (routers/claims.py) for the short-lived
# signed-URL download endpoints that replaced it (SYSTEM-DESIGN-V4.md
# section 11.1). The directory itself must still exist for upload handlers
# to write into.
(ROOT / "uploads").mkdir(parents=True, exist_ok=True)
