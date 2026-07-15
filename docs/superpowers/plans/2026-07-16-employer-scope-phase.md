# Employer Scope Phase Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add historical project-manager-to-employer authorization and enforce it across employer-scoped enterprise operations, with exactly one active primary manager per actual employer.

**Architecture:** Preserve the existing `admin | enterprise | salesperson` top-level login roles for compatibility and add `enterprise_role = owner | project_manager` to enterprise users. Centralize employer scope resolution in `backend/services/employer_scopes.py`; routers consume that service instead of duplicating role logic. Store assignments as historical rows with `assigned_at` and `revoked_at`, and change primary manager in one transaction.

**Tech Stack:** FastAPI, SQLAlchemy 2, Alembic, Pydantic, SQLite smoke tests, Vue 3/TypeScript/Vite.

## Global Constraints

- Work from a new external worktree and `codex/role-timeliness-v42-scope` branch, never directly from `main`.
- Before creating the worktree, update `docs/ai-handoffs/recharge-accounts-phase-a.md` only if independent evidence confirms its merged state; do not infer completion from stale handoff text.
- Resolve ownership of `backend/core/security.py`, `backend/routers/auth.py`, `backend/schemas/auth.py`, `web/src/api/types.ts`, `web/src/router/routes.ts`, `web/src/layouts/AppShell.vue`, and `web/src/stores/auth.ts` with the active `salesperson-portal` branch before editing them.
- At execution time, run `alembic heads`; the new migration must have exactly one `down_revision`, equal to the sole latest merged head. Abort migration creation if there is more than one head or an unmerged migration owner.
- Preserve existing enterprise-owner behavior during the role backfill: `is_owner = true` becomes `enterprise_role = owner`; other enterprise users become `project_manager`.
- A project manager with zero active scopes receives zero rows and cannot create, update, upload, or delete employer-scoped objects.
- Platform administrators remain global but every scope mutation is audited.
- Phase 1 does not implement employment facts, timeliness calculations, commission statements, or production deployment.

---

### Task 1: Claim the Phase and Establish Red Security Tests

**Files:**
- Modify: `docs/ai-handoffs/role-timeliness-v42.md`
- Create: `tests/employer_scope_smoke.py`

**Interfaces:**
- Consumes: Existing HTTP login and enterprise/operator/actual-employer endpoints.
- Produces: A black-box regression executable that later tasks must make pass.

- [ ] **Step 1: Run coordination preflight and verify the dependency state**

Run:

```bash
bash scripts/ai_coordination_check.sh
git status --short --branch
git worktree list
git log -5 --oneline --decorate
```

Expected: no tracked changes in `main`; recharge merge appears in history; no other active branch owns migrations. If shared auth/Web files remain owned by `salesperson-portal`, record those files as blocked and perform backend non-overlapping tasks first.

- [ ] **Step 2: Update the handoff before code changes**

Set the handoff fields to the real worktree values and declare this phase's files:

```markdown
- status: `active`
- branch: `codex/role-timeliness-v42-scope`
- worktree: `/Users/madisonshen/Desktop/Demo-worktrees/codex-role-timeliness-v42-scope`
- migration_owner: `yes`

## Active Phase 1 Scope

- enterprise role compatibility and employer-scope migration
- employer-scope model, service, schemas and router
- employer filtering for positions, insured people, enrollment and claims
- owner scope-management Web UI
```

- [ ] **Step 3: Write a failing black-box authorization test**

Create `tests/employer_scope_smoke.py` using the server lifecycle and `call_json` helpers from `tests/security_smoke.py`. The core assertions must be:

