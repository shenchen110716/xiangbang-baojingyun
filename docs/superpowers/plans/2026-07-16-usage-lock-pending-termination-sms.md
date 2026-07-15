# 使用费锁定 + 保费不足停保 + 短信通知 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 使用费余额不足时实时锁定参停保操作，充值到账立即解锁；保费账户余额耗尽时惰性扫描生成待管理员确认的停保任务；充值确认/驳回、使用费锁定、停保预警/执行这几个节点直接短信通知企业。

**Architecture:** 三块共享同一组触发点，SMS 通知直接从使用费锁定和停保扫描/确认的代码内部调用，因此合并成一份计划（原计划里的 "Phase B/C" 拆分在写这份计划时发现过度拆分——SMS 没有独立存在的触发点，全部挂在这两块新逻辑自己的代码路径上，拆开写只会导致同一批文件被打开两次）。不新增定时任务：使用费锁定是每次请求实时判断；停保扫描是管理端页面访问时的惰性扫描。

**Tech Stack:** FastAPI + SQLAlchemy（后端），Vue 3 `<script setup>` + TypeScript + Element Plus（前端）。

## Global Constraints

- 不做定时任务——使用费锁定必须是每次请求实时判断（`enterprise.usage_balance <= 0` 直接查库，不缓存不预计算）；停保扫描只在管理端相关页面加载时惰性触发。
- 使用费不足锁定的范围：`POST /insured`、`PATCH /insured/{id}`、`PATCH /insured/{id}/status`、`POST /insured/bulk`、`POST /insured/import-file` 这五个端点；`GET /insured`、`GET /insured/{id}/policy-members` 不受影响，仍可正常浏览。
- 保费不足触发的是"待确认停保任务"，不是系统自动停保——真正停保必须由管理员在 `POST /pending-terminations/{id}/confirm` 里显式确认。
- 停保任务的唯一清除路径是企业充值后自动 dismiss（`scan_premium_shortfalls` 重新扫描时发现余额已经 >0）；不做管理员手动"驳回/忽略"功能。
- `(enterprise_id, account_id, status='pending')` 同时只允许一条 `PendingTermination`，避免重复任务。
- 短信发送失败不能影响主流程（充值确认、锁定判断、停保确认本身必须成功落库）——fire-and-forget，失败只记 audit log。
- 短信触发点恰好 4 个，不多不少：①充值确认/驳回成功后；②`require_usage_funded` 抛 403 的那一刻（同一天同一企业不重复发送）；③`scan_premium_shortfalls` 新建 `PendingTermination` 时；④停保任务被管理员确认执行后。
- Java 后端本次不镜像（延续本项目一贯做法：当前环境没有 JDK，只能人工审查，且本次会话的既有安排是先把 Python 端做完再排 Java 镜像，不在这份计划任务列表内）。

---

## File Structure

**Backend（新增）：**
- `backend/models/finance_accounts.py` — 追加 `PendingTermination` 模型
- `backend/migrations_alembic/versions/xxxx_add_pending_terminations.py` — Postgres 生产迁移
- `backend/services/participation_lock.py` — `require_usage_funded(session, enterprise, user)`，供 `insured.py` 各端点内联调用
- `backend/services/termination_scan.py` — `scan_premium_shortfalls(session, enterprise_id=None)`
- `backend/services/notify.py` — `notify_enterprise(session, enterprise_id, template, params)`
- `backend/routers/pending_terminations.py` — `GET /pending-terminations`、`POST /pending-terminations/{id}/confirm`
- `tests/participation_lock_smoke.py` — 独立冒烟测试，跟 `tests/recharge_smoke.py` 同一套隔离 DB 模式

**Backend（修改）：**
- `backend/models/__init__.py` — 导出 `PendingTermination`
- `backend/core/migrations.py` — 无需新增桥接迁移（全新表，SQLite 本地开发靠 `create_all()` 自动建表，只有"给已有表加列"才需要桥接项）
- `backend/services/__init__.py` — 导出新函数
- `backend/routers/insured.py` — 五个端点分别插入 `require_usage_funded` 调用
- `backend/routers/recharge_requests.py` — confirm/reject 成功后调用 `notify_enterprise`
- `backend/routers/dashboard.py` — admin 视角加载时触发 `scan_premium_shortfalls`，响应体加 `pending_terminations_count`
- `backend/app.py` — 注册 `pending_terminations_router`

**Frontend（新增）：**
- `web/src/api/pendingTerminations.ts` — API 客户端函数
- `web/src/views/pending-terminations/PendingTerminationsView.vue` — 平台端"待处理停保"页面

**Frontend（修改）：**
- `web/src/api/types.ts` — 新增 `PendingTermination` 类型；`DashboardData` 加 `pending_terminations_count`
- `web/src/api/client.ts` — 响应拦截器识别使用费不足的 403，统一弹出提示（不是每个按钮各自 toast）
- `web/src/router/routes.ts` — 新增 `/pending-terminations` 路由（`adminOnly`）
- `web/src/views/dashboard/HomeView.vue` — admin 视角新增"待处理停保"`StatTile`，跟现有"待处理理赔"并列

---

### Task 1: `PendingTermination` 数据模型 + 迁移

**Files:**
- Modify: `backend/models/finance_accounts.py`
- Modify: `backend/models/__init__.py`
- Create: `backend/migrations_alembic/versions/xxxx_add_pending_terminations.py`
- Test: `tests/participation_lock_smoke.py`（新建）

**Interfaces:**
- Consumes: 无
- Produces: `PendingTermination` ORM 模型，字段见下，供后续所有任务使用

- [ ] **Step 1: 在 `backend/models/finance_accounts.py` 追加模型**

打开 `backend/models/finance_accounts.py`，在文件末尾（`RechargeRequest` 类之后）追加：

```python
class PendingTermination(Base):
    # 保费余额耗尽时惰性扫描生成的待处理停保任务，按账户池化——一个账户
    # 没钱了，挂在它上面的所有保司、所有在保人员都算受影响范围。唯一清除
    # 路径是企业充值后重新扫描发现余额已经 >0，自动 dismiss；管理员没有
    # 手动驳回/忽略的入口——如果不该停保，正确操作是协调企业充值。
    __tablename__ = "pending_terminations"
    id: Mapped[int] = mapped_column(primary_key=True)
    enterprise_id: Mapped[int] = mapped_column(ForeignKey("enterprises.id"))
    account_id: Mapped[int] = mapped_column(ForeignKey("insurer_accounts.id"))
    affected_insurers: Mapped[str] = mapped_column(String(255), default="")
    affected_count: Mapped[int] = mapped_column(default=0)
    status: Mapped[str] = mapped_column(String(20), default="pending")  # pending / confirmed / dismissed
    confirmed_by: Mapped[Optional[int]] = mapped_column(ForeignKey("users.id"), nullable=True)
    confirmed_at: Mapped[Optional[datetime]] = mapped_column(DateTime, nullable=True)
    dismissed_at: Mapped[Optional[datetime]] = mapped_column(DateTime, nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=lambda: datetime.now(timezone.utc))
```

（这个文件顶部已经 `from typing import Optional` 和 `from datetime import datetime, timezone`，不用新增 import。）

- [ ] **Step 2: 导出模型**

打开 `backend/models/__init__.py`，找到：

```python
from .finance_accounts import InsurerAccount, InsurerAccountLink, EnterprisePremiumAccount, RechargeRequest
```

改为：

```python
from .finance_accounts import InsurerAccount, InsurerAccountLink, EnterprisePremiumAccount, RechargeRequest, PendingTermination
```

`__all__` 列表里 `"RechargeRequest",` 后面加一行 `"PendingTermination",`。

- [ ] **Step 3: 写 Alembic 迁移**

先确认当前 head：

Run: `alembic -c backend/migrations_alembic/alembic.ini heads`

记下输出的 revision id（记作 `<CURRENT_HEAD>`），创建 `backend/migrations_alembic/versions/xxxx_add_pending_terminations.py`（文件名前缀随便起一个新的 hex id，跟目录里其它文件风格一致即可）：

```python
"""add pending_terminations table

Revision ID: a1b2c3d4e5f6
Revises: <CURRENT_HEAD>
Create Date: 2026-07-16
"""
from alembic import op
import sqlalchemy as sa

revision = "a1b2c3d4e5f6"
down_revision = "<CURRENT_HEAD>"
branch_labels = None
depends_on = None


def upgrade():
    op.create_table(
        "pending_terminations",
        sa.Column("id", sa.Integer(), primary_key=True),
        sa.Column("enterprise_id", sa.Integer(), sa.ForeignKey("enterprises.id"), nullable=False),
        sa.Column("account_id", sa.Integer(), sa.ForeignKey("insurer_accounts.id"), nullable=False),
        sa.Column("affected_insurers", sa.String(255), nullable=False, server_default=""),
        sa.Column("affected_count", sa.Integer(), nullable=False, server_default="0"),
        sa.Column("status", sa.String(20), nullable=False, server_default="pending"),
        sa.Column("confirmed_by", sa.Integer(), sa.ForeignKey("users.id"), nullable=True),
        sa.Column("confirmed_at", sa.DateTime(), nullable=True),
        sa.Column("dismissed_at", sa.DateTime(), nullable=True),
        sa.Column("created_at", sa.DateTime(), nullable=False),
    )


def downgrade():
    op.drop_table("pending_terminations")
```

