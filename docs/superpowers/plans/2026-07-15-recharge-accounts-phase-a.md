# 保司分账户充值（阶段 A：基础）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 企业可以按保司自助发起充值（对公转账 + 上传回单），管理员审核确认后余额按收款账户（可被多个保司共用）入账，首页/大屏/企业详情能看到按账户拆分的余额。

**Architecture:** 新增 5 张表（`InsurerAccount`/`InsurerAccountLink`/`EnterprisePremiumAccount`/`RechargeRequest` + `LedgerEntry.account_id`），账户是余额的主体、保司通过 `InsurerAccountLink` 多对一映射到账户。两个新路由文件（收款账户管理、充值申请）+ 一个新服务模块（`services/recharge.py`）承载账户解析/余额读写逻辑，复用既有的 `post_ledger_entry`/签名下载 URL/`serialize()` 等基础设施。`Enterprise.premium_balance` 列保留但停止读写，启动时惰性回填历史余额到一个占位账户。

**Tech Stack:** FastAPI + SQLAlchemy + Alembic（Python 后端）、Vue3 + Element Plus + TypeScript（Web 前端）。本计划范围内不改小程序、不改 Java 后端（Java 镜像是阶段 A 完成后的独立收尾任务，本计划不包含）。

## Global Constraints

- 不做 OCR 自动识别回单金额——v1 全部人工审核（`docs/superpowers/specs/2026-07-15-insurer-scoped-recharge-design.md` 范围边界）。
- 使用费账户不拆分，继续用 `Enterprise.usage_balance` 单一字段。
- 不引入独立 Insurer 实体表，`InsurerAccountLink.insurer` 是自由字符串，需与 `InsurancePlan.insurer` 的既有取值对齐。
- `Enterprise.premium_balance` 列保留不删，停止读写，仅作历史字段。
- 回单文件类型限制 `.pdf/.jpg/.jpeg/.png`，大小上限 20MB（与现有保单文件上传一致）。
- 本计划**不包含**使用费锁定参停保、保费不足待确认停保、短信通知、参保年龄限制、Java 后端镜像——这些是后续独立阶段/任务，设计文档里已经写明但这份计划刻意只覆盖阶段 A。

---

## File Structure

**新建：**
- `backend/models/finance_accounts.py` — `InsurerAccount`、`InsurerAccountLink`、`EnterprisePremiumAccount`、`RechargeRequest` 四个模型
- `backend/migrations_alembic/versions/<自动生成>_add_recharge_accounts.py` — 上述四张表 + `LedgerEntry.account_id` 的 Alembic 迁移
- `backend/services/recharge.py` — 账户解析（保司→账户）、按企业查询按账户拆分余额的服务函数
- `backend/routers/insurer_accounts.py` — 收款账户 + 保司映射的 admin CRUD
- `backend/routers/recharge_requests.py` — 充值申请提交/列表/确认/驳回/回单下载
- `web/src/api/recharge.ts` — 上述新接口的前端客户端
- `web/src/views/recharge/RechargeCenterView.vue` — 企业发起充值 + 管理员审核（同一页面按角色分支内容）
- `tests/recharge_smoke.py` — 本计划专用的冒烟测试（独立于已有的 `tests/system_smoke.py`，因为那个文件的身份证号测试夹具当前有一个跟本计划无关的既存 bug，不应该让新功能的测试依赖一个本来就是红的文件）

**修改：**
- `backend/models/finance.py` — `LedgerEntry` 新增 `account_id` 列
- `backend/models/__init__.py` — 导出新模型
- `backend/core/migrations.py` — 新增 `migrate_premium_balances()`，把历史 `premium_balance` 回填到占位账户
- `backend/app.py` — 启动钩子调用 `migrate_premium_balances`；注册两个新路由
- `backend/services/ledger.py` — `post_ledger_entry()` 新增可选 `account_id` 参数
- `backend/services/__init__.py` — 导出新服务函数
- `backend/schemas/finance.py` — 新增 `InsurerAccountIn`/`InsurerAccountUpdate`/`InsurerAccountLinkIn`
- `backend/schemas/__init__.py` — 导出新 schema
- `backend/routers/enterprises.py` — 新增 `GET /enterprises/{id}/premium-accounts`
- `backend/routers/dashboard.py` — `dashboard()` 里 `premium_balance`/`balance_alerts` 的计算逻辑改成按账户
- `web/src/api/types.ts` — 新增类型，`DashboardData.premium_balance` 改为 `premium_accounts` 数组
- `web/src/router/routes.ts` — 新增 `/recharge` 路由
- `web/src/views/plans/PlansAdminView.vue` — 新增收款账户管理区块
- `web/src/views/dashboard/HomeView.vue` — 保费余额展示改成按账户列表 + 充值入口
- `web/src/views/dashboard/ScreenView.vue` — 新增余额健康度展示

---

### Task 1: 新增数据模型（InsurerAccount / InsurerAccountLink / EnterprisePremiumAccount / RechargeRequest / LedgerEntry.account_id）

**Files:**
- Create: `backend/models/finance_accounts.py`
- Modify: `backend/models/finance.py`
- Modify: `backend/models/__init__.py`
- Test: `tests/recharge_smoke.py`

**Interfaces:**
- Produces: `InsurerAccount(id, label, bank_name, account_no, account_holder, status, created_at)`，`InsurerAccountLink(id, insurer, account_id, created_at)`，`EnterprisePremiumAccount(id, enterprise_id, account_id, balance)`，`RechargeRequest(id, enterprise_id, account_type, insurer, account_id, amount, receipt_file_url, status, reject_reason, created_by, confirmed_by, confirmed_at, created_at)`，`LedgerEntry.account_id`（新增列，nullable）

- [ ] **Step 1: 写失败的测试**

创建 `tests/recharge_smoke.py`：

```python
"""Smoke test for the insurer-scoped recharge accounts feature (Phase A).

Isolated from tests/system_smoke.py on purpose: that file's PersonIn
fixture currently fails an unrelated ID-checksum validation bug, which
would make every scenario appended after it fail for reasons that have
nothing to do with this feature. This file builds its own minimal
fixtures instead of importing from system_smoke.py.
"""
import os
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))


def run():
    with tempfile.TemporaryDirectory(prefix="xbb-recharge-smoke-") as folder:
        os.environ["DATABASE_URL"] = f"sqlite:///{Path(folder) / 'test.db'}"
        os.environ["ADMIN_PASSWORD"] = "admin123"
        os.environ["ENTERPRISE_PASSWORD"] = "enterprise123"

        from sqlalchemy import select

        from backend.app import startup
        from backend.core.db import SessionLocal
        from backend.models import InsurerAccount, InsurerAccountLink, EnterprisePremiumAccount, RechargeRequest, LedgerEntry

        startup()
        with SessionLocal() as session:
            account = InsurerAccount(label="测试账户", bank_name="测试银行", account_no="1234567890", account_holder="测试收款方", status="active")
            session.add(account); session.commit(); session.refresh(account)
            assert account.id is not None and account.status == "active"

            link = InsurerAccountLink(insurer="测试保司", account_id=account.id)
            session.add(link); session.commit(); session.refresh(link)
            assert link.account_id == account.id

            premium_account = EnterprisePremiumAccount(enterprise_id=1, account_id=account.id, balance=100.0)
            session.add(premium_account); session.commit(); session.refresh(premium_account)
            assert premium_account.balance == 100.0

            request = RechargeRequest(enterprise_id=1, account_type="premium", insurer="测试保司", account_id=account.id, amount=50.0, receipt_file_url="/uploads/x.png", status="pending", created_by=1)
            session.add(request); session.commit(); session.refresh(request)
            assert request.status == "pending" and request.confirmed_by is None

            entry = LedgerEntry(enterprise_id=1, account="premium", direction="credit", amount=50, business_type="test", account_id=account.id)
            session.add(entry); session.commit()
            reloaded = session.scalar(select(LedgerEntry).where(LedgerEntry.id == entry.id))
            assert reloaded.account_id == account.id

    print("recharge smoke: ok")


if __name__ == "__main__":
    run()
```

- [ ] **Step 2: 运行确认失败**

Run: `python3 tests/recharge_smoke.py`
Expected: `ImportError: cannot import name 'InsurerAccount' from 'backend.models'`

- [ ] **Step 3: 实现模型**

创建 `backend/models/finance_accounts.py`：

```python
from datetime import datetime, timezone
from typing import Optional

from sqlalchemy import DateTime, Float, ForeignKey, String
from sqlalchemy.orm import Mapped, mapped_column

from ..core.db import Base


class InsurerAccount(Base):
    # 一个收款账户可以被多个保司共用（见 InsurerAccountLink），所以账户本身
    # 不直接携带 insurer 字段——账户是余额归属的主体，保司只是挂在账户上的
    # 标签。label 供管理员在多个保司共用一个账户时快速识别（如"平安/太平洋
    # 共用账户"）。
    __tablename__ = "insurer_accounts"
    id: Mapped[int] = mapped_column(primary_key=True)
    label: Mapped[str] = mapped_column(String(100), default="")
    bank_name: Mapped[str] = mapped_column(String(100), default="")
    account_no: Mapped[str] = mapped_column(String(60), default="")
    account_holder: Mapped[str] = mapped_column(String(100), default="")
    status: Mapped[str] = mapped_column(String(20), default="active")
    created_at: Mapped[datetime] = mapped_column(DateTime, default=lambda: datetime.now(timezone.utc))


class InsurerAccountLink(Base):
    # 保司名（对应 InsurancePlan.insurer 的自由文本取值）到收款账户的映射，
    # 多对一：一个保司同一时间只能绑定一个账户，一个账户可以绑定多个保司。
    # 应用层保证同一 insurer 只有一条记录（见 routers/insurer_accounts.py）。
    __tablename__ = "insurer_account_links"
    id: Mapped[int] = mapped_column(primary_key=True)
    insurer: Mapped[str] = mapped_column(String(100))
    account_id: Mapped[int] = mapped_column(ForeignKey("insurer_accounts.id"))
    created_at: Mapped[datetime] = mapped_column(DateTime, default=lambda: datetime.now(timezone.utc))


class EnterprisePremiumAccount(Base):
    # 余额挂在"企业 + 账户"上，不是"企业 + 保司"——共用账户的保司自然共享
    # 同一笔余额。(enterprise_id, account_id) 唯一由应用层的
    # get_or_create_premium_account() 保证。
    __tablename__ = "enterprise_premium_accounts"
    id: Mapped[int] = mapped_column(primary_key=True)
    enterprise_id: Mapped[int] = mapped_column(ForeignKey("enterprises.id"))
    account_id: Mapped[int] = mapped_column(ForeignKey("insurer_accounts.id"))
    balance: Mapped[float] = mapped_column(Float, default=0)


class RechargeRequest(Base):
    # insurer 是企业提交时选择的那个（仅用于展示/审计），account_id 是后端
    # 据此解析出的实际入账账户——两者在共用账户场景下可能不同保司但同一
    # account_id，这正是"金额不拆分，只判断一个账户余额是否够"的落地方式。
    __tablename__ = "recharge_requests"
    id: Mapped[int] = mapped_column(primary_key=True)
    enterprise_id: Mapped[int] = mapped_column(ForeignKey("enterprises.id"))
    account_type: Mapped[str] = mapped_column(String(20))  # premium / usage
    insurer: Mapped[Optional[str]] = mapped_column(String(100), nullable=True)
    account_id: Mapped[Optional[int]] = mapped_column(ForeignKey("insurer_accounts.id"), nullable=True)
    amount: Mapped[float] = mapped_column(Float, default=0)
    receipt_file_url: Mapped[str] = mapped_column(String(255), default="")
    status: Mapped[str] = mapped_column(String(20), default="pending")  # pending / confirmed / rejected
    reject_reason: Mapped[str] = mapped_column(String(255), default="")
    created_by: Mapped[int] = mapped_column(ForeignKey("users.id"))
    confirmed_by: Mapped[Optional[int]] = mapped_column(ForeignKey("users.id"), nullable=True)
    confirmed_at: Mapped[Optional[datetime]] = mapped_column(DateTime, nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=lambda: datetime.now(timezone.utc))
```

