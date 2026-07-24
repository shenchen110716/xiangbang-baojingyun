# 企业自助注册开户（响帮帮无忧保）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让企业主能在无需登录的公开页面提交入驻申请（单位信息 + 登录账号），复用系统里已有的
`Enterprise.status` pending/approved 审核字段，管理员在【企业管理】现有列表里审核通过后，企业主
账号自动激活可登录；官网营销页新增入口引导流量进来。

**Architecture:** 后端新增一个无鉴权的 `POST /api/enterprises/apply` 端点，在一个事务里创建
`Enterprise(status="pending")` 和一个 `active=False` 的企业主 `User`；复用现有的
`PATCH /enterprises/{id}/status` 审核端点，通过时联动把该账号 `active` 置为 `True`。前端新增一个
不挂在鉴权布局下的公开 Vue 页面，并给现有【企业管理】列表补一个此前从未接线的"审核通过"按钮
（`setEnterpriseStatus` API 早就存在，只是没有 UI 调用它）。不新建数据表、不新增数据库迁移。

**Tech Stack:** FastAPI + SQLAlchemy（后端），Vue 3 + Element Plus + vue-router（前端）。测试用本仓库
`tests/*_smoke.py` 的直接函数调用模式（不经 HTTP 层），独立临时 SQLite。

## Global Constraints

- 不新建数据表、不新增 Alembic 迁移——全部复用 `Enterprise.status` 与 `User.active` 现有字段。
- 不做营业执照上传/OCR——公开申请只收文本字段。
- 不加验证码/短信验证/限流——仅做服务端基础校验（必填项非空、用户名唯一、统一社会信用代码去重）。
- 不加短信/邮件通知（申请或审核结果）。
- `POST /api/enterprises/apply` 响应体只返回 `{"message": "..."}`，不得调用 `serialize()` 或返回完整
  `Enterprise`/`User` 字段——避免向未登录调用方暴露内部数据结构。
- 新前端公开路由必须同时做三处登记，缺一个都会导致 404 或被登录守卫拦截：
  `web/src/router/routes.ts`（路由本身）、`web/src/router/index.ts`（登录守卫白名单）、
  `web/src/App.vue`（`isAuthPage`，跳过 AppShell 布局）；此外还要加进
  `backend/app.py` 的 `_FRONTEND_ROUTES`（SPA 静态回退白名单），否则直接访问该 URL 会 404。
- 本计划不包含推送到生产（`git push origin main`）——发布环节需在计划执行完成后单独向用户确认。
- 开发前先按 CLAUDE.md 要求跑一次 `bash scripts/ai_coordination_check.sh`，并在独立工作树/分支下
  开发（不要直接在 `main` 上改）；本任务会修改 `backend/routers/enterprises.py`、
  `backend/schemas/enterprise.py`、`web/src/views/enterprises/EnterprisesPanel.vue`
  等公共文件，动手前确认没有其他代理正在改同一批文件。

---

## 文件结构

**后端**：
- `backend/schemas/enterprise.py` —— 加一个新 Pydantic 类 `EnterpriseApplyIn`。
- `backend/schemas/__init__.py` —— 导出 `EnterpriseApplyIn`。
- `backend/routers/enterprises.py` —— 新增 `apply_enterprise` 端点函数；修改现有
  `enterprise_status` 函数联动激活账号。
- `tests/enterprise_apply_smoke.py`（新建）—— 覆盖申请创建、去重、审核前登录失败、审核通过后
  登录成功、拒绝后仍锁定五个断言点。

**前端**：
- `web/src/api/enterprises.ts` —— 新增 `applyEnterprise()` 函数（`setEnterpriseStatus` 已存在，
  不用改）。
- `web/src/views/enterprises/EnterpriseApplyView.vue`（新建）—— 公开申请表单页。
- `web/src/views/enterprises/EnterprisesPanel.vue` —— 操作列加"审核通过"按钮。
- `web/src/router/routes.ts` —— 注册 `/enterprise-apply` 路由。
- `web/src/router/index.ts` —— 登录守卫加公开路由白名单。
- `web/src/App.vue` —— `isAuthPage` 加 `'enterprise-apply'`。
- `backend/app.py` —— `_FRONTEND_ROUTES` 加 `"/enterprise-apply"`。
- `web/public/xbbzp.html` —— 首页 spotlight 区块 + `#/baojingyun` 企业端 badge 各加一个 CTA。

---

