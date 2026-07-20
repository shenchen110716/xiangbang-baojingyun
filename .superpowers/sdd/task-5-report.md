# Task 5 Report: Router — rewire `/api/payments` for WeChat

## What I implemented

Rewrote `backend/routers/payments.py` per the brief's Step 3, with one deliberate deviation (see "Self-review findings"):

- `_apply_paid(session, row)`: shared "mark paid" logic — sets `status="paid"`, credits the enterprise's `usage_balance`/`premium_balance`, posts a `LedgerEntry` via `post_ledger_entry` with `idempotency_key=row.order_no`, commits.
- `create_payment`: `account="premium"` still rejected with 400. `account="usage"` now creates a real WeChat order via `wechat_pay_provider()` — `channel="jsapi"` requires `user.wx_openid` (400 if missing) and calls `create_jsapi_order`; otherwise calls `create_native_order`. Persists `channel`/`openid` on the `PaymentRecord`.
- `payment_callback`: refactored to call `_apply_paid` instead of inlining balance/ledger logic; idempotency check (`status == "paid"`) happens before any mutation. Unchanged external behavior/signature.
- `wechat_notify` (new, async): verifies signature via `wechat_pay_provider().verify_notify(headers, body)`; 400 immediately (no DB read/write) if verification fails; looks up the order, idempotency-checks before mutating, then delegates to `_apply_paid`, recording `provider_trade_no`/`paid_at` first.
- `get_payment` (new): scoped via `assert_enterprise_scope` — enterprise users get 403 on orders belonging to another enterprise; admins unrestricted.
- `list_payments` (new): admin-only (`require_role("admin", ...)`), supports `enterprise_id`/`status`/`channel` filters.
- `payment_reconcile`: unchanged behavior.

**Deviation from literal brief code**: the brief's Step 3 registers `@router.get("/payments/{order_no}")` (get_payment) *before* `@router.get("/payments/reconcile")`. Since FastAPI/Starlette matches routes in registration order, a request to `GET /api/payments/reconcile` would have been swallowed by `get_payment` (treating `"reconcile"` as `order_no`), breaking the existing admin reconcile endpoint used by `web/src/api/finance.ts` and documented in `README.md`. None of the smoke tests exercise this via real HTTP routing (they call the functions directly), so this wouldn't have been caught by the test suite — I found it by inspection. I reordered the routes so `list_payments` and `payment_reconcile` (both static paths) register before `get_payment` (dynamic path), preserving all specified behavior while avoiding the regression.

## What I tested

1. **RED**: Appended Steps C-H to `tests/wechat_pay_smoke.py`'s `run()` exactly as specified in the brief. Ran `python3 tests/wechat_pay_smoke.py` before touching the router — failed with `ImportError: cannot import name 'get_payment' from 'backend.routers.payments'` (import line pulls in `create_payment, get_payment, list_payments, wechat_notify` together, so it reports the first missing name rather than `wechat_notify` specifically, but same root cause the brief anticipated: none of the new symbols existed yet).
2. **GREEN**: After rewriting the router (including the route-order fix), `python3 tests/wechat_pay_smoke.py` → `wechat pay smoke: ok`.
3. **Full regression**:
   ```
   python3 -m compileall -q backend   # clean
   python3 tests/recharge_smoke.py    # recharge smoke: ok
   python3 tests/system_smoke.py      # system smoke: ok
   python3 tests/security_smoke.py    # security smoke: ok
   python3 tests/participation_lock_smoke.py  # participation lock smoke: ok
   ```
   `tests/recharge_smoke.py` line 271's `create_payment(..., account="usage", ...)` call still returns `status: "pending"` — confirmed by the pass.
4. `git diff --check` — clean, no whitespace errors.

## Files changed

- `backend/routers/payments.py` — full rewrite (with route-order fix noted above)
- `tests/wechat_pay_smoke.py` — appended Steps C-H per brief

## Self-review findings

- `_apply_paid()` is called exactly once per order, from both `payment_callback` and `wechat_notify` — no duplicated credit/ledger logic elsewhere. Confirmed by reading the file.
- Idempotency check (`if row.status == "paid": return ... idempotent: True`) is checked before any mutation in both handlers.
- `wechat_notify` returns 400 with no DB read/write when `verify_notify()` returns falsy — the `session.scalar` lookup happens only after the signature check passes.
- `get_payment` scopes correctly via `assert_enterprise_scope` — verified 403 in test Step H.
- `list_payments` requires `admin` role via `require_role` dependency.
- `account="premium"` still rejected with 400 (Step C).
- `tests/recharge_smoke.py` passes unchanged.
- **Found and fixed**: route-shadowing bug in the brief's literal ordering (`/payments/{order_no}` before `/payments/reconcile`) that would have broken the existing reconcile endpoint over real HTTP. Fixed by reordering; behavior of all endpoints when called directly (as the smoke tests do) is unaffected, but this matters for actual API routing in production/other callers (`web/src/api/finance.ts`).
- Test output pristine — no stray warnings in any of the five test runs.

## Issues or concerns

- The route-order deviation above. I judged this a bug worth fixing proactively (silent break of a working admin feature, not caught by any test using real HTTP routing) rather than replicating verbatim, but flagging it explicitly since it deviates from the brief's literal code.

## Post-review coverage gap closure

Added assertion to test Step H (`get_payment` call in Step H of `tests/wechat_pay_smoke.py`):

```python
            admin_view_of_order = get_payment(native_order_no, admin, session)
            assert admin_view_of_order["status"] == "paid"
```

This proves that admin users can successfully call `get_payment` on orders belonging to other enterprises (previously only tested enterprise-scoped self-access + cross-enterprise 403 rejection). Test output: `wechat pay smoke: ok` with clean `python3 -m compileall -q backend`.