```python
owner = create_enterprise_owner("scope_owner")
manager = create_operator(owner, "scope_manager")
employer_a = create_actual_employer(owner, "项目 A")
employer_b = create_actual_employer(owner, "项目 B")

assert patch_operator(manager["id"], {"enterprise_role": "project_manager"}).status == 200
assert create_scope(owner, manager["id"], employer_a["id"], "primary").status == 200

assert ids(get_actual_employers(manager.token)) == {employer_a["id"]}
assert create_position(manager.token, employer_a["id"]).status == 200
assert create_position(manager.token, employer_b["id"]).status == 403
assert create_scope(manager.token, manager["id"], employer_b["id"], "collaborator").status == 403

assert revoke_scope(owner, manager["id"], employer_a["id"]).status == 200
assert get_actual_employers(manager.token).json() == []
assert get_positions(manager.token).json() == []
```

Also assert cross-enterprise assignment is rejected, two simultaneous primary managers are rejected by ordinary scope creation, and the dedicated primary-manager replacement endpoint closes the old primary before creating the new one.

- [ ] **Step 4: Run the test to prove the capability is absent**

Run:

```bash
python3 tests/employer_scope_smoke.py
```

Expected: FAIL because `enterprise_role` and `/api/employer-scopes` do not exist.

- [ ] **Step 5: Commit the red test and active handoff**

```bash
git add tests/employer_scope_smoke.py docs/ai-handoffs/role-timeliness-v42.md
git commit -m "test: define project manager employer scope security"
```

### Task 2: Add the Role and Scope Schema

**Files:**
- Create: `backend/migrations_alembic/versions/d5a4c12f7b91_add_employer_scopes.py`
- Modify: `backend/models/user.py`
- Modify: `backend/models/enterprise.py`
- Modify: `backend/models/__init__.py`
- Modify: `backend/core/migrations.py`
- Test: `tests/employer_scope_smoke.py`

**Interfaces:**
- Consumes: `User`, `Enterprise`, and `ActualEmployer` primary keys.
- Produces: `User.enterprise_role: str | None` and `UserEmployerScope` historical assignment rows.

- [ ] **Step 1: Verify the migration head before generating a revision**

Run:

```bash
alembic -c alembic.ini heads
```

Expected: exactly one head. Store that revision as the generated migration's `down_revision`; do not hard-code the July 16 head if `main` has advanced.

- [ ] **Step 2: Extend the red test with database invariants**

Add direct SQLAlchemy assertions after API setup:

```python
assert manager_row.enterprise_role == "project_manager"
active = session.scalars(
    select(UserEmployerScope).where(
        UserEmployerScope.user_id == manager_row.id,
        UserEmployerScope.status == "active",
        UserEmployerScope.revoked_at.is_(None),
    )
).all()
assert len(active) == 1
assert active[0].actual_employer_id == employer_a["id"]
assert active[0].responsibility_type == "primary"
```

- [ ] **Step 3: Add the SQLAlchemy fields and model**

Add to `User`:

```python
enterprise_role: Mapped[Optional[str]] = mapped_column(String(30), nullable=True)
```

Add to `backend/models/enterprise.py`:

```python
class UserEmployerScope(Base):
    __tablename__ = "user_employer_scopes"
    id: Mapped[int] = mapped_column(primary_key=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id"), index=True)
    enterprise_id: Mapped[int] = mapped_column(ForeignKey("enterprises.id"), index=True)
    actual_employer_id: Mapped[int] = mapped_column(ForeignKey("actual_employers.id"), index=True)
    responsibility_type: Mapped[str] = mapped_column(String(20), default="collaborator")
    granted_by: Mapped[int] = mapped_column(ForeignKey("users.id"))
    assigned_at: Mapped[datetime] = mapped_column(DateTime, default=lambda: datetime.now(timezone.utc))
    revoked_at: Mapped[Optional[datetime]] = mapped_column(DateTime, nullable=True)
    status: Mapped[str] = mapped_column(String(20), default="active")
    created_at: Mapped[datetime] = mapped_column(DateTime, default=lambda: datetime.now(timezone.utc))
```

Export `UserEmployerScope` from `backend/models/__init__.py`.

- [ ] **Step 4: Create the Alembic migration**

