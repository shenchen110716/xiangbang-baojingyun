# 业务员登录门户 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a `role="salesperson"` account log in through a third portal option and land on a standalone, minimal page showing only their own bound 投保单位/产品 and佣金明细，with self-service password change.

**Architecture:** The account, password hash, and commission-aggregation logic all already exist (`User.role="salesperson"`, `commissions.agent_commission_rows()`/`agent_commission_summary()`). This plan adds exactly one new login-portal branch, one new self-service read endpoint (`GET /agents/me`, scoped to `current_user.id` — never accepts an external id), and one new standalone frontend route that bypasses the admin/enterprise `AppShell` entirely.

**Tech Stack:** FastAPI + SQLAlchemy (backend), Vue 3 `<script setup>` + TypeScript + Element Plus + Pinia + Vue Router (frontend).

## Global Constraints

- 不新增数据表、不改现有佣金计算逻辑 — `AgentCommission`/`commission_accrual()`/`agent_commission_rows()`/`agent_commission_summary()` 原样复用，不修改。
- 不做业务员自助修改手机号/姓名等资料 — 范围内只有查看 + 改密码。
- 不改动 admin 视角下现有的 `/agents`、`/agents/{id}/commissions`、`/agent-commissions` 端点的权限逻辑 — 只新增 `/agents/me`，不touch旧端点。
- 前端不复用 `AppShell`（侧边栏+多导航项），走独立极简页面。
- `GET /agents/me` 只能查自己（`current_user.id`），不接受外部传入 id，杜绝业务员查别人。
- Java 后端本次不镜像（当前会话的其余工作项已经排到 Phase B/C/D 之后，业务员门户的 Java 镜像作为独立后续任务，不在本计划任务列表内）。

---

## File Structure

**Backend (2 files modified):**
- `backend/schemas/auth.py` — `LoginIn.portal` 的 `Literal` 类型扩展一个值
- `backend/routers/auth.py` — `login()` 新增一个 `elif` 分支
- `backend/routers/agents.py` — 新增 `GET /agents/me`

**Backend test (1 new file):**
- `tests/salesperson_portal_smoke.py` — 独立冒烟测试，跟 `tests/recharge_smoke.py` 同一个模式（隔离 DB、`run()` 内部才 import）

**Frontend (6 files modified, 2 new):**
- `web/src/api/types.ts` — `Role` 类型加一个值，新增 `AgentMeResponse` 接口
- `web/src/api/auth.ts` — `login()` 的 `portal` 参数类型扩展
- `web/src/api/agents.ts` — 新增 `getMyCommissions()`
- `web/src/stores/auth.ts` — `login()` 的 `portal` 参数类型扩展
- `web/src/components/PasswordChangeDialog.vue`（新建）— 最小可复用改密码弹窗
- `web/src/views/auth/LoginView.vue` — 新增第三个门户选项 + 登录后按角色分流跳转
- `web/src/router/routes.ts` — 新增 `/agent-portal` 路由
- `web/src/App.vue` — `isAuthPage` 判断加入 `agent-portal`
- `web/src/layouts/AppShell.vue` — `onMounted` 里加一条"业务员误入 AppShell 路由则重定向"的检查
- `web/src/views/agent-portal/AgentPortalView.vue`（新建）— 独立极简页面本体

---

### Task 1: 后端 — 业务员登录门户

**Files:**
- Modify: `backend/schemas/auth.py:6`
- Modify: `backend/routers/auth.py:23-30`
- Test: `tests/salesperson_portal_smoke.py`（新建）

**Interfaces:**
- Consumes: 无（复用已有 `User.role`/`pwd.verify`/`_issue_token`）
- Produces: `POST /auth/login` 现在接受 `portal="salesperson"`，非 salesperson 账号传该 portal 会被 403 拒绝；salesperson 账号传 `admin`/`enterprise` 同样会被 403 拒绝（既有逻辑已经覆盖，不用改）

- [ ] **Step 1: 修改 `LoginIn` schema**

打开 `backend/schemas/auth.py`，第 6 行：

```python
class LoginIn(BaseModel): username: str; password: str; portal: Literal["admin","enterprise"] = "admin"
```