修改 `backend/models/finance.py`，在 `LedgerEntry` 类里新增一行（紧跟 `business_id` 之后）：

```python
    account_id: Mapped[Optional[int]] = mapped_column(ForeignKey("insurer_accounts.id"), nullable=True)
```

（`Optional` 已经在该文件顶部 import 过，不需要新增 import。）

修改 `backend/models/__init__.py`：

```python
from ..core.db import Base
from .user import User
from .enterprise import Enterprise, ActualEmployer
from .position import WorkPosition, PositionVideo
from .plan import InsurancePlan, PlanTier
from .insured import InsuredPerson, Policy, PolicyMember
from .claim import Claim, ClaimTimeline, ClaimDocument
from .finance import AgentCommission, PaymentRecord, Invoice, LedgerEntry
from .finance_accounts import InsurerAccount, InsurerAccountLink, EnterprisePremiumAccount, RechargeRequest
from .misc import AuditLog, EnrollmentEmail

__all__ = [
    "Base",
    "User",
    "Enterprise",
    "ActualEmployer",
    "WorkPosition",
    "PositionVideo",
    "InsurancePlan",
    "PlanTier",
    "InsuredPerson",
    "Policy",
    "PolicyMember",
    "Claim",
    "ClaimTimeline",
    "ClaimDocument",
    "AgentCommission",
    "PaymentRecord",
    "Invoice",
    "LedgerEntry",
    "InsurerAccount",
    "InsurerAccountLink",
    "EnterprisePremiumAccount",
    "RechargeRequest",
    "AuditLog",
    "EnrollmentEmail",
]
```

- [ ] **Step 4: 运行确认通过**

Run: `python3 tests/recharge_smoke.py`
Expected: `recharge smoke: ok`

- [ ] **Step 5: Commit**

```bash
git add backend/models/finance_accounts.py backend/models/finance.py backend/models/__init__.py tests/recharge_smoke.py
git commit -m "feat: add InsurerAccount/InsurerAccountLink/EnterprisePremiumAccount/RechargeRequest models"
```

---

### Task 2: Alembic 迁移

**Files:**
- Create: `backend/migrations_alembic/versions/<自动生成>_add_recharge_accounts.py`

**Interfaces:**
- Consumes: Task 1 的四个新模型 + `LedgerEntry.account_id`（用于比对迁移内容与模型定义一致）

- [ ] **Step 1: 生成迁移骨架**

Run: `alembic revision -m "add recharge accounts"`
Expected: 在 `backend/migrations_alembic/versions/` 下生成一个新文件，`down_revision` 自动设为当前 head `e59219cc15ef`

- [ ] **Step 2: 编辑生成的文件**

把 `upgrade()`/`downgrade()` 替换成（`revision`/`down_revision`/`Create Date` 保留自动生成的值不要动）：

```python
def upgrade() -> None:
    """Upgrade schema."""
    op.create_table(
        'insurer_accounts',
        sa.Column('id', sa.Integer(), nullable=False),
        sa.Column('label', sa.String(length=100), nullable=False, server_default=''),
        sa.Column('bank_name', sa.String(length=100), nullable=False, server_default=''),
        sa.Column('account_no', sa.String(length=60), nullable=False, server_default=''),
        sa.Column('account_holder', sa.String(length=100), nullable=False, server_default=''),
        sa.Column('status', sa.String(length=20), nullable=False, server_default='active'),
        sa.Column('created_at', sa.DateTime(), nullable=False),
        sa.PrimaryKeyConstraint('id'),
    )
    op.create_table(
        'insurer_account_links',
        sa.Column('id', sa.Integer(), nullable=False),
        sa.Column('insurer', sa.String(length=100), nullable=False),
        sa.Column('account_id', sa.Integer(), nullable=False),
        sa.Column('created_at', sa.DateTime(), nullable=False),
        sa.ForeignKeyConstraint(['account_id'], ['insurer_accounts.id']),
        sa.PrimaryKeyConstraint('id'),
    )
    op.create_table(
        'enterprise_premium_accounts',
        sa.Column('id', sa.Integer(), nullable=False),
        sa.Column('enterprise_id', sa.Integer(), nullable=False),
        sa.Column('account_id', sa.Integer(), nullable=False),
        sa.Column('balance', sa.Float(), nullable=False, server_default='0'),
        sa.ForeignKeyConstraint(['enterprise_id'], ['enterprises.id']),
        sa.ForeignKeyConstraint(['account_id'], ['insurer_accounts.id']),
        sa.PrimaryKeyConstraint('id'),
    )
    op.create_table(
        'recharge_requests',
        sa.Column('id', sa.Integer(), nullable=False),
        sa.Column('enterprise_id', sa.Integer(), nullable=False),
        sa.Column('account_type', sa.String(length=20), nullable=False),
        sa.Column('insurer', sa.String(length=100), nullable=True),
        sa.Column('account_id', sa.Integer(), nullable=True),
        sa.Column('amount', sa.Float(), nullable=False, server_default='0'),
        sa.Column('receipt_file_url', sa.String(length=255), nullable=False, server_default=''),
        sa.Column('status', sa.String(length=20), nullable=False, server_default='pending'),
        sa.Column('reject_reason', sa.String(length=255), nullable=False, server_default=''),
        sa.Column('created_by', sa.Integer(), nullable=False),
        sa.Column('confirmed_by', sa.Integer(), nullable=True),
        sa.Column('confirmed_at', sa.DateTime(), nullable=True),
        sa.Column('created_at', sa.DateTime(), nullable=False),
        sa.ForeignKeyConstraint(['enterprise_id'], ['enterprises.id']),
        sa.ForeignKeyConstraint(['account_id'], ['insurer_accounts.id']),
        sa.ForeignKeyConstraint(['created_by'], ['users.id']),
        sa.ForeignKeyConstraint(['confirmed_by'], ['users.id']),
        sa.PrimaryKeyConstraint('id'),
    )
    op.add_column('ledger_entries', sa.Column('account_id', sa.Integer(), nullable=True))
    op.create_foreign_key('fk_ledger_entries_account_id', 'ledger_entries', 'insurer_accounts', ['account_id'], ['id'])


def downgrade() -> None:
    """Downgrade schema."""
    op.drop_constraint('fk_ledger_entries_account_id', 'ledger_entries', type_='foreignkey')
    op.drop_column('ledger_entries', 'account_id')
    op.drop_table('recharge_requests')
    op.drop_table('enterprise_premium_accounts')
    op.drop_table('insurer_account_links')
    op.drop_table('insurer_accounts')
```

- [ ] **Step 3: 针对临时 SQLite 验证迁移能跑通**

Run:
```bash
DATABASE_URL="sqlite:////tmp/xbb-alembic-check.db" alembic upgrade head
```
Expected: 命令成功退出，无报错（`INFO  [alembic.runtime.migration] Running upgrade ... -> <新revision>, add recharge accounts`）

- [ ] **Step 4: 验证可回滚**

Run:
```bash
DATABASE_URL="sqlite:////tmp/xbb-alembic-check.db" alembic downgrade -1
rm /tmp/xbb-alembic-check.db
```
Expected: 命令成功退出，无报错

- [ ] **Step 5: Commit**

```bash
git add backend/migrations_alembic/versions/
git commit -m "feat: alembic migration for recharge account tables"
```

---

### Task 3: 历史 `premium_balance` 回填迁移

**Files:**
- Modify: `backend/core/migrations.py`
- Modify: `backend/app.py`
- Test: `tests/recharge_smoke.py`

**Interfaces:**
- Consumes: `InsurerAccount`, `EnterprisePremiumAccount`, `Enterprise`（Task 1）
- Produces: `migrate_premium_balances(session: Session) -> None`（幂等，可在每次启动时安全重复调用）

- [ ] **Step 1: 写失败的测试**

在 `tests/recharge_smoke.py` 的 `run()` 里，`startup()` 调用**之前**手动构造一个带历史余额的企业场景。因为 `startup()` 已经会跑迁移，测试这个函数需要绕开自动调用，直接测函数本身。在文件顶部 import 区加入：

```python
from backend.core.migrations import migrate_premium_balances
```

在 `with SessionLocal() as session:` 块的末尾（Task 1 写的断言之后）追加：

```python
            from backend.models import Enterprise
            legacy_enterprise = Enterprise(name="历史余额企业", kind="企业", contact="", phone="", status="active", premium_balance=88.0)
            session.add(legacy_enterprise); session.commit(); session.refresh(legacy_enterprise)

            migrate_premium_balances(session)
            migrated = session.scalar(select(EnterprisePremiumAccount).where(EnterprisePremiumAccount.enterprise_id == legacy_enterprise.id))
            assert migrated is not None and migrated.balance == 88.0
            placeholder_account = session.get(InsurerAccount, migrated.account_id)
            assert placeholder_account.label == "未分类（历史余额）"

            # idempotent: running it again must not create a second row
            migrate_premium_balances(session)
            count = len(session.scalars(select(EnterprisePremiumAccount).where(EnterprisePremiumAccount.enterprise_id == legacy_enterprise.id)).all())
            assert count == 1
```

- [ ] **Step 2: 运行确认失败**

Run: `python3 tests/recharge_smoke.py`
Expected: `ImportError: cannot import name 'migrate_premium_balances' from 'backend.core.migrations'`

- [ ] **Step 3: 实现**

修改 `backend/core/migrations.py`，在文件顶部新增 import，在文件末尾新增函数：

```python
from sqlalchemy import select
from sqlalchemy.orm import Session


def run_sqlite_bridge_migrations(s: Session, database_url: str) -> None:
    ...  # 原有内容不变
```

（顶部已有 `from sqlalchemy.orm import Session`，新增 `from sqlalchemy import select` 一行即可，不用重复整个函数。）

文件末尾追加：

```python
def migrate_premium_balances(s: Session) -> None:
    # SYSTEM-DESIGN-V4.md 之外的补充设计（保司分账户充值 spec）：Enterprise.
    # premium_balance 停止读写，历史余额一次性回填到一个占位 InsurerAccount
    # 下，之后由管理员在后台手动改配到具体保司账户。幂等：已经有
    # EnterprisePremiumAccount 记录的企业跳过，可以在每次启动时安全重跑。
    from ..models import Enterprise, EnterprisePremiumAccount, InsurerAccount

    placeholder = s.scalar(select(InsurerAccount).where(InsurerAccount.label == "未分类（历史余额）"))
    for enterprise in s.scalars(select(Enterprise).where(Enterprise.premium_balance != 0)):
        if s.scalar(select(EnterprisePremiumAccount).where(EnterprisePremiumAccount.enterprise_id == enterprise.id)):
            continue
        if not placeholder:
            placeholder = InsurerAccount(label="未分类（历史余额）", bank_name="", account_no="", account_holder="", status="paused")
            s.add(placeholder); s.flush()
        s.add(EnterprisePremiumAccount(enterprise_id=enterprise.id, account_id=placeholder.id, balance=enterprise.premium_balance))
    s.commit()
```