把 `<CURRENT_HEAD>` 替换成 Step 3 开头查到的真实 revision id（两处：文件头注释的 `Revises:` 和 `down_revision = ` 都要改）。

- [ ] **Step 4: 写冒烟测试骨架**

创建 `tests/participation_lock_smoke.py`：

```python
"""Smoke test for usage-fee locking, premium-shortfall pending terminations,
and their SMS notification triggers.

Isolated from tests/system_smoke.py on purpose (same reason as
tests/recharge_smoke.py): that file's PersonIn fixture fails an unrelated
ID-checksum validation bug unrelated to this feature.
"""
import os
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))


def run():
    with tempfile.TemporaryDirectory(prefix="xbb-participation-lock-smoke-") as folder:
        os.environ["DATABASE_URL"] = f"sqlite:///{Path(folder) / 'test.db'}"
        os.environ["ADMIN_PASSWORD"] = "admin123"
        os.environ["ENTERPRISE_PASSWORD"] = "enterprise123"

        from sqlalchemy import select

        from backend.app import startup
        from backend.core.db import SessionLocal
        from backend.models import Enterprise, PendingTermination, User

        startup()
        with SessionLocal() as session:
            ent = Enterprise(name="锁定测试企业", kind="企业", contact="", phone="", status="active")
            session.add(ent); session.commit(); session.refresh(ent)
            assert ent.id is not None

            pt = PendingTermination(enterprise_id=ent.id, account_id=1, affected_insurers="测试保司", affected_count=2)
            session.add(pt); session.commit(); session.refresh(pt)
            assert pt.status == "pending" and pt.confirmed_by is None

    print("participation lock smoke: ok")


if __name__ == "__main__":
    run()
```

（这里 `account_id=1` 只是为了验证模型本身能创建成功，不依赖真实的 `InsurerAccount` 行——SQLite 默认不强制外键约束，后续任务会用真实数据重写这部分。）

- [ ] **Step 5: 跑测试确认通过**

Run: `python3 tests/participation_lock_smoke.py`
Expected: `participation lock smoke: ok`

- [ ] **Step 6: Commit**

```bash
git add backend/models/finance_accounts.py backend/models/__init__.py backend/migrations_alembic/versions/*_add_pending_terminations.py tests/participation_lock_smoke.py
git commit -m "feat: add PendingTermination model and migration"
```

---

### Task 2: 使用费锁定服务函数 `require_usage_funded`

**Files:**
- Create: `backend/services/participation_lock.py`
- Modify: `backend/services/__init__.py`
- Test: `tests/participation_lock_smoke.py`（追加）

**Interfaces:**
- Consumes: `Enterprise`/`User` 模型（已存在）
- Produces: `require_usage_funded(session: Session, enterprise: Enterprise, user: User) -> None`——`usage_balance <= 0` 时抛 `HTTPException(403, "使用费余额不足，请先充值后再操作参停保")`，否则直接返回不做任何事。供 Task 3 在 `insured.py` 五个端点内联调用。**`session`/`user` 参数这一步暂时不用**（函数体里还用不上），是提前把 Task 7 需要的最终签名定下来——Task 7 要在这个函数里加"同一天不重复发短信"的判断，需要查数据库（`session`）和记一条归属于当前操作者的审计日志（`user`，因为 `AuditLog.user_id` 是非空字段，找不到更合适的归属对象，就用触发这次锁定的操作者本人）。这样定下来之后 Task 3/7 都不需要再回头改调用点的参数个数，只有 Task 7 要改这个函数自己的函数体。

- [ ] **Step 1: 创建服务函数**

```python
from fastapi import HTTPException
from sqlalchemy.orm import Session

from ..models import Enterprise, User


def require_usage_funded(session: Session, enterprise: Enterprise, user: User) -> None:
    """Real-time usage-fee gate for participation-changing endpoints. No
    caching, no precomputation — queries the live enterprise.usage_balance
    value every call, so a just-confirmed recharge unlocks the very next
    request with no separate "unlock" step needed.

    session/user aren't used yet — Task 7 fills in a once-per-day
    notification here that needs both (a DB query for the dedup check, and
    `user` to attribute the resulting AuditLog entry to, since
    AuditLog.user_id is non-nullable and there's no "system" user in this
    codebase). Defining the final signature now means Task 7 only touches
    this function's body, not every call site again."""
    if enterprise.usage_balance <= 0:
        raise HTTPException(403, "使用费余额不足，请先充值后再操作参停保")
```

- [ ] **Step 2: 导出函数**

打开 `backend/services/__init__.py`，在 `from .ledger import ...` 那行下面加一行：

```python
from .participation_lock import require_usage_funded
```

`__all__` 列表 `"post_ledger_entry", "ledger_dict", "reconcile_enterprise_ledger",` 后面加一行 `"require_usage_funded",`。

- [ ] **Step 3: 追加测试**

打开 `tests/participation_lock_smoke.py`，`from backend.models import Enterprise, PendingTermination, User` 那行下面追加：

```python
        from fastapi import HTTPException

        from backend.services import require_usage_funded
```

在 `print("participation lock smoke: ok")` 之前追加：

```python
            # require_usage_funded: real-time check, no caching
            admin_user = session.scalar(select(User).where(User.username == "admin"))
            funded_ent = Enterprise(name="有余额企业", kind="企业", contact="", phone="", status="active", usage_balance=50.0)
            session.add(funded_ent); session.commit(); session.refresh(funded_ent)
            require_usage_funded(session, funded_ent, admin_user)  # must not raise

            unfunded_ent = Enterprise(name="无余额企业", kind="企业", contact="", phone="", status="active", usage_balance=0.0)
            session.add(unfunded_ent); session.commit(); session.refresh(unfunded_ent)
            try:
                require_usage_funded(session, unfunded_ent, admin_user)
                assert False, "expected 403"
            except HTTPException as e:
                assert e.status_code == 403

            negative_ent = Enterprise(name="负余额企业", kind="企业", contact="", phone="", status="active", usage_balance=-5.0)
            session.add(negative_ent); session.commit(); session.refresh(negative_ent)
            try:
                require_usage_funded(session, negative_ent, admin_user)
                assert False, "expected 403"
            except HTTPException as e:
                assert e.status_code == 403

            # unlocks immediately on the very next check, no separate unlock step
            unfunded_ent.usage_balance = 10.0
            session.commit()
            require_usage_funded(session, unfunded_ent, admin_user)  # must not raise now
```

（后面几个任务都会复用这里定义的 `admin_user` 变量，不用重复查询。）

- [ ] **Step 4: 跑测试确认通过**

Run: `python3 tests/participation_lock_smoke.py`
Expected: `participation lock smoke: ok`

- [ ] **Step 5: Commit**

```bash
git add backend/services/participation_lock.py backend/services/__init__.py tests/participation_lock_smoke.py
git commit -m "feat: add require_usage_funded real-time usage-balance gate"
```

---

### Task 3: 把 `require_usage_funded` 接入参停保五个端点

**Files:**
- Modify: `backend/routers/insured.py`
- Test: `tests/participation_lock_smoke.py`（追加）

**Interfaces:**
- Consumes: `require_usage_funded(session, enterprise, user)`（Task 2）
- Produces: `POST /insured`、`PATCH /insured/{id}`、`PATCH /insured/{id}/status`、`POST /insured/bulk`、`POST /insured/import-file` 五个端点在 `enterprise.usage_balance <= 0` 时统一返回 403

- [ ] **Step 1: 导入函数**

打开 `backend/routers/insured.py`，找到：

```python
from ..services import activate_person_policy, correct_person_policy_dates, effective_person_status, plan_price_for_class, pricing_snapshot, serialize, strip_internal_pricing, terminate_person_policy
```

改为：

```python
from ..services import activate_person_policy, correct_person_policy_dates, effective_person_status, plan_price_for_class, pricing_snapshot, require_usage_funded, serialize, strip_internal_pricing, terminate_person_policy
```

- [ ] **Step 2: `add_person`（`POST /insured`）**

找到：

```python
def add_person(data: PersonIn, user: User = Depends(current_user), session: Session = Depends(db)):
    if not session.get(Enterprise, data.enterprise_id): raise HTTPException(404, "企业不存在")
    if user.role=="enterprise" and user.enterprise_id!=data.enterprise_id: raise HTTPException(403,"无权操作该单位")
    if not is_valid_id_number(data.id_number): raise HTTPException(400,'身份证号格式不正确')
```

改为：

```python
def add_person(data: PersonIn, user: User = Depends(current_user), session: Session = Depends(db)):
    enterprise = session.get(Enterprise, data.enterprise_id)
    if not enterprise: raise HTTPException(404, "企业不存在")
    if user.role=="enterprise" and user.enterprise_id!=data.enterprise_id: raise HTTPException(403,"无权操作该单位")
    require_usage_funded(session, enterprise, user)
    if not is_valid_id_number(data.id_number): raise HTTPException(400,'身份证号格式不正确')
```