改为：

```python
class LoginIn(BaseModel): username: str; password: str; portal: Literal["admin","enterprise","salesperson"] = "admin"
```

- [ ] **Step 2: `login()` 新增 salesperson 分支**

打开 `backend/routers/auth.py`，找到：

```python
@router.post("/auth/login", response_model=TokenOut)
def login(data: LoginIn, session: Session = Depends(db)):
    user = session.scalar(select(User).where(User.username == data.username))
    if not user or not pwd.verify(data.password, user.password_hash): raise HTTPException(401, "账号或密码错误")
    if not user.active: raise HTTPException(403, "该账号已停用，请联系单位主管")
    if data.portal == "admin" and user.role != "admin": raise HTTPException(403, "该账号不是总后台账号")
    if data.portal == "enterprise" and user.role != "enterprise": raise HTTPException(403, "该账号不是参保单位账号")
    return TokenOut(access_token=_issue_token(user))
```

在最后一个 `if data.portal ==` 判断后面加一行：

```python
@router.post("/auth/login", response_model=TokenOut)
def login(data: LoginIn, session: Session = Depends(db)):
    user = session.scalar(select(User).where(User.username == data.username))
    if not user or not pwd.verify(data.password, user.password_hash): raise HTTPException(401, "账号或密码错误")
    if not user.active: raise HTTPException(403, "该账号已停用，请联系单位主管")
    if data.portal == "admin" and user.role != "admin": raise HTTPException(403, "该账号不是总后台账号")
    if data.portal == "enterprise" and user.role != "enterprise": raise HTTPException(403, "该账号不是参保单位账号")
    if data.portal == "salesperson" and user.role != "salesperson": raise HTTPException(403, "该账号不是业务员账号")
    return TokenOut(access_token=_issue_token(user))
```

- [ ] **Step 3: 写冒烟测试**

创建 `tests/salesperson_portal_smoke.py`：

```python
"""Smoke test for the salesperson portal login feature.

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
    with tempfile.TemporaryDirectory(prefix="xbb-salesperson-smoke-") as folder:
        os.environ["DATABASE_URL"] = f"sqlite:///{Path(folder) / 'test.db'}"
        os.environ["ADMIN_PASSWORD"] = "admin123"
        os.environ["ENTERPRISE_PASSWORD"] = "enterprise123"

        from fastapi import HTTPException
        from sqlalchemy import select

        from backend.app import startup
        from backend.core.db import SessionLocal
        from backend.core.security import pwd
        from backend.models import User
        from backend.routers.auth import login
        from backend.schemas import LoginIn

        startup()
        with SessionLocal() as session:
            salesperson = User(username="test_salesperson", password_hash=pwd.hash("sp12345"), name="测试业务员", role="salesperson")
            session.add(salesperson); session.commit(); session.refresh(salesperson)

            enterprise_admin = session.scalar(select(User).where(User.username == "enterprise"))
            assert enterprise_admin is not None

            # salesperson logging in with portal="salesperson" succeeds
            token = login(LoginIn(username="test_salesperson", password="sp12345", portal="salesperson"), session)
            assert token.access_token

            # salesperson logging in with portal="admin" or "enterprise" is rejected
            try:
                login(LoginIn(username="test_salesperson", password="sp12345", portal="admin"), session)
                assert False, "expected 403"
            except HTTPException as e:
                assert e.status_code == 403

            try:
                login(LoginIn(username="test_salesperson", password="sp12345", portal="enterprise"), session)
                assert False, "expected 403"
            except HTTPException as e:
                assert e.status_code == 403

            # a non-salesperson account logging in with portal="salesperson" is rejected
            try:
                login(LoginIn(username="enterprise", password="enterprise123", portal="salesperson"), session)
                assert False, "expected 403"
            except HTTPException as e:
                assert e.status_code == 403

    print("salesperson portal smoke: ok")


if __name__ == "__main__":
    run()
```

- [ ] **Step 4: 跑测试确认通过**

Run: `python3 tests/salesperson_portal_smoke.py`
Expected: `salesperson portal smoke: ok`

- [ ] **Step 5: Commit**