修改 `backend/app.py` 的 `startup()`：

```python
from .core.migrations import run_sqlite_bridge_migrations, migrate_premium_balances
from .core.seed import seed_default_accounts


@app.on_event("startup")
def startup():
    Base.metadata.create_all(engine)
    with SessionLocal() as s:
        run_sqlite_bridge_migrations(s, DATABASE_URL)
        seed_default_accounts(s)
        migrate_premium_balances(s)
```

- [ ] **Step 4: 运行确认通过**

Run: `python3 tests/recharge_smoke.py`
Expected: `recharge smoke: ok`

- [ ] **Step 5: Commit**

```bash
git add backend/core/migrations.py backend/app.py tests/recharge_smoke.py
git commit -m "feat: backfill legacy premium_balance into a placeholder InsurerAccount on startup"
```

---

### Task 4: 服务层——账户解析与余额查询

**Files:**
- Create: `backend/services/recharge.py`
- Modify: `backend/services/ledger.py`
- Modify: `backend/services/__init__.py`
- Test: `tests/recharge_smoke.py`

**Interfaces:**
- Consumes: `InsurerAccount`, `InsurerAccountLink`, `EnterprisePremiumAccount`（Task 1）, `post_ledger_entry`（既有，需扩展签名）
- Produces: `resolve_account_for_insurer(session, insurer) -> InsurerAccount | None`，`insurers_for_account(session, account_id) -> list[str]`，`insurer_account_dict(item, session) -> dict`，`get_or_create_premium_account(session, enterprise_id, account_id) -> EnterprisePremiumAccount`，`premium_accounts_for_enterprise(session, enterprise_id) -> list[dict]`；`post_ledger_entry(..., account_id: Optional[int] = None)`

- [ ] **Step 1: 写失败的测试**

在 `tests/recharge_smoke.py` 顶部 import 区加入：

```python
from backend.services.recharge import (
    resolve_account_for_insurer, insurers_for_account, insurer_account_dict,
    get_or_create_premium_account, premium_accounts_for_enterprise,
)
```

在 Task 3 断言之后追加：

```python
            second_link = InsurerAccountLink(insurer="第二保司", account_id=account.id)
            session.add(second_link); session.commit()

            resolved = resolve_account_for_insurer(session, "测试保司")
            assert resolved is not None and resolved.id == account.id

            names = insurers_for_account(session, account.id)
            assert set(names) == {"测试保司", "第二保司"}

            account_payload = insurer_account_dict(account, session)
            assert set(account_payload["insurers"]) == {"测试保司", "第二保司"}

            fetched_again = get_or_create_premium_account(session, 1, account.id)
            assert fetched_again.id == premium_account.id  # get_or_create must not duplicate

            rows = premium_accounts_for_enterprise(session, 1)
            assert len(rows) == 1 and rows[0]["balance"] == 100.0 and set(rows[0]["insurers"]) == {"测试保司", "第二保司"}
```

- [ ] **Step 2: 运行确认失败**

Run: `python3 tests/recharge_smoke.py`
Expected: `ModuleNotFoundError: No module named 'backend.services.recharge'`

- [ ] **Step 3: 实现**

创建 `backend/services/recharge.py`：

```python
from sqlalchemy import select
from sqlalchemy.orm import Session

from ..models import EnterprisePremiumAccount, InsurerAccount, InsurerAccountLink
from .serialization import amount, serialize


def resolve_account_for_insurer(session: Session, insurer: str) -> InsurerAccount | None:
    link = session.scalar(select(InsurerAccountLink).where(InsurerAccountLink.insurer == insurer))
    if not link:
        return None
    account = session.get(InsurerAccount, link.account_id)
    return account if account and account.status == "active" else None


def insurers_for_account(session: Session, account_id: int) -> list[str]:
    return [row[0] for row in session.execute(select(InsurerAccountLink.insurer).where(InsurerAccountLink.account_id == account_id)).all()]


def insurer_account_dict(item: InsurerAccount, session: Session) -> dict:
    return {**serialize(item), "insurers": insurers_for_account(session, item.id)}


def get_or_create_premium_account(session: Session, enterprise_id: int, account_id: int) -> EnterprisePremiumAccount:
    row = session.scalar(select(EnterprisePremiumAccount).where(EnterprisePremiumAccount.enterprise_id == enterprise_id, EnterprisePremiumAccount.account_id == account_id))
    if row:
        return row
    row = EnterprisePremiumAccount(enterprise_id=enterprise_id, account_id=account_id, balance=0)
    session.add(row)
    session.flush()
    return row


def premium_accounts_for_enterprise(session: Session, enterprise_id: int) -> list[dict]:
    rows = session.scalars(select(EnterprisePremiumAccount).where(EnterprisePremiumAccount.enterprise_id == enterprise_id))
    result = []
    for row in rows:
        account = session.get(InsurerAccount, row.account_id)
        if not account:
            continue
        result.append({
            "account_id": row.account_id,
            "label": account.label,
            "insurers": insurers_for_account(session, row.account_id),
            "balance": amount(row.balance),
        })
    return result
```

修改 `backend/services/ledger.py` 的 `post_ledger_entry` 签名与函数体：

```python
def post_ledger_entry(
    session: Session,
    enterprise: Enterprise,
    account: Literal["premium", "usage"],
    direction: Literal["credit", "debit"],
    value: float,
    business_type: str,
    business_id: str = "",
    user: Optional[User] = None,
    idempotency_key: str = "",
    account_id: Optional[int] = None,
) -> LedgerEntry:
    # Caller is responsible for updating enterprise.premium_balance /
    # enterprise.usage_balance and calling session.commit() in the SAME
    # transaction as this insert — that's what keeps the cached balance
    # (BalanceSnapshot) and the ledger from drifting apart. See
    # reconcile_enterprise_ledger() below for the periodic cross-check.
    #
    # account_id (added for the insurer-scoped recharge feature) records
    # which InsurerAccount a premium-type entry belongs to; usage-type
    # entries leave it None since the usage account is not insurer-scoped.
    entry = LedgerEntry(
        enterprise_id=enterprise.id,
        account=account,
        direction=direction,
        amount=Decimal(str(amount(value))),
        business_type=business_type,
        business_id=business_id,
        idempotency_key=idempotency_key,
        created_by=user.id if user else None,
        account_id=account_id,
    )
    session.add(entry)
    return entry
```

修改 `backend/services/__init__.py`：

```python
from .serialization import serialize, amount
from .pricing import plan_price_for_class, pricing_snapshot, plan_dict, validate_commission_price, strip_internal_pricing
from .commissions import commission_accrual, commission_dict, agent_commission_rows, agent_commission_summary
from .accruals import billable_date_range, last_billable_date, period_amount, usage_person_days
from .policies import policy_dict
from .ledger import post_ledger_entry, ledger_dict, reconcile_enterprise_ledger
from .recharge import (
    resolve_account_for_insurer, insurers_for_account, insurer_account_dict,
    get_or_create_premium_account, premium_accounts_for_enterprise,
)
from .policy_members import (
    activate_person_policy, correct_person_policy_dates, earliest_effective_at,
    earliest_termination_at, effective_person_status, terminate_person_policy, validate_person_policy_dates,
)

__all__ = [
    "serialize", "amount",
    "plan_price_for_class", "pricing_snapshot", "plan_dict", "validate_commission_price", "strip_internal_pricing",
    "commission_accrual", "commission_dict", "agent_commission_rows", "agent_commission_summary",
    "billable_date_range", "last_billable_date", "period_amount", "usage_person_days",
    "policy_dict",
    "post_ledger_entry", "ledger_dict", "reconcile_enterprise_ledger",
    "resolve_account_for_insurer", "insurers_for_account", "insurer_account_dict",
    "get_or_create_premium_account", "premium_accounts_for_enterprise",
    "activate_person_policy", "correct_person_policy_dates", "terminate_person_policy",
    "earliest_effective_at", "earliest_termination_at", "effective_person_status", "validate_person_policy_dates",
]
```

- [ ] **Step 4: 运行确认通过**

Run: `python3 tests/recharge_smoke.py`
Expected: `recharge smoke: ok`

- [ ] **Step 5: Commit**

```bash
git add backend/services/recharge.py backend/services/ledger.py backend/services/__init__.py tests/recharge_smoke.py
git commit -m "feat: recharge service helpers for account resolution and balance lookup"
```

---

### Task 5: Schema 定义

**Files:**
- Modify: `backend/schemas/finance.py`
- Modify: `backend/schemas/__init__.py`

**Interfaces:**
- Produces: `InsurerAccountIn`, `InsurerAccountUpdate`, `InsurerAccountLinkIn`

这一步没有独立可跑的测试（纯 Pydantic 声明），下一个任务（路由）会在使用它们时间接验证。

- [ ] **Step 1: 实现**

修改 `backend/schemas/finance.py`，在文件末尾追加：

```python
class InsurerAccountIn(BaseModel): label: str = ""; bank_name: str; account_no: str; account_holder: str
class InsurerAccountUpdate(BaseModel):
    label: Optional[str] = None
    bank_name: Optional[str] = None
    account_no: Optional[str] = None
    account_holder: Optional[str] = None
    status: Optional[Literal["active", "paused"]] = None
class InsurerAccountLinkIn(BaseModel): insurer: str = Field(min_length=1); account_id: int
```

（需要在文件顶部把 `from typing import Literal` 改成 `from typing import Literal, Optional`。）

修改 `backend/schemas/__init__.py`：

```python
from .finance import PaymentIn, PaymentCallbackIn, InvoiceIn, InvoiceUpdate, InsurerAccountIn, InsurerAccountUpdate, InsurerAccountLinkIn
```

以及 `__all__` 列表里 `"PaymentIn", "PaymentCallbackIn", "InvoiceIn", "InvoiceUpdate",` 这一行改成：

```python
    "PaymentIn", "PaymentCallbackIn", "InvoiceIn", "InvoiceUpdate", "InsurerAccountIn", "InsurerAccountUpdate", "InsurerAccountLinkIn",
```

- [ ] **Step 2: 用 Python 直接验证能正常 import**

Run: `python3 -c "from backend.schemas import InsurerAccountIn, InsurerAccountUpdate, InsurerAccountLinkIn; print('ok')"`
Expected: `ok`

- [ ] **Step 3: Commit**

```bash
git add backend/schemas/finance.py backend/schemas/__init__.py
git commit -m "feat: add Pydantic schemas for insurer account management"
```

---

### Task 6: 收款账户管理路由

**Files:**
- Create: `backend/routers/insurer_accounts.py`
- Modify: `backend/app.py`
- Test: `tests/recharge_smoke.py`

**Interfaces:**
- Consumes: `InsurerAccount`, `InsurerAccountLink`（Task 1）, `insurer_account_dict`（Task 4）, `InsurerAccountIn`/`InsurerAccountUpdate`/`InsurerAccountLinkIn`（Task 5）
- Produces: `GET/POST /api/insurer-accounts`, `PATCH /api/insurer-accounts/{id}`, `GET/POST /api/insurer-account-links`, `DELETE /api/insurer-account-links/{id}`

- [ ] **Step 1: 写失败的测试**

在 `tests/recharge_smoke.py` 顶部 import 区加入：

```python
from backend.routers.insurer_accounts import (
    insurer_accounts, add_insurer_account, update_insurer_account,
    insurer_account_links, add_insurer_account_link, delete_insurer_account_link,
)
from backend.schemas import InsurerAccountIn, InsurerAccountUpdate, InsurerAccountLinkIn
from backend.core.security import current_user
```

还需要一个 admin `User` 对象来调用这些路由函数（它们接受 `user: User` 参数）。在 Task 4 断言之后追加：