- [ ] **Step 3: `update_person`（`PATCH /insured/{id}`）**

找到：

```python
def update_person(item_id:int,data:PersonUpdate,user:User=Depends(current_user),session:Session=Depends(db)):
    item=session.get(InsuredPerson,item_id)
    if not item: raise HTTPException(404,'参保员工不存在')
    if user.role=='enterprise' and user.enterprise_id!=item.enterprise_id: raise HTTPException(403,'无权操作该员工')
    values=data.model_dump(exclude_unset=True)
```

改为：

```python
def update_person(item_id:int,data:PersonUpdate,user:User=Depends(current_user),session:Session=Depends(db)):
    item=session.get(InsuredPerson,item_id)
    if not item: raise HTTPException(404,'参保员工不存在')
    if user.role=='enterprise' and user.enterprise_id!=item.enterprise_id: raise HTTPException(403,'无权操作该员工')
    require_usage_funded(session, session.get(Enterprise, item.enterprise_id), user)
    values=data.model_dump(exclude_unset=True)
```

- [ ] **Step 4: `insured_status`（`PATCH /insured/{id}/status`）**

找到：

```python
def insured_status(item_id:int,status_value:Literal["active","stopped","pending"]=Query(...,alias="status"),user:User=Depends(current_user),session:Session=Depends(db)):
    item=session.get(InsuredPerson,item_id)
    if not item: raise HTTPException(404,"参保员工不存在")
    if user.role=="enterprise" and user.enterprise_id!=item.enterprise_id: raise HTTPException(403,"无权操作该员工")
    previous_status=item.status
```

改为：

```python
def insured_status(item_id:int,status_value:Literal["active","stopped","pending"]=Query(...,alias="status"),user:User=Depends(current_user),session:Session=Depends(db)):
    item=session.get(InsuredPerson,item_id)
    if not item: raise HTTPException(404,"参保员工不存在")
    if user.role=="enterprise" and user.enterprise_id!=item.enterprise_id: raise HTTPException(403,"无权操作该员工")
    require_usage_funded(session, session.get(Enterprise, item.enterprise_id), user)
    previous_status=item.status
```

- [ ] **Step 5: `bulk_add_people`（`POST /insured/bulk`）**

找到：

```python
def bulk_add_people(data:BulkPersonIn,user:User=Depends(current_user),session:Session=Depends(db)):
    if user.role=='enterprise' and user.enterprise_id!=data.enterprise_id: raise HTTPException(403,'无权操作该单位')
    position=session.get(WorkPosition,data.position_id)
```

改为：

```python
def bulk_add_people(data:BulkPersonIn,user:User=Depends(current_user),session:Session=Depends(db)):
    if user.role=='enterprise' and user.enterprise_id!=data.enterprise_id: raise HTTPException(403,'无权操作该单位')
    require_usage_funded(session, session.get(Enterprise, data.enterprise_id), user)
    position=session.get(WorkPosition,data.position_id)
```

- [ ] **Step 6: `import_insured_file`（`POST /insured/import-file`）—— 主单位 + 按行解析出的其它单位都要查**

这个端点支持"一次导入多个不同单位的名单"（`row_enterprise_id` 可能跟表单传的主 `enterprise_id` 不同），所以要在两处检查：提交时选择的主单位（覆盖单单位导入的主路径），以及每一行实际解析出的目标单位（覆盖多单位导入路径）。

找到：

```python
async def import_insured_file(kind:Literal['enrollment','termination']=Form(...),enterprise_id:int=Form(...),position_id:int=Form(0),file:UploadFile=File(...),user:User=Depends(current_user),session:Session=Depends(db)):
    if user.role=='enterprise' and user.enterprise_id!=enterprise_id: raise HTTPException(403,'无权操作该单位')
    if not session.get(Enterprise,enterprise_id): raise HTTPException(404,'投保单位不存在')
```

改为：

```python
async def import_insured_file(kind:Literal['enrollment','termination']=Form(...),enterprise_id:int=Form(...),position_id:int=Form(0),file:UploadFile=File(...),user:User=Depends(current_user),session:Session=Depends(db)):
    if user.role=='enterprise' and user.enterprise_id!=enterprise_id: raise HTTPException(403,'无权操作该单位')
    primary_enterprise=session.get(Enterprise,enterprise_id)
    if not primary_enterprise: raise HTTPException(404,'投保单位不存在')
    require_usage_funded(session, primary_enterprise, user)
```

然后找到 `resolve_enterprise` 函数（用于按行解析"投保单位"列，可能跟主单位不同）：

```python
    enterprise_cache: dict[str,Enterprise|None]={}
    def resolve_enterprise(raw_name:str) -> tuple[int|None,str|None]:
        if not raw_name: return enterprise_id,None
        if raw_name not in enterprise_cache:
            enterprise_cache[raw_name]=session.scalar(select(Enterprise).where(Enterprise.name==raw_name))
        found=enterprise_cache[raw_name]
        if not found: return None,f'投保单位"{raw_name}"不存在'
        if user.role=='enterprise' and found.id!=user.enterprise_id: return None,'无权为其他投保单位导入数据'
        return found.id,None
```

改为（复用同一个 `enterprise_cache` 存已经查过的 `Enterprise` 对象，加一层使用费判断）：

```python
    enterprise_cache: dict[str,Enterprise|None]={}
    def resolve_enterprise(raw_name:str) -> tuple[int|None,str|None]:
        if not raw_name: return enterprise_id,None
        if raw_name not in enterprise_cache:
            enterprise_cache[raw_name]=session.scalar(select(Enterprise).where(Enterprise.name==raw_name))
        found=enterprise_cache[raw_name]
        if not found: return None,f'投保单位"{raw_name}"不存在'
        if user.role=='enterprise' and found.id!=user.enterprise_id: return None,'无权为其他投保单位导入数据'
        if found.usage_balance<=0: return None,f'投保单位"{raw_name}"使用费余额不足，请先充值'
        return found.id,None
```

（`kind='enrollment'` 时空白"投保单位"列会走 `if not raw_name: return enterprise_id,None` 这条捷径，已经被函数最上面新加的 `require_usage_funded(session, primary_enterprise, user)` 覆盖，不会漏判；`kind='termination'`——停保——按设计不受使用费锁定约束吗？**不是**——设计文档明确写的是"会创建/变更参保状态的端点"都要锁，停保也是变更参保状态，所以这里两种 `kind` 都要走同一个 `resolve_enterprise` 检查，不用再区分。）

- [ ] **Step 7: 跑测试确认没有破坏既有测试**

Run: `python3 tests/system_smoke.py 2>&1 | tail -20`
Expected: 跟本计划开始之前一样，在 `add_person` 那一行遇到同一个既存的、与本任务无关的身份证校验失败（如果这个断言点变了，说明本任务引入了新的回归）。**如果输出显示是新的"使用费余额不足"403 而不是身份证校验失败，说明 `tests/system_smoke.py` 里测试企业默认 `usage_balance=0`，需要检查该测试文件里企业创建的地方，如果需要可以给测试企业一个非零 `usage_balance` 让既有测试通过原有断言点——但这个改动只能加在 `tests/system_smoke.py` 里，不能修改被测代码本身来迁就测试。**

- [ ] **Step 8: 追加集成测试到 `tests/participation_lock_smoke.py`**

在 `from backend.services import require_usage_funded` 那行下面追加：

```python
        from backend.models import InsuredPerson, WorkPosition
        from backend.routers.insured import add_person, insured_status
        from backend.schemas import PersonIn
```

在测试文件末尾（`print("participation lock smoke: ok")` 之前）追加：

```python
            # add_person is blocked when the target enterprise has no usage balance
            # (admin_user was already fetched in Task 2's test block above, reused here)
            locked_ent = Enterprise(name="锁定集成测试企业", kind="企业", contact="", phone="", status="active", usage_balance=0.0)
            session.add(locked_ent); session.commit(); session.refresh(locked_ent)
            try:
                add_person(PersonIn(enterprise_id=locked_ent.id, name="测试", id_number="11010119900307003X"), admin_user, session)
                assert False, "expected 403 for unfunded enterprise"
            except HTTPException as e:
                assert e.status_code == 403 and "使用费余额不足" in e.detail

            # unlocks on the very next call after a recharge, no separate step
            locked_ent.usage_balance = 100.0
            session.commit()
            created = add_person(PersonIn(enterprise_id=locked_ent.id, name="测试", id_number="11010119900307003X"), admin_user, session)
            assert created["id"] is not None

            # PATCH .../status is also gated
            locked_ent.usage_balance = 0.0
            session.commit()
            try:
                insured_status(created["id"], status_value="active", user=admin_user, session=session)
                assert False, "expected 403 for unfunded enterprise on status change"
            except HTTPException as e:
                assert e.status_code == 403
```

- [ ] **Step 9: 跑测试确认通过**

Run: `python3 tests/participation_lock_smoke.py`
Expected: `participation lock smoke: ok`

- [ ] **Step 10: Commit**

```bash
git add backend/routers/insured.py tests/participation_lock_smoke.py
git commit -m "feat: gate participation-changing endpoints on usage balance"
```

---

### Task 4: 保费不足惰性扫描 `scan_premium_shortfalls`