```bash
git add backend/schemas/auth.py backend/routers/auth.py tests/salesperson_portal_smoke.py
git commit -m "feat: allow salesperson accounts to log in via a third portal"
```

---

### Task 2: 后端 — `GET /agents/me` 自助佣金查询

**Files:**
- Modify: `backend/routers/agents.py`
- Test: `tests/salesperson_portal_smoke.py`（追加）

**Interfaces:**
- Consumes: `agent_commission_rows(session, agent_id) -> list[dict]`、`agent_commission_summary(session, agent_id) -> dict`（已存在，`backend/services/commissions.py`，本任务不改）
- Produces: `GET /agents/me` → `{"summary": {...}, "rows": [...]}`，`summary`/`rows` 的 dict 形状分别就是 `agent_commission_summary()`/`agent_commission_rows()` 的原样返回值

- [ ] **Step 1: 新增端点**

打开 `backend/routers/agents.py`，在 `agent_status()` 函数（`@router.patch("/agents/{item_id}/status"...)`）后面、`@router.get("/agent-commissions"...)` 前面插入：

```python
@router.get("/agents/me", dependencies=[Depends(require_role("salesperson", detail="仅业务员账号可查看"))])
def my_commissions(user: User = Depends(current_user), session: Session = Depends(db)):
    return {"summary": agent_commission_summary(session, user.id), "rows": agent_commission_rows(session, user.id)}
```

（`agent_commission_summary`/`agent_commission_rows`/`current_user`/`require_role` 都已经在这个文件顶部 import 过了，不需要新增 import。）

- [ ] **Step 2: 追加测试**

打开 `tests/salesperson_portal_smoke.py`，在 `from backend.schemas import LoginIn` 那行下面追加两个 import：

```python
        from backend.models import AgentCommission, Enterprise, InsurancePlan
        from backend.routers.agents import my_commissions
```

在 `startup()` 和 `with SessionLocal() as session:` 之间不用改，在 `with SessionLocal() as session:` 块内、`print("salesperson portal smoke: ok")` 之前追加：

```python
            # GET /agents/me only returns this salesperson's own bound data
            plan = InsurancePlan(insurer="测试保司", name="测试产品", price=100.0)
            session.add(plan); session.commit(); session.refresh(plan)

            bound_enterprise = Enterprise(name="业务员测试企业", kind="企业", contact="", phone="", status="active")
            session.add(bound_enterprise); session.commit(); session.refresh(bound_enterprise)

            commission = AgentCommission(agent_id=salesperson.id, enterprise_id=bound_enterprise.id, plan_id=plan.id, rate=0.1, mode="rebate", sale_price=100.0, status="active")
            session.add(commission); session.commit()

            result = my_commissions(salesperson, session)
            assert result["summary"]["enterprise_count"] == 1, result
            assert result["summary"]["product_count"] == 1, result
            assert len(result["rows"]) == 1, result
            assert result["rows"][0]["enterprise_name"] == "业务员测试企业", result
            assert result["rows"][0]["plan_name"] == "测试产品", result

            # a different salesperson with no bound commissions sees an empty result, never another agent's data
            other_salesperson = User(username="other_salesperson", password_hash=pwd.hash("sp12345"), name="另一个业务员", role="salesperson")
            session.add(other_salesperson); session.commit(); session.refresh(other_salesperson)
            other_result = my_commissions(other_salesperson, session)
            assert other_result["summary"]["enterprise_count"] == 0, other_result
            assert other_result["rows"] == [], other_result
```

- [ ] **Step 3: 跑测试确认通过**

Run: `python3 tests/salesperson_portal_smoke.py`
Expected: `salesperson portal smoke: ok`

- [ ] **Step 4: Commit**

```bash
git add backend/routers/agents.py tests/salesperson_portal_smoke.py
git commit -m "feat: add GET /agents/me self-service commission endpoint"
```

---

### Task 3: 前端 — 共享类型与 API 客户端

**Files:**
- Modify: `web/src/api/types.ts`
- Modify: `web/src/api/auth.ts`
- Modify: `web/src/api/agents.ts`
- Modify: `web/src/stores/auth.ts`