### Task 1: 公开申请接口 `POST /api/enterprises/apply`

**Files:**
- Modify: `backend/schemas/enterprise.py`
- Modify: `backend/schemas/__init__.py`
- Modify: `backend/routers/enterprises.py`
- Test: `tests/enterprise_apply_smoke.py`（新建）

**Interfaces:**
- Produces: `backend.schemas.EnterpriseApplyIn(enterprise_name: str, credit_code: str = "", contact: str = "", phone: str = "", username: str, password: str)`
- Produces: `backend.routers.enterprises.apply_enterprise(data: EnterpriseApplyIn, session: Session) -> dict` —— 路由函数，也可直接函数调用（测试用）；成功返回 `{"message": "提交成功，请等待审核"}`，失败抛 `HTTPException(400|409, "...")`。

- [ ] **Step 1: 写第一个失败测试（申请创建 pending 企业 + 未激活账号）**

创建 `tests/enterprise_apply_smoke.py`：

```python
"""Smoke test: public enterprise self-signup — apply, review, login.

Covers the new POST /enterprises/apply flow end to end: a pending
Enterprise + inactive owner User get created together, duplicate
credit_code submissions are rejected, and approving the application
(PATCH /enterprises/{id}/status?status=approved) activates the owner
account so it can log in — while rejecting leaves it inactive.
"""
import os
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))


def run():
    with tempfile.TemporaryDirectory(prefix="xbb-enterprise-apply-") as folder:
        os.environ["DATABASE_URL"] = f"sqlite:///{Path(folder) / 'test.db'}"
        os.environ["ADMIN_PASSWORD"] = "admin123"
        os.environ["ENTERPRISE_PASSWORD"] = "enterprise123"

        from fastapi import HTTPException
        from sqlalchemy import select

        from backend.app import startup
        from backend.core.db import SessionLocal
        from backend.models import Enterprise, User
        from backend.routers.enterprises import apply_enterprise
        from backend.schemas import EnterpriseApplyIn

        startup()
        with SessionLocal() as session:
            apply_enterprise(
                EnterpriseApplyIn(
                    enterprise_name="自助入驻测试单位", credit_code="91XBBZP0001",
                    contact="王申请", phone="13700000001",
                    username="apply_owner", password="pass1234",
                ),
                session,
            )
            enterprise = session.scalar(select(Enterprise).where(Enterprise.name == "自助入驻测试单位"))
            assert enterprise is not None, "apply_enterprise 应该创建一条 Enterprise 记录"
            assert enterprise.status == "pending", f"新申请应处于待核验，实际 status={enterprise.status!r}"
            owner = session.scalar(select(User).where(User.username == "apply_owner"))
            assert owner is not None, "apply_enterprise 应该创建关联的企业主账号"
            assert owner.active is False, "审核通过前账号不能登录，实际 active=True"
            assert owner.is_owner is True and owner.enterprise_role == "owner", "首个账号必须是 owner"

            try:
                apply_enterprise(
                    EnterpriseApplyIn(
                        enterprise_name="重复单位", credit_code="91XBBZP0001",
                        contact="李重复", phone="13700000002",
                        username="apply_owner_dup", password="pass1234",
                    ),
                    session,
                )
                raise AssertionError("重复统一社会信用代码应该被拒绝，但没有抛出异常")
            except HTTPException as e:
                assert e.status_code == 409, f"重复申请应返回 409，实际 {e.status_code}"

    print("enterprise apply smoke test: PASS")


if __name__ == "__main__":
    run()
```

- [ ] **Step 2: 运行测试，确认因为 `apply_enterprise` 不存在而失败**

Run: `python3 tests/enterprise_apply_smoke.py`
Expected: `ImportError: cannot import name 'apply_enterprise' from 'backend.routers.enterprises'`

- [ ] **Step 3: 加 `EnterpriseApplyIn` schema**

在 `backend/schemas/enterprise.py` 末尾追加：

```python
class EnterpriseApplyIn(BaseModel): enterprise_name: str; credit_code: str = ""; contact: str = ""; phone: str = ""; username: str; password: str
```

在 `backend/schemas/__init__.py` 里：
- 第 3 行改为：
  ```python
  from .enterprise import EnterpriseIn, EnterpriseUpdate, RechargeIn, EnterpriseApplyIn
  ```
- 第 28 行改为：
  ```python
      "EnterpriseIn", "EnterpriseUpdate", "RechargeIn", "EnterpriseApplyIn",
  ```