The upgrade must add nullable `users.enterprise_role`, backfill enterprise users, then create scope indexes and constraints:

```python
op.add_column("users", sa.Column("enterprise_role", sa.String(30), nullable=True))
op.execute("UPDATE users SET enterprise_role = CASE WHEN is_owner = 1 THEN 'owner' ELSE 'project_manager' END WHERE role = 'enterprise'")
op.create_check_constraint(
    "ck_users_enterprise_role",
    "users",
    "enterprise_role IS NULL OR enterprise_role IN ('owner', 'project_manager')",
)
op.create_table(
    "user_employer_scopes",
    # columns matching the SQLAlchemy model
    sa.CheckConstraint("responsibility_type IN ('primary', 'collaborator')", name="ck_scope_responsibility_type"),
    sa.CheckConstraint("status IN ('active', 'revoked')", name="ck_scope_status"),
)
op.create_index(
    "ix_scope_active_lookup",
    "user_employer_scopes",
    ["user_id", "enterprise_id", "actual_employer_id", "status"],
)
```

For PostgreSQL, add a partial unique index for one active primary per employer. Because SQLite test databases do not execute production Alembic in every smoke test, the service in Task 3 must enforce the same invariant transactionally.

- [ ] **Step 5: Keep SQLite compatibility initialization aligned**

Extend `backend/core/migrations.py` only for the repository's existing SQLite compatibility path: add the new user column and create the scope table/indexes when absent. Do not add a second PostgreSQL migration history.

- [ ] **Step 6: Verify schema upgrade and downgrade on a temporary database**

Run:

```bash
tmpdb="$(mktemp -d)/scope.db"
DATABASE_URL="sqlite:///$tmpdb" alembic -c alembic.ini upgrade head
DATABASE_URL="sqlite:///$tmpdb" alembic -c alembic.ini downgrade -1
DATABASE_URL="sqlite:///$tmpdb" alembic -c alembic.ini upgrade head
```

Expected: all three commands exit 0.

- [ ] **Step 7: Commit the schema**

```bash
git add backend/models/user.py backend/models/enterprise.py backend/models/__init__.py backend/core/migrations.py backend/migrations_alembic/versions
git commit -m "feat: add historical employer scope schema"
```

### Task 3: Centralize Scope Resolution and Mutation

**Files:**
- Create: `backend/services/employer_scopes.py`
- Modify: `backend/services/__init__.py`
- Create: `tests/employer_scope_service_test.py`

**Interfaces:**
- Consumes: `User`, `ActualEmployer`, `UserEmployerScope`, and SQLAlchemy `Session`.
- Produces: `is_enterprise_owner`, `allowed_employer_ids`, `assert_employer_access`, `replace_primary_manager`, `grant_employer_scope`, and `revoke_employer_scope`.

- [ ] **Step 1: Write focused failing service tests**

Create fixtures for one owner, two managers, two employers in one enterprise, and one employer in another enterprise. Test these exact contracts:

```python
assert is_enterprise_owner(owner) is True
assert is_enterprise_owner(manager) is False
assert allowed_employer_ids(session, owner) is None
assert allowed_employer_ids(session, manager_without_scope) == set()
assert allowed_employer_ids(session, manager_with_a) == {employer_a.id}

assert_employer_access(session, manager_with_a, employer_a.id)
with pytest.raises(HTTPException) as denied:
    assert_employer_access(session, manager_with_a, employer_b.id)
assert denied.value.status_code == 403
```

Test grant rejection for cross-enterprise user/employer pairs, duplicate active rows, and a second ordinary primary. Test `replace_primary_manager` leaves exactly one active primary and preserves the revoked row.

- [ ] **Step 2: Run service tests and confirm they fail**

Run:

```bash
pytest -q tests/employer_scope_service_test.py
```

Expected: FAIL with missing `backend.services.employer_scopes`.

- [ ] **Step 3: Implement owner detection and scope lookup**