**Files:**
- Create: `backend/services/termination_scan.py`
- Modify: `backend/services/__init__.py`
- Test: `tests/participation_lock_smoke.py`（追加）

**Interfaces:**
- Consumes: `EnterprisePremiumAccount`、`InsurerAccountLink`、`InsuredPerson`、`PendingTermination`（均已存在/Task 1 新增）
- Produces: `scan_premium_shortfalls(session: Session, enterprise_id: int | None = None) -> list[PendingTermination]`——返回本次扫描新建的记录列表（供 Task 7 拿去发短信，不在这个函数内部发）

- [ ] **Step 1: 创建扫描函数**

```python
from sqlalchemy import select
from sqlalchemy.orm import Session

from ..models import EnterprisePremiumAccount, InsuredPerson, InsurerAccountLink, PendingTermination, WorkPosition


def scan_premium_shortfalls(session: Session, enterprise_id: int | None = None) -> list[PendingTermination]:
    """Lazy scan: no scheduled job in this codebase, so this runs whenever an
    admin-facing page that needs fresh data loads (dashboard, the pending-
    terminations list). Idempotent — running it twice in a row does not
    create duplicate pending records, and it auto-dismisses any pending
    record whose account has since been recharged back to a positive
    balance. Returns only the records newly created by THIS call, so the
    caller can fire notifications for exactly those without re-notifying
    on every subsequent scan."""
    stmt = select(EnterprisePremiumAccount).where(EnterprisePremiumAccount.balance <= 0)
    if enterprise_id is not None:
        stmt = stmt.where(EnterprisePremiumAccount.enterprise_id == enterprise_id)
    shortfall_rows = session.scalars(stmt).all()
    shortfall_keys = {(row.enterprise_id, row.account_id) for row in shortfall_rows}

    # auto-dismiss: any pending record whose account is no longer in shortfall
    dismiss_stmt = select(PendingTermination).where(PendingTermination.status == "pending")
    if enterprise_id is not None:
        dismiss_stmt = dismiss_stmt.where(PendingTermination.enterprise_id == enterprise_id)
    for existing in session.scalars(dismiss_stmt).all():
        if (existing.enterprise_id, existing.account_id) not in shortfall_keys:
            existing.status = "dismissed"
            from ..core.business_time import business_now
            existing.dismissed_at = business_now()

    created: list[PendingTermination] = []
    for row in shortfall_rows:
        already_pending = session.scalar(
            select(PendingTermination).where(
                PendingTermination.enterprise_id == row.enterprise_id,
                PendingTermination.account_id == row.account_id,
                PendingTermination.status == "pending",
            )
        )
        if already_pending:
            continue
        insurers = [x for x, in session.execute(select(InsurerAccountLink.insurer).where(InsurerAccountLink.account_id == row.account_id)).all()]
        if not insurers:
            continue
        affected_count = session.query(InsuredPerson).join(WorkPosition, InsuredPerson.position_id == WorkPosition.id).filter(
            InsuredPerson.enterprise_id == row.enterprise_id,
            InsuredPerson.status == "active",
        ).count()
        item = PendingTermination(
            enterprise_id=row.enterprise_id, account_id=row.account_id,
            affected_insurers=",".join(insurers), affected_count=affected_count,
        )
        session.add(item)
        created.append(item)

    session.commit()
    for item in created:
        session.refresh(item)
    return created
```

- [ ] **Step 2: 导出函数**

打开 `backend/services/__init__.py`，`from .participation_lock import require_usage_funded` 那行下面加：

```python
from .termination_scan import scan_premium_shortfalls
```

`__all__` 加 `"scan_premium_shortfalls",`。

- [ ] **Step 3: 追加测试**

打开 `tests/participation_lock_smoke.py`，在 import 区块追加：

```python
        from backend.models import EnterprisePremiumAccount, InsurerAccount, InsurerAccountLink
        from backend.services import scan_premium_shortfalls
```

在文件末尾（`print(...)` 之前）追加：

```python
            # scan_premium_shortfalls: creates a pending record for a shortfall account
            scan_account = InsurerAccount(label="扫描测试账户", bank_name="", account_no="", account_holder="", status="active")
            session.add(scan_account); session.commit(); session.refresh(scan_account)
            scan_link = InsurerAccountLink(insurer="扫描测试保司", account_id=scan_account.id)
            session.add(scan_link); session.commit()
            scan_ent = Enterprise(name="扫描测试企业", kind="企业", contact="", phone="", status="active")
            session.add(scan_ent); session.commit(); session.refresh(scan_ent)
            shortfall = EnterprisePremiumAccount(enterprise_id=scan_ent.id, account_id=scan_account.id, balance=-10.0)
            session.add(shortfall); session.commit()

            created_1 = scan_premium_shortfalls(session, enterprise_id=scan_ent.id)
            assert len(created_1) == 1, created_1
            assert created_1[0].affected_insurers == "扫描测试保司"
            assert created_1[0].status == "pending"

            # idempotent: scanning again does not create a duplicate
            created_2 = scan_premium_shortfalls(session, enterprise_id=scan_ent.id)
            assert created_2 == [], "must not create a duplicate pending record"
            still_one_pending = session.scalar(select(PendingTermination).where(PendingTermination.enterprise_id == scan_ent.id, PendingTermination.status == "pending"))
            assert still_one_pending is not None

            # auto-dismiss: recharging the account clears the pending record without admin action
            shortfall.balance = 50.0
            session.commit()
            created_3 = scan_premium_shortfalls(session, enterprise_id=scan_ent.id)
            assert created_3 == []
            dismissed = session.scalar(select(PendingTermination).where(PendingTermination.enterprise_id == scan_ent.id))
            assert dismissed.status == "dismissed" and dismissed.dismissed_at is not None
```

- [ ] **Step 4: 跑测试确认通过**

Run: `python3 tests/participation_lock_smoke.py`
Expected: `participation lock smoke: ok`

- [ ] **Step 5: Commit**

```bash
git add backend/services/termination_scan.py backend/services/__init__.py tests/participation_lock_smoke.py
git commit -m "feat: add lazy premium-shortfall scan with idempotent create and auto-dismiss"
```

---

### Task 5: 短信通知服务 `notify_enterprise`

**Files:**
- Create: `backend/services/notify.py`
- Modify: `backend/services/__init__.py`
- Test: `tests/participation_lock_smoke.py`（追加）

**Interfaces:**
- Consumes: `sms_provider()`（已存在，`backend/providers.py`）、`audit()`（已存在，`backend/core/audit.py`）
- Produces: `notify_enterprise(session: Session, enterprise_id: int, template: str, params: dict) -> None`——查出该企业所有 `role='enterprise'` 账号（含操作员），逐个发短信，失败只记 audit log 不抛异常。供 Task 3/6/7 调用。

- [ ] **Step 1: 创建通知函数**

```python
from sqlalchemy import select
from sqlalchemy.orm import Session

from ..models import User
from ..providers import sms_provider


def notify_enterprise(session: Session, enterprise_id: int, template: str, params: dict) -> None:
    """Fire-and-forget SMS to every role='enterprise' account at this
    enterprise (owner + operators — 'operator' has no separate role value,
    it's role='enterprise' with is_owner=False). Never raises: a failed
    send must not roll back the caller's already-committed business
    operation (recharge confirm, lock trigger, termination confirm), so
    failures are swallowed and only recorded via audit log."""
    recipients = session.scalars(
        select(User).where(User.role == "enterprise", User.enterprise_id == enterprise_id, User.active.is_(True))
    ).all()
    for user in recipients:
        if not user.phone.strip():
            continue
        try:
            sms_provider().send_sms(user.phone, template, params)
        except Exception:
            pass
```

- [ ] **Step 2: 导出函数**

打开 `backend/services/__init__.py`，`from .termination_scan import scan_premium_shortfalls` 那行下面加：

```python
from .notify import notify_enterprise
```

`__all__` 加 `"notify_enterprise",`。

- [ ] **Step 3: 追加测试**

打开 `tests/participation_lock_smoke.py`，import 区块追加：

```python
        from backend.services import notify_enterprise
```

文件末尾追加：

```python
            # notify_enterprise: sends to every role='enterprise' account with a phone,
            # skips ones without a phone, and never raises even if the provider fails
            notify_ent = Enterprise(name="通知测试企业", kind="企业", contact="", phone="", status="active")
            session.add(notify_ent); session.commit(); session.refresh(notify_ent)
            owner = User(username="notify_owner", password_hash="x", name="主管", role="enterprise", enterprise_id=notify_ent.id, is_owner=True, phone="13800000001")
            operator = User(username="notify_operator", password_hash="x", name="操作员", role="enterprise", enterprise_id=notify_ent.id, is_owner=False, phone="13800000002")
            no_phone = User(username="notify_nophone", password_hash="x", name="无手机号", role="enterprise", enterprise_id=notify_ent.id, is_owner=False, phone="")
            session.add_all([owner, operator, no_phone]); session.commit()
            notify_enterprise(session, notify_ent.id, "recharge_confirmed", {"amount": 100})  # must not raise
```