```python
            from backend.models import User
            admin = session.scalar(select(User).where(User.username == "admin"))

            new_account = add_insurer_account(InsurerAccountIn(label="新账户", bank_name="工商银行", account_no="9999", account_holder="测试收款方2"), admin, session)
            assert new_account["status"] == "active"

            updated_account = update_insurer_account(new_account["id"], InsurerAccountUpdate(status="paused"), admin, session)
            assert updated_account["status"] == "paused"

            all_accounts = insurer_accounts(session)
            assert any(a["id"] == new_account["id"] for a in all_accounts)

            new_link = add_insurer_account_link(InsurerAccountLinkIn(insurer="第三保司", account_id=new_account["id"]), admin, session)
            assert new_link["insurer"] == "第三保司"

            try:
                add_insurer_account_link(InsurerAccountLinkIn(insurer="第三保司", account_id=account.id), admin, session)
                raise AssertionError("duplicate insurer link should be rejected")
            except HTTPException as error:
                assert error.status_code == 409

            all_links = insurer_account_links(session)
            assert any(link["id"] == new_link["id"] for link in all_links)

            deleted = delete_insurer_account_link(new_link["id"], admin, session)
            assert deleted["ok"] is True
```

同时在 import 区顶部加入 `from fastapi import HTTPException`（如果还没有）。

- [ ] **Step 2: 运行确认失败**

Run: `python3 tests/recharge_smoke.py`
Expected: `ModuleNotFoundError: No module named 'backend.routers.insurer_accounts'`

- [ ] **Step 3: 实现**

创建 `backend/routers/insurer_accounts.py`：

```python
from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy import select
from sqlalchemy.orm import Session

from ..core.audit import audit
from ..core.db import db
from ..core.rbac import require_role
from ..core.security import current_user
from ..models import InsurerAccount, InsurerAccountLink, User
from ..schemas import InsurerAccountIn, InsurerAccountLinkIn, InsurerAccountUpdate
from ..services import insurer_account_dict, serialize

router = APIRouter(prefix="/api", tags=["insurer-accounts"])


@router.get("/insurer-accounts", dependencies=[Depends(require_role("admin", detail="仅总后台可管理收款账户"))])
def insurer_accounts(session: Session = Depends(db)):
    return [insurer_account_dict(x, session) for x in session.scalars(select(InsurerAccount).order_by(InsurerAccount.id.desc()))]


@router.post("/insurer-accounts", dependencies=[Depends(require_role("admin", detail="仅总后台可管理收款账户"))])
def add_insurer_account(data: InsurerAccountIn, user: User = Depends(current_user), session: Session = Depends(db)):
    item = InsurerAccount(**data.model_dump(), status="active")
    session.add(item); session.commit(); session.refresh(item)
    audit(session, user, "create", "insurer_account", str(item.id))
    return insurer_account_dict(item, session)


@router.patch("/insurer-accounts/{item_id}", dependencies=[Depends(require_role("admin", detail="仅总后台可管理收款账户"))])
def update_insurer_account(item_id: int, data: InsurerAccountUpdate, user: User = Depends(current_user), session: Session = Depends(db)):
    item = session.get(InsurerAccount, item_id)
    if not item: raise HTTPException(404, "收款账户不存在")
    for key, value in data.model_dump(exclude_unset=True).items(): setattr(item, key, value)
    session.commit(); audit(session, user, "update", "insurer_account", str(item.id))
    return insurer_account_dict(item, session)


@router.get("/insurer-account-links", dependencies=[Depends(require_role("admin", detail="仅总后台可管理保司映射"))])
def insurer_account_links(session: Session = Depends(db)):
    return [serialize(x) for x in session.scalars(select(InsurerAccountLink).order_by(InsurerAccountLink.id.desc()))]


@router.post("/insurer-account-links", dependencies=[Depends(require_role("admin", detail="仅总后台可管理保司映射"))])
def add_insurer_account_link(data: InsurerAccountLinkIn, user: User = Depends(current_user), session: Session = Depends(db)):
    if not session.get(InsurerAccount, data.account_id): raise HTTPException(404, "收款账户不存在")
    if session.scalar(select(InsurerAccountLink).where(InsurerAccountLink.insurer == data.insurer)):
        raise HTTPException(409, "该保司已绑定收款账户，请先解绑旧映射")
    item = InsurerAccountLink(**data.model_dump())
    session.add(item); session.commit(); session.refresh(item)
    audit(session, user, "create", "insurer_account_link", str(item.id))
    return serialize(item)


@router.delete("/insurer-account-links/{item_id}", dependencies=[Depends(require_role("admin", detail="仅总后台可管理保司映射"))])
def delete_insurer_account_link(item_id: int, user: User = Depends(current_user), session: Session = Depends(db)):
    item = session.get(InsurerAccountLink, item_id)
    if not item: raise HTTPException(404, "映射不存在")
    session.delete(item); session.commit()
    audit(session, user, "delete", "insurer_account_link", str(item_id))
    return {"ok": True}
```

修改 `backend/app.py`：在路由 import 区加入

```python
from .routers.insurer_accounts import router as insurer_accounts_router
```

在 `app.include_router(...)` 列表里加入（放在 `enterprises_router` 之后即可，顺序对功能无影响）：

```python
app.include_router(insurer_accounts_router)
```

- [ ] **Step 4: 运行确认通过**

Run: `python3 tests/recharge_smoke.py`
Expected: `recharge smoke: ok`

- [ ] **Step 5: Commit**

```bash
git add backend/routers/insurer_accounts.py backend/app.py tests/recharge_smoke.py
git commit -m "feat: admin CRUD endpoints for insurer accounts and insurer-account links"
```

---

### Task 7: 充值申请路由（提交/列表/确认/驳回/回单下载）

**Files:**
- Create: `backend/routers/recharge_requests.py`
- Modify: `backend/app.py`
- Test: `tests/recharge_smoke.py`

**Interfaces:**
- Consumes: `RechargeRequest`（Task 1）, `resolve_account_for_insurer`/`get_or_create_premium_account`（Task 4）, `post_ledger_entry`（Task 4 扩展版）, `make_download_token`/`verify_download_token`（既有 `core/file_tokens.py`）
- Produces: `POST /api/recharge-requests`, `GET /api/recharge-requests`, `PATCH /api/recharge-requests/{id}/confirm`, `PATCH /api/recharge-requests/{id}/reject`, `GET /api/recharge-requests/{id}/receipt`

- [ ] **Step 1: 写失败的测试**

在 `tests/recharge_smoke.py` 顶部 import 区加入：

```python
import asyncio
import io
from fastapi import UploadFile
from backend.routers.recharge_requests import (
    create_recharge_request, list_recharge_requests, confirm_recharge_request, reject_recharge_request,
)
```

在 Task 6 断言之后追加：

```python
            # id=1 is the demo enterprise seed_default_accounts() always creates on a fresh DB.
            enterprise = session.get(Enterprise, 1)
            enterprise_id = enterprise.id
            balance_before = get_or_create_premium_account(session, enterprise_id, account.id).balance

            fake_receipt = UploadFile(file=io.BytesIO(b"fake-image-bytes"), filename="receipt.png")
            submitted = asyncio.run(create_recharge_request(
                enterprise_id=enterprise_id, account_type="premium", insurer="测试保司", amount=30.0,
                file=fake_receipt, user=admin, session=session,
            ))
            assert submitted["status"] == "pending" and submitted["account_id"] == account.id

            listed = list_recharge_requests("", admin, session)
            assert any(r["id"] == submitted["id"] for r in listed)

            confirmed = confirm_recharge_request(submitted["id"], admin, session)
            assert confirmed["status"] == "confirmed"
            balance_after = get_or_create_premium_account(session, enterprise_id, account.id).balance
            assert balance_after - balance_before == 30.0

            try:
                confirm_recharge_request(submitted["id"], admin, session)
                raise AssertionError("confirming an already-confirmed request should fail")
            except HTTPException as error:
                assert error.status_code == 400

            fake_receipt_2 = UploadFile(file=io.BytesIO(b"fake-image-bytes-2"), filename="receipt2.png")
            second_submission = asyncio.run(create_recharge_request(
                enterprise_id=enterprise_id, account_type="usage", insurer="", amount=15.0,
                file=fake_receipt_2, user=admin, session=session,
            ))
            usage_before = enterprise.usage_balance
            rejected = reject_recharge_request(second_submission["id"], "回单金额与申请金额不符", admin, session)
            assert rejected["status"] == "rejected" and rejected["reject_reason"] == "回单金额与申请金额不符"
            assert enterprise.usage_balance == usage_before  # rejecting must not touch the balance
```

- [ ] **Step 2: 运行确认失败**

Run: `python3 tests/recharge_smoke.py`
Expected: `ModuleNotFoundError: No module named 'backend.routers.recharge_requests'`

- [ ] **Step 3: 实现**

创建 `backend/routers/recharge_requests.py`：