```python
def is_enterprise_owner(user: User) -> bool:
    return user.role == "enterprise" and (user.enterprise_role == "owner" or user.is_owner)

def allowed_employer_ids(session: Session, user: User) -> set[int] | None:
    if user.role == "admin" or is_enterprise_owner(user):
        return None
    if user.role != "enterprise" or user.enterprise_role != "project_manager":
        raise HTTPException(403, "无权访问实际工作单位数据")
    return set(session.scalars(select(UserEmployerScope.actual_employer_id).where(
        UserEmployerScope.user_id == user.id,
        UserEmployerScope.enterprise_id == user.enterprise_id,
        UserEmployerScope.status == "active",
        UserEmployerScope.revoked_at.is_(None),
    )))

def assert_employer_access(session: Session, user: User, actual_employer_id: int) -> ActualEmployer:
    employer = session.get(ActualEmployer, actual_employer_id)
    if not employer:
        raise HTTPException(404, "实际工作单位不存在")
    if user.role == "enterprise" and employer.enterprise_id != user.enterprise_id:
        raise HTTPException(403, "无权访问其他企业数据")
    allowed = allowed_employer_ids(session, user)
    if allowed is not None and employer.id not in allowed:
        raise HTTPException(403, "未获授权访问该实际工作单位")
    return employer
```

- [ ] **Step 4: Implement transactional mutations**

`grant_employer_scope` must validate user and employer enterprise equality, reject an active duplicate, and reject a second active primary. `revoke_employer_scope` sets `status="revoked"` and `revoked_at=now` without deleting. `replace_primary_manager` locks active primary rows where supported, revokes them, then creates one primary row in the same transaction.

```python
def replace_primary_manager(session: Session, actor: User, employer: ActualEmployer, manager: User) -> UserEmployerScope:
    assert_scope_manager(actor, employer.enterprise_id)
    assert_project_manager(manager, employer.enterprise_id)
    now = datetime.now(timezone.utc)
    active = session.scalars(select(UserEmployerScope).where(
        UserEmployerScope.actual_employer_id == employer.id,
        UserEmployerScope.responsibility_type == "primary",
        UserEmployerScope.status == "active",
        UserEmployerScope.revoked_at.is_(None),
    ).with_for_update()).all()
    for row in active:
        row.status = "revoked"
        row.revoked_at = now
    scope = UserEmployerScope(
        user_id=manager.id,
        enterprise_id=employer.enterprise_id,
        actual_employer_id=employer.id,
        responsibility_type="primary",
        granted_by=actor.id,
        assigned_at=now,
    )
    session.add(scope)
    session.flush()
    return scope
```

- [ ] **Step 5: Run focused tests**

Run:

```bash
pytest -q tests/employer_scope_service_test.py
```

Expected: PASS.

- [ ] **Step 6: Commit the service**

```bash
git add backend/services/employer_scopes.py backend/services/__init__.py tests/employer_scope_service_test.py
git commit -m "feat: centralize employer scope authorization"
```

### Task 4: Expose Owner-Managed Scope APIs

**Files:**
- Create: `backend/schemas/employer_scope.py`
- Modify: `backend/schemas/__init__.py`
- Create: `backend/routers/employer_scopes.py`
- Modify: `backend/routers/__init__.py`
- Modify: `backend/app.py`
- Modify: `backend/schemas/operator.py`
- Modify: `backend/routers/operators.py`
- Test: `tests/employer_scope_smoke.py`

**Interfaces:**
- Consumes: Task 3 scope service.
- Produces: `/api/employer-scopes` CRUD, `/api/actual-employers/{id}/primary-manager`, and operator `enterprise_role` management.

- [ ] **Step 1: Add failing API assertions for response fields and audit behavior**

Assert scope responses contain:

```python
{
    "id", "user_id", "user_name", "enterprise_id", "actual_employer_id",
    "actual_employer_name", "responsibility_type", "assigned_at",
    "revoked_at", "status",
}
```