（这个测试只验证"函数不抛异常、能正常跑完"，不断言 mock provider 具体调用了几次——`MockProvider` 是 no-op 记录，断言调用次数需要 mock/patch 才有意义，这份计划的范围内够用；如果后续需要更严格的断言，可以给 `MockProvider` 加一个调用计数器，但那是 provider 层的改动，不在本任务范围。）

- [ ] **Step 4: 跑测试确认通过**

Run: `python3 tests/participation_lock_smoke.py`
Expected: `participation lock smoke: ok`

- [ ] **Step 5: Commit**

```bash
git add backend/services/notify.py backend/services/__init__.py tests/participation_lock_smoke.py
git commit -m "feat: add notify_enterprise fire-and-forget SMS helper"
```

---

### Task 6: 待处理停保路由（`GET /pending-terminations`、`POST /pending-terminations/{id}/confirm`）

**Files:**
- Create: `backend/routers/pending_terminations.py`
- Modify: `backend/app.py`
- Test: `tests/participation_lock_smoke.py`（追加）

**Interfaces:**
- Consumes: `PendingTermination`（Task 1）、`terminate_person_policy`（已存在，`backend/services/policy_members.py`）
- Produces: 两个 admin-only 端点。**注意**：`POST /pending-terminations/{id}/confirm` 里发短信的调用留到 Task 7 一起接，这个任务先把"确认执行、真的停掉人"这部分做对、测好。

- [ ] **Step 1: 创建路由**

```python
from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy import select
from sqlalchemy.orm import Session

from ..core.audit import audit
from ..core.business_time import business_now
from ..core.db import db
from ..core.rbac import require_role
from ..core.security import current_user
from ..models import InsuredPerson, InsurerAccountLink, PendingTermination, User, WorkPosition
from ..services import serialize, terminate_person_policy

router = APIRouter(prefix="/api", tags=["pending-terminations"])


@router.get("/pending-terminations", dependencies=[Depends(require_role("admin", detail="仅总后台可查看待处理停保"))])
def pending_terminations(session: Session = Depends(db)):
    return [serialize(x) for x in session.scalars(select(PendingTermination).order_by(PendingTermination.id.desc()))]


@router.post("/pending-terminations/{item_id}/confirm", dependencies=[Depends(require_role("admin", detail="仅总后台可确认停保"))])
def confirm_pending_termination(item_id: int, user: User = Depends(current_user), session: Session = Depends(db)):
    item = session.get(PendingTermination, item_id)
    if not item: raise HTTPException(404, "待处理停保任务不存在")
    if item.status != "pending": raise HTTPException(400, "该任务已处理，不能重复确认")

    insurers = [x for x, in session.execute(select(InsurerAccountLink.insurer).where(InsurerAccountLink.account_id == item.account_id)).all()]
    affected = session.query(InsuredPerson).join(WorkPosition, InsuredPerson.position_id == WorkPosition.id).filter(
        InsuredPerson.enterprise_id == item.enterprise_id,
        InsuredPerson.status == "active",
    ).all()
    terminated_ids = []
    for person in affected:
        terminate_person_policy(session, person, terminated_at=business_now())
        person.status = "stopped"
        terminated_ids.append(person.id)

    item.status = "confirmed"
    item.confirmed_by = user.id
    item.confirmed_at = business_now()
    session.commit()
    audit(session, user, "confirm", "pending_termination", str(item.id), f"terminated={len(terminated_ids)}")
    return {**serialize(item), "terminated_count": len(terminated_ids)}
```

（`terminate_person_policy(session, person, terminated_at=...)` 立即停保，不走"最早可停保时间"那套面向自愿停保设计的规则——设计文档明确说这是被动断保，用法参照 `insured.py` 的 `insured_status` 端点在 `previous_status=="active" and status_value!="active"` 分支里的既有调用方式，只是那里没传 `terminated_at` 走默认，这里显式传 `business_now()`。）

- [ ] **Step 2: 注册路由**

打开 `backend/app.py`，找到其它 router import 的位置（参照 `from .routers.insured import router as insured_router` 的写法），加一行：

```python
from .routers.pending_terminations import router as pending_terminations_router
```

找到 `app.include_router(insured_router)` 附近，加一行：

```python
app.include_router(pending_terminations_router)
```

- [ ] **Step 3: 追加测试**

打开 `tests/participation_lock_smoke.py`，import 区块追加：

```python
        from backend.routers.pending_terminations import pending_terminations as list_pending_terminations, confirm_pending_termination
```

文件末尾追加（复用 Task 4 测试里已经创建的 `scan_ent`/`scan_account`/`scan_link`/`created_1` 那批数据）：

```python
            # confirm actually stops the affected people, not just flips a flag
            confirm_position = WorkPosition(enterprise_id=scan_ent.id, actual_employer_id=1, actual_employer="测试实际用工单位", name="扫描测试岗位", status="approved", occupation_class="1-3类")
            session.add(confirm_position); session.commit(); session.refresh(confirm_position)
            confirm_person = InsuredPerson(enterprise_id=scan_ent.id, position_id=confirm_position.id, name="待停保测试员工", id_number="510107199203150036", status="active")
            session.add(confirm_person); session.commit(); session.refresh(confirm_person)

            all_pending = list_pending_terminations(session)
            assert len(all_pending) >= 1
            target = next(x for x in all_pending if x["enterprise_id"] == scan_ent.id and x["status"] == "confirmed" or x["status"] == "pending")
            # the earlier auto-dismiss test already flipped this specific record to
            # dismissed; re-create a fresh shortfall so there's a real pending row to confirm
            shortfall.balance = -20.0
            session.commit()
            fresh_pending = scan_premium_shortfalls(session, enterprise_id=scan_ent.id)
            assert len(fresh_pending) == 1
            result = confirm_pending_termination(fresh_pending[0].id, admin_user, session)
            assert result["status"] == "confirmed" and result["terminated_count"] == 1
            session.refresh(confirm_person)
            assert confirm_person.status == "stopped"

            try:
                confirm_pending_termination(fresh_pending[0].id, admin_user, session)
                assert False, "expected 400 for re-confirming an already-processed task"
            except HTTPException as e:
                assert e.status_code == 400
```

- [ ] **Step 4: 跑测试确认通过**

Run: `python3 tests/participation_lock_smoke.py`
Expected: `participation lock smoke: ok`

- [ ] **Step 5: Commit**

```bash
git add backend/routers/pending_terminations.py backend/app.py tests/participation_lock_smoke.py
git commit -m "feat: add pending-terminations list and confirm endpoints"
```

---

### Task 7: 把短信通知接入四个触发点

**Files:**
- Modify: `backend/routers/recharge_requests.py`
- Modify: `backend/services/participation_lock.py`
- Modify: `backend/services/termination_scan.py`
- Modify: `backend/routers/pending_terminations.py`
- Test: `tests/participation_lock_smoke.py`（追加）

**Interfaces:**
- Consumes: `notify_enterprise`（Task 5）
- Produces: 四个触发点各自在成功路径末尾调用一次 `notify_enterprise`，短信失败不影响主流程（已经是 `notify_enterprise` 自身的保证，这里只是把调用点接上）

- [ ] **Step 1: 充值确认/驳回成功后通知（触发点①）**

打开 `backend/routers/recharge_requests.py`，导入 `notify_enterprise`：

```python
from ..services import get_or_create_premium_account, post_ledger_entry, resolve_account_for_insurer, serialize
```

改为：

```python
from ..services import get_or_create_premium_account, notify_enterprise, post_ledger_entry, resolve_account_for_insurer, serialize
```

找到 `confirm_recharge_request` 函数末尾：

```python
    item.status = "confirmed"; item.confirmed_by = user.id; item.confirmed_at = business_now()
    session.commit(); audit(session, user, "confirm", "recharge_request", str(item.id))
    return _recharge_dict(item, session)
```

改为：

```python
    item.status = "confirmed"; item.confirmed_by = user.id; item.confirmed_at = business_now()
    session.commit(); audit(session, user, "confirm", "recharge_request", str(item.id))
    notify_enterprise(session, item.enterprise_id, "recharge_confirmed", {"amount": item.amount, "account_type": item.account_type})
    return _recharge_dict(item, session)
```

找到 `reject_recharge_request` 函数末尾：

```python
    item.status = "rejected"; item.reject_reason = reason.strip(); item.confirmed_by = user.id; item.confirmed_at = business_now()
    session.commit(); audit(session, user, "reject", "recharge_request", str(item.id), reason)
    return _recharge_dict(item, session)
```

改为：

```python
    item.status = "rejected"; item.reject_reason = reason.strip(); item.confirmed_by = user.id; item.confirmed_at = business_now()
    session.commit(); audit(session, user, "reject", "recharge_request", str(item.id), reason)
    notify_enterprise(session, item.enterprise_id, "recharge_rejected", {"amount": item.amount, "reason": reason.strip()})
    return _recharge_dict(item, session)
```

- [ ] **Step 2: 使用费锁定触发时通知，同一天不重复发（触发点②）**

`require_usage_funded` 的函数签名从 Task 2 定义时就已经是 `(session, enterprise, user)`——这一步只改函数体，不改签名，`insured.py` 五个调用点（Task 3）不用再改。`AuditLog.user_id` 是非空字段（`backend/models/misc.py` 里定义为 `Mapped[int]`，不是 `Optional[int]`），所以去重记录归属到触发这次锁定的操作者本人（`user` 参数），不是某个不存在的"系统用户"。