```python
import secrets
from pathlib import Path
from typing import Literal

from fastapi import APIRouter, Depends, File, Form, HTTPException, Query, UploadFile
from fastapi.responses import FileResponse
from sqlalchemy import select
from sqlalchemy.orm import Session

from ..core.audit import audit
from ..core.business_time import business_now
from ..core.config import ROOT
from ..core.db import db
from ..core.file_tokens import make_download_token, verify_download_token
from ..core.rbac import require_role
from ..core.security import current_user
from ..models import Enterprise, RechargeRequest, User
from ..services import get_or_create_premium_account, post_ledger_entry, resolve_account_for_insurer, serialize

router = APIRouter(prefix="/api", tags=["recharge-requests"])


def _recharge_dict(item: RechargeRequest, session: Session) -> dict:
    enterprise = session.get(Enterprise, item.enterprise_id)
    payload = serialize(item)
    payload["enterprise_name"] = enterprise.name if enterprise else ""
    if item.receipt_file_url:
        token, expires = make_download_token(f"recharge-receipt:{item.id}")
        payload["receipt_download_url"] = f"/api/recharge-requests/{item.id}/receipt?token={token}&expires={expires}"
    return payload


@router.post("/recharge-requests", dependencies=[Depends(require_role("admin", "enterprise", detail="无权发起充值申请"))])
async def create_recharge_request(
    enterprise_id: int = Form(...),
    account_type: Literal["premium", "usage"] = Form(...),
    insurer: str = Form(""),
    amount: float = Form(...),
    file: UploadFile = File(...),
    user: User = Depends(current_user),
    session: Session = Depends(db),
):
    if user.role == "enterprise" and user.enterprise_id != enterprise_id: raise HTTPException(403, "无权为其他单位发起充值")
    if not session.get(Enterprise, enterprise_id): raise HTTPException(404, "投保单位不存在")
    if amount <= 0: raise HTTPException(400, "充值金额必须大于 0")
    account_id = None
    if account_type == "premium":
        if not insurer.strip(): raise HTTPException(400, "请选择保司")
        account = resolve_account_for_insurer(session, insurer.strip())
        if not account: raise HTTPException(400, "该保司尚未配置收款账户，请联系平台")
        account_id = account.id
    suffix = Path(file.filename or "").suffix.lower()
    if suffix not in {".pdf", ".jpg", ".jpeg", ".png"}: raise HTTPException(400, "仅支持 PDF 或图片格式")
    content = await file.read()
    if len(content) > 20 * 1024 * 1024: raise HTTPException(400, "文件不能超过 20MB")
    folder = ROOT / "uploads" / "recharge-receipts" / str(enterprise_id)
    folder.mkdir(parents=True, exist_ok=True)
    stored = f"{secrets.token_hex(8)}{suffix}"
    (folder / stored).write_bytes(content)
    item = RechargeRequest(
        enterprise_id=enterprise_id, account_type=account_type,
        insurer=insurer.strip() if account_type == "premium" else None, account_id=account_id,
        amount=amount, receipt_file_url=f"/uploads/recharge-receipts/{enterprise_id}/{stored}",
        status="pending", created_by=user.id,
    )
    session.add(item); session.commit(); session.refresh(item)
    audit(session, user, "create", "recharge_request", str(item.id), f"{account_type}:{amount}")
    return _recharge_dict(item, session)


@router.get("/recharge-requests")
def list_recharge_requests(status_value: str = Query("", alias="status"), user: User = Depends(current_user), session: Session = Depends(db)):
    stmt = select(RechargeRequest).order_by(RechargeRequest.id.desc())
    if user.role == "enterprise" and user.enterprise_id: stmt = stmt.where(RechargeRequest.enterprise_id == user.enterprise_id)
    elif user.role != "admin": raise HTTPException(403, "无权查看充值记录")
    if status_value: stmt = stmt.where(RechargeRequest.status == status_value)
    return [_recharge_dict(x, session) for x in session.scalars(stmt)]


@router.patch("/recharge-requests/{item_id}/confirm", dependencies=[Depends(require_role("admin", detail="仅总后台可确认充值"))])
def confirm_recharge_request(item_id: int, user: User = Depends(current_user), session: Session = Depends(db)):
    item = session.get(RechargeRequest, item_id)
    if not item: raise HTTPException(404, "充值申请不存在")
    if item.status != "pending": raise HTTPException(400, "该申请已处理，不能重复确认")
    enterprise = session.get(Enterprise, item.enterprise_id)
    if item.account_type == "premium":
        premium_account = get_or_create_premium_account(session, item.enterprise_id, item.account_id)
        premium_account.balance += item.amount
        post_ledger_entry(session, enterprise, "premium", "credit", item.amount, "recharge_request", str(item.id), user, account_id=item.account_id)
    else:
        enterprise.usage_balance += item.amount
        post_ledger_entry(session, enterprise, "usage", "credit", item.amount, "recharge_request", str(item.id), user)
    item.status = "confirmed"; item.confirmed_by = user.id; item.confirmed_at = business_now()
    session.commit(); audit(session, user, "confirm", "recharge_request", str(item.id))
    return _recharge_dict(item, session)


@router.patch("/recharge-requests/{item_id}/reject", dependencies=[Depends(require_role("admin", detail="仅总后台可驳回充值"))])
def reject_recharge_request(item_id: int, reason: str = Query(...), user: User = Depends(current_user), session: Session = Depends(db)):
    item = session.get(RechargeRequest, item_id)
    if not item: raise HTTPException(404, "充值申请不存在")
    if item.status != "pending": raise HTTPException(400, "该申请已处理，不能重复驳回")
    if not reason.strip(): raise HTTPException(400, "驳回时必须填写原因")
    item.status = "rejected"; item.reject_reason = reason.strip(); item.confirmed_by = user.id; item.confirmed_at = business_now()
    session.commit(); audit(session, user, "reject", "recharge_request", str(item.id), reason)
    return _recharge_dict(item, session)


@router.get("/recharge-requests/{item_id}/receipt")
def download_recharge_receipt(item_id: int, token: str, expires: int, session: Session = Depends(db)):
    if not verify_download_token(f"recharge-receipt:{item_id}", expires, token): raise HTTPException(403, "下载链接无效或已过期")
    item = session.get(RechargeRequest, item_id)
    if not item or not item.receipt_file_url: raise HTTPException(404, "回单不存在")
    return FileResponse(ROOT / item.receipt_file_url.lstrip("/"))
```

修改 `backend/app.py`：

```python
from .routers.recharge_requests import router as recharge_requests_router
```

```python
app.include_router(recharge_requests_router)
```

- [ ] **Step 4: 运行确认通过**

Run: `python3 tests/recharge_smoke.py`
Expected: `recharge smoke: ok`

- [ ] **Step 5: Commit**

```bash
git add backend/routers/recharge_requests.py backend/app.py tests/recharge_smoke.py
git commit -m "feat: recharge request submit/list/confirm/reject/receipt-download endpoints"
```

---

### Task 8: 企业按账户余额查询端点

**Files:**
- Modify: `backend/routers/enterprises.py`
- Test: `tests/recharge_smoke.py`

**Interfaces:**
- Consumes: `premium_accounts_for_enterprise`（Task 4）
- Produces: `GET /api/enterprises/{id}/premium-accounts`

- [ ] **Step 1: 写失败的测试**

在 `tests/recharge_smoke.py` 顶部 import 区加入：

```python
from backend.routers.enterprises import enterprise_premium_accounts
```

在 Task 7 断言之后追加：

```python
            premium_rows = enterprise_premium_accounts(enterprise_id, admin, session)
            assert any(row["account_id"] == account.id and row["balance"] == balance_after for row in premium_rows)
```

- [ ] **Step 2: 运行确认失败**

Run: `python3 tests/recharge_smoke.py`
Expected: `ImportError: cannot import name 'enterprise_premium_accounts' from 'backend.routers.enterprises'`

- [ ] **Step 3: 实现**

打开 `backend/routers/enterprises.py`，在文件顶部 import 区把

```python
from ..services import ledger_dict, post_ledger_entry, pricing_snapshot, reconcile_enterprise_ledger, serialize, strip_internal_pricing
```

改成

```python
from ..services import ledger_dict, post_ledger_entry, premium_accounts_for_enterprise, pricing_snapshot, reconcile_enterprise_ledger, serialize, strip_internal_pricing
```

在 `@router.get("/enterprises/{item_id}/ledger")` 端点定义之前（任意位置均可，紧邻 ledger 端点方便查找）插入：

```python
@router.get("/enterprises/{item_id}/premium-accounts")
def enterprise_premium_accounts(item_id: int, user: User = Depends(current_user), session: Session = Depends(db)):
    if user.role == "enterprise" and user.enterprise_id != item_id: raise HTTPException(403, "无权查看该单位账户")
    if not session.get(Enterprise, item_id): raise HTTPException(404, "投保单位不存在")
    return premium_accounts_for_enterprise(session, item_id)
```

- [ ] **Step 4: 运行确认通过**

Run: `python3 tests/recharge_smoke.py`
Expected: `recharge smoke: ok`

- [ ] **Step 5: Commit**

```bash
git add backend/routers/enterprises.py tests/recharge_smoke.py
git commit -m "feat: GET /enterprises/{id}/premium-accounts"
```

---

### Task 9: `dashboard()` 改成按账户计算保费余额

**Files:**
- Modify: `backend/routers/dashboard.py`
- Test: `tests/recharge_smoke.py`

**Interfaces:**
- Consumes: `premium_accounts_for_enterprise`（Task 4）
- Produces: `GET /api/dashboard` 响应体新增 `premium_accounts: list[dict]` 字段（替代原来的标量 `premium_balance`），`balance_alerts` 的 premium 部分改为逐账户计算

**这是本计划里逻辑最复杂的一步，务必先读一遍现有实现再动手。** 现状（`backend/routers/dashboard.py` 的 `dashboard()`）：`daily_premium` 是把该企业所有 `active` 保单的日均保费汇总成一个数，跟 `ent.premium_balance` 这一个数直接相除得到 `days_left`。改成按账户后，`daily_premium` 也必须按账户分别计算——只汇总"保单对应的 `InsurancePlan.insurer` 落在这个账户名下"的那些保单。

- [ ] **Step 1: 写失败的测试**

在 `tests/recharge_smoke.py` 顶部 import 区加入：

```python
from backend.routers.dashboard import dashboard as dashboard_endpoint
```

在 Task 8 断言之后追加：

```python
            dashboard_data = dashboard_endpoint(admin, session)
            assert "premium_accounts" in dashboard_data
            assert "premium_balance" not in dashboard_data  # replaced, not just supplemented
            matching = next((row for row in dashboard_data["premium_accounts"] if row["account_id"] == account.id), None)
            assert matching is not None and matching["balance"] == balance_after
```

- [ ] **Step 2: 运行确认失败**

Run: `python3 tests/recharge_smoke.py`
Expected: `AssertionError`（`"premium_accounts" in dashboard_data` 为 False，因为字段还叫 `premium_balance`）

- [ ] **Step 3: 实现**

打开 `backend/routers/dashboard.py`，把顶部 import 改成：

```python
from ..services import amount, effective_person_status, policy_dict, premium_accounts_for_enterprise, pricing_snapshot, strip_internal_pricing, usage_person_days
```

把整个 `dashboard()` 函数替换为：

```python
@router.get("/dashboard")
def dashboard(user: User = Depends(current_user), session: Session = Depends(db)):
    enterprise_filter = [user.enterprise_id] if user.role == "enterprise" and user.enterprise_id else None
    enterprises = session.query(Enterprise).filter(Enterprise.id.in_(enterprise_filter)).all() if enterprise_filter else session.query(Enterprise).all()
    people = session.query(InsuredPerson).filter(InsuredPerson.enterprise_id.in_(enterprise_filter)).all() if enterprise_filter else session.query(InsuredPerson).all()
    def _status(x):
        if x.status!='stopped': return x.status
        member=session.scalar(select(PolicyMember).where(PolicyMember.person_id==x.id).order_by(PolicyMember.id.desc()))
        return effective_person_status(x,member.terminated_at if member else None)
    active_people=[x for x in people if _status(x) in {'active','pending'}]

    alerts=[]
    premium_agg: dict[int, dict] = {}
    for ent in enterprises:
        today=business_today(); enterprise_active_count=usage_person_days(session,ent.id,today,today)['active_people']
        daily_usage=enterprise_active_count*float(ent.usage_fee_daily or 0.1)
        active_policies=list(session.scalars(select(Policy).where(Policy.enterprise_id==ent.id,Policy.status=='active')))
        for row in premium_accounts_for_enterprise(session, ent.id):
            insurer_set = set(row["insurers"])
            daily_premium = 0.0
            for p in active_policies:
                plan = session.get(InsurancePlan, p.plan_id)
                if not plan or plan.insurer not in insurer_set: continue
                billing = policy_dict(p, session)
                daily_premium += float(billing['premium'] or 0) / (1 if billing['billing_mode'] == 'daily' else 30)
            days_left = 999999 if daily_premium <= 0 else row["balance"] / daily_premium
            if row["account_id"] not in premium_agg:
                premium_agg[row["account_id"]] = {"account_id": row["account_id"], "label": row["label"], "insurers": row["insurers"], "balance": 0.0}
            premium_agg[row["account_id"]]["balance"] += row["balance"]
            if days_left <= int(ent.alert_days or 3):
                alerts.append({'enterprise_id':ent.id,'enterprise_name':ent.name,'account':'premium','account_id':row["account_id"],'label':row["label"],'balance':row["balance"],'daily_burn':daily_premium,'days_left':round(days_left,1),'alert_days':ent.alert_days or 3,'level':'critical' if days_left<=1 else 'warning'})
        usage_days_left=999999 if daily_usage<=0 else ent.usage_balance/daily_usage
        if usage_days_left <= int(ent.alert_days or 3): alerts.append({'enterprise_id':ent.id,'enterprise_name':ent.name,'account':'usage','balance':ent.usage_balance,'daily_burn':daily_usage,'days_left':round(usage_days_left,1),'alert_days':ent.alert_days or 3,'level':'critical' if usage_days_left<=1 else 'warning'})

    return {"portal": "enterprise" if user.role == "enterprise" else "admin", "enterprises": len(enterprises), "people": len(people), "active_people":len(active_people), "active_policies": session.query(Policy).filter(Policy.status == "active", Policy.enterprise_id.in_(enterprise_filter)).count() if enterprise_filter else session.query(Policy).filter(Policy.status == "active").count(), "pending_enterprises": session.query(Enterprise).filter(Enterprise.status == "pending").count() if not enterprise_filter else 0, "pending_people": len([x for x in people if x.status == "pending"]), "claims_open": session.query(Claim).filter(Claim.status.not_in(["paid", "closed"]), Claim.enterprise_id.in_(enterprise_filter)).count() if enterprise_filter else session.query(Claim).filter(Claim.status.not_in(["paid", "closed"])).count(), "premium_accounts": list(premium_agg.values()), "usage_balance": sum(x.usage_balance for x in enterprises), "balance_alerts": alerts}
```