- [ ] **Step 4: 实现 `apply_enterprise` 端点**

在 `backend/routers/enterprises.py` 里，`from ..schemas import ...` 那一行加上 `EnterpriseApplyIn`
（改成 `from ..schemas import AgentIn, EnterpriseApplyIn, EnterpriseIn, EnterpriseUpdate, RechargeIn`），
然后在 `add_enterprise` 函数**之前**插入：

```python
@router.post("/enterprises/apply")
def apply_enterprise(data: EnterpriseApplyIn, session: Session = Depends(db)):
    if not data.enterprise_name.strip() or not data.contact.strip() or not data.phone.strip() or not data.username.strip() or not data.password.strip():
        raise HTTPException(400, "请填写单位名称、联系人、联系电话、登录账号和密码")
    if session.scalar(select(User).where(User.username == data.username)):
        raise HTTPException(409, "该账号名已被占用，请更换登录账号")
    if data.credit_code.strip():
        existing = session.scalar(select(Enterprise).where(Enterprise.credit_code == data.credit_code, Enterprise.status != "rejected"))
        if existing:
            raise HTTPException(409, "该单位已提交过申请，请等待审核或联系客服")
    enterprise = Enterprise(name=data.enterprise_name, credit_code=data.credit_code, contact=data.contact, phone=data.phone, status="pending")
    session.add(enterprise)
    session.flush()
    owner = User(username=data.username, password_hash=pwd.hash(data.password), name=data.contact, phone=data.phone, role="enterprise", enterprise_id=enterprise.id, is_owner=True, enterprise_role="owner", active=False)
    session.add(owner)
    session.commit()
    audit(session, owner, "apply", "enterprise", str(enterprise.id))
    return {"message": "提交成功，请等待审核"}
```

- [ ] **Step 5: 运行测试，确认通过**

Run: `python3 tests/enterprise_apply_smoke.py`
Expected: `enterprise apply smoke test: PASS`

- [ ] **Step 6: 编译检查 + commit**

Run: `python3 -m compileall -q backend && git add backend/schemas/enterprise.py backend/schemas/__init__.py backend/routers/enterprises.py tests/enterprise_apply_smoke.py && git commit -m "feat(backend): add public enterprise self-signup endpoint"`
Expected: compileall 无输出（成功），commit 成功。

---

### Task 2: 审核通过时联动激活企业主账号

**Files:**
- Modify: `backend/routers/enterprises.py`
- Test: `tests/enterprise_apply_smoke.py`（追加断言）

**Interfaces:**
- Consumes: Task 1 的 `apply_enterprise`、`EnterpriseApplyIn`。
- Produces: 修改后的 `enterprise_status(item_id: int, status_value: str, user: User, session: Session)` —— `status_value == "approved"` 时，该企业下 `is_owner=True` 的账号 `active` 变为 `True`；其他值不改账号状态。

- [ ] **Step 1: 扩展测试，写审核前登录失败 + 审核通过后登录成功 + 拒绝仍锁定三段断言**

把 `tests/enterprise_apply_smoke.py` 的 `run()` 函数，从原来的 `import` 段开始替换为：