Assert owner list is limited to their enterprise, platform admin can filter by `enterprise_id`, project managers receive 403 from mutation endpoints, and audit logs contain create/revoke/replace-primary actions.

- [ ] **Step 2: Add Pydantic schemas**

```python
class EmployerScopeIn(BaseModel):
    user_id: int
    actual_employer_id: int
    responsibility_type: Literal["primary", "collaborator"] = "collaborator"

class PrimaryManagerIn(BaseModel):
    user_id: int

class EmployerScopeOut(BaseModel):
    id: int
    user_id: int
    user_name: str
    enterprise_id: int
    actual_employer_id: int
    actual_employer_name: str
    responsibility_type: Literal["primary", "collaborator"]
    assigned_at: datetime
    revoked_at: datetime | None
    status: Literal["active", "revoked"]
```

Add `enterprise_role: Literal["owner", "project_manager"] | None` to operator update/output contracts. Enterprise users cannot promote themselves or another user to owner; only admin may change ownership, while enterprise owners may manage project-manager scopes.

- [ ] **Step 3: Implement the router with service-only mutations**

The router must call Task 3 functions, commit once per request, and audit after successful mutation. It must not duplicate scope SQL.

```python
@router.post("/employer-scopes", response_model=EmployerScopeOut)
def create_scope(data: EmployerScopeIn, user=Depends(current_user), session=Depends(db)):
    scope = grant_employer_scope(session, user, data.user_id, data.actual_employer_id, data.responsibility_type)
    session.commit()
    session.refresh(scope)
    audit(session, user, "create", "user_employer_scope", str(scope.id))
    return scope_payload(session, scope)
```

Implement list, revoke, and replace-primary endpoints with the same pattern.

- [ ] **Step 4: Register the router and update operator management**

Export the router from `backend/routers/__init__.py`, include it in `backend/app.py`, return `enterprise_role` from `operator_dict`, and permit only authorized role changes in `backend/routers/operators.py`.

- [ ] **Step 5: Run API and existing security tests**

Run:

```bash
python3 tests/employer_scope_smoke.py
python3 tests/security_smoke.py
```

Expected: new API assertions that do not depend on downstream router filtering pass; existing security smoke remains PASS.

- [ ] **Step 6: Commit the API**

```bash
git add backend/schemas/employer_scope.py backend/schemas/__init__.py backend/routers/employer_scopes.py backend/routers/__init__.py backend/app.py backend/schemas/operator.py backend/routers/operators.py tests/employer_scope_smoke.py
git commit -m "feat: add employer scope management API"
```

### Task 5: Enforce Scope Across Employer-Scoped Operations

**Files:**
- Modify: `backend/routers/positions.py`
- Modify: `backend/routers/insured.py`
- Modify: `backend/routers/enrollment.py`
- Modify: `backend/routers/claims.py`
- Modify: `backend/services/claims.py`
- Modify: `tests/employer_scope_smoke.py`
- Modify: `tests/security_smoke.py`

**Interfaces:**
- Consumes: `allowed_employer_ids(session, user)` and `assert_employer_access(session, user, id)`.
- Produces: fail-closed filtering and object authorization for Phase 1 core operations.

- [ ] **Step 1: Expand the black-box test to every operation family**

Create approved positions and insured people in employers A and B, plus claims linked through those people. Assert the project manager scoped only to A:

```python
assert employer_ids(GET("/api/actual-employers", manager)) == {a.id}
assert employer_ids(GET("/api/positions", manager)) == {a.id}
assert employer_ids(GET("/api/insured", manager)) == {a.id}
assert employer_ids(GET("/api/claims", manager)) == {a.id}
assert POST(position_for_b, manager).status == 403
assert PATCH(person_in_b, manager).status == 403
assert POST(claim_for_b, manager).status == 403
assert GET(claim_document_in_b, manager).status == 403
```