（`premium_agg` 对 admin 视角天然是跨企业累加同一个 `account_id` 的余额；对 enterprise 视角 `enterprises` 列表只有一个企业，等价于该企业自己的按账户余额列表——不需要为两种角色写两套逻辑。）

- [ ] **Step 4: 运行确认通过**

Run: `python3 tests/recharge_smoke.py`
Expected: `recharge smoke: ok`

- [ ] **Step 5: Commit**

```bash
git add backend/routers/dashboard.py tests/recharge_smoke.py
git commit -m "feat: dashboard premium balance broken down by insurer account"
```

---

### Task 10: 前端类型与 API 客户端

**Files:**
- Modify: `web/src/api/types.ts`
- Create: `web/src/api/recharge.ts`

**Interfaces:**
- Consumes: Task 6/7/8/9 的后端响应体形状
- Produces: `InsurerAccount`, `InsurerAccountLink`, `RechargeRequest`, `PremiumAccountRow` 类型；`listInsurerAccounts`/`createInsurerAccount`/`updateInsurerAccount`/`listInsurerAccountLinks`/`createInsurerAccountLink`/`deleteInsurerAccountLink`/`listRechargeRequests`/`createRechargeRequest`/`confirmRechargeRequest`/`rejectRechargeRequest`/`getEnterprisePremiumAccounts` 客户端函数

这一步是纯类型/客户端代码，没有独立跑得起来的单元测试（前端测试基建本身不在这个仓库里），用 `vue-tsc` 类型检查作为"测试"。

- [ ] **Step 1: 实现类型**

修改 `web/src/api/types.ts`。找到 `export interface DashboardData {` 这段，把

```ts
  premium_balance: number
  usage_balance: number
  balance_alerts: Array<{
    enterprise_id: number
    enterprise_name: string
    account: string
    balance: number
    daily_burn: number
    days_left: number
    alert_days: number
    level: 'critical' | 'warning'
  }>
}
```

替换为：

```ts
  premium_accounts: PremiumAccountRow[]
  usage_balance: number
  balance_alerts: Array<{
    enterprise_id: number
    enterprise_name: string
    account: string
    account_id?: number
    label?: string
    balance: number
    daily_burn: number
    days_left: number
    alert_days: number
    level: 'critical' | 'warning'
  }>
}

export interface PremiumAccountRow {
  account_id: number
  label: string
  insurers: string[]
  balance: number
}

export interface InsurerAccount {
  id: number
  label: string
  bank_name: string
  account_no: string
  account_holder: string
  status: 'active' | 'paused'
  created_at: string
  insurers: string[]
}

export interface InsurerAccountLink {
  id: number
  insurer: string
  account_id: number
  created_at: string
}

export interface RechargeRequest {
  id: number
  enterprise_id: number
  enterprise_name: string
  account_type: 'premium' | 'usage'
  insurer: string | null
  account_id: number | null
  amount: number
  receipt_file_url: string
  receipt_download_url?: string
  status: 'pending' | 'confirmed' | 'rejected'
  reject_reason: string
  created_by: number
  confirmed_by: number | null
  confirmed_at: string | null
  created_at: string
}
```

- [ ] **Step 2: 实现 API 客户端**

创建 `web/src/api/recharge.ts`：

```ts
import { client } from './client'
import type { InsurerAccount, InsurerAccountLink, RechargeRequest, PremiumAccountRow } from './types'

export function listInsurerAccounts() {
  return client.get<InsurerAccount[]>('/insurer-accounts').then((r) => r.data)
}

export function createInsurerAccount(data: { label: string; bank_name: string; account_no: string; account_holder: string }) {
  return client.post<InsurerAccount>('/insurer-accounts', data).then((r) => r.data)
}

export function updateInsurerAccount(id: number, data: Partial<{ label: string; bank_name: string; account_no: string; account_holder: string; status: 'active' | 'paused' }>) {
  return client.patch<InsurerAccount>(`/insurer-accounts/${id}`, data).then((r) => r.data)
}

export function listInsurerAccountLinks() {
  return client.get<InsurerAccountLink[]>('/insurer-account-links').then((r) => r.data)
}

export function createInsurerAccountLink(data: { insurer: string; account_id: number }) {
  return client.post<InsurerAccountLink>('/insurer-account-links', data).then((r) => r.data)
}

export function deleteInsurerAccountLink(id: number) {
  return client.delete<{ ok: boolean }>(`/insurer-account-links/${id}`).then((r) => r.data)
}

export function listRechargeRequests(status?: string) {
  return client.get<RechargeRequest[]>('/recharge-requests', { params: status ? { status } : undefined }).then((r) => r.data)
}

export function createRechargeRequest(data: { enterprise_id: number; account_type: 'premium' | 'usage'; insurer: string; amount: number; file: File }) {
  const form = new FormData()
  form.append('enterprise_id', String(data.enterprise_id))
  form.append('account_type', data.account_type)
  form.append('insurer', data.insurer)
  form.append('amount', String(data.amount))
  form.append('file', data.file)
  return client.post<RechargeRequest>('/recharge-requests', form, { headers: { 'Content-Type': 'multipart/form-data' } }).then((r) => r.data)
}

export function confirmRechargeRequest(id: number) {
  return client.patch<RechargeRequest>(`/recharge-requests/${id}/confirm`).then((r) => r.data)
}

export function rejectRechargeRequest(id: number, reason: string) {
  return client.patch<RechargeRequest>(`/recharge-requests/${id}/reject`, null, { params: { reason } }).then((r) => r.data)
}

export function getEnterprisePremiumAccounts(id: number) {
  return client.get<PremiumAccountRow[]>(`/enterprises/${id}/premium-accounts`).then((r) => r.data)
}
```

- [ ] **Step 3: 类型检查**

Run: `cd web && npx vue-tsc --noEmit`
Expected: 命令成功退出，无类型错误（`HomeView.vue`/`ScreenView.vue` 引用了旧的 `data.premium_balance` 字段会在这一步报错——那是预期的，Task 12/13 会修；如果这一步除了那两个文件之外还有其他文件报错，说明本任务的类型定义写错了，需要修正）

- [ ] **Step 4: Commit**

```bash
git add web/src/api/types.ts web/src/api/recharge.ts
git commit -m "feat: frontend types and API client for recharge accounts"
```

---

### Task 11: 收款账户管理页面（`PlansAdminView.vue`）

**Files:**
- Modify: `web/src/views/plans/PlansAdminView.vue`

**Interfaces:**
- Consumes: `listInsurerAccounts`/`createInsurerAccount`/`updateInsurerAccount`/`listInsurerAccountLinks`/`createInsurerAccountLink`/`deleteInsurerAccountLink`（Task 10）

- [ ] **Step 1: 实现**

在 `web/src/views/plans/PlansAdminView.vue` 的 `<script setup>` 顶部 import 区加入：

```ts
import * as rechargeApi from '@/api/recharge'
import type { InsurerAccount, InsurerAccountLink } from '@/api/types'
```

在既有的 `const loading = ref(true)` 附近加入新的状态和加载逻辑：

```ts
const insurerAccounts = ref<InsurerAccount[]>([])
const insurerAccountLinks = ref<InsurerAccountLink[]>([])

async function loadInsurerAccounts() {
  const [accounts, links] = await Promise.all([rechargeApi.listInsurerAccounts(), rechargeApi.listInsurerAccountLinks()])
  insurerAccounts.value = accounts
  insurerAccountLinks.value = links
}

const accountFormVisible = ref(false)
const accountEditingId = ref<number | null>(null)
const accountForm = reactive({ label: '', bank_name: '', account_no: '', account_holder: '' })
function openAccountCreate() {
  accountEditingId.value = null
  Object.assign(accountForm, { label: '', bank_name: '', account_no: '', account_holder: '' })
  accountFormVisible.value = true
}
function openAccountEdit(item: InsurerAccount) {
  accountEditingId.value = item.id
  Object.assign(accountForm, { label: item.label, bank_name: item.bank_name, account_no: item.account_no, account_holder: item.account_holder })
  accountFormVisible.value = true
}
async function submitAccountForm() {
  if (!accountForm.bank_name || !accountForm.account_no || !accountForm.account_holder) { ElMessage.error('请填写开户行、账号、账户名称'); return }
  try {
    if (accountEditingId.value) await rechargeApi.updateInsurerAccount(accountEditingId.value, accountForm)
    else await rechargeApi.createInsurerAccount(accountForm)
    ElMessage.success('保存成功')
    accountFormVisible.value = false
    loadInsurerAccounts()
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}
async function toggleAccountStatus(item: InsurerAccount) {
  try {
    await rechargeApi.updateInsurerAccount(item.id, { status: item.status === 'active' ? 'paused' : 'active' })
    ElMessage.success('已更新')
    loadInsurerAccounts()
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}

const linkFormVisible = ref(false)
const linkForm = reactive({ insurer: '', account_id: null as number | null })
function openLinkCreate(account: InsurerAccount) {
  linkForm.insurer = ''
  linkForm.account_id = account.id
  linkFormVisible.value = true
}
async function submitLinkForm() {
  if (!linkForm.insurer.trim() || !linkForm.account_id) { ElMessage.error('请填写保司名称'); return }
  try {
    await rechargeApi.createInsurerAccountLink({ insurer: linkForm.insurer.trim(), account_id: linkForm.account_id })
    ElMessage.success('绑定成功')
    linkFormVisible.value = false
    loadInsurerAccounts()
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}
async function removeLink(link: InsurerAccountLink) {
  try {
    await ElMessageBox.confirm(`确定解绑保司「${link.insurer}」吗？不影响历史余额和流水，只影响之后新提交的充值往哪个账户解析。`, '解绑确认', { type: 'warning' })
  } catch { return }
  try {
    await rechargeApi.deleteInsurerAccountLink(link.id)
    ElMessage.success('已解绑')
    loadInsurerAccounts()
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}
```

找到既有的 `onMounted(load)` 那一行，改成：

```ts
onMounted(() => { load(); loadInsurerAccounts() })
```

在 `<template>` 里，`</PageCard>`（对应"已录入方案"那张卡片的收尾标签）之后、`</div>`（最外层容器收尾）之前，插入新的 `PageCard`：