**Interfaces:**
- Consumes: 后端 `GET /agents/me` 返回的 `{summary, rows}` 形状（Task 2）
- Produces: `AgentMeResponse` 类型、`getMyCommissions(): Promise<AgentMeResponse>`、`Role` 类型新增 `'salesperson'`，供 Task 5/6 使用

- [ ] **Step 1: 扩展 `Role` 类型，新增 `AgentMeResponse`**

打开 `web/src/api/types.ts`，找到第 1 行：

```ts
export type Role = 'admin' | 'enterprise'
```

改为：

```ts
export type Role = 'admin' | 'enterprise' | 'salesperson'
```

在 `AgentCommission` 接口定义后面（`created_at: string` 那行的闭合 `}` 之后，`Agent` 接口之前）新增：

```ts
export interface AgentMeResponse {
  summary: {
    enterprise_count: number
    product_count: number
    insured_count: number
    total_commission: number
  }
  rows: AgentCommission[]
}
```

- [ ] **Step 2: `login()` 的 portal 参数类型扩展**

打开 `web/src/api/auth.ts`，找到：

```ts
export function login(username: string, password: string, portal: 'admin' | 'enterprise') {
  return client.post<{ access_token: string; token_type: string }>('/auth/login', { username, password, portal }).then((r) => r.data)
}
```

改为：

```ts
export function login(username: string, password: string, portal: 'admin' | 'enterprise' | 'salesperson') {
  return client.post<{ access_token: string; token_type: string }>('/auth/login', { username, password, portal }).then((r) => r.data)
}
```

- [ ] **Step 3: 新增 `getMyCommissions()`**

打开 `web/src/api/agents.ts`，第一行 import 从：

```ts
import type { Agent, AgentCommission } from './types'
```

改为：

```ts
import type { Agent, AgentCommission, AgentMeResponse } from './types'
```

在文件末尾追加：

```ts
export function getMyCommissions() {
  return client.get<AgentMeResponse>('/agents/me').then((r) => r.data)
}
```

- [ ] **Step 4: auth store 的 `login()` portal 参数类型扩展**

打开 `web/src/stores/auth.ts`，找到：

```ts
  async function login(username: string, password: string, portal: 'admin' | 'enterprise') {
```

改为：

```ts
  async function login(username: string, password: string, portal: 'admin' | 'enterprise' | 'salesperson') {
```

- [ ] **Step 5: 类型检查**

Run: `cd web && npx vue-tsc -b --noEmit`
Expected: 0 errors（这一步之后还没有任何地方真正传 `'salesperson'` 给这几个函数，所以只是类型放宽，不会引入新错误；但如果报错要检查是不是改错了签名）

- [ ] **Step 6: Commit**

```bash
git add web/src/api/types.ts web/src/api/auth.ts web/src/api/agents.ts web/src/stores/auth.ts
git commit -m "feat: widen Role and login() to support the salesperson portal"
```

---

### Task 4: 前端 — 可复用改密码弹窗

**Files:**
- Create: `web/src/components/PasswordChangeDialog.vue`

**Interfaces:**
- Consumes: `changePassword(current_password, new_password): Promise<{ok: boolean}>`（`web/src/api/auth.ts`，已存在，不用改）
- Produces: `<PasswordChangeDialog v-model="visible" />` — 一个标准 `v-model` 组件，父组件用一个 `ref<boolean>` 控制显隐，组件内部处理表单、提交、成功/失败提示

- [ ] **Step 1: 创建组件**