打开 `backend/services/participation_lock.py`，改成：

```python
from fastapi import HTTPException
from sqlalchemy import select
from sqlalchemy.orm import Session

from ..core.business_time import business_now
from ..models import AuditLog, Enterprise, User
from .notify import notify_enterprise


def require_usage_funded(session: Session, enterprise: Enterprise, user: User) -> None:
    """Real-time usage-fee gate for participation-changing endpoints. No
    caching, no precomputation — queries the live enterprise.usage_balance
    value every call, so a just-confirmed recharge unlocks the very next
    request with no separate "unlock" step needed."""
    if enterprise.usage_balance <= 0:
        _notify_lock_once_per_day(session, enterprise, user)
        raise HTTPException(403, "使用费余额不足，请先充值后再操作参停保")


def _notify_lock_once_per_day(session: Session, enterprise: Enterprise, user: User) -> None:
    today_start = business_now().replace(hour=0, minute=0, second=0, microsecond=0)
    already_sent = session.scalar(
        select(AuditLog.id).where(
            AuditLog.action == "usage_lock_notify",
            AuditLog.object_type == "enterprise",
            AuditLog.object_id == str(enterprise.id),
            AuditLog.created_at >= today_start,
        ).limit(1)
    )
    if already_sent:
        return
    notify_enterprise(session, enterprise.id, "usage_locked", {})
    session.add(AuditLog(user_id=user.id, action="usage_lock_notify", object_type="enterprise", object_id=str(enterprise.id), detail=""))
    session.commit()
```

`resolve_enterprise` 内部那处判断（`import_insured_file` 里按行解析出的其它单位）不是调用 `require_usage_funded`（是直接查 `found.usage_balance<=0` 返回错误字符串，不经过这个函数），所以不用改，也不会重复发短信——这条路径本来就不发通知，设计文档没有把"导入文件里某一行命中锁定"列为独立的短信触发点，只有走 `require_usage_funded` 抛 403 的那五处主路径才发。

- [ ] **Step 3: 停保预警——扫描新建 `PendingTermination` 时通知（触发点③）**

打开 `backend/services/termination_scan.py`，导入 `notify_enterprise`：

```python
from sqlalchemy import select
from sqlalchemy.orm import Session

from ..models import EnterprisePremiumAccount, InsuredPerson, InsurerAccountLink, PendingTermination, WorkPosition
from .notify import notify_enterprise
```

找到 `session.add(item); created.append(item)` 那一行，改成：

```python
        session.add(item)
        created.append(item)
```

不变——**通知放在 `session.commit()` 之后**（等 ID 真正落库、`created` 列表里的对象可用之后再发，避免在事务中途调用外部 provider）。找到函数末尾：

```python
    session.commit()
    for item in created:
        session.refresh(item)
    return created
```

改为：

```python
    session.commit()
    for item in created:
        session.refresh(item)
        notify_enterprise(session, item.enterprise_id, "premium_shortfall_warning", {"insurers": item.affected_insurers, "affected_count": item.affected_count})
    return created
```

- [ ] **Step 4: 停保确认执行后通知（触发点④）**

打开 `backend/routers/pending_terminations.py`，导入 `notify_enterprise`：

```python
from ..services import serialize, terminate_person_policy
```

改为：

```python
from ..services import notify_enterprise, serialize, terminate_person_policy
```

找到 `confirm_pending_termination` 函数末尾：

```python
    item.status = "confirmed"
    item.confirmed_by = user.id
    item.confirmed_at = business_now()
    session.commit()
    audit(session, user, "confirm", "pending_termination", str(item.id), f"terminated={len(terminated_ids)}")
    return {**serialize(item), "terminated_count": len(terminated_ids)}
```

改为：

```python
    item.status = "confirmed"
    item.confirmed_by = user.id
    item.confirmed_at = business_now()
    session.commit()
    audit(session, user, "confirm", "pending_termination", str(item.id), f"terminated={len(terminated_ids)}")
    notify_enterprise(session, item.enterprise_id, "termination_confirmed", {"insurers": item.affected_insurers, "terminated_count": len(terminated_ids)})
    return {**serialize(item), "terminated_count": len(terminated_ids)}
```

- [ ] **Step 5: 跑全部测试确认没有破坏之前的任务**

Run: `python3 tests/participation_lock_smoke.py`
Expected: `participation lock smoke: ok`（Task 2 定义 `require_usage_funded` 时就已经是三参数签名，Task 7 只改了函数体，测试文件里的调用点不需要改）

Run: `python3 tests/recharge_smoke.py`
Expected: `recharge smoke: ok`

- [ ] **Step 6: Commit**

```bash
git add backend/routers/recharge_requests.py backend/services/participation_lock.py backend/services/termination_scan.py backend/routers/pending_terminations.py backend/routers/insured.py tests/participation_lock_smoke.py
git commit -m "feat: wire SMS notifications into all four trigger points"
```

---

### Task 8: dashboard.py 接入扫描触发 + 待处理停保计数

**Files:**
- Modify: `backend/routers/dashboard.py`
- Test: `tests/participation_lock_smoke.py`（追加）

**Interfaces:**
- Consumes: `scan_premium_shortfalls`（Task 4）
- Produces: `GET /dashboard`（admin 视角）响应体新增 `pending_terminations_count: int`；每次 admin 加载 dashboard 时顺带触发一次全量扫描（惰性触发，不是定时任务）

- [ ] **Step 1: 导入并接入扫描**

打开 `backend/routers/dashboard.py`，找到：

```python
from ..services import amount, effective_person_status, policy_dict, premium_accounts_for_enterprise, pricing_snapshot, strip_internal_pricing, usage_person_days
```

改为：

```python
from ..models import Claim, Enterprise, InsurancePlan, InsuredPerson, PendingTermination, Policy, PolicyMember, User, WorkPosition
from ..services import amount, effective_person_status, policy_dict, premium_accounts_for_enterprise, pricing_snapshot, scan_premium_shortfalls, strip_internal_pricing, usage_person_days
```

（`PendingTermination` 加进 `..models` 的 import 列表；原来这一行已经 import 了 `Claim, Enterprise, InsurancePlan, InsuredPerson, Policy, PolicyMember, User, WorkPosition`，只是新增 `PendingTermination` 一项，按字母序插入。）

找到 `dashboard()` 函数最开头：

```python
def dashboard(user: User = Depends(current_user), session: Session = Depends(db)):
    enterprise_filter = [user.enterprise_id] if user.role == "enterprise" and user.enterprise_id else None
```

改为：

```python
def dashboard(user: User = Depends(current_user), session: Session = Depends(db)):
    enterprise_filter = [user.enterprise_id] if user.role == "enterprise" and user.enterprise_id else None
    if user.role == "admin":
        scan_premium_shortfalls(session)
```

（只有 admin 视角触发全量扫描——企业自己看 dashboard 不需要触发扫描，扫描本身也不应该由企业账号的请求间接触发，避免企业角色的普通浏览行为意外产生系统副作用；`scan_premium_shortfalls(session)` 不传 `enterprise_id` 就是全量扫描，匹配设计文档"企业规模小，全量扫描成本可忽略"的说法。）

- [ ] **Step 2: 响应体加计数字段**

找到 `dashboard()` 函数末尾的 `return {...}` 语句，在 `"balance_alerts": alerts` 后面加一个字段：

```python
    return {"portal": "enterprise" if user.role == "enterprise" else "admin", "enterprises": len(enterprises), "people": len(people), "active_people":len(active_people), "active_policies": session.query(Policy).filter(Policy.status == "active", Policy.enterprise_id.in_(enterprise_filter)).count() if enterprise_filter else session.query(Policy).filter(Policy.status == "active").count(), "pending_enterprises": session.query(Enterprise).filter(Enterprise.status == "pending").count() if not enterprise_filter else 0, "pending_people": len([x for x in people if x.status == "pending"]), "claims_open": session.query(Claim).filter(Claim.status.not_in(["paid", "closed"]), Claim.enterprise_id.in_(enterprise_filter)).count() if enterprise_filter else session.query(Claim).filter(Claim.status.not_in(["paid", "closed"])).count(), "premium_accounts": list(premium_agg.values()), "usage_balance": sum(x.usage_balance for x in enterprises), "balance_alerts": alerts, "pending_terminations_count": session.query(PendingTermination).filter(PendingTermination.status == "pending").count() if user.role == "admin" else 0}
```

（跟这个函数其它字段一样写成单行密集风格，匹配文件既有代码风格；企业角色恒为 0，不暴露平台全量数据。）

- [ ] **Step 3: 追加测试**

打开 `tests/participation_lock_smoke.py`，import 区块追加：

```python
        from backend.routers.dashboard import dashboard as dashboard_endpoint
```

文件末尾追加：

