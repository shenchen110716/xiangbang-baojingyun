import os
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
ENVIRONMENT = os.getenv("ENVIRONMENT", "development")

# 扫码参保二维码要嵌一个绝对 URL（二维码没有"当前站点"这个概念，必须是完整地址）。
# 默认指向已经在用的生产域名，其他环境按需用环境变量覆盖。
PUBLIC_BASE_URL = os.getenv("PUBLIC_BASE_URL", "https://bx.xbbzp.com").rstrip("/")

DATABASE_URL = os.getenv("DATABASE_URL", f"sqlite:///{ROOT / 'data.db'}")
if DATABASE_URL.startswith("postgres://"):
    DATABASE_URL = DATABASE_URL.replace("postgres://", "postgresql+psycopg://", 1)
elif DATABASE_URL.startswith("postgresql://"):
    DATABASE_URL = DATABASE_URL.replace("postgresql://", "postgresql+psycopg://", 1)

_DEV_JWT_SECRET = "dev-only-change-this-secret-at-least-32-bytes"
SECRET_KEY = os.getenv("JWT_SECRET", _DEV_JWT_SECRET)
ALGORITHM = "HS256"

# Encrypts stored resident ID numbers (v4.2 §6.4). Rotating this value makes
# existing ciphertext undecryptable, so it is set once per environment and
# never regenerated per deploy.
_DEV_ID_KEY = "dev-only-id-key-change-me-0000000000000000000"
ID_ENCRYPTION_KEY = os.getenv("ID_ENCRYPTION_KEY", _DEV_ID_KEY)


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
    if ID_ENCRYPTION_KEY == _DEV_ID_KEY:
        problems.append("ID_ENCRYPTION_KEY 未设置或仍为开发默认值")
    if not os.getenv("ADMIN_PASSWORD"):
        problems.append("ADMIN_PASSWORD 未设置")
    if DATABASE_URL.startswith("sqlite"):
        problems.append("DATABASE_URL 仍为本地 SQLite 默认值，未指向生产数据库")
    if problems:
        detail = "\n".join(f"  - {p}" for p in problems)
        raise RuntimeError(f"生产环境配置缺失，拒绝启动：\n{detail}")


if ENVIRONMENT == "production":
    _check_production_config()