```vue
<script setup lang="ts">
import { reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { changePassword } from '@/api/auth'

const props = defineProps<{ modelValue: boolean }>()
const emit = defineEmits<{ 'update:modelValue': [value: boolean] }>()

const visible = ref(props.modelValue)
watch(
  () => props.modelValue,
  (v) => {
    visible.value = v
  },
)
watch(visible, (v) => emit('update:modelValue', v))

const form = reactive({ current_password: '', new_password: '' })
const submitting = ref(false)

function reset() {
  form.current_password = ''
  form.new_password = ''
}

async function submit() {
  if (!form.current_password) {
    ElMessage.error('请输入当前密码')
    return
  }
  if (form.new_password.length < 6) {
    ElMessage.error('新密码至少 6 位')
    return
  }
  submitting.value = true
  try {
    await changePassword(form.current_password, form.new_password)
    ElMessage.success('密码已修改')
    visible.value = false
    reset()
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <el-dialog v-model="visible" title="修改密码" width="380px" @close="reset">
    <el-form label-width="90px">
      <el-form-item label="当前密码">
        <el-input v-model="form.current_password" type="password" show-password placeholder="请输入当前密码" />
      </el-form-item>
      <el-form-item label="新密码">
        <el-input v-model="form.new_password" type="password" show-password placeholder="至少 6 位" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="submitting" @click="submit">确认修改</el-button>
    </template>
  </el-dialog>
</template>
```

- [ ] **Step 2: 类型检查**

Run: `cd web && npx vue-tsc -b --noEmit`
Expected: 0 errors（这个组件还没有任何地方引用它，纯新增文件应该直接通过）

- [ ] **Step 3: Commit**

```bash
git add web/src/components/PasswordChangeDialog.vue
git commit -m "feat: add reusable PasswordChangeDialog component"
```

---

### Task 5: 前端 — 登录页第三门户 + 路由接线

**Files:**
- Modify: `web/src/views/auth/LoginView.vue`
- Modify: `web/src/router/routes.ts`
- Modify: `web/src/App.vue`
- Modify: `web/src/layouts/AppShell.vue`

**Interfaces:**
- Consumes: `AgentPortalView.vue`（Task 6 产出，这里只接线路由，组件本身 Task 6 才创建 — 注意本任务的 `routes.ts` 改动引用了 `@/views/agent-portal/AgentPortalView.vue`，在 Task 6 完成前这个动态 import 路径指向的文件还不存在，`vue-tsc`/`vite build` 都不会因为文件不存在而在这一步报错（动态 `import()` 字符串不做静态存在性检查），但 `npm run dev` 手动打开 `/agent-portal` 会 404 —这是预期的，Task 6 做完才能端到端验证）

- [ ] **Step 1: LoginView.vue 加第三个门户选项**

打开 `web/src/views/auth/LoginView.vue`，第 12-16 行：

```ts
const form = reactive({
  portal: (route.query.portal === 'enterprise' ? 'enterprise' : 'admin') as 'admin' | 'enterprise',
  username: isLocal ? 'admin' : '',
  password: isLocal ? 'admin123' : '',
})
```

改为：

```ts
const form = reactive({
  portal: (route.query.portal === 'enterprise'
    ? 'enterprise'
    : route.query.portal === 'salesperson'
      ? 'salesperson'
      : 'admin') as 'admin' | 'enterprise' | 'salesperson',
  username: isLocal ? 'admin' : '',
  password: isLocal ? 'admin123' : '',
})
```

第 20-33 行的 `portals` 数组：

```ts
const portals = [
  {
    key: 'admin' as const,
    eyebrow: '01 · 平台管理端',
    title: '总后台',
    desc: '岗位审核、保单与理赔管理、资金核算',
  },
  {
    key: 'enterprise' as const,
    eyebrow: '02 · 参保单位端',
    title: '企业 / HR 后台',
    desc: '批量参停保、员工报表、多单位切换',
  },
]
```

改为：

```ts
const portals = [
  {
    key: 'admin' as const,
    eyebrow: '01 · 平台管理端',
    title: '总后台',
    desc: '岗位审核、保单与理赔管理、资金核算',
  },
  {
    key: 'enterprise' as const,
    eyebrow: '02 · 参保单位端',
    title: '企业 / HR 后台',
    desc: '批量参停保、员工报表、多单位切换',
  },
  {
    key: 'salesperson' as const,
    eyebrow: '03 · 业务员端',
    title: '业务员',
    desc: '查看本人绑定单位、产品与佣金明细',
  },
]
```

第 37-49 行的 `submit()`：

```ts
async function submit() {
  errorText.value = ''
  loading.value = true
  try {
    await auth.login(form.username, form.password, form.portal)
    const redirect = (route.query.redirect as string) || '/home'
    router.push(redirect)
  } catch (err) {
    errorText.value = err instanceof Error ? err.message : '登录失败'
  } finally {
    loading.value = false
  }
}
```