```python
def run():
    with tempfile.TemporaryDirectory(prefix="xbb-enterprise-apply-") as folder:
        os.environ["DATABASE_URL"] = f"sqlite:///{Path(folder) / 'test.db'}"
        os.environ["ADMIN_PASSWORD"] = "admin123"
        os.environ["ENTERPRISE_PASSWORD"] = "enterprise123"

        from fastapi import HTTPException
        from sqlalchemy import select

        from backend.app import startup
        from backend.core.db import SessionLocal
        from backend.models import Enterprise, User
        from backend.routers.auth import login
        from backend.routers.enterprises import apply_enterprise, enterprise_status
        from backend.schemas import EnterpriseApplyIn, LoginIn

        startup()
        with SessionLocal() as session:
            admin = session.scalar(select(User).where(User.role == "admin"))

            apply_enterprise(
                EnterpriseApplyIn(
                    enterprise_name="自助入驻测试单位", credit_code="91XBBZP0001",
                    contact="王申请", phone="13700000001",
                    username="apply_owner", password="pass1234",
                ),
                session,
            )
            enterprise = session.scalar(select(Enterprise).where(Enterprise.name == "自助入驻测试单位"))
            assert enterprise is not None, "apply_enterprise 应该创建一条 Enterprise 记录"
            assert enterprise.status == "pending", f"新申请应处于待核验，实际 status={enterprise.status!r}"
            owner = session.scalar(select(User).where(User.username == "apply_owner"))
            assert owner is not None, "apply_enterprise 应该创建关联的企业主账号"
            assert owner.active is False, "审核通过前账号不能登录，实际 active=True"
            assert owner.is_owner is True and owner.enterprise_role == "owner", "首个账号必须是 owner"

            try:
                apply_enterprise(
                    EnterpriseApplyIn(
                        enterprise_name="重复单位", credit_code="91XBBZP0001",
                        contact="李重复", phone="13700000002",
                        username="apply_owner_dup", password="pass1234",
                    ),
                    session,
                )
                raise AssertionError("重复统一社会信用代码应该被拒绝，但没有抛出异常")
            except HTTPException as e:
                assert e.status_code == 409, f"重复申请应返回 409，实际 {e.status_code}"

            try:
                login(LoginIn(username="apply_owner", password="pass1234", portal="enterprise"), session)
                raise AssertionError("审核通过前不应该能登录")
            except HTTPException as e:
                assert e.status_code == 403, f"未激活账号登录应返回 403，实际 {e.status_code}"

            enterprise_status(enterprise.id, "approved", admin, session)
            session.refresh(owner)
            assert owner.active is True, "审核通过后企业主账号应该被激活"

            token = login(LoginIn(username="apply_owner", password="pass1234", portal="enterprise"), session)
            assert token.access_token, "审核通过后应该能用申请时的账号密码登录"

            apply_enterprise(
                EnterpriseApplyIn(
                    enterprise_name="被拒单位", credit_code="91XBBZP0002",
                    contact="赵拒绝", phone="13700000003",
                    username="apply_owner_rejected", password="pass1234",
                ),
                session,
            )
            rejected_enterprise = session.scalar(select(Enterprise).where(Enterprise.name == "被拒单位"))
            enterprise_status(rejected_enterprise.id, "rejected", admin, session)
            rejected_owner = session.scalar(select(User).where(User.username == "apply_owner_rejected"))
            assert rejected_owner.active is False, "被拒绝的申请，账号必须保持不能登录"

    print("enterprise apply smoke test: PASS")
```

（文件顶部的 `import os` / `import sys` / `import tempfile` / `from pathlib import Path` /
`sys.path.insert(...)` 保持不动，只替换 `def run():` 到 `print(...)` 之间的内容。）

- [ ] **Step 2: 运行测试，确认因为账号未被激活而失败**

Run: `python3 tests/enterprise_apply_smoke.py`
Expected: `AssertionError: 审核通过后企业主账号应该被激活`

- [ ] **Step 3: 修改 `enterprise_status` 联动激活账号**

在 `backend/routers/enterprises.py` 里把现有的：

```python
@router.patch("/enterprises/{item_id}/status", dependencies=[Depends(require_role("admin", detail="仅总后台可审核投保单位"))])
def enterprise_status(item_id: int, status_value: str = Query(..., alias="status"), user: User = Depends(current_user), session: Session = Depends(db)):
    item = session.get(Enterprise, item_id)
    if not item: raise HTTPException(404, "企业不存在")
    item.status = status_value; session.commit(); audit(session, user, "status_change", "enterprise", str(item.id), status_value); return serialize(item)
```

替换为：

```python
@router.patch("/enterprises/{item_id}/status", dependencies=[Depends(require_role("admin", detail="仅总后台可审核投保单位"))])
def enterprise_status(item_id: int, status_value: str = Query(..., alias="status"), user: User = Depends(current_user), session: Session = Depends(db)):
    item = session.get(Enterprise, item_id)
    if not item: raise HTTPException(404, "企业不存在")
    item.status = status_value
    if status_value == "approved":
        owner = session.scalar(select(User).where(User.enterprise_id == item_id, User.role == "enterprise", User.is_owner.is_(True)))
        if owner: owner.active = True
    session.commit(); audit(session, user, "status_change", "enterprise", str(item.id), status_value); return serialize(item)
```

- [ ] **Step 4: 运行测试，确认通过**

Run: `python3 tests/enterprise_apply_smoke.py`
Expected: `enterprise apply smoke test: PASS`

- [ ] **Step 5: 跑一遍全量既有 smoke 测试，确认没有破坏其他行为**

Run: `python3 tests/system_smoke.py && python3 tests/security_smoke.py`
Expected: 两个都打印各自的 `PASS` 输出，无报错。