After revocation, all four lists must be empty and every object endpoint must return 403. Owner and admin paths must retain their existing access.

- [ ] **Step 2: Run the test and verify current overexposure**

Run:

```bash
python3 tests/employer_scope_smoke.py
```

Expected: FAIL because current routers filter only by enterprise.

- [ ] **Step 3: Filter collection endpoints**

For queries whose rows contain `actual_employer_id`, apply:

```python
allowed = allowed_employer_ids(session, user)
if allowed is not None:
    stmt = stmt.where(Model.actual_employer_id.in_(allowed))
```

For `InsuredPerson` and `Claim`, join through `WorkPosition` or the persisted employer snapshot defined by the current schema. Do not filter by employer display-name strings.

- [ ] **Step 4: Guard object and mutation endpoints**

Resolve the target's actual employer and call:

```python
assert_employer_access(session, user, actual_employer_id)
```

before returning details, uploading files, mutating state, exporting, emailing enrollment lists, or deleting records. If a legacy row has no resolvable `actual_employer_id`, owners/admins may access it, but project managers must receive 403 rather than inheriting it.

- [ ] **Step 5: Run the focused and full Python regressions**

Run:

```bash
python3 tests/employer_scope_smoke.py
python3 tests/security_smoke.py
python3 tests/system_smoke.py
python3 tests/recharge_smoke.py
```

Expected: all commands exit 0.

- [ ] **Step 6: Commit router enforcement**

```bash
git add backend/routers/positions.py backend/routers/insured.py backend/routers/enrollment.py backend/routers/claims.py backend/services/claims.py tests/employer_scope_smoke.py tests/security_smoke.py
git commit -m "fix: enforce project manager employer data scope"
```

### Task 6: Add the Owner Scope Management UI

**Files:**
- Modify: `web/src/api/types.ts`
- Modify: `web/src/api/operators.ts`
- Create: `web/src/api/employerScopes.ts`
- Modify: `web/src/views/operators/OperatorsView.vue`
- Modify: `web/src/layouts/AppShell.vue`
- Modify: `web/src/stores/auth.ts`
- Test: `tests/employer_scope_smoke.py`

**Interfaces:**
- Consumes: Task 4 APIs and response types.
- Produces: enterprise-owner UI for assigning employers and replacing the primary manager; project-manager navigation constrained to permitted capabilities.

- [ ] **Step 1: Confirm shared-file ownership before editing**

Run:

```bash
git diff --name-only main...worktree-salesperson-portal
```

Expected: if any planned UI file is still owned by the active salesperson task, stop this task until that branch is merged or the handoff transfers ownership. Do not resolve semantic conflicts with ours/theirs.

- [ ] **Step 2: Add exact frontend types and API calls**

```ts
export type EnterpriseRole = 'owner' | 'project_manager'

export interface EmployerScope {
  id: number
  user_id: number
  user_name: string
  enterprise_id: number
  actual_employer_id: number
  actual_employer_name: string
  responsibility_type: 'primary' | 'collaborator'
  assigned_at: string
  revoked_at: string | null
  status: 'active' | 'revoked'
}
```

`web/src/api/employerScopes.ts` must expose `listEmployerScopes`, `createEmployerScope`, `revokeEmployerScope`, and `replacePrimaryManager`, all through the existing authenticated API client.

- [ ] **Step 3: Add owner-only assignment controls**

In `OperatorsView.vue`, label enterprise users as “企业主管” or “项目负责人”. For project managers, show active employer chips, responsibility type, revoke action, and an assignment dialog. Replacing a primary manager must call the dedicated endpoint, not revoke/create from the browser.

- [ ] **Step 4: Constrain project-manager navigation**

Use `enterprise_role` from `/api/auth/me` to expose only project dashboard, employees, enrollment, import, positions, claims, and settings. This is usability only; Task 5 remains the security boundary.

- [ ] **Step 5: Build and run security smoke**

Run:

