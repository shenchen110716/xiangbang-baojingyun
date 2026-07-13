import os
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
ENVIRONMENT = os.getenv("ENVIRONMENT", "development")

DATABASE_URL = os.getenv("DATABASE_URL", f"sqlite:///{ROOT / 'data.db'}")
if DATABASE_URL.startswith("postgres://"):
    DATABASE_URL = DATABASE_URL.replace("postgres://", "postgresql+psycopg://", 1)
elif DATABASE_URL.startswith("postgresql://"):
    DATABASE_URL = DATABASE_URL.replace("postgresql://", "postgresql+psycopg://", 1)

_DEV_JWT_SECRET = "dev-only-change-this-secret-at-least-32-bytes"
SECRET_KEY = os.getenv("JWT_SECRET", _DEV_JWT_SECRET)
ALGORITHM = "HS256"


def _check_production_config() -> None:
    # SYSTEM-DESIGN-V4.md Phase 0 stop-loss item #4: "生产环境缺少 JWT、管理员
    # 密码、数据库和对象存储配置时必须拒绝启动". Runs at import time (before
    # uvicorn binds a port) so a misconfigured production deploy fails fast
    # instead of silently running with dev-grade secrets.
    problems = []
    if SECRET_KEY == _DEV_JWT_SECRET:
        problems.append("JWT_SECRET 未设置或仍为开发默认值")
    elif len(SECRET_KEY.encode()) < 32:
        problems.append("JWT_SECRET 长度不足 32 字节")
    if not os.getenv("ADMIN_PASSWORD"):
        problems.append("ADMIN_PASSWORD 未设置")
    if DATABASE_URL.startswith("sqlite"):
        problems.append("DATABASE_URL 仍为本地 SQLite 默认值，未指向生产数据库")
    if problems:
        detail = "\n".join(f"  - {p}" for p in problems)
        raise RuntimeError(f"生产环境配置缺失，拒绝启动：\n{detail}")


if ENVIRONMENT == "production":
    _check_production_config()