- [ ] **Step 6: commit**

```bash
git add backend/routers/enterprises.py tests/enterprise_apply_smoke.py
git commit -m "feat(backend): activate owner account when enterprise application is approved"
```

---

### Task 3: 【企业管理】加"审核通过"按钮

**Files:**
- Modify: `web/src/views/enterprises/EnterprisesPanel.vue`

**Interfaces:**
- Consumes: `enterprisesApi.setEnterpriseStatus(id: number, status: string): Promise<Enterprise>`（已存在，`web/src/api/enterprises.ts`，本任务不改这个文件）。

- [ ] **Step 1: 加处理函数**

在 `web/src/views/enterprises/EnterprisesPanel.vue` 里，紧接着 `removeEnterprise` 函数之后
（第 113-121 行附近）插入：

```ts
async function approveEnterprise(item: Enterprise) {
  try {
    await ElMessageBox.confirm(`确定审核通过投保单位「${item.name}」吗？通过后企业主账号将立即可以登录。`, '审核确认', { type: 'warning' })
  } catch { return }
  try {
    await enterprisesApi.setEnterpriseStatus(item.id, 'approved')
    ElMessage.success('已通过审核')
    load()
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}
```

- [ ] **Step 2: 加按钮**

在模板里第 173-179 行的操作列，`<el-button link type="primary" size="small" @click="openEdit(row)">编辑</el-button>`
**之前**插入：

```html
<el-button v-if="row.status === 'pending'" link type="success" size="small" @click="approveEnterprise(row)">审核通过</el-button>
```

- [ ] **Step 3: 类型检查**

Run: `cd web && npx vue-tsc -b --noEmit`
Expected: 无报错。

- [ ] **Step 4: 本地人工验证**

启动后端（`python3 -m uvicorn backend.app:app --host 127.0.0.1 --port 8001 --reload`）和前端
（`cd web && npm run dev`），用 admin/admin123 登录后台，打开【企业管理】，筛选"待核验"，确认能
看到 Task 2 测试里创建的记录不会出现（那是临时 SQLite，不影响开发库）——用页面上"＋新增投保单位"
先新建一个测试企业，确认列表操作列出现"审核通过"按钮，点击后确认状态变为"已核验"且按钮消失。

- [ ] **Step 5: commit**

```bash
git add web/src/views/enterprises/EnterprisesPanel.vue
git commit -m "feat(web): wire up the enterprise review approve action"
```

---

### Task 4: 公开申请页面 + 路由接线

**Files:**
- Modify: `web/src/api/enterprises.ts`
- Create: `web/src/views/enterprises/EnterpriseApplyView.vue`
- Modify: `web/src/router/routes.ts`
- Modify: `web/src/router/index.ts`
- Modify: `web/src/App.vue`
- Modify: `backend/app.py`

**Interfaces:**
- Consumes: Task 1 的 `POST /api/enterprises/apply`（返回 `{"message": string}`，失败时 axios 拦截器已把 `error.response.data.detail` 转成 `Error.message`，见 `web/src/api/client.ts`）。
- Produces: `applyEnterprise(data: { enterprise_name: string; credit_code?: string; contact: string; phone: string; username: string; password: string }): Promise<{ message: string }>`（`web/src/api/enterprises.ts`）。
- Produces: 路由 `{ path: '/enterprise-apply', name: 'enterprise-apply' }`。

- [ ] **Step 1: 加 API 封装**

在 `web/src/api/enterprises.ts` 末尾追加：

```ts
export function applyEnterprise(data: { enterprise_name: string; credit_code?: string; contact: string; phone: string; username: string; password: string }) {
  return client.post<{ message: string }>('/enterprises/apply', data).then((r) => r.data)
}
```

- [ ] **Step 2: 写申请页面组件**

创建 `web/src/views/enterprises/EnterpriseApplyView.vue`：