改为：

```ts
async function submit() {
  errorText.value = ''
  loading.value = true
  try {
    await auth.login(form.username, form.password, form.portal)
    if (auth.user?.role === 'salesperson') {
      router.push('/agent-portal')
    } else {
      const redirect = (route.query.redirect as string) || '/home'
      router.push(redirect)
    }
  } catch (err) {
    errorText.value = err instanceof Error ? err.message : '登录失败'
  } finally {
    loading.value = false
  }
}
```

`<style scoped>` 块里的 `.portal-picker`（第 282-287 行）：

```css
.portal-picker {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px;
  margin-bottom: 28px;
}
```

改为：

```css
.portal-picker {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 10px;
  margin-bottom: 28px;
}
```

- [ ] **Step 2: 新增 `/agent-portal` 路由**

打开 `web/src/router/routes.ts`，找到 `/login` 那一行：

```ts
  { path: '/login', name: 'login', component: () => import('@/views/auth/LoginView.vue'), meta: { title: '登录' } },
```

在它后面插入（不带 `group`，跟 `login` 一样不出现在 `AppShell` 的导航里）：

```ts
  { path: '/agent-portal', name: 'agent-portal', component: () => import('@/views/agent-portal/AgentPortalView.vue'), meta: { title: '业务员工作台' } },
```

- [ ] **Step 3: App.vue 绕过 AppShell**

打开 `web/src/App.vue`，找到：

```ts
const isAuthPage = computed(() => route.name === 'login' || route.name === 'certificate')
```

改为：

```ts
const isAuthPage = computed(() => route.name === 'login' || route.name === 'certificate' || route.name === 'agent-portal')
```

- [ ] **Step 4: AppShell.vue 防止业务员误入管理端路由**

打开 `web/src/layouts/AppShell.vue`，找到：

```ts
onMounted(async () => {
  if (!auth.user) {
    await auth.loadProfile().catch(() => {})
  }
  loadMessageCount()
  loadLinkedAccounts()
})
```

改为：

```ts
onMounted(async () => {
  if (!auth.user) {
    await auth.loadProfile().catch(() => {})
  }
  if (auth.user?.role === 'salesperson') {
    router.replace({ name: 'agent-portal' })
    return
  }
  loadMessageCount()
  loadLinkedAccounts()
})
```

（这个文件顶部已经有 `const router = useRouter()`，不用新增 import。这一条覆盖"业务员账号手动输入 `/home` 等 URL 或用旧书签"的情况——`AppShell` 是所有非 `agent-portal`/`login`/`certificate` 路由的外壳，在这里拦一次就覆盖了全部管理端路由。）

- [ ] **Step 5: 类型检查**

Run: `cd web && npx vue-tsc -b --noEmit`
Expected: 0 errors

- [ ] **Step 6: Commit**

```bash
git add web/src/views/auth/LoginView.vue web/src/router/routes.ts web/src/App.vue web/src/layouts/AppShell.vue
git commit -m "feat: wire up the salesperson portal route and post-login redirect"
```

---

### Task 6: 前端 — 业务员工作台页面

**Files:**
- Create: `web/src/views/agent-portal/AgentPortalView.vue`

**Interfaces:**
- Consumes: `getMyCommissions()`（Task 3）、`PasswordChangeDialog`（Task 4）、`useAuthStore()`（已存在）、`StatTile`/`PageCard`（已存在共享组件）、`money()`（`web/src/utils/format.ts`，已存在）
- Produces: 完整的独立页面，路由已在 Task 5 接好

- [ ] **Step 1: 创建页面**