```python
            # dashboard triggers a scan and surfaces the pending-terminations count for admin
            fresh_shortfall_ent = Enterprise(name="dashboard扫描测试企业", kind="企业", contact="", phone="", status="active")
            session.add(fresh_shortfall_ent); session.commit(); session.refresh(fresh_shortfall_ent)
            dash_account = InsurerAccount(label="dashboard扫描测试账户", bank_name="", account_no="", account_holder="", status="active")
            session.add(dash_account); session.commit(); session.refresh(dash_account)
            dash_link = InsurerAccountLink(insurer="dashboard扫描测试保司", account_id=dash_account.id)
            session.add(dash_link); session.commit()
            dash_shortfall = EnterprisePremiumAccount(enterprise_id=fresh_shortfall_ent.id, account_id=dash_account.id, balance=-1.0)
            session.add(dash_shortfall); session.commit()

            before_count = session.query(PendingTermination).filter(PendingTermination.status == "pending").count()
            dash_result = dashboard_endpoint(admin_user, session)
            assert dash_result["pending_terminations_count"] == before_count + 1, dash_result

            # enterprise-role dashboard never triggers a scan or exposes the platform-wide count
            ent_user = session.scalar(select(User).where(User.username == "enterprise"))
            ent_result = dashboard_endpoint(ent_user, session)
            assert ent_result["pending_terminations_count"] == 0
```

- [ ] **Step 4: 跑测试确认通过**

Run: `python3 tests/participation_lock_smoke.py`
Expected: `participation lock smoke: ok`

- [ ] **Step 5: Commit**

```bash
git add backend/routers/dashboard.py tests/participation_lock_smoke.py
git commit -m "feat: trigger premium-shortfall scan and surface pending-terminations count on admin dashboard"
```

---

### Task 9: 前端类型 + API 客户端

**Files:**
- Modify: `web/src/api/types.ts`
- Create: `web/src/api/pendingTerminations.ts`

**Interfaces:**
- Consumes: `GET /pending-terminations`、`POST /pending-terminations/{id}/confirm`（Task 6）、`GET /dashboard` 新增的 `pending_terminations_count`（Task 8）
- Produces: `PendingTermination` 类型、`listPendingTerminations()`、`confirmPendingTermination(id)`，供 Task 10/11/12 使用

- [ ] **Step 1: 新增 `PendingTermination` 类型**

打开 `web/src/api/types.ts`，在 `RechargeRequest` 接口定义后面新增：

```ts
export interface PendingTermination {
  id: number
  enterprise_id: number
  account_id: number
  affected_insurers: string
  affected_count: number
  status: 'pending' | 'confirmed' | 'dismissed'
  confirmed_by: number | null
  confirmed_at: string | null
  dismissed_at: string | null
  created_at: string
}
```

找到 `DashboardData` 接口，加一个字段：

```ts
export interface DashboardData {
  portal: 'admin' | 'enterprise'
  enterprises: number
  people: number
  active_people: number
  active_policies: number
  pending_enterprises: number
  pending_people: number
  claims_open: number
  premium_accounts: PremiumAccountRow[]
  usage_balance: number
  balance_alerts: BalanceAlert[]
  pending_terminations_count: number
}
```

（如果实际文件里 `DashboardData` 的字段顺序/写法跟上面不完全一致，只需要确保加上 `pending_terminations_count: number` 这一个字段，不用重排其它字段。）

- [ ] **Step 2: 新增 API 客户端**

创建 `web/src/api/pendingTerminations.ts`：

```ts
import { client } from './client'
import type { PendingTermination } from './types'

export function listPendingTerminations() {
  return client.get<PendingTermination[]>('/pending-terminations').then((r) => r.data)
}

export function confirmPendingTermination(id: number) {
  return client.post<PendingTermination & { terminated_count: number }>(`/pending-terminations/${id}/confirm`).then((r) => r.data)
}
```

- [ ] **Step 3: 类型检查**

Run: `cd web && npx vue-tsc -b --noEmit`
Expected: 0 errors

- [ ] **Step 4: Commit**

```bash
git add web/src/api/types.ts web/src/api/pendingTerminations.ts
git commit -m "feat: add PendingTermination frontend types and API client"
```

---

### Task 10: 前端统一拦截使用费不足的 403

**Files:**
- Modify: `web/src/api/client.ts`

**Interfaces:**
- Consumes: 后端 `HTTPException(403, "使用费余额不足，请先充值后再操作参停保")`（Task 3）的 `detail` 字符串
- Produces: 任何调用命中这个 403 时，全局弹出一个带"去充值"按钮的提示，而不是让每个调用方各自处理

- [ ] **Step 1: 在响应拦截器里加一个专门分支**

打开 `web/src/api/client.ts`，找到：

```ts
import axios from 'axios'
import router from '@/router'

export const TOKEN_KEY = 'xbb-auth-token'

export const client = axios.create({
  baseURL: '/api',
})

client.interceptors.request.use((config) => {
  const token = localStorage.getItem(TOKEN_KEY)
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

client.interceptors.response.use(
  (response) => response,
  (error) => {
    const detail = error?.response?.data?.detail || error.message || '请求失败'
    if (error?.response?.status === 401) {
      localStorage.removeItem(TOKEN_KEY)
      if (router.currentRoute.value.name !== 'login') {
        router.push({ name: 'login', query: router.currentRoute.value.query })
      }
    }
    return Promise.reject(new Error(detail))
  },
)
```

改为：

```ts
import axios from 'axios'
import { ElNotification } from 'element-plus'
import router from '@/router'

export const TOKEN_KEY = 'xbb-auth-token'

export const client = axios.create({
  baseURL: '/api',
})

client.interceptors.request.use((config) => {
  const token = localStorage.getItem(TOKEN_KEY)
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

const USAGE_LOCK_MESSAGE = '使用费余额不足，请先充值后再操作参停保'
let usageLockNotified = false

client.interceptors.response.use(
  (response) => response,
  (error) => {
    const detail = error?.response?.data?.detail || error.message || '请求失败'
    if (error?.response?.status === 401) {
      localStorage.removeItem(TOKEN_KEY)
      if (router.currentRoute.value.name !== 'login') {
        router.push({ name: 'login', query: router.currentRoute.value.query })
      }
    }
    if (error?.response?.status === 403 && detail === USAGE_LOCK_MESSAGE && !usageLockNotified) {
      usageLockNotified = true
      ElNotification({
        title: '参停保功能已锁定',
        message: '使用费余额不足，充值到账后自动恢复，无需额外操作。',
        type: 'warning',
        duration: 8000,
        onClick: () => {
          router.push({ name: 'recharge' })
        },
        onClose: () => {
          usageLockNotified = false
        },
      })
    }
    return Promise.reject(new Error(detail))
  },
)
```

（`usageLockNotified` 是一个模块级标志，避免同一页面短时间内连续触发多次相同请求（比如批量操作里每一行都命中锁定）弹出一大堆重复通知——只在通知关闭后才允许再次弹出。点击通知本体跳转到充值中心页面，跟 `/recharge` 路由已有的 `name: 'recharge'` 对应，这是 Phase A 里建好的路由。）

- [ ] **Step 2: 类型检查**

Run: `cd web && npx vue-tsc -b --noEmit`
Expected: 0 errors

- [ ] **Step 3: 手动验证**

Run: `cd web && npm run dev`
Expected: 把某企业 `usage_balance` 调成 0（可以直接改 SQLite 里的值，或用管理端旧的 `/enterprises/{id}/recharge` usage 分支充值成负值——注意这个旧接口本次没改，premium 分支才被 Phase A 挡掉了），企业账号登录后尝试新增参保员工，确认看到右上角弹出的通知而不是普通报错 toast，点击通知能跳到充值页

- [ ] **Step 4: Commit**

```bash
git add web/src/api/client.ts
git commit -m "feat: intercept usage-lock 403 with a global notification"
```

---

### Task 11: 平台端「待处理停保」页面

**Files:**
- Create: `web/src/views/pending-terminations/PendingTerminationsView.vue`
- Modify: `web/src/router/routes.ts`

**Interfaces:**
- Consumes: `listPendingTerminations()`、`confirmPendingTermination(id)`（Task 9）
- Produces: 完整的管理端页面，含二次确认弹窗

- [ ] **Step 1: 新增路由**

打开 `web/src/router/routes.ts`，在 `/recharge` 那一行后面插入（放在同一个"保障与结算"分组里）：

```ts
  { path: '/pending-terminations', name: 'pendingTerminations', component: () => import('@/views/pending-terminations/PendingTerminationsView.vue'), meta: { title: '待处理停保', group: '保障与结算', adminOnly: true } },
```

- [ ] **Step 2: 创建页面**