```vue
<script setup lang="ts">
import { reactive, ref } from 'vue'
import { applyEnterprise } from '@/api/enterprises'

const form = reactive({
  enterprise_name: '',
  credit_code: '',
  contact: '',
  phone: '',
  username: '',
  password: '',
})
const loading = ref(false)
const errorText = ref('')
const submitted = ref(false)

async function submit() {
  errorText.value = ''
  if (!form.enterprise_name || !form.contact || !form.phone || !form.username || !form.password) {
    errorText.value = '请填写单位名称、联系人、联系电话、登录账号和密码'
    return
  }
  loading.value = true
  try {
    await applyEnterprise(form)
    submitted.value = true
  } catch (e) {
    errorText.value = (e as Error).message
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="apply-screen">
    <a class="apply-brand" href="/xbbzp.html">
      <span class="brand-mark">响</span>
      <span class="brand-text">响帮帮<span class="brand-sub">XIANGBANGBANG · 无忧保</span></span>
    </a>

    <div class="apply-card" v-if="!submitted">
      <h1>企业免费入驻</h1>
      <p class="apply-lede">填写单位信息和登录账号，提交后由平台审核，通过后即可用下方账号登录企业后台。</p>

      <el-form label-position="top" @submit.prevent>
        <el-form-item label="单位名称" required>
          <el-input v-model="form.enterprise_name" placeholder="请输入单位全称" />
        </el-form-item>
        <el-form-item label="统一社会信用代码">
          <el-input v-model="form.credit_code" placeholder="选填" />
        </el-form-item>
        <el-form-item label="联系人" required>
          <el-input v-model="form.contact" />
        </el-form-item>
        <el-form-item label="联系电话" required>
          <el-input v-model="form.phone" />
        </el-form-item>
        <el-form-item label="登录账号" required>
          <el-input v-model="form.username" placeholder="审核通过后用此账号登录企业后台" />
        </el-form-item>
        <el-form-item label="登录密码" required>
          <el-input v-model="form.password" type="password" show-password />
        </el-form-item>
      </el-form>

      <p v-if="errorText" class="apply-error">{{ errorText }}</p>
      <el-button type="primary" size="large" :loading="loading" @click="submit" style="width:100%">提交申请</el-button>
      <a class="apply-back" href="/xbbzp.html">&larr; 返回官网</a>
    </div>

    <div class="apply-card apply-success" v-else>
      <h1>提交成功</h1>
      <p class="apply-lede">请等待平台审核，通过后可用刚才填写的账号密码登录企业后台。</p>
      <router-link class="apply-back" :to="{ name: 'login', query: { portal: 'enterprise' } }">前往登录页 &rarr;</router-link>
    </div>
  </div>
</template>

<style scoped>
.apply-screen {
  min-height: 100vh;
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 48px 20px;
  background: var(--el-bg-color-page, #f5f6f8);
}
.apply-brand {
  display: flex;
  align-items: center;
  gap: 10px;
  text-decoration: none;
  color: inherit;
  margin-bottom: 32px;
}
.brand-mark {
  width: 32px;
  height: 32px;
  border-radius: 8px;
  background: #1f2a44;
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  font-weight: 700;
}
.brand-text { font-weight: 700; }
.brand-sub {
  display: block;
  font-size: 11px;
  font-weight: 400;
  opacity: 0.6;
  letter-spacing: 0.04em;
}
.apply-card {
  width: 100%;
  max-width: 440px;
  background: var(--el-bg-color, #fff);
  border-radius: 16px;
  padding: 36px;
  box-shadow: 0 8px 30px rgba(0, 0, 0, 0.08);
}
.apply-card h1 { font-size: 22px; margin-bottom: 8px; }
.apply-lede { font-size: 13.5px; color: var(--el-text-color-secondary); margin-bottom: 24px; }
.apply-error { color: var(--el-color-danger); font-size: 13px; margin-bottom: 12px; }
.apply-back { display: block; text-align: center; margin-top: 16px; font-size: 13px; color: var(--el-text-color-secondary); text-decoration: none; }
.apply-success { text-align: center; }
</style>
```

- [ ] **Step 3: 注册路由**

在 `web/src/router/routes.ts` 里，`{ path: '/login', ... }`（第 45 行）之后插入：

```ts
  { path: '/enterprise-apply', name: 'enterprise-apply', component: () => import('@/views/enterprises/EnterpriseApplyView.vue'), meta: { title: '企业免费入驻' } },
```

- [ ] **Step 4: 登录守卫加白名单**

把 `web/src/router/index.ts` 的：

```ts
router.beforeEach((to) => {
  const hasToken = !!localStorage.getItem(TOKEN_KEY)
  if (to.name !== 'login' && !hasToken) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }
  if (to.name === 'login' && hasToken) {
    return { name: 'home' }
  }
  return true
})
```

替换为：