```bash
cd web && npm run build
cd .. && python3 tests/employer_scope_smoke.py
```

Expected: Vite exits 0; smoke test exits 0.

- [ ] **Step 6: Commit the UI**

```bash
git add web/src/api/types.ts web/src/api/operators.ts web/src/api/employerScopes.ts web/src/views/operators/OperatorsView.vue web/src/layouts/AppShell.vue web/src/stores/auth.ts tests/employer_scope_smoke.py
git commit -m "feat: manage project employer assignments"
```

### Task 7: Mirror the Schema in Java and Complete the Phase Gate

**Files:**
- Modify: `java-backend/src/main/java/com/xbb/baojing/common/User.java`
- Create: `java-backend/src/main/java/com/xbb/baojing/enterprise/UserEmployerScope.java`
- Create: `java-backend/src/main/java/com/xbb/baojing/enterprise/UserEmployerScopeMapper.java`
- Modify: Java controllers that expose employer-scoped positions, insured people, claims, and actual employers
- Modify: `docs/ai-handoffs/role-timeliness-v42.md`

**Interfaces:**
- Consumes: the merged Alembic column/table names and Phase 1 authorization semantics.
- Produces: Java read/write parity and a review-ready handoff; no independent Java migration file.

- [ ] **Step 1: Add Java mapping tests before implementation**

Add Maven tests that create an owner and project manager, insert one active scope, and assert project-manager list queries return only the assigned employer. Include zero-scope and revoked-scope cases.

- [ ] **Step 2: Run Maven tests and confirm failure**

Run:

```bash
cd java-backend && mvn test
```

Expected: FAIL because Java scope classes and filters are absent.

- [ ] **Step 3: Map the Alembic schema without creating Java migration history**

Add `enterpriseRole` to `User`, map `UserEmployerScope` fields exactly to Alembic names, and add mapper queries for active employer IDs and event-time primary manager lookup. Do not add `V5__...sql`; Alembic remains authoritative.

- [ ] **Step 4: Apply fail-closed Java controller filtering**

For project managers, require active scope IDs in actual-employer, position, insured and claim queries. Empty scope collections must produce empty lists and 403 on object access. Owner and admin behavior must remain unchanged.

- [ ] **Step 5: Run the complete Phase 1 verification matrix**

Run:

```bash
python3 tests/employer_scope_smoke.py
python3 tests/security_smoke.py
python3 tests/system_smoke.py
python3 tests/recharge_smoke.py
cd web && npm run build
cd ../java-backend && mvn test
cd .. && alembic -c alembic.ini heads
git diff --check
git status --short --branch
```

Expected: all tests/builds exit 0; Alembic reports one head; no whitespace errors; only intentional user-owned untracked files remain.

- [ ] **Step 6: Update the handoff to review**

Record every commit, migration revision, command and result. Set:

```markdown
- status: `review`
- migration_owner: `yes（复核及合并完成前保持）`
```

List known risks and state that Phase 2 cannot create a migration until Phase 1 is merged and its migration lock is released.

- [ ] **Step 7: Commit Java parity and handoff**

```bash
git add java-backend/src/main/java docs/ai-handoffs/role-timeliness-v42.md
git commit -m "feat: mirror employer scope authorization in Java"
```

## Plan Self-Review Result

- Spec coverage for Phase 1: role compatibility, historical many-to-many assignment, one primary manager, owner management, fail-closed filtering, audit, Web UI, Java parity, migration and negative tests are mapped to Tasks 1–7.
- Deliberately deferred: employment facts, feedback import, timeliness calculations, commission statements and payment allocations belong to later roadmap phases.
- Type consistency: `enterprise_role`, `UserEmployerScope`, `allowed_employer_ids`, `assert_employer_access`, and the scope API names are consistent across tasks.
- Execution blocker: shared auth/Web ownership must be resolved against the active `salesperson-portal` branch before Task 6 or any overlapping backend auth edit.