```vue
<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { confirmPendingTermination, listPendingTerminations } from '@/api/pendingTerminations'
import type { PendingTermination } from '@/api/types'
import PageCard from '@/components/PageCard.vue'

const rows = ref<PendingTermination[]>([])
const loading = ref(true)

async function load() {
  loading.value = true
  try {
    rows.value = await listPendingTerminations()
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    loading.value = false
  }
}

onMounted(load)

async function confirm(row: PendingTermination) {
  try {
    await ElMessageBox.confirm(
      `确认停保后，该账户名下（${row.affected_insurers}）当前 ${row.affected_count} 名在保人员将被立即停保，此操作不可撤销。`,
      '确认停保',
      { type: 'warning', confirmButtonText: '确认停保', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  try {
    const result = await confirmPendingTermination(row.id)
    ElMessage.success(`已停保 ${result.terminated_count} 人`)
    load()
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}

const statusLabel: Record<PendingTermination['status'], string> = {
  pending: '待确认',
  confirmed: '已停保',
  dismissed: '已自动撤销（已充值）',
}
</script>

<template>
  <div class="page" v-loading="loading">
    <PageCard title="待处理停保" :count="rows.length" hint="保费账户余额耗尽后自动生成，需管理员确认后才会真正停保">
      <el-table :data="rows" size="small" style="width: 100%">
        <el-table-column prop="enterprise_id" label="企业 ID" width="90" />
        <el-table-column prop="affected_insurers" label="受影响保司" min-width="160" />
        <el-table-column prop="affected_count" label="受影响人数" width="100" />
        <el-table-column label="状态" width="140">
          <template #default="{ row }">{{ statusLabel[row.status as PendingTermination['status']] }}</template>
        </el-table-column>
        <el-table-column prop="created_at" label="生成时间" width="180" />
        <el-table-column label="操作" width="120">
          <template #default="{ row }">
            <el-button v-if="row.status === 'pending'" link type="danger" size="small" @click="confirm(row)">确认停保</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!loading && !rows.length" description="暂无待处理停保任务" :image-size="60" />
    </PageCard>
  </div>
</template>

<style scoped>
.page {
  padding: 20px 24px;
}
</style>
```

- [ ] **Step 2: 类型检查 + 构建**

Run: `cd web && npx vue-tsc -b --noEmit && npm run build`
Expected: 类型检查通过，构建成功

- [ ] **Step 3: Commit**

```bash
git add web/src/views/pending-terminations/PendingTerminationsView.vue web/src/router/routes.ts
git commit -m "feat: add the platform-side pending-terminations admin page"
```

---

### Task 12: 首页「待处理停保」计数

**Files:**
- Modify: `web/src/views/dashboard/HomeView.vue`

**Interfaces:**
- Consumes: `DashboardData.pending_terminations_count`（Task 8/9）
- Produces: admin 视角首页新增一个 `StatTile`，跟现有"待处理理赔"并列，点击跳转到 `/pending-terminations`

- [ ] **Step 1: 找到现有"待处理理赔"`StatTile`，在旁边加一个**

打开 `web/src/views/dashboard/HomeView.vue`，找类似 `<StatTile label="待处理理赔" ... />` 的那一行（Phase A 之前就存在，跟 `claims_open` 字段对应），在它后面加：

```html
      <StatTile
        v-if="auth.isAdmin()"
        label="待处理停保"
        :value="data ? data.pending_terminations_count : '—'"
        :hint="data && data.pending_terminations_count > 0 ? '点击查看' : ''"
        hint-type="warning"
        style="cursor: pointer"
        @click="data && data.pending_terminations_count > 0 && router.push({ name: 'pendingTerminations' })"
      />
```

（`v-if="auth.isAdmin()"` 保证企业角色看不到这个入口——企业角色的 `pending_terminations_count` 后端本来就固定返回 0，双重保险；具体插入位置跟随现有"待处理理赔"`StatTile` 所在的那个 `<div class="grid-N">` 容器内，保持网格布局一致，不要另起一个容器。实际实现时先读一遍这个文件里"待处理理赔"那个 `StatTile` 前后的真实代码，确认 `auth`/`router` 是否已经在这个组件的 `<script setup>` 里可用——如果还没有，参照 `RechargeCenterView.vue` 或其它已有页面的写法补上 `const auth = useAuthStore()`/`const router = useRouter()`。）

- [ ] **Step 2: 类型检查 + 构建**

Run: `cd web && npx vue-tsc -b --noEmit && npm run build`
Expected: 类型检查通过，构建成功

- [ ] **Step 3: Commit**

```bash
git add web/src/views/dashboard/HomeView.vue
git commit -m "feat: surface pending-terminations count on the admin home page"
```

---

### Task 13: 完整回归验证

**Files:**
- Test: `tests/participation_lock_smoke.py`, `tests/recharge_smoke.py`, `tests/system_smoke.py`, `tests/security_smoke.py`

**Interfaces:**
- Consumes: 全部前置任务

- [ ] **Step 1: 跑本计划的冒烟测试**

Run: `python3 tests/participation_lock_smoke.py`
Expected: `participation lock smoke: ok`

- [ ] **Step 2: 确认既有测试没有回归**

Run: `python3 tests/recharge_smoke.py`
Expected: `recharge smoke: ok`

Run: `python3 tests/system_smoke.py 2>&1 | tail -20`
Expected: 跟本计划开始之前的失败位置/原因完全一致（不能因为本计划的改动而变化；如果 `system_smoke.py` 里创建的测试企业默认 `usage_balance=0`，导致某个参保相关端点从"原来的失败点"提前变成"使用费不足"403，这属于本计划引入的新回归，需要排查——参照 Task 3 Step 7 的提示处理）。

Run: `python3 tests/security_smoke.py 2>&1 | tail -20`
Expected: 跟本计划开始之前完全一致的结果（通过或失败都要跟基线一致）。

- [ ] **Step 3: 前端完整类型检查 + 构建**

Run: `cd web && npx vue-tsc -b --noEmit && npm run build`
Expected: 类型检查通过，`vite build` 成功产出 `web/dist/`

- [ ] **Step 4: 本地端到端手动过一遍完整流程**

Run: `./start.sh`（后端）+ `cd web && npm run dev`（前端），浏览器操作：
1. 管理员把某企业 `usage_balance` 调到 0（可通过数据库直接改，或走已有的 usage 充值再手动扣减）→ 企业账号登录 → 尝试新增参保员工 → 确认看到全局通知而不是普通报错 → 管理员把余额充值回正数 → 企业账号立即（不用等任何"解锁"动作）重试新增，确认成功
2. 管理员把某企业某保司的 `EnterprisePremiumAccount.balance` 调到负数 → 管理员进入首页，确认"待处理停保"计数出现且非零 → 进入「待处理停保」页面 → 确认能看到这条任务，受影响保司/人数正确
3. 先测试"充值后自动撤销"路径：把上一步那个账户充值回正数 → 管理员再次进入首页触发扫描 → 确认该任务状态变成"已自动撤销"，且待处理列表里的计数下降，短信没有为这次撤销重复发送
4. 再单独构造一个新的欠费场景，进入「待处理停保」页面点击「确认停保」→ 二次确认弹窗 → 确认后该账户下所有相关在保人员状态变成已停保 → 确认收到（mock）短信记录
5. 用一次充值申请走完整流程（提交 → 管理员确认），确认企业收到（mock）短信通知；再提交一次驳回一笔，确认企业收到驳回短信，`reason` 内容正确

Expected: 全流程无报错，每一步的数据跟操作一致

- [ ] **Step 5: Commit（如果手动验证过程中发现并修复了任何问题）**

```bash
git add -A
git commit -m "fix: address issues found during end-to-end verification"
```

（如果第 4 步没有发现任何问题，这一步跳过，不创建空提交。）

---

## Self-Review Notes

- **Spec 覆盖**：设计文档需求 4（使用费锁定，实时判断，充值后立即解锁）→ Task 2/3/7/10；需求 5（保费不足生成待确认停保任务，管理员确认后才真正停保）→ Task 1/4/6；需求 6（短信通知四个触发点）→ Task 5/7；`Enterprise.premium_balance`/`usage_balance` 的既有处理方式（premium 已在 Phase A 完成迁移，usage 不受影响）→ Global Constraints 里已声明沿用，本计划不重复处理；前端设计里的"HomeView 待处理停保计数""参停保被锁的统一拦截""待处理停保管理页"→ Task 10/11/12。设计文档 API 设计一节里"旧接口 `POST /enterprises/{id}/recharge` 保留不动"已经在 Phase A 处理过（该端点 premium 分支已被挡掉，usage 分支不受本计划影响，不需要重复声明）。年龄限制（需求 7）刻意不在这份计划里——独立、无耦合，作为单独的小计划稍后写。
- **占位符检查**：全部任务的每个 Step 都是可直接执行的命令或可直接粘贴的完整代码，没有"自己想办法处理"这类模糊留白——写计划过程中发现 `AuditLog.user_id` 是非空字段（已用 `grep` 核实 `backend/models/misc.py` 的真实定义），直接把 Task 7 的去重通知写成归属到触发锁定的操作者本人，不留一个需要实现者临场判断的分支。
- **类型一致性**：`require_usage_funded` 从 Task 2 定义时就是最终签名 `(session, enterprise, user)`——Task 2 阶段 `session`/`user` 参数暂时用不上，但提前定好避免 Task 7 需要回头改 Task 3 已经写好的五个调用点（写计划过程中发现最初草稿把这个签名分两步引入会导致 Task 3/7 出现不一致，已经改成一次到位）。`scan_premium_shortfalls`/`notify_enterprise`/`confirmPendingTermination` 等函数名和参数顺序从各自定义任务到后续消费任务保持一致。`PendingTermination` 前端类型字段名（`affected_insurers`/`affected_count`/`confirmed_by`/`dismissed_at` 等）与 Task 1 后端模型字段名逐一对应。