```ts
const PUBLIC_ROUTE_NAMES = new Set(['login', 'enterprise-apply'])

router.beforeEach((to) => {
  const hasToken = !!localStorage.getItem(TOKEN_KEY)
  if (!PUBLIC_ROUTE_NAMES.has(to.name as string) && !hasToken) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }
  if (to.name === 'login' && hasToken) {
    return { name: 'home' }
  }
  return true
})
```

- [ ] **Step 5: `App.vue` 跳过 AppShell 布局**

把 `web/src/App.vue` 的：

```ts
const isAuthPage = computed(() => route.name === 'login' || route.name === 'certificate' || route.name === 'agent-portal' || route.name === 'insurer-portal')
```

替换为：

```ts
const isAuthPage = computed(() => route.name === 'login' || route.name === 'certificate' || route.name === 'agent-portal' || route.name === 'insurer-portal' || route.name === 'enterprise-apply')
```

- [ ] **Step 6: 后端 SPA 路由白名单**

把 `backend/app.py` 的：

```python
_FRONTEND_ROUTES = {
    "/", "/home", "/screen", "/team", "/dispatch", "/workers", "/work-relations",
    "/agents", "/insurance", "/policy", "/claims", "/insurers", "/insurer-management", "/exports",
    "/report", "/billing", "/recharge", "/pending-terminations", "/promotion",
    "/operators", "/message", "/settings", "/login", "/agent-portal",
    "/timeliness", "/system-settings", "/insurer-portal",
}
```

替换为：

```python
_FRONTEND_ROUTES = {
    "/", "/home", "/screen", "/team", "/dispatch", "/workers", "/work-relations",
    "/agents", "/insurance", "/policy", "/claims", "/insurers", "/insurer-management", "/exports",
    "/report", "/billing", "/recharge", "/pending-terminations", "/promotion",
    "/operators", "/message", "/settings", "/login", "/agent-portal",
    "/timeliness", "/system-settings", "/insurer-portal", "/enterprise-apply",
}
```

- [ ] **Step 7: 构建 + 类型检查**

Run: `cd web && npm run build`
Expected: 构建成功，无报错。

- [ ] **Step 8: 本地人工验证全链路**

启动后端和 `npm run dev`，浏览器打开无痕窗口访问 `http://127.0.0.1:5173/enterprise-apply`
（不要带任何登录态），确认：
1. 页面直接展示申请表单，不会被重定向到登录页。
2. 不填必填项点提交，出现"请填写单位名称..."错误提示。
3. 正确填写并提交，出现"提交成功"页面。
4. 用 admin/admin123 登录后台，【企业管理】待核验列表能看到刚提交的单位，点"审核通过"。
5. 回到 `/enterprise-apply` 生成的登录页链接，用刚才申请的账号密码、选择"参保单位端"登录成功。

- [ ] **Step 9: commit**

```bash
git add web/src/api/enterprises.ts web/src/views/enterprises/EnterpriseApplyView.vue web/src/router/routes.ts web/src/router/index.ts web/src/App.vue backend/app.py
git commit -m "feat(web): add public enterprise self-signup page and routing"
```

---

### Task 5: 官网营销页新增入驻入口

**Files:**
- Modify: `web/public/xbbzp.html`

**Interfaces:**
- Consumes: Task 4 的 `/enterprise-apply` 路由。

- [ ] **Step 1: 首页 spotlight 区块加 CTA**

把 `web/public/xbbzp.html` 第 395-406 行的：

```html
  <section style="padding-top:0">
    <div class="shell">
      <div class="spotlight">
        <div>
          <div class="eyebrow" style="color:var(--amber)">旗下产品</div>
          <h2>响帮帮无忧保</h2>
          <p>专为灵活用工人员设计的参保管理云平台——岗位定级、参停保、理赔工作台、资金账本，平台管理端、参保单位端、保司端、微信小程序多端登录入口，见下一页。</p>
        </div>
        <a href="#/baojingyun#access" class="btn btn-amber">进入登录入口</a>
      </div>
    </div>
  </section>
```

替换为：

```html
  <section style="padding-top:0">
    <div class="shell">
      <div class="spotlight">
        <div>
          <div class="eyebrow" style="color:var(--amber)">旗下产品</div>
          <h2>响帮帮无忧保</h2>
          <p>专为灵活用工人员设计的参保管理云平台——岗位定级、参停保、理赔工作台、资金账本，平台管理端、参保单位端、保司端、微信小程序多端登录入口，见下一页。</p>
        </div>
        <div class="hero-actions">
          <a href="/enterprise-apply" class="btn btn-amber">企业免费入驻</a>
          <a href="#/baojingyun#access" class="btn btn-ghost">进入登录入口</a>
        </div>
      </div>
    </div>
  </section>
```