```vue
<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useAuthStore } from '@/stores/auth'
import { getMyCommissions } from '@/api/agents'
import type { AgentMeResponse } from '@/api/types'
import { money } from '@/utils/format'
import StatTile from '@/components/StatTile.vue'
import PageCard from '@/components/PageCard.vue'
import PasswordChangeDialog from '@/components/PasswordChangeDialog.vue'

const router = useRouter()
const auth = useAuthStore()

const data = ref<AgentMeResponse | null>(null)
const loading = ref(true)
const passwordDialogVisible = ref(false)

async function load() {
  loading.value = true
  try {
    if (!auth.user) await auth.loadProfile()
    if (auth.user?.role !== 'salesperson') {
      router.replace({ name: 'home' })
      return
    }
    data.value = await getMyCommissions()
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    loading.value = false
  }
}

onMounted(load)

function logout() {
  ElMessageBox.confirm('确定要退出登录吗？', '退出登录', { type: 'warning' }).then(() => {
    auth.logout()
    router.push({ name: 'login' })
  })
}
</script>

<template>
  <div class="agent-portal">
    <header class="portal-header">
      <div class="portal-brand">响帮帮保经云 · 业务员工作台</div>
      <div class="portal-actions">
        <span class="portal-user">{{ auth.user?.name }}</span>
        <el-button size="small" @click="passwordDialogVisible = true">修改密码</el-button>
        <el-button size="small" @click="logout">退出登录</el-button>
      </div>
    </header>

    <main class="portal-body" v-loading="loading">
      <div class="stat-grid">
        <StatTile label="绑定投保单位" :value="data?.summary.enterprise_count ?? '—'" />
        <StatTile label="绑定产品数" :value="data?.summary.product_count ?? '—'" />
        <StatTile label="在保人数" :value="data?.summary.insured_count ?? '—'" />
        <StatTile label="佣金总额" :value="data ? money(data.summary.total_commission) : '—'" />
      </div>

      <PageCard title="佣金明细" :count="data?.rows.length">
        <el-table :data="data?.rows ?? []" size="small" style="width: 100%">
          <el-table-column prop="enterprise_name" label="投保单位" min-width="140" />
          <el-table-column prop="plan_name" label="产品方案" min-width="140" />
          <el-table-column prop="insurer" label="保司" min-width="100" />
          <el-table-column prop="insured_count" label="在保人数" width="100" />
          <el-table-column label="佣金" width="120">
            <template #default="{ row }">{{ money(row.agent_commission_total) }}</template>
          </el-table-column>
          <el-table-column prop="status" label="状态" width="90" />
        </el-table>
        <el-empty v-if="data && !data.rows.length" description="暂无绑定的投保单位或产品" :image-size="60" />
      </PageCard>
    </main>

    <PasswordChangeDialog v-model="passwordDialogVisible" />
  </div>
</template>

<style scoped>
.agent-portal {
  min-height: 100vh;
  background: var(--el-bg-color-page);
}
.portal-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 16px 28px;
  background: #fff;
  border-bottom: 1px solid var(--el-border-color-lighter);
}
.portal-brand {
  font-weight: 700;
  font-size: 15px;
}
.portal-actions {
  display: flex;
  align-items: center;
  gap: 12px;
}
.portal-user {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}
.portal-body {
  max-width: 1080px;
  margin: 0 auto;
  padding: 24px 28px 40px;
  display: flex;
  flex-direction: column;
  gap: 20px;
}
.stat-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 14px;
}
@media (max-width: 720px) {
  .stat-grid {
    grid-template-columns: repeat(2, 1fr);
  }
}
</style>
```

- [ ] **Step 2: 类型检查 + 构建**

Run: `cd web && npx vue-tsc -b --noEmit && npm run build`
Expected: 类型检查通过，构建成功产出 `web/dist/`

- [ ] **Step 3: Commit**

```bash
git add web/src/views/agent-portal/AgentPortalView.vue
git commit -m "feat: add the standalone salesperson portal page"
```

---

### Task 7: 完整回归验证

**Files:**
- Test: `tests/salesperson_portal_smoke.py`, `tests/system_smoke.py`, `tests/security_smoke.py`

**Interfaces:**
- Consumes: 全部前置任务

- [ ] **Step 1: 跑本计划的冒烟测试**

Run: `python3 tests/salesperson_portal_smoke.py`
Expected: `salesperson portal smoke: ok`

- [ ] **Step 2: 确认既有测试没有回归**