```html
    <PageCard title="收款账户管理" hint="一个账户可以被多个保司共用，余额按账户池化">
      <template #actions>
        <el-button type="primary" @click="openAccountCreate">＋ 新增收款账户</el-button>
      </template>
      <el-table :data="insurerAccounts" size="small">
        <el-table-column prop="label" label="账户备注名" min-width="140" />
        <el-table-column label="开户行/账号" min-width="200">
          <template #default="{ row }">{{ row.bank_name }} · {{ row.account_no }}</template>
        </el-table-column>
        <el-table-column prop="account_holder" label="账户名称" min-width="140" />
        <el-table-column label="绑定保司" min-width="200">
          <template #default="{ row }">
            <el-tag v-for="name in row.insurers" :key="name" size="small" style="margin-right: 6px">{{ name }}</el-tag>
            <span v-if="!row.insurers.length" class="muted">未绑定</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.status === 'active' ? 'success' : 'info'" size="small">{{ row.status === 'active' ? '启用' : '暂停' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="220" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openAccountEdit(row)">编辑</el-button>
            <el-button link type="primary" size="small" @click="toggleAccountStatus(row)">{{ row.status === 'active' ? '暂停' : '启用' }}</el-button>
            <el-button link type="primary" size="small" @click="openLinkCreate(row)">绑定保司</el-button>
          </template>
        </el-table-column>
      </el-table>
      <div v-if="insurerAccountLinks.length" style="padding: 16px 20px 0">
        <p class="tier-hint">保司映射（点击解绑可改配到其他账户）：</p>
        <el-tag v-for="link in insurerAccountLinks" :key="link.id" closable style="margin: 0 8px 8px 0" @close="removeLink(link)">
          {{ link.insurer }}
        </el-tag>
      </div>
    </PageCard>

    <el-dialog v-model="accountFormVisible" :title="accountEditingId ? '编辑收款账户' : '新增收款账户'" width="480px">
      <el-form :model="accountForm" label-width="120px">
        <el-form-item label="账户备注名"><el-input v-model="accountForm.label" placeholder="如：平安/太平洋共用账户" /></el-form-item>
        <el-form-item label="开户行" required><el-input v-model="accountForm.bank_name" /></el-form-item>
        <el-form-item label="银行账号" required><el-input v-model="accountForm.account_no" /></el-form-item>
        <el-form-item label="账户名称" required><el-input v-model="accountForm.account_holder" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="accountFormVisible = false">取消</el-button>
        <el-button type="primary" @click="submitAccountForm">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="linkFormVisible" title="绑定保司到该账户" width="420px">
      <el-form :model="linkForm" label-width="100px">
        <el-form-item label="保司名称" required><el-input v-model="linkForm.insurer" placeholder="需与保险产品里的保险公司名称一致" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="linkFormVisible = false">取消</el-button>
        <el-button type="primary" @click="submitLinkForm">绑定</el-button>
      </template>
    </el-dialog>
```

- [ ] **Step 2: 类型检查**

Run: `cd web && npx vue-tsc --noEmit`
Expected: `PlansAdminView.vue` 不再报错（`HomeView.vue`/`ScreenView.vue` 仍会报错，留给 Task 12/13）

- [ ] **Step 3: 手动验证**

Run: `cd web && npm run dev`（本地起 Vite + 需要本地后端 `./start.sh` 同时跑着）
Expected: 浏览器打开保险公司页面，能看到新的"收款账户管理"卡片；新增一个账户、绑定一个保司名称、解绑，界面反应符合预期

- [ ] **Step 4: Commit**

```bash
git add web/src/views/plans/PlansAdminView.vue
git commit -m "feat: insurer account management UI on the 保险公司 admin page"
```

---

### Task 12: 充值中心页面（企业发起 + 管理员审核）

**Files:**
- Create: `web/src/views/recharge/RechargeCenterView.vue`
- Modify: `web/src/router/routes.ts`

**Interfaces:**
- Consumes: `listRechargeRequests`/`createRechargeRequest`/`confirmRechargeRequest`/`rejectRechargeRequest`/`getEnterprisePremiumAccounts`（Task 10）, `listInsurerAccounts`（Task 10，用于企业端选保司时查回单收款信息——实际上企业端应该看到的是"保司名"输入/选择而非直接选账户，账户信息在提交后由后端解析，前端只需要在用户选定保司后展示对应账户；这需要 `listInsurerAccountLinks` 做本地查找）

- [ ] **Step 1: 实现路由**

修改 `web/src/router/routes.ts`，在 `/billing` 那一行之后插入：

```ts
  { path: '/recharge', name: 'recharge', component: () => import('@/views/recharge/RechargeCenterView.vue'), meta: { title: '账户充值', group: '保障与结算' } },
```

- [ ] **Step 2: 实现页面**

创建 `web/src/views/recharge/RechargeCenterView.vue`：

```vue
<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import * as rechargeApi from '@/api/recharge'
import { listEnterprises } from '@/api/enterprises'
import type { Enterprise, InsurerAccount, InsurerAccountLink, RechargeRequest } from '@/api/types'
import { useAuthStore } from '@/stores/auth'
import { money, formatDateTime } from '@/utils/format'
import PageCard from '@/components/PageCard.vue'
import StatTile from '@/components/StatTile.vue'
import TablePagination from '@/components/TablePagination.vue'
import { usePagedList } from '@/composables/usePagedList'

const auth = useAuthStore()
const loading = ref(true)
const requests = ref<RechargeRequest[]>([])
const insurerAccountLinks = ref<InsurerAccountLink[]>([])
const insurerAccounts = ref<InsurerAccount[]>([])
const enterprises = ref<Enterprise[]>([])

async function load() {
  loading.value = true
  try {
    const tasks: Promise<unknown>[] = [
      rechargeApi.listRechargeRequests().then((r) => (requests.value = r)),
      rechargeApi.listInsurerAccountLinks().then((r) => (insurerAccountLinks.value = r)),
    ]
    if (auth.isAdmin()) {
      tasks.push(rechargeApi.listInsurerAccounts().then((r) => (insurerAccounts.value = r)))
      tasks.push(listEnterprises().then((r) => (enterprises.value = r)))
    }
    await Promise.all(tasks)
  } finally {
    loading.value = false
  }
}
onMounted(load)

const { page, pageSize, total: pagedTotal, paged } = usePagedList(requests)
const pendingCount = computed(() => requests.value.filter((r) => r.status === 'pending').length)

const STATUS_TEXT: Record<string, string> = { pending: '待确认', confirmed: '已到账', rejected: '已驳回' }
const STATUS_TYPE: Record<string, string> = { pending: 'warning', confirmed: 'success', rejected: 'danger' }

// ---- submit ----
const submitVisible = ref(false)
const submitForm = reactive({ enterprise_id: null as number | null, account_type: 'premium' as 'premium' | 'usage', insurer: '', amount: 0, file: null as File | null })
const matchedAccount = computed(() => {
  if (submitForm.account_type !== 'premium' || !submitForm.insurer.trim()) return null
  const link = insurerAccountLinks.value.find((l) => l.insurer === submitForm.insurer.trim())
  return link ? insurerAccounts.value.find((a) => a.id === link.account_id) ?? null : null
})
function openSubmit() {
  Object.assign(submitForm, { enterprise_id: auth.isEnterprise() ? auth.user?.enterprise_id ?? null : null, account_type: 'premium', insurer: '', amount: 0, file: null })
  submitVisible.value = true
}
function handleFileChange(e: Event) {
  const input = e.target as HTMLInputElement
  submitForm.file = input.files?.[0] ?? null
}
async function submitRecharge() {
  if (!submitForm.enterprise_id) { ElMessage.error('请选择投保单位'); return }
  if (submitForm.account_type === 'premium' && !submitForm.insurer.trim()) { ElMessage.error('请填写保司名称'); return }
  if (submitForm.amount <= 0) { ElMessage.error('请输入充值金额'); return }
  if (!submitForm.file) { ElMessage.error('请上传转账回单'); return }
  try {
    await rechargeApi.createRechargeRequest({
      enterprise_id: submitForm.enterprise_id,
      account_type: submitForm.account_type,
      insurer: submitForm.insurer.trim(),
      amount: submitForm.amount,
      file: submitForm.file,
    })
    ElMessage.success('充值申请已提交，等待平台确认到账')
    submitVisible.value = false
    load()
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}

// ---- admin review ----
async function confirmRequest(row: RechargeRequest) {
  try {
    await ElMessageBox.confirm(`确认「${row.enterprise_name}」的这笔 ${money(row.amount)} 已经到账吗？`, '确认到账', { type: 'warning' })
  } catch { return }
  try {
    await rechargeApi.confirmRechargeRequest(row.id)
    ElMessage.success('已确认到账')
    load()
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}
async function rejectRequest(row: RechargeRequest) {
  try {
    const { value } = await ElMessageBox.prompt('请填写驳回原因', '驳回充值申请', { inputValidator: (v) => !!v?.trim() || '驳回原因必填' })
    await rechargeApi.rejectRechargeRequest(row.id, value)
    ElMessage.success('已驳回')
    load()
  } catch (e) {
    if (e instanceof Error) ElMessage.error(e.message)
  }
}
</script>

<template>
  <div v-loading="loading" class="recharge-view">
    <div class="stat-grid">
      <StatTile label="待确认申请" :value="pendingCount" hint-type="warning" />
    </div>

    <PageCard title="充值记录" :count="requests.length">
      <template #actions>
        <el-button type="primary" @click="openSubmit">＋ 发起充值</el-button>
      </template>
      <el-table :data="paged" size="small">
        <el-table-column v-if="auth.isAdmin()" prop="enterprise_name" label="投保单位" min-width="140" />
        <el-table-column label="账户类型" width="100">
          <template #default="{ row }">{{ row.account_type === 'premium' ? '保费' : '使用费' }}</template>
        </el-table-column>
        <el-table-column prop="insurer" label="保司" min-width="120">
          <template #default="{ row }">{{ row.insurer || '—' }}</template>
        </el-table-column>
        <el-table-column label="金额" width="110">
          <template #default="{ row }">{{ money(row.amount) }}</template>
        </el-table-column>
        <el-table-column label="回单" width="90">
          <template #default="{ row }">
            <a v-if="row.receipt_download_url" :href="row.receipt_download_url" target="_blank" rel="noopener">查看</a>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="STATUS_TYPE[row.status]" size="small">{{ STATUS_TEXT[row.status] }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="驳回原因" min-width="160">
          <template #default="{ row }">{{ row.reject_reason || '—' }}</template>
        </el-table-column>
        <el-table-column label="提交时间" width="160">
          <template #default="{ row }">{{ formatDateTime(row.created_at) }}</template>
        </el-table-column>
        <el-table-column v-if="auth.isAdmin()" label="操作" width="140" fixed="right">
          <template #default="{ row }">
            <template v-if="row.status === 'pending'">
              <el-button link type="primary" size="small" @click="confirmRequest(row)">确认到账</el-button>
              <el-button link type="danger" size="small" @click="rejectRequest(row)">驳回</el-button>
            </template>
          </template>
        </el-table-column>
      </el-table>
      <TablePagination v-model:page="page" v-model:page-size="pageSize" :total="pagedTotal" />
    </PageCard>

    <el-dialog v-model="submitVisible" title="发起充值" width="480px">
      <el-form :model="submitForm" label-width="100px">
        <el-form-item v-if="auth.isAdmin()" label="投保单位" required>
          <el-select v-model="submitForm.enterprise_id" filterable placeholder="请选择">
            <el-option v-for="e in enterprises" :key="e.id" :label="e.name" :value="e.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="账户类型" required>
          <el-radio-group v-model="submitForm.account_type">
            <el-radio-button value="premium">保费</el-radio-button>
            <el-radio-button value="usage">使用费</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="submitForm.account_type === 'premium'" label="保司" required>
          <el-input v-model="submitForm.insurer" placeholder="请填写保险公司名称" />
        </el-form-item>
        <el-form-item v-if="matchedAccount" label="收款账户">
          <div class="account-hint">
            <p>{{ matchedAccount.bank_name }} · {{ matchedAccount.account_no }}</p>
            <p>户名：{{ matchedAccount.account_holder }}</p>
            <p v-if="matchedAccount.insurers.length > 1" class="muted">该账户同时用于：{{ matchedAccount.insurers.join('、') }}</p>
          </div>
        </el-form-item>
        <el-form-item label="充值金额" required><el-input-number v-model="submitForm.amount" :min="0.01" :step="100" style="width: 100%" /></el-form-item>
        <el-form-item label="转账回单" required><input type="file" accept=".pdf,.jpg,.jpeg,.png" @change="handleFileChange" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="submitVisible = false">取消</el-button>
        <el-button type="primary" @click="submitRecharge">提交</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.recharge-view {
  display: grid;
  gap: 18px;
}
.stat-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
  gap: 14px;
}
.account-hint {
  font-size: 12.5px;
  line-height: 1.7;
  color: var(--el-text-color-regular);
}
.muted {
  color: var(--el-text-color-placeholder);
}
</style>
```

