from __future__ import annotations

import asyncio
import os
from contextlib import asynccontextmanager

from fastapi import FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from fastapi.openapi.utils import get_openapi
from fastapi.responses import FileResponse
from fastapi.staticfiles import StaticFiles

from .core.config import ROOT, DATABASE_URL
from .core.db import Base, engine, SessionLocal
from .core.migrations import run_sqlite_bridge_migrations, migrate_premium_balances
from .core.seed import seed_default_accounts
from .services.openapi.background import background_loop


def startup():
    """既有四步启动顺序，次序不可变（create_all → SQLite 桥 → 种子 →
    余额搬迁）。保留为模块级可独立调用：存量 ~70 个 smoke 都是
    `from backend.app import startup; startup()`，这是测试契约的一部分。
    """
    Base.metadata.create_all(engine)
    with SessionLocal() as s:
        run_sqlite_bridge_migrations(s, DATABASE_URL)
        seed_default_accounts(s)
        migrate_premium_balances(s)


@asynccontextmanager
async def _lifespan(app: FastAPI):
    # Starlette 的 on_event 与 lifespan 互斥（传了 lifespan 就忽略
    # on_event），所以原 @app.on_event("startup") 并入这里，整个应用只有
    # 这一个 lifespan。
    startup()
    # 统一后台任务循环（开放平台清理任务等，30s tick；PG 上用
    # pg_try_advisory_lock 保证多实例只有一个在跑）。
    stop_event = asyncio.Event()
    task = asyncio.create_task(background_loop(stop_event))
    app.state.openapi_background_task = task
    try:
        yield
    finally:
        stop_event.set()
        try:
            await asyncio.wait_for(task, timeout=35)
        except (asyncio.TimeoutError, asyncio.CancelledError):
            task.cancel()


app = FastAPI(title="响帮帮无忧保 API", version="3.6.0", lifespan=_lifespan)
cors_origins = [origin.strip() for origin in os.getenv("CORS_ORIGINS", "*").split(",") if origin.strip()]
app.add_middleware(
    CORSMiddleware,
    allow_origins=cors_origins,
    allow_credentials=cors_origins != ["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)


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
from .routers.enroll import router as enroll_router
from .routers.enroll_review import router as enroll_review_router
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
from .routers.insurers import router as insurers_router
from .routers.insurer_portal import router as insurer_portal_router
from .routers.openapi import router as openapi_router
from .routers.openapi_admin import router as openapi_admin_router

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
app.include_router(enroll_router)
app.include_router(enroll_review_router)
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
app.include_router(insurers_router)
app.include_router(insurer_portal_router)
app.include_router(openapi_router)
app.include_router(openapi_admin_router)


# /openapi/v1 的 schema 与内部 /api 的 /docs 分开暴露（story-p1-readonly
# AC15）：主 schema（/openapi.json → /docs）反向排除开放路由组；开放平台
# 自己的 schema 在 GET /openapi/v1/openapi.json（routers/openapi/__init__.py）。
def _internal_api_openapi():
    if app.openapi_schema:
        return app.openapi_schema
    schema = get_openapi(title=app.title, version=app.version, routes=app.routes)
    # 按生成后的 path 过滤而不是过滤 routes：FastAPI 0.139 的 include_router
    # 产物是惰性 _IncludedRouter（无 path 属性），routes 级过滤筛不掉。
    schema["paths"] = {path: item for path, item in schema.get("paths", {}).items()
                       if not path.startswith("/openapi/v1")}
    app.openapi_schema = schema
    return app.openapi_schema


app.openapi = _internal_api_openapi

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
    "/agents", "/insurance", "/policy", "/claims", "/insurers", "/insurer-management", "/exports",
    "/report", "/billing", "/recharge", "/pending-terminations", "/promotion",
    "/operators", "/message", "/settings", "/login", "/agent-portal", "/open-api",
    "/timeliness", "/system-settings", "/insurer-portal", "/enterprise-apply",
    "/enroll-review",
}


@app.get("/{path:path}", include_in_schema=False)
def serve_frontend(path: str):
    if path in _WEB_ROOT_FILES:
        return FileResponse(WEB_DIST / path)
    if f"/{path}" in _FRONTEND_ROUTES or path.startswith("certificate/") or path.startswith("enroll/"):
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