Run: `python3 tests/system_smoke.py`
Expected: 跟本计划开始之前一样，在 `add_person(...)` 那一行遇到同一个既存的、与本计划无关的身份证校验失败（`HTTPException: 400: 身份证号格式不正确）——如果错误信息或位置变了，说明本计划的改动引入了新的回归，需要排查（这个既存 bug 不在本计划范围内，不需要在这里修）。

Run: `python3 tests/security_smoke.py`
Expected: 跟本计划开始之前一样的失败情况（不能因为本计划的改动而变化）。

- [ ] **Step 3: 前端完整类型检查 + 构建**

Run: `cd web && npx vue-tsc -b --noEmit && npm run build`
Expected: 类型检查通过，`vite build` 成功产出 `web/dist/`

- [ ] **Step 4: 本地端到端手动过一遍完整流程**

Run: `./start.sh`（后端）+ `cd web && npm run dev`（前端），浏览器操作：
1. 管理员登录 → 业务员管理页 → 新建一个业务员账号，记下账号密码
2. 管理员在 `业务员管理`/`推广与佣金` 页给该业务员绑定 1-2 条投保单位/产品的佣金关系
3. 退出登录 → 打开登录页 → 选"业务员"门户 → 用刚才的账号密码登录 → 确认直接跳转到 `/agent-portal`，不是 `/home`
4. 确认页面上只显示这个业务员自己绑定的单位/产品/佣金，统计数字跟第 2 步配置的一致
5. 点"修改密码" → 输入当前密码和新密码 → 确认成功 → 退出登录 → 用新密码重新登录成功
6. 尝试直接在地址栏输入 `/home`（业务员账号登录状态下）→ 确认被重定向回 `/agent-portal`，不是显示空白管理后台
7. 用 `role=admin` 或 `role=enterprise` 的账号尝试选"业务员"门户登录 → 确认被 403 拒绝

Expected: 全流程无报错，每一步的数据跟操作一致

- [ ] **Step 5: Commit（如果手动验证过程中发现并修复了任何问题）**

```bash
git add -A
git commit -m "fix: address issues found during end-to-end verification"
```

（如果第 4 步没有发现任何问题，这一步跳过，不创建空提交。）

---

## Self-Review Notes

- **Spec 覆盖**：设计文档"登录"（`/auth/login` 新增 portal 分支）→ Task 1；"自助佣金查询"（`GET /agents/me`，复用 `agent_commission_rows`/`agent_commission_summary`，只查自己）→ Task 2；"前端设计"里门户选择第三选项、独立路由不挂 `AppShell`、页面内容（StatTile + 表格 + 修改密码入口）、登录后按角色分流跳转 → Task 3/5/6；"密码修改"复用现有 `POST /auth/change-password`（前端 `changePassword()` 已存在）→ Task 4 直接调用，没有新写后端逻辑，符合设计文档"不需要新写后端逻辑"的范围声明；"错误处理"两条（非 salesperson 角色误入 `/agent-portal` 前端拦截、`GET /agents/me` 后端 403 走现有 401/403 拦截逻辑）→ 前者是 Task 6 Step 1 的 `load()` 里处理，后者是既有 `api/client.ts` 拦截器已经覆盖，不用新写。设计文档"Java 后端"一节明确本次只做人工审查/后续镜像，不在这份计划任务列表内，已经在 Global Constraints 里注明。
- **占位符检查**：全部任务的每个 Step 都是可直接执行的命令或可直接粘贴的完整代码，没有"实现类似逻辑""补充测试"这类模糊指代。
- **类型一致性**：`AgentMeResponse` 在 Task 3 定义后，在 Task 6 `AgentPortalView.vue` 里字段名（`summary.enterprise_count`/`summary.product_count`/`summary.insured_count`/`summary.total_commission`/`rows`）逐一对应，没有改名不同步。`getMyCommissions()`/`PasswordChangeDialog`/`StatTile`/`PageCard`/`money()` 的函数名和 props/emit 名称从定义处到 Task 6 消费处保持一致。`Role` 类型新增的 `'salesperson'` 值在 Task 3（类型定义）、Task 5（`LoginView.vue`/`AppShell.vue` 里 `auth.user?.role === 'salesperson'` 判断）、Task 6（`AgentPortalView.vue` 里同样的判断）三处字符串字面量拼写一致。