（企业角色不会有 `listInsurerAccounts()`/`listEnterprises()` 数据，所以 `matchedAccount` computed 里 `insurerAccounts.value` 为空数组——企业端提交时只填保司名，看不到"该账户同时用于哪些保司"这行提示，这是可接受的降级：后端仍然正确解析账户，只是提交前的"账户同名保司"提示只在管理员视角完整可用。企业自己发起充值不需要预先看到,提交后从充值记录列表里能看到状态。）

- [ ] **Step 3: 类型检查**

Run: `cd web && npx vue-tsc --noEmit`
Expected: `RechargeCenterView.vue`/`routes.ts` 不报错（`HomeView.vue`/`ScreenView.vue` 仍会报错，留给 Task 13/14）

- [ ] **Step 4: 手动验证**

Run: `cd web && npm run dev`
Expected: 侧边栏出现"账户充值"入口；企业账号登录后能提交一笔充值（选保费+填保司+上传文件）；管理员账号登录后能在列表里看到并确认/驳回

- [ ] **Step 5: Commit**

```bash
git add web/src/views/recharge/RechargeCenterView.vue web/src/router/routes.ts
git commit -m "feat: recharge center page for enterprise submission and admin review"
```

---

### Task 13: 首页余额展示改造

**Files:**
- Modify: `web/src/views/dashboard/HomeView.vue`

**Interfaces:**
- Consumes: `DashboardData.premium_accounts`（Task 9/10）

- [ ] **Step 1: 实现**

打开 `web/src/views/dashboard/HomeView.vue`，把

```html
      <StatTile label="保费账户余额" :value="data ? money(data.premium_balance) : '—'" />
      <StatTile label="服务费账户余额" :value="data ? money(data.usage_balance) : '—'" />
    </div>
```

替换为：

```html
      <StatTile label="服务费账户余额" :value="data ? money(data.usage_balance) : '—'" />
    </div>

    <PageCard title="保费账户余额" :hint="isAdmin ? '按收款账户汇总' : ''">
      <el-table :data="data?.premium_accounts ?? []" size="small" style="width: 100%">
        <el-table-column label="账户/保司" min-width="200">
          <template #default="{ row }">
            <div>{{ row.label || '未命名账户' }}</div>
            <small class="muted">{{ row.insurers.join('、') }}</small>
          </template>
        </el-table-column>
        <el-table-column label="余额" width="140">
          <template #default="{ row }">{{ money(row.balance) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="100">
          <template #default>
            <el-button link type="primary" size="small" @click="router.push({ name: 'recharge' })">去充值</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="data && !data.premium_accounts.length" description="暂无保费账户" :image-size="60" />
    </PageCard>
```

（`.muted` class 目前这个文件的 `<style scoped>` 里没有定义，需要在 `<style scoped>` 块末尾加入：）

```css
.muted {
  color: var(--el-text-color-placeholder);
  font-size: 11.5px;
}
```

`balance_alerts` 表格那一段（`账户余额预警明细`）不用改结构，只是数据源里 premium 类型的行现在多了 `account_id`/`label` 字段，既有的 `row.account === 'premium' ? '保费账户' : '服务费账户'` 判断逻辑不受影响，继续按 `account` 字段区分即可。

- [ ] **Step 2: 类型检查**

Run: `cd web && npx vue-tsc --noEmit`
Expected: `HomeView.vue` 不再报错（`ScreenView.vue` 留给 Task 14）

- [ ] **Step 3: 手动验证**

Run: `cd web && npm run dev`
Expected: 首页能看到按账户列出的保费余额，"去充值"按钮跳转到 `/recharge`

- [ ] **Step 4: Commit**

```bash
git add web/src/views/dashboard/HomeView.vue
git commit -m "feat: home page premium balance broken down by account"
```

---

### Task 14: 经营大屏余额健康度展示

**Files:**
- Modify: `web/src/views/dashboard/ScreenView.vue`

**Interfaces:**
- Consumes: `DashboardData.premium_accounts`/`usage_balance`/`balance_alerts`（Task 9/10）

- [ ] **Step 1: 实现**

打开 `web/src/views/dashboard/ScreenView.vue`，在 `<div class="panel-row">`（第一个，保费规模 Top 8 产品 + 在保人次按保司分布 所在的那个）之后插入一个新的 `panel-row`：

```html
    <div class="panel-row">
      <section class="panel">
        <h2>账户余额健康度</h2>
        <div v-if="dashboard?.premium_accounts.length" class="balance-list">
          <div v-for="row in dashboard.premium_accounts" :key="row.account_id" class="balance-row">
            <span class="balance-label">{{ row.label || '未命名账户' }}<small>{{ row.insurers.join('、') }}</small></span>
            <span class="balance-amount">{{ money(row.balance) }}</span>
          </div>
        </div>
        <p v-else class="empty">暂无数据</p>
      </section>
      <section class="panel">
        <h2>低余额预警</h2>
        <div v-if="dashboard?.balance_alerts.length" class="balance-list">
          <div v-for="alert in dashboard.balance_alerts" :key="`${alert.enterprise_id}-${alert.account}-${alert.account_id ?? ''}`" class="balance-row">
            <span class="balance-label">{{ alert.enterprise_name }}<small>{{ alert.account === 'premium' ? (alert.label || '保费账户') : '服务费账户' }}</small></span>
            <span :class="['balance-amount', alert.level === 'critical' ? 'critical' : 'warning']">{{ alert.days_left }} 天</span>
          </div>
        </div>
        <p v-else class="empty">暂无预警</p>
      </section>
    </div>
```

在 `<style scoped>` 块里加入（找到既有的 `.panel`/`.empty` 定义附近插入）：

```css
.balance-list {
  display: grid;
  gap: 10px;
  max-height: 320px;
  overflow-y: auto;
}
.balance-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 8px 0;
  border-bottom: 1px solid rgba(255, 255, 255, 0.08);
}
.balance-label {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.balance-label small {
  color: var(--el-text-color-placeholder, #8a94a6);
  font-size: 11px;
}
.balance-amount {
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}
.balance-amount.critical {
  color: #ef6e76;
}
.balance-amount.warning {
  color: #f39b50;
}
```

- [ ] **Step 2: 类型检查**

Run: `cd web && npx vue-tsc --noEmit`
Expected: 命令成功退出，全仓库无类型错误（这是本计划最后一次改前端文件，所有 Task 10 引入的类型变更此时应该已经在所有消费者处更新完毕）

- [ ] **Step 3: 手动验证**

Run: `cd web && npm run dev`
Expected: 经营大屏能看到"账户余额健康度"和"低余额预警"两个新面板

- [ ] **Step 4: Commit**

```bash
git add web/src/views/dashboard/ScreenView.vue
git commit -m "feat: screen dashboard balance health panels"
```

---

### Task 15: 完整回归验证

**Files:**
- Test: `tests/recharge_smoke.py`, `tests/system_smoke.py`, `tests/security_smoke.py`

**Interfaces:**
- Consumes: 全部前置任务

- [ ] **Step 1: 跑本计划的冒烟测试**

Run: `python3 tests/recharge_smoke.py`
Expected: `recharge smoke: ok`

- [ ] **Step 2: 确认既有测试没有因为本计划的改动而回归**

Run: `python3 tests/system_smoke.py`
Expected: 在到达 `add_person(PersonIn(..., id_number="340123199001019999", ...))` 这一行时，跟本计划开始之前一样，仍然是同一个既存的、与本计划无关的身份证校验失败（`HTTPException: 400: 身份证号格式不正确`）——**如果错误信息变了、或者错误发生的位置变了，说明本计划的改动引入了新的回归，需要排查**。这个既存 bug 不在本计划范围内，不需要在这里修。

Run: `python3 tests/security_smoke.py`
Expected: 同上，跟本计划开始之前的失败情况完全一致（如果本来就失败，位置和原因不能因为本计划的改动而改变；如果本来就通过，必须继续通过）

- [ ] **Step 3: 前端完整类型检查 + 构建**

Run: `cd web && npx vue-tsc --noEmit && npm run build`
Expected: 类型检查通过，`vite build` 成功产出 `web/dist/`

- [ ] **Step 4: 本地端到端手动过一遍完整流程**

Run: `./start.sh`（后端）+ `cd web && npm run dev`（前端），浏览器操作：
1. 管理员登录 → 保险公司页 → 新增一个收款账户，绑定两个不同的保司名称到同一个账户
2. 企业账号登录 → 账户充值 → 选保费 → 填第一个保司名 → 确认页面显示的收款账户信息正确 → 填金额、上传任意图片文件 → 提交
3. 管理员账号登录 → 账户充值 → 在列表里看到刚才那笔待确认申请 → 点"确认到账"
4. 企业账号登录 → 首页 → 保费账户余额里能看到刚才充值的金额，账户下方显示两个保司名称
5. 管理员账号登录 → 经营大屏 → 账户余额健康度面板能看到同一笔余额

Expected: 全流程无报错，每一步的数据跟操作一致

- [ ] **Step 5: Commit（如果手动验证过程中发现并修复了任何问题）**

```bash
git add -A
git commit -m "fix: address issues found during end-to-end verification"
```

（如果第 4 步没有发现任何问题，这一步跳过，不创建空提交。）

---

## Self-Review Notes

- **Spec 覆盖**：设计文档"数据模型"（账户池化模型、5 张表）→ Task 1-3；"API 设计"里的收款账户管理/充值申请/余额查询三组端点 → Task 6-8；"旧接口"保留不动 → 没有任何任务改动 `POST /enterprises/{id}/recharge`，符合设计；"前端设计"里企业端充值页/首页/大屏/平台端收款账户管理 → Task 11-14。设计文档里的"使用费不足锁定""保费不足待确认停保""短信通知""参保年龄限制"三块**不在本计划范围内**（已在 Global Constraints 里明确标注为后续阶段）。
- **占位符检查**：全部任务的每个 Step 都是可直接执行的命令或可直接粘贴的完整代码，没有"实现类似逻辑""补充测试"这类模糊指代。
- **类型一致性**：`premium_accounts_for_enterprise()` 返回的 `{account_id, label, insurers, balance}` 形状在 Task 4 定义后，在 Task 8（企业端点直接透传）、Task 9（dashboard 聚合复用同一字段名）、Task 10（`PremiumAccountRow` TS 接口逐字段对应）、Task 13（HomeView 表格列绑定同名字段）里保持完全一致，没有出现字段改名不同步的情况。`resolve_account_for_insurer`/`get_or_create_premium_account` 的函数名和参数顺序从 Task 4 定义到 Task 7/9 使用处保持一致。