（`.hero-actions` 是已有的 `display:flex; gap:14px; flex-wrap:wrap` 类，直接复用不新增样式。）

- [ ] **Step 2: `#/baojingyun` 企业端 badge 加入驻链接**

把第 570-579 行左右的（用 `grep -n "02 · 参保单位端" web/public/xbbzp.html` 定位到准确行号）：

```html
        <div class="badge badge-enterprise">
          <div class="badge-eyebrow">02 · 参保单位端</div>
          <h3>企业 / HR 后台</h3>
          <p class="badge-desc">面向参保单位的 HR 或负责人，管理岗位、批量参停保、查看报表与账户余额，支持一人多单位切换。</p>
          <div class="badge-perf">
            <span class="chip">批量参停保</span>
            <span class="chip">员工报表</span>
            <span class="chip">多单位切换</span>
          </div>
          <a class="badge-cta" href="/login?portal=enterprise" target="_blank" rel="noopener">登录企业后台 →</a>
        </div>
```

替换为：

```html
        <div class="badge badge-enterprise">
          <div class="badge-eyebrow">02 · 参保单位端</div>
          <h3>企业 / HR 后台</h3>
          <p class="badge-desc">面向参保单位的 HR 或负责人，管理岗位、批量参停保、查看报表与账户余额，支持一人多单位切换。</p>
          <div class="badge-perf">
            <span class="chip">批量参停保</span>
            <span class="chip">员工报表</span>
            <span class="chip">多单位切换</span>
          </div>
          <a class="badge-cta" href="/login?portal=enterprise" target="_blank" rel="noopener">登录企业后台 →</a>
          <a href="/enterprise-apply" target="_blank" rel="noopener" style="margin-top:12px;font-family:var(--mono);font-size:12px;font-weight:700;color:var(--steel);text-decoration:none;">还没有账号？企业免费入驻 →</a>
        </div>
```

**关键约束提醒**：这个文件第 583 行附近是内嵌的 base64 二维码图片（单行约 35000 字符）。上面两处
编辑的 `old_string`/`new_string` 都不涉及这一行，编辑时只用锚点文本匹配，绝不要把二维码那一行
内容读入或写入 diff。

- [ ] **Step 3: 构建验证**

Run: `cd web && npm run build`
Expected: 构建成功（`xbbzp.html` 在 `public/` 下原样拷贝，不经 Vite 处理，构建成功只是确认没有
误改到其他文件）。

- [ ] **Step 4: 本地人工验证**

浏览器打开 `web/dist/xbbzp.html`（或 `http://127.0.0.1:8001/xbbzp.html`，需要先跑一次
`npm run build` 并启动后端），确认：
1. 首页 spotlight 区块出现"企业免费入驻"和"进入登录入口"两个并排按钮，点"企业免费入驻"跳到
   `/enterprise-apply`。
2. 跳到 `#/baojingyun#access`，参保单位端卡片里"登录企业后台"按钮下方出现"还没有账号？企业免费
   入驻"链接，点击能跳转。
3. 切换深色模式和窄屏（900px/640px 断点），两处新增元素显示正常，不溢出。

- [ ] **Step 5: commit**

```bash
git add web/public/xbbzp.html
git commit -m "feat(web): add enterprise self-signup CTA to marketing site"
```

---

## Self-Review Notes

- **Spec coverage**：目标 1-4 分别对应 Task 1（申请接口）、Task 2（审核联动激活）、Task 3+4（复用
  待核验列表 + 补齐缺失的审核按钮）、Task 5（营销页入口）。范围边界里的"不做 OCR/不建新表/不加
  验证码/不加通知"均未在任何任务里引入，符合 spec。
- **Type consistency**：`EnterpriseApplyIn` 字段名（`enterprise_name`/`credit_code`/`contact`/
  `phone`/`username`/`password`）在 Task 1 的 schema 定义、测试调用、Task 4 的前端 `applyEnterprise`
  参数类型和表单字段名之间保持一致。`enterprise_status` 的调用签名（`item_id, status_value, user,
  session`）在 Task 2 的直接函数调用测试里与路由定义的参数顺序一致。
- **发布**：所有 commit 都停在本地分支，不包含 push/合并到 `main` 的步骤，符合 Global Constraints
  和 CLAUDE.md 的发布授权要求。
