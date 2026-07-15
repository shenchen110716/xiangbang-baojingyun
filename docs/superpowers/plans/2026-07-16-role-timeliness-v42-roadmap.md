# Role Timeliness v4.2 Delivery Roadmap

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement each phase plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver the approved v4.2 design as independently reviewable, testable phases without parallel edits to migration, authorization, commission, or shared frontend files.

**Architecture:** Keep the FastAPI modular monolith and add domain modules in dependency order. Each phase owns one coherent business result, starts from the latest merged `main`, uses one external worktree, and cannot begin until its predecessor is merged and its handoff is updated.

**Tech Stack:** Python 3, FastAPI, SQLAlchemy 2, Alembic, PostgreSQL/SQLite tests, openpyxl, Vue 3/TypeScript/Vite, WeChat Mini Program, Java 17/Spring Boot/MyBatis, Maven.

## Global Constraints

- Read `docs/AI-DEVELOPMENT-PROTOCOL.md` and all unfinished handoffs before every phase.
- Run `bash scripts/ai_coordination_check.sh`, `git status --short --branch`, and `git worktree list` before claiming files.
- Never implement a feature directly on `main`; create an external worktree and a `codex/` branch.
- Only one active task may own Alembic migrations and shared authorization, commission, dashboard, route, or type files.
- Python/Alembic is the only schema migration authority; Java follows the merged Alembic schema.
- Actual hire and leave times are the only basis for coverage timeliness.
- Monthly products allow 24 hours for both hire and leave feedback; daily and immediate products allow no grace period.
- Feedback grace changes responsibility metrics only, never coverage timeliness.
- A project manager with no active employer scope receives no employer-scoped data.
- A salesperson may see every active product and its platform minimum sale price, but only their own commission and payment data.
- Do not deploy, migrate production, change production secrets, or upload a Mini Program release without explicit authorization.

---

## Phase Plans

| Phase | Independently testable outcome | Dependency | Plan |
| --- | --- | --- | --- |
| 1 | Project-manager role, historical employer scopes, primary manager, and enforced employer-level data filtering | Recharge merged; salesperson branch ownership resolved for shared auth/Web files | `docs/superpowers/plans/2026-07-16-employer-scope-phase.md` |
| 2 | Employment feedback batches, facts, matching, corrections, identity protection, and atomic preview/confirm import | Phase 1 merged | Create after Phase 1 API and migration names are final |
| 3 | Versioned product timing rules, operation snapshots, pure timeliness engine, responsibility evidence, and recalculation | Phase 2 merged | Create after Phase 2 fact interfaces are final |
| 4 | Enterprise timeliness summary, details, data-quality queue, XLSX export, and Web/Mini Program views | Phase 3 merged | Create after Phase 3 result schema is final |
| 5 | Agent product catalogue, own-commission statements, payments, allocations, exports, and portal UI | Existing `salesperson-portal` work is reviewed and merged or cancelled | Create from the surviving salesperson API/UI contracts |
| 6 | Java mirrors, cross-runtime contract checks, full security regression, migration validation, and release-readiness handoff | Phases 1–5 merged | Create after all schema heads and endpoints are final |

## Phase Exit Rules

Every phase must end with:

1. A clean focused diff and small commits recorded in its handoff.
2. Fresh Python security/system tests and tests specific to that phase.
3. Web build or Mini Program checks when those clients changed.
4. Empty-database and old-database Alembic validation when schema changed.
5. Cross-enterprise, cross-employer, or cross-salesperson negative tests as applicable.
6. A `review` handoff before independent review, then `ready` only after all required checks pass.

## Execution Order

Do not create all migrations or UI routes up front. Execute Phase 1, merge it, update `main`, then write Phase 2 against the actual merged interfaces. This prevents stale migration heads and speculative contracts from propagating through six subsystems.
