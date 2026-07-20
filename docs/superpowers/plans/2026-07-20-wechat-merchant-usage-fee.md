# 平台服务费微信商户号收款 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an online WeChat Pay channel (Native QR on Web + JSAPI in the miniprogram) for 平台服务费/使用费 collection, set it as the default alongside the existing manual bank-transfer review flow, with admin-configurable merchant credentials and automatic ledger/record generation on payment success.

**Architecture:** Extend the existing `PaymentRecord` / `POST /api/payments` / `POST /api/payments/callback` online-payment path (which already supports `account="usage"`) rather than building a parallel model. Add a `wechat_pay_provider()` following the project's existing `INTEGRATION_MODE=mock|real` provider pattern, a signature-verified `/api/payments/wechat-notify` webhook, and admin-configurable merchant credentials via the existing `SETTINGS_REGISTRY` (Fernet-encrypted secrets, auto-rendered admin UI).

**Tech Stack:** FastAPI + SQLAlchemy + Alembic (backend), Vue 3 + Element Plus + Pinia (Web), native WeChat Mini Program JS (miniprogram), `cryptography` (already a dependency) for WeChat Pay v3 RSA signing / AES-256-GCM notify decryption.

## Global Constraints

- Design spec: `docs/superpowers/specs/2026-07-20-wechat-merchant-usage-fee-design.md` — every task below implements a section of it.
- Scope is 使用费 (usage account) only; `account="premium"` must keep being rejected by `POST /api/payments` exactly as today.
- `INTEGRATION_MODE` defaults to `mock`; mock mode must never make a real network call to WeChat.
- All new/changed Alembic migrations must be additive (new nullable columns), idempotent (`if "col" not in columns` guards), based on the current single head `e5f6a7b8c9d0`, and validated on real PostgreSQL via `python3 scripts/pg_migration_check.py` before merge (per `CLAUDE.md`) — this plan implements the migration; running that script is a pre-merge gate the executing engineer/user must perform once credentials are available, not a step this plan can execute.
- Boolean column defaults must use `sa.true()`/`sa.false()` if any are ever added (none are needed by this plan).
- Secrets (WeChat merchant private key, APIv3 key, platform cert) are only ever stored via `services/settings.py`'s existing Fernet-encrypted `SystemSetting` registry — never logged, never returned in plaintext by any endpoint.
- Every backend task must keep `python3 -m compileall -q backend` and `git diff --check` clean, and the full existing regression suite passing: `tests/system_smoke.py`, `tests/security_smoke.py`, `tests/participation_lock_smoke.py`, `tests/recharge_smoke.py`, `tests/salesperson_portal_smoke.py`, `tests/settings_smoke.py`, `tests/id_number_test.py`.
- Every Web task must keep `cd web && npm run build` (`vue-tsc -b && vite build`) passing — this is the project's only frontend verification gate (no unit test runner is configured).
- Per `CLAUDE.md`'s multi-agent protocol, all implementation happens on an isolated branch/worktree (e.g. `feat/wechat-merchant-usage-fee`), never directly on `main`; the design spec commit already on `main` (`1065c1f`) is docs-only and exempt.
- Follow this file's existing dense one-line function style in `backend/routers/payments.py` and `backend/core/migrations.py` (established project convention) rather than reformatting to a different style.

---

### Task 1: Data model & Alembic migration for WeChat payment fields

**Files:**
- Modify: `backend/models/finance.py:27-37` (`PaymentRecord`)
- Modify: `backend/models/user.py:9-33` (`User`)
- Create: `backend/migrations_alembic/versions/b7c8d9e0f1a2_wechat_pay_fields.py`
- Modify: `backend/core/migrations.py` (`run_sqlite_bridge_migrations`, after line 13's `users` phone/status/is_owner/enterprise_role loop)
- Create: `tests/wechat_pay_smoke.py`

**Interfaces:**
- Produces: `PaymentRecord.channel: str` (`"native"|"jsapi"`, default `"native"`), `PaymentRecord.openid: str|None`, `PaymentRecord.provider_trade_no: str|None`, `PaymentRecord.paid_at: datetime|None`, `User.wx_openid: str|None` (unique). All later tasks read/write these exact attribute names.

- [ ] **Step 1: Write the failing test**

Create `tests/wechat_pay_smoke.py`:

```python
"""Smoke test for the WeChat merchant-account payment channel for 平台服务费
(platform usage-fee) collection: online order creation (native + jsapi),
signed-notify callback (idempotent, rejects bad signatures), ledger/balance
credit, RBAC, and the admin-configurable default-collection-method setting.

Isolated from tests/system_smoke.py for the same reason recharge_smoke.py is
(unrelated PersonIn ID-checksum fixture bug) — builds its own minimal
fixtures. Grows task-by-task; run the whole file after every task.
"""
import os
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))


def run():
    with tempfile.TemporaryDirectory(prefix="xbb-wechat-pay-smoke-") as folder:
        os.environ["DATABASE_URL"] = f"sqlite:///{Path(folder) / 'test.db'}"
        os.environ["ADMIN_PASSWORD"] = "admin123"
        os.environ["ENTERPRISE_PASSWORD"] = "enterprise123"
        os.environ["INTEGRATION_MODE"] = "mock"

        from sqlalchemy import select

        from backend.app import startup
        from backend.core.db import SessionLocal
        from backend.models import Enterprise, PaymentRecord, User

        startup()
        with SessionLocal() as session:
            admin = session.scalar(select(User).where(User.username == "admin"))
            enterprise_user = session.scalar(select(User).where(User.username == "enterprise"))
            enterprise = session.scalar(select(Enterprise).where(Enterprise.id == enterprise_user.enterprise_id))
            assert enterprise is not None

            # Step 1: schema roundtrip for the new columns (proves both the
            # SQLAlchemy models AND the SQLite bridge migration are wired).
            probe = PaymentRecord(order_no="PAY-SCHEMA-PROBE", enterprise_id=enterprise.id, account="usage",
                                   amount=1.0, status="pending", provider="wechat", channel="native",
                                   openid=None, provider_trade_no=None, paid_at=None)
            session.add(probe); session.commit(); session.refresh(probe)
            assert probe.channel == "native" and probe.provider_trade_no is None and probe.paid_at is None
            enterprise_user.wx_openid = "probe-openid"
            session.commit()
            reloaded_user = session.scalar(select(User).where(User.id == enterprise_user.id))
            assert reloaded_user.wx_openid == "probe-openid"
            session.delete(probe); session.commit()

        print("wechat pay smoke: ok")


if __name__ == "__main__":
    run()
```

- [ ] **Step 2: Run test to verify it fails**

Run: `python3 tests/wechat_pay_smoke.py`
Expected: FAIL — `TypeError: 'channel' is an invalid keyword argument for PaymentRecord` (columns don't exist yet).

- [ ] **Step 3: Add the new columns to the models**

In `backend/models/finance.py`, replace the `PaymentRecord` class body:

```python
class PaymentRecord(Base):
    __tablename__ = "payment_records"
    id: Mapped[int] = mapped_column(primary_key=True)
    order_no: Mapped[str] = mapped_column(String(100), unique=True)
    enterprise_id: Mapped[int] = mapped_column(ForeignKey("enterprises.id"))
    account: Mapped[str] = mapped_column(String(20), default="premium")
    amount: Mapped[float] = mapped_column(Float, default=0)
    status: Mapped[str] = mapped_column(String(30), default="pending")
    provider: Mapped[str] = mapped_column(String(60), default="payment")
    channel: Mapped[str] = mapped_column(String(20), default="native")
    openid: Mapped[Optional[str]] = mapped_column(String(64), nullable=True)
    provider_trade_no: Mapped[Optional[str]] = mapped_column(String(80), nullable=True)
    paid_at: Mapped[Optional[datetime]] = mapped_column(DateTime, nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=lambda: datetime.now(timezone.utc))
```

In `backend/models/user.py`, add after the `is_owner` line (line 24):

```python
    wx_openid: Mapped[Optional[str]] = mapped_column(String(64), unique=True, nullable=True)
```

- [ ] **Step 4: Write the Alembic migration**

Create `backend/migrations_alembic/versions/b7c8d9e0f1a2_wechat_pay_fields.py`:

```python
"""wechat pay fields

Revision ID: b7c8d9e0f1a2
Revises: e5f6a7b8c9d0
Create Date: 2026-07-20 00:00:00.000000

"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


revision: str = 'b7c8d9e0f1a2'
down_revision: Union[str, Sequence[str], None] = 'e5f6a7b8c9d0'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    """微信商户号收款：payment_records 加支付渠道/openid/微信交易号/支付时间，
    users 加绑定的微信 openid。均为新增可空列，幂等（列已存在则跳过）。"""
    payment_columns = {c["name"] for c in sa.inspect(op.get_bind()).get_columns("payment_records")}
    if "channel" not in payment_columns:
        op.add_column("payment_records", sa.Column("channel", sa.String(length=20), nullable=False, server_default="native"))
    if "openid" not in payment_columns:
        op.add_column("payment_records", sa.Column("openid", sa.String(length=64), nullable=True))
    if "provider_trade_no" not in payment_columns:
        op.add_column("payment_records", sa.Column("provider_trade_no", sa.String(length=80), nullable=True))
    if "paid_at" not in payment_columns:
        op.add_column("payment_records", sa.Column("paid_at", sa.DateTime(), nullable=True))

    user_columns = {c["name"] for c in sa.inspect(op.get_bind()).get_columns("users")}
    if "wx_openid" not in user_columns:
        op.add_column("users", sa.Column("wx_openid", sa.String(length=64), nullable=True))
        op.create_unique_constraint("uq_users_wx_openid", "users", ["wx_openid"])


def downgrade() -> None:
    op.drop_constraint("uq_users_wx_openid", "users", type_="unique")
    op.drop_column("users", "wx_openid")
    op.drop_column("payment_records", "paid_at")
    op.drop_column("payment_records", "provider_trade_no")
    op.drop_column("payment_records", "openid")
    op.drop_column("payment_records", "channel")
```

- [ ] **Step 5: Extend the SQLite bridge migration**

In `backend/core/migrations.py`, insert after line 13 (the `for column, definition in [("phone", ...` loop) and before line 14's `s.connection().exec_driver_sql("UPDATE users SET enterprise_role=...")`:

```python
    if "wx_openid" not in columns: s.connection().exec_driver_sql("ALTER TABLE users ADD COLUMN wx_openid VARCHAR(64)")
```

And insert this block right after the existing `ledger_columns` block at the end of the function (after line 76's `if "account_id" not in ledger_columns: ...`):

```python
    payment_columns = {row[1] for row in s.connection().exec_driver_sql("PRAGMA table_info(payment_records)")}
    for column, definition in [("channel", "VARCHAR(20) DEFAULT 'native'"), ("openid", "VARCHAR(64)"), ("provider_trade_no", "VARCHAR(80)"), ("paid_at", "DATETIME")]:
        if column not in payment_columns: s.connection().exec_driver_sql(f"ALTER TABLE payment_records ADD COLUMN {column} {definition}")
```

- [ ] **Step 6: Run test to verify it passes**

Run: `python3 tests/wechat_pay_smoke.py`
Expected: `wechat pay smoke: ok`

- [ ] **Step 7: Run full regression + compile checks**

Run: `python3 -m compileall -q backend && python3 tests/system_smoke.py && python3 tests/recharge_smoke.py && python3 tests/security_smoke.py`
Expected: all pass (no output = compileall ok; each smoke script prints its own `... ok` line).

- [ ] **Step 8: Commit**

```bash
git add backend/models/finance.py backend/models/user.py backend/migrations_alembic/versions/b7c8d9e0f1a2_wechat_pay_fields.py backend/core/migrations.py tests/wechat_pay_smoke.py
git commit -m "feat: add WeChat payment fields to payment_records and users"
```

---

### Task 2: Schemas & settings registry for WeChat payment config

**Files:**
- Modify: `backend/schemas/finance.py:6` (`PaymentIn`)
- Create: `backend/schemas/wechat.py`
- Modify: `backend/schemas/__init__.py:14,33`
- Modify: `backend/services/settings.py:21-40` (`SETTINGS_REGISTRY`)
- Create: `tests/wechat_pay_config_test.py`

**Interfaces:**
- Consumes: none (leaf task).
- Produces: `PaymentIn(enterprise_id, account, amount, channel="native")`, `WeChatBindOpenidIn(code: str)`, registry keys `WECHAT_PAY_MCH_ID`, `WECHAT_PAY_APP_ID`, `WECHAT_PAY_NOTIFY_URL`, `WECHAT_PAY_CERT_SERIAL_NO`, `WECHAT_PAY_API_V3_KEY` (secret), `WECHAT_PAY_PRIVATE_KEY` (secret), `WECHAT_PAY_PLATFORM_CERT` (secret), `WECHAT_MINIPROGRAM_APP_SECRET` (secret), `USAGE_FEE_DEFAULT_METHOD` (select, default `"wechat"`). Later tasks read these via `settings_service.get(key)`.

- [ ] **Step 1: Write the failing test**

Create `tests/wechat_pay_config_test.py`:

```python
"""微信支付配置纯逻辑单测：PaymentIn.channel 默认值/校验、SETTINGS_REGISTRY
新增分组的 key/secret 声明是否正确——不涉及数据库。"""
import os
import sys

os.environ.setdefault("ID_ENCRYPTION_KEY", "x" * 44)
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from pydantic import ValidationError

from backend.schemas import PaymentIn, WeChatBindOpenidIn
from backend.services import settings as S


def test_payment_in_channel_defaults_to_native_and_rejects_bad_values():
    data = PaymentIn(enterprise_id=1, account="usage", amount=10.0)
    assert data.channel == "native"
    assert PaymentIn(enterprise_id=1, account="usage", amount=10.0, channel="jsapi").channel == "jsapi"
    try:
        PaymentIn(enterprise_id=1, account="usage", amount=10.0, channel="bogus")
        raise AssertionError("invalid channel should be rejected")
    except ValidationError:
        pass


def test_wechat_bind_openid_in_requires_code():
    assert WeChatBindOpenidIn(code="abc").code == "abc"
    try:
        WeChatBindOpenidIn()
        raise AssertionError("missing code should be rejected")
    except ValidationError:
        pass


def test_settings_registry_declares_wechat_pay_group_with_correct_secrecy():
    by_key = {item["key"]: item for item in S.SETTINGS_REGISTRY}
    non_secret = ["WECHAT_PAY_MCH_ID", "WECHAT_PAY_APP_ID", "WECHAT_PAY_NOTIFY_URL", "WECHAT_PAY_CERT_SERIAL_NO"]
    secret = ["WECHAT_PAY_API_V3_KEY", "WECHAT_PAY_PRIVATE_KEY", "WECHAT_PAY_PLATFORM_CERT", "WECHAT_MINIPROGRAM_APP_SECRET"]
    for key in non_secret:
        assert by_key[key]["group"] == "微信支付" and by_key[key]["secret"] is False, key
    for key in secret:
        assert by_key[key]["group"] == "微信支付" and by_key[key]["secret"] is True, key
    assert by_key["USAGE_FEE_DEFAULT_METHOD"]["kind"] == "select"
    assert by_key["USAGE_FEE_DEFAULT_METHOD"]["options"] == ["wechat", "bank"]
    assert S.get("USAGE_FEE_DEFAULT_METHOD", "wechat") == "wechat"


if __name__ == "__main__":
    for name, fn in sorted(globals().items()):
        if name.startswith("test_"):
            fn()
            print(f"  {name} ok")
    print("wechat pay config tests passed")
```

- [ ] **Step 2: Run test to verify it fails**

Run: `python3 tests/wechat_pay_config_test.py`
Expected: FAIL — `ImportError: cannot import name 'WeChatBindOpenidIn'`.

- [ ] **Step 3: Add `channel` to `PaymentIn`**

In `backend/schemas/finance.py`, replace line 6:

```python
class PaymentIn(BaseModel): enterprise_id: int; account: Literal["premium","usage"] = "premium"; amount: float = Field(gt=0); channel: Literal["native","jsapi"] = "native"
```

- [ ] **Step 4: Add the new schema file**

Create `backend/schemas/wechat.py`:

```python
from pydantic import BaseModel


class WeChatBindOpenidIn(BaseModel):
    code: str
```

- [ ] **Step 5: Export it**

In `backend/schemas/__init__.py`, change line 14:

```python
from .finance import PaymentIn, PaymentCallbackIn, InvoiceIn, InvoiceUpdate, InsurerAccountIn, InsurerAccountUpdate, InsurerAccountLinkIn
```

to also import the new schema (insert right after it):

```python
from .finance import PaymentIn, PaymentCallbackIn, InvoiceIn, InvoiceUpdate, InsurerAccountIn, InsurerAccountUpdate, InsurerAccountLinkIn
from .wechat import WeChatBindOpenidIn
```

And add `"WeChatBindOpenidIn",` to the `__all__` list (line 33 area, right after `"InsurerAccountLinkIn",`).

- [ ] **Step 6: Add the settings registry entries**

In `backend/services/settings.py`, insert before the closing `]` of `SETTINGS_REGISTRY` (after the `OCR_APP_KEY` line, line 39):

```python
    # 微信支付（平台服务费收款商户号）
    {"key": "WECHAT_PAY_MCH_ID", "group": "微信支付", "label": "商户号", "secret": False, "kind": "text"},
    {"key": "WECHAT_PAY_APP_ID", "group": "微信支付", "label": "AppID", "secret": False, "kind": "text", "hint": "公众号或小程序 AppID"},
    {"key": "WECHAT_PAY_NOTIFY_URL", "group": "微信支付", "label": "支付结果通知地址", "secret": False, "kind": "text", "hint": "形如 https://your-domain/api/payments/wechat-notify"},
    {"key": "WECHAT_PAY_CERT_SERIAL_NO", "group": "微信支付", "label": "商户证书序列号", "secret": False, "kind": "text"},
    {"key": "WECHAT_PAY_API_V3_KEY", "group": "微信支付", "label": "APIv3 密钥", "secret": True, "kind": "password"},
    {"key": "WECHAT_PAY_PRIVATE_KEY", "group": "微信支付", "label": "商户 API 私钥（PEM）", "secret": True, "kind": "password"},
    {"key": "WECHAT_PAY_PLATFORM_CERT", "group": "微信支付", "label": "微信支付平台证书（PEM）", "secret": True, "kind": "password"},
    {"key": "WECHAT_MINIPROGRAM_APP_SECRET", "group": "微信支付", "label": "小程序 AppSecret", "secret": True, "kind": "password", "hint": "用于 wx.login() 换取 openid"},
    # 使用费收款
    {"key": "USAGE_FEE_DEFAULT_METHOD", "group": "使用费收款", "label": "默认收款方式", "secret": False, "kind": "select", "options": ["wechat", "bank"], "hint": "使用费缴纳页默认选中的收款方式"},
```

- [ ] **Step 7: Run test to verify it passes**

Run: `python3 tests/wechat_pay_config_test.py`
Expected: all `test_*` lines print `ok`, then `wechat pay config tests passed`.

- [ ] **Step 8: Run full regression**

Run: `python3 -m compileall -q backend && python3 tests/settings_smoke.py && python3 tests/wechat_pay_smoke.py`
Expected: all pass.

- [ ] **Step 9: Commit**

```bash
git add backend/schemas/finance.py backend/schemas/wechat.py backend/schemas/__init__.py backend/services/settings.py tests/wechat_pay_config_test.py
git commit -m "feat: add WeChat payment schema fields and settings registry group"
```

---

### Task 3: WeChat Pay provider layer (mock + real crypto)

**Files:**
- Modify: `backend/providers.py`
- Create: `tests/wechat_pay_provider_test.py`

**Interfaces:**
- Consumes: `backend.services.settings.get(key, default)` (Task 2's registry keys).
- Produces: `wechat_pay_provider() -> WeChatPayProvider` (mock) or `RealWeChatPayProvider` (real, subclass of `WeChatPayProvider`). Both expose:
  - `create_native_order(amount: float, order_no: str, description: str) -> ProviderResult` — `result.data` has `code_url`.
  - `create_jsapi_order(amount: float, order_no: str, openid: str, description: str) -> ProviderResult` — `result.data` has `prepay_id, timeStamp, nonceStr, package, signType, paySign` (the exact shape WeChat Mini Program `wx.requestPayment()` needs).
  - `code_to_openid(code: str) -> str | None`.
  - `verify_notify(headers: dict, raw_body: bytes) -> dict | None` — returns `None` on any signature/format failure, else a normalized `{"out_trade_no": str, "status": "paid"|"failed"|"pending", "transaction_id": str}`.
  - Router tasks (4, 5) call these exact methods/return shapes.

- [ ] **Step 1: Write the failing test**

Create `tests/wechat_pay_provider_test.py`:

```python
"""微信支付 provider 纯逻辑单测：mock 验签闭环、真实模式的 RSA 请求签名、
JSAPI 客户端参数签名与 AES-256-GCM 回调解密——全部用现场生成的测试密钥对，
不依赖数据库、不发真实网络请求。"""
import base64
import datetime
import hashlib
import hmac
import json
import os
import secrets
import sys
import time
import uuid

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding, rsa
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.x509.oid import NameOID

from backend.providers import RealWeChatPayProvider, WeChatPayProvider


def _generate_rsa_keypair():
    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    pem = key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()).decode()
    return key, pem


def _generate_self_signed_cert(key):
    subject = issuer = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "mock-wechatpay-platform")])
    return (
        x509.CertificateBuilder()
        .subject_name(subject).issuer_name(issuer).public_key(key.public_key())
        .serial_number(x509.random_serial_number())
        .not_valid_before(datetime.datetime.utcnow())
        .not_valid_after(datetime.datetime.utcnow() + datetime.timedelta(days=1))
        .sign(key, hashes.SHA256())
    )


def test_mock_native_and_jsapi_order_return_ok():
    provider = WeChatPayProvider()
    native = provider.create_native_order(88.0, "PAY-TEST-1", "测试")
    assert native.ok and native.data["code_url"]
    jsapi = provider.create_jsapi_order(88.0, "PAY-TEST-2", "mock-openid-abc", "测试")
    assert jsapi.ok and jsapi.data["prepay_id"] and jsapi.data["package"].startswith("prepay_id=")
    for field in ("timeStamp", "nonceStr", "signType", "paySign"):
        assert jsapi.data[field]


def test_mock_code_to_openid_is_deterministic():
    provider = WeChatPayProvider()
    assert provider.code_to_openid("abc") == provider.code_to_openid("abc")
    assert provider.code_to_openid("abc") != provider.code_to_openid("def")
    assert provider.code_to_openid("") is None


def test_mock_verify_notify_accepts_correctly_signed_payload_and_rejects_others():
    provider = WeChatPayProvider()
    body = json.dumps({"out_trade_no": "PAY-TEST-3", "status": "paid", "transaction_id": "mock-txn-1"}).encode()
    signature = hmac.new(WeChatPayProvider.MOCK_NOTIFY_SECRET.encode(), body, hashlib.sha256).hexdigest()
    payload = provider.verify_notify({"X-Mock-Signature": signature}, body)
    assert payload == {"out_trade_no": "PAY-TEST-3", "status": "paid", "transaction_id": "mock-txn-1"}
    assert provider.verify_notify({}, body) is None
    assert provider.verify_notify({"X-Mock-Signature": "deadbeef"}, body) is None
    tampered = body.replace(b"paid", b"paix")
    assert provider.verify_notify({"X-Mock-Signature": signature}, tampered) is None


def test_real_provider_sign_produces_verifiable_authorization_header():
    key, pem = _generate_rsa_keypair()
    os.environ.update({
        "WECHAT_PAY_MCH_ID": "1900000001",
        "WECHAT_PAY_CERT_SERIAL_NO": "TESTSERIAL01",
        "WECHAT_PAY_PRIVATE_KEY": pem,
    })
    provider = RealWeChatPayProvider()
    body = '{"out_trade_no":"PAY-TEST-4"}'
    headers = provider._sign("POST", "/v3/pay/transactions/native", body)
    auth = headers["Authorization"]
    assert auth.startswith("WECHATPAY2-SHA256-RSA2048 ")
    fields = dict(item.split("=", 1) for item in auth[len("WECHATPAY2-SHA256-RSA2048 "):].split(","))
    for name in ("mchid", "nonce_str", "timestamp", "serial_no", "signature"):
        assert name in fields, f"missing {name}"
    signature = base64.b64decode(fields["signature"].strip('"'))
    message = f'POST\n/v3/pay/transactions/native\n{fields["timestamp"].strip(chr(34))}\n{fields["nonce_str"].strip(chr(34))}\n{body}\n'.encode()
    key.public_key().verify(signature, message, padding.PKCS1v15(), hashes.SHA256())  # 不抛异常即验签通过


def test_real_provider_jsapi_client_params_are_verifiably_signed():
    key, pem = _generate_rsa_keypair()
    os.environ.update({"WECHAT_PAY_APP_ID": "wx1234567890", "WECHAT_PAY_PRIVATE_KEY": pem})
    provider = RealWeChatPayProvider()
    params = provider._jsapi_client_params("mock-prepay-xyz")
    assert params["package"] == "prepay_id=mock-prepay-xyz"
    message = f'wx1234567890\n{params["timeStamp"]}\n{params["nonceStr"]}\n{params["package"]}\n'.encode()
    signature = base64.b64decode(params["paySign"])
    key.public_key().verify(signature, message, padding.PKCS1v15(), hashes.SHA256())


def test_real_provider_verify_notify_decrypts_and_normalises_trade_state():
    platform_key, _ = _generate_rsa_keypair()
    cert = _generate_self_signed_cert(platform_key)
    cert_pem = cert.public_bytes(serialization.Encoding.PEM).decode()
    api_v3_key_str = secrets.token_hex(16)

    resource_plaintext = json.dumps({"out_trade_no": "PAY-TEST-5", "transaction_id": "wx-txn-5", "trade_state": "SUCCESS"}).encode()
    nonce_str = uuid.uuid4().hex[:12]
    associated_data_str = "transaction"
    ciphertext = AESGCM(api_v3_key_str.encode()).encrypt(nonce_str.encode(), resource_plaintext, associated_data_str.encode())
    envelope = json.dumps({"resource": {
        "algorithm": "AEAD_AES_256_GCM",
        "ciphertext": base64.b64encode(ciphertext).decode(),
        "associated_data": associated_data_str,
        "nonce": nonce_str,
    }}).encode()

    sig_nonce = uuid.uuid4().hex
    sig_timestamp = str(int(time.time()))
    message = f"{sig_timestamp}\n{sig_nonce}\n{envelope.decode()}\n".encode()
    signature = platform_key.sign(message, padding.PKCS1v15(), hashes.SHA256())

    os.environ["WECHAT_PAY_PLATFORM_CERT"] = cert_pem
    os.environ["WECHAT_PAY_API_V3_KEY"] = api_v3_key_str
    headers = {
        "Wechatpay-Timestamp": sig_timestamp,
        "Wechatpay-Nonce": sig_nonce,
        "Wechatpay-Signature": base64.b64encode(signature).decode(),
    }
    provider = RealWeChatPayProvider()
    payload = provider.verify_notify(headers, envelope)
    assert payload == {"out_trade_no": "PAY-TEST-5", "status": "paid", "transaction_id": "wx-txn-5"}

    bad_headers = {**headers, "Wechatpay-Signature": base64.b64encode(b"not-a-signature").decode()}
    assert provider.verify_notify(bad_headers, envelope) is None


if __name__ == "__main__":
    for name, fn in sorted(globals().items()):
        if name.startswith("test_"):
            fn()
            print(f"  {name} ok")
    print("wechat pay provider tests passed")
```

- [ ] **Step 2: Run test to verify it fails**

Run: `python3 tests/wechat_pay_provider_test.py`
Expected: FAIL — `ImportError: cannot import name 'WeChatPayProvider'`.

- [ ] **Step 3: Add the required imports to `providers.py`**

In `backend/providers.py`, replace the import block (lines 1-13) with:

```python
"""外部服务适配层。

默认使用 MockProvider。生产接入时按保司/供应商的签名规则实现对应 adapter，
并通过 .env 注入密钥；业务层不直接依赖第三方 SDK。
"""
from __future__ import annotations

import base64
import hashlib
import hmac
import json
import os
import time
import urllib.request
import uuid
from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Any, Optional

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

@dataclass
class ProviderResult:
    ok: bool
    provider: str
    request_id: str
    data: dict[str, Any]
    message: str = ""
```

(This is the same `ProviderResult` already in the file — only the imports above it change; leave `class MockProvider:` and everything below it untouched.)

- [ ] **Step 4: Add `WeChatPayProvider` (mock) and `RealWeChatPayProvider`, and the factory function**

In `backend/providers.py`, append at the end of the file (after the existing `def payment_provider() -> MockProvider: ...` line):

```python

class WeChatPayProvider(MockProvider):
    """mock 模式微信支付：不发真实请求，用确定性假数据 + 简化 HMAC 验签，
    使 /api/payments/wechat-notify 端点本身（含验签失败分支）也能被冒烟测试
    完整覆盖，而不必依赖真实微信证书。"""
    MOCK_NOTIFY_SECRET = "mock-wechat-notify-secret"

    def __init__(self, name: str = "wechat"):
        super().__init__(name)

    def create_native_order(self, amount: float, order_no: str, description: str) -> ProviderResult:
        return ProviderResult(True, self.name, order_no, {"code_url": f"weixin://wxpay/bizpayurl?mock={order_no}"}, "模拟微信 Native 下单成功")

    def create_jsapi_order(self, amount: float, order_no: str, openid: str, description: str) -> ProviderResult:
        prepay_id = f"mock-prepay-{order_no}"
        return ProviderResult(True, self.name, order_no, {
            "prepay_id": prepay_id,
            "timeStamp": str(int(datetime.now(timezone.utc).timestamp())),
            "nonceStr": order_no,
            "package": f"prepay_id={prepay_id}",
            "signType": "RSA",
            "paySign": "mock-pay-sign",
        }, "模拟微信 JSAPI 下单成功")

    def code_to_openid(self, code: str) -> Optional[str]:
        return f"mock-openid-{code}" if code else None

    def verify_notify(self, headers: dict, raw_body: bytes) -> Optional[dict]:
        signature = headers.get("X-Mock-Signature") or headers.get("x-mock-signature")
        if not signature:
            return None
        expected = hmac.new(self.MOCK_NOTIFY_SECRET.encode(), raw_body, hashlib.sha256).hexdigest()
        if not hmac.compare_digest(signature, expected):
            return None
        try:
            return json.loads(raw_body.decode())
        except Exception:
            return None


class RealWeChatPayProvider(WeChatPayProvider):
    """微信支付 v3 API：商户私钥 RSA-SHA256 签名请求（PKCS1v15），APIv3 密钥
    AES-256-GCM 解密回调 resource，微信支付平台证书 RSA-SHA256 验签回调头。
    密钥全部来自 services.settings（系统设置，Fernet 加密入库）。"""
    API_BASE = "https://api.mch.weixin.qq.com"

    def __init__(self, name: str = "wechat"):
        super().__init__(name)

    def _settings(self):
        from .services import settings as settings_service
        return settings_service

    def _sign(self, method: str, url_path: str, body: str) -> dict:
        S = self._settings()
        mch_id = S.get("WECHAT_PAY_MCH_ID")
        serial_no = S.get("WECHAT_PAY_CERT_SERIAL_NO")
        private_key_pem = S.get("WECHAT_PAY_PRIVATE_KEY")
        timestamp = str(int(time.time()))
        nonce = uuid.uuid4().hex
        message = f"{method}\n{url_path}\n{timestamp}\n{nonce}\n{body}\n".encode()
        private_key = serialization.load_pem_private_key(private_key_pem.encode(), password=None)
        signature = base64.b64encode(private_key.sign(message, padding.PKCS1v15(), hashes.SHA256())).decode()
        authorization = (
            f'WECHATPAY2-SHA256-RSA2048 mchid="{mch_id}",nonce_str="{nonce}",'
            f'timestamp="{timestamp}",serial_no="{serial_no}",signature="{signature}"'
        )
        return {"Authorization": authorization, "Content-Type": "application/json", "Accept": "application/json"}

    def _post(self, url_path: str, payload: dict) -> ProviderResult:
        body = json.dumps(payload, ensure_ascii=False)
        headers = self._sign("POST", url_path, body)
        try:
            req = urllib.request.Request(f"{self.API_BASE}{url_path}", data=body.encode(), headers=headers, method="POST")
            with urllib.request.urlopen(req, timeout=15) as res:
                data = json.loads(res.read() or "{}")
            return ProviderResult(True, self.name, payload.get("out_trade_no", ""), data, "微信支付下单成功")
        except Exception as exc:
            return ProviderResult(False, self.name, payload.get("out_trade_no", ""), {}, f"微信支付下单失败：{exc}")

    def create_native_order(self, amount: float, order_no: str, description: str) -> ProviderResult:
        S = self._settings()
        payload = {
            "appid": S.get("WECHAT_PAY_APP_ID"), "mchid": S.get("WECHAT_PAY_MCH_ID"),
            "description": description, "out_trade_no": order_no,
            "notify_url": S.get("WECHAT_PAY_NOTIFY_URL"),
            "amount": {"total": round(amount * 100), "currency": "CNY"},
        }
        return self._post("/v3/pay/transactions/native", payload)

    def _jsapi_client_params(self, prepay_id: str) -> dict:
        S = self._settings()
        app_id = S.get("WECHAT_PAY_APP_ID")
        timestamp = str(int(time.time()))
        nonce = uuid.uuid4().hex
        package = f"prepay_id={prepay_id}"
        message = f"{app_id}\n{timestamp}\n{nonce}\n{package}\n".encode()
        private_key = serialization.load_pem_private_key(S.get("WECHAT_PAY_PRIVATE_KEY").encode(), password=None)
        pay_sign = base64.b64encode(private_key.sign(message, padding.PKCS1v15(), hashes.SHA256())).decode()
        return {"prepay_id": prepay_id, "timeStamp": timestamp, "nonceStr": nonce, "package": package, "signType": "RSA", "paySign": pay_sign}

    def create_jsapi_order(self, amount: float, order_no: str, openid: str, description: str) -> ProviderResult:
        S = self._settings()
        payload = {
            "appid": S.get("WECHAT_PAY_APP_ID"), "mchid": S.get("WECHAT_PAY_MCH_ID"),
            "description": description, "out_trade_no": order_no,
            "notify_url": S.get("WECHAT_PAY_NOTIFY_URL"),
            "amount": {"total": round(amount * 100), "currency": "CNY"},
            "payer": {"openid": openid},
        }
        order_result = self._post("/v3/pay/transactions/jsapi", payload)
        if not order_result.ok:
            return order_result
        prepay_id = order_result.data.get("prepay_id", "")
        return ProviderResult(True, self.name, order_no, self._jsapi_client_params(prepay_id), "微信支付下单成功")

    def code_to_openid(self, code: str) -> Optional[str]:
        S = self._settings()
        app_id = S.get("WECHAT_PAY_APP_ID")
        app_secret = S.get("WECHAT_MINIPROGRAM_APP_SECRET")
        url = f"https://api.weixin.qq.com/sns/jscode2session?appid={app_id}&secret={app_secret}&js_code={code}&grant_type=authorization_code"
        try:
            with urllib.request.urlopen(url, timeout=10) as res:
                data = json.loads(res.read() or "{}")
            return data.get("openid")
        except Exception:
            return None

    def verify_notify(self, headers: dict, raw_body: bytes) -> Optional[dict]:
        S = self._settings()
        try:
            timestamp = headers.get("Wechatpay-Timestamp") or headers.get("wechatpay-timestamp")
            nonce = headers.get("Wechatpay-Nonce") or headers.get("wechatpay-nonce")
            signature = headers.get("Wechatpay-Signature") or headers.get("wechatpay-signature")
            if not (timestamp and nonce and signature):
                return None
            message = f"{timestamp}\n{nonce}\n{raw_body.decode()}\n".encode()
            cert = x509.load_pem_x509_certificate(S.get("WECHAT_PAY_PLATFORM_CERT").encode())
            cert.public_key().verify(base64.b64decode(signature), message, padding.PKCS1v15(), hashes.SHA256())
        except Exception:
            return None
        try:
            envelope = json.loads(raw_body.decode())
            resource = envelope["resource"]
            api_v3_key = S.get("WECHAT_PAY_API_V3_KEY").encode()
            resource_nonce = resource["nonce"].encode()
            associated_data = resource.get("associated_data", "").encode()
            ciphertext = base64.b64decode(resource["ciphertext"])
            decrypted = json.loads(AESGCM(api_v3_key).decrypt(resource_nonce, ciphertext, associated_data).decode())
        except Exception:
            return None
        trade_state = decrypted.get("trade_state", "")
        return {
            "out_trade_no": decrypted.get("out_trade_no", ""),
            "status": "paid" if trade_state == "SUCCESS" else ("failed" if trade_state else "pending"),
            "transaction_id": decrypted.get("transaction_id", ""),
        }


def wechat_pay_provider() -> WeChatPayProvider:
    return WeChatPayProvider("wechat") if provider_mode() == "mock" else RealWeChatPayProvider("wechat")
```

- [ ] **Step 5: Run test to verify it passes**

Run: `python3 tests/wechat_pay_provider_test.py`
Expected: all `test_*` lines print `ok`, then `wechat pay provider tests passed`.

- [ ] **Step 6: Run full regression**

Run: `python3 -m compileall -q backend && python3 tests/wechat_pay_smoke.py && python3 tests/recharge_smoke.py && python3 tests/system_smoke.py`
Expected: all pass (adding the new imports/classes must not break any existing provider usage).

- [ ] **Step 7: Commit**

```bash
git add backend/providers.py tests/wechat_pay_provider_test.py
git commit -m "feat: add WeChat Pay provider (mock + real v3 signing/verification)"
```

---

### Task 4: Router — WeChat openid binding endpoint

**Files:**
- Create: `backend/routers/wechat.py`
- Modify: `backend/app.py` (router registration, near line 40 and line 68)
- Modify: `tests/wechat_pay_smoke.py` (append Step B)

**Interfaces:**
- Consumes: `wechat_pay_provider()` (Task 3), `WeChatBindOpenidIn` (Task 2), `User.wx_openid` (Task 1).
- Produces: `POST /api/wechat/bind-openid` → `{"wx_openid": str}`; importable as `backend.routers.wechat.bind_openid(data, user, session)` for direct-call tests (matches project convention in `tests/recharge_smoke.py`).

- [ ] **Step 1: Write the failing test**

In `tests/wechat_pay_smoke.py`, inside the `with SessionLocal() as session:` block, replace the final two lines (`session.delete(probe); session.commit()` then the blank line before `print(...)`) so the block continues with a new step instead of ending there. Change:

```python
            session.delete(probe); session.commit()

        print("wechat pay smoke: ok")
```

to:

```python
            session.delete(probe); session.commit()

            # Step B: openid binding — first bind succeeds, a second account
            # trying to bind the SAME openid must be rejected (prevents one
            # openid paying on behalf of two enterprises).
            from backend.routers.wechat import bind_openid
            from backend.schemas import WeChatBindOpenidIn

            bound = bind_openid(WeChatBindOpenidIn(code="wx-code-123"), enterprise_user, session)
            assert bound["wx_openid"] == "mock-openid-wx-code-123"
            session.refresh(enterprise_user)
            assert enterprise_user.wx_openid == "mock-openid-wx-code-123"

            other_enterprise = Enterprise(name="其他单位", kind="企业", contact="", phone="", status="active")
            session.add(other_enterprise); session.commit(); session.refresh(other_enterprise)
            other_user = User(username="other-enterprise", password_hash=enterprise_user.password_hash, name="其他单位",
                               role="enterprise", enterprise_id=other_enterprise.id, is_owner=True)
            session.add(other_user); session.commit()
            from fastapi import HTTPException
            try:
                bind_openid(WeChatBindOpenidIn(code="wx-code-123"), other_user, session)
                raise AssertionError("同一个 openid 不应能绑定到第二个账号")
            except HTTPException as error:
                assert error.status_code == 409

        print("wechat pay smoke: ok")
```

- [ ] **Step 2: Run test to verify it fails**

Run: `python3 tests/wechat_pay_smoke.py`
Expected: FAIL — `ModuleNotFoundError: No module named 'backend.routers.wechat'`.

- [ ] **Step 3: Create the router**

Create `backend/routers/wechat.py`:

```python
from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from ..core.db import db
from ..core.rbac import require_role
from ..core.security import current_user
from ..models import User
from ..providers import wechat_pay_provider
from ..schemas import WeChatBindOpenidIn

router = APIRouter(prefix="/api", tags=["wechat"])


@router.post("/wechat/bind-openid", dependencies=[Depends(require_role("admin", "enterprise", detail="无权绑定微信账号"))])
def bind_openid(data: WeChatBindOpenidIn, user: User = Depends(current_user), session: Session = Depends(db)):
    openid = wechat_pay_provider().code_to_openid(data.code)
    if not openid:
        raise HTTPException(400, "微信授权码无效，请重试")
    user.wx_openid = openid
    try:
        session.commit()
    except IntegrityError:
        session.rollback()
        raise HTTPException(409, "该微信号已绑定其他账号")
    return {"wx_openid": user.wx_openid}
```

- [ ] **Step 4: Register the router**

In `backend/app.py`, add the import near line 40 (next to the other router imports, e.g. right after the `payments` import):

```python
from .routers.wechat import router as wechat_router
```

And add the include near line 68 (right after `app.include_router(payments_router)`):

```python
app.include_router(wechat_router)
```

- [ ] **Step 5: Run test to verify it passes**

Run: `python3 tests/wechat_pay_smoke.py`
Expected: `wechat pay smoke: ok`

- [ ] **Step 6: Run full regression**

Run: `python3 -m compileall -q backend && python3 tests/system_smoke.py && python3 tests/security_smoke.py`
Expected: all pass.

- [ ] **Step 7: Commit**

```bash
git add backend/routers/wechat.py backend/app.py tests/wechat_pay_smoke.py
git commit -m "feat: add /api/wechat/bind-openid endpoint"
```

---

### Task 5: Router — rewire `/api/payments` for WeChat (native + jsapi orders, signed notify, status, admin list)

**Files:**
- Modify: `backend/routers/payments.py` (full rewrite)
- Modify: `tests/wechat_pay_smoke.py` (append Steps C-H)

**Interfaces:**
- Consumes: `wechat_pay_provider()`, `PaymentIn.channel`, `User.wx_openid`, `PaymentRecord.{channel,openid,provider_trade_no,paid_at}` (all prior tasks).
- Produces: `create_payment(data, user, session) -> dict` (unchanged call signature, `account="usage"` now uses WeChat; `account="premium"` still rejected), `_apply_paid(session, row) -> None`, `wechat_notify(request, session) -> dict` (async), `get_payment(order_no, user, session) -> dict`, `list_payments(enterprise_id, status_value, channel, session) -> list[dict]`. Task 6 imports `payment_callback`'s sibling `_apply_paid` indirectly (no direct import needed — it's file-local).

- [ ] **Step 1: Write the failing test**

In `tests/wechat_pay_smoke.py`, replace the final `print("wechat pay smoke: ok")` line (after Step B) with:

```python
            # Step C: premium 继续被拒绝（既有行为不回归）
            from backend.routers.payments import create_payment, get_payment, list_payments, wechat_notify
            from backend.schemas import PaymentIn

            try:
                create_payment(PaymentIn(enterprise_id=enterprise.id, account="premium", amount=10.0), enterprise_user, session)
                raise AssertionError("premium 使用微信支付应被拒绝")
            except HTTPException as error:
                assert error.status_code == 400

            # Step D: native 下单成功
            native_result = create_payment(PaymentIn(enterprise_id=enterprise.id, account="usage", amount=88.0, channel="native"), enterprise_user, session)
            assert native_result["status"] == "pending" and native_result["code_url"]
            native_order_no = native_result["order_no"]

            # Step E: jsapi 下单成功（openid 已在 Step B 绑定）
            jsapi_result = create_payment(PaymentIn(enterprise_id=enterprise.id, account="usage", amount=66.0, channel="jsapi"), enterprise_user, session)
            assert jsapi_result["status"] == "pending" and jsapi_result["prepay_id"]

            # jsapi 未绑定 openid 时必须被拒绝
            try:
                create_payment(PaymentIn(enterprise_id=other_enterprise.id, account="usage", amount=1.0, channel="jsapi"), other_user, session)
                raise AssertionError("未绑定 openid 时 jsapi 下单应被拒绝")
            except HTTPException as error:
                assert error.status_code == 400

            # Step F: wechat-notify 验签失败 —— 不落库、不加余额
            import asyncio
            import hashlib
            import hmac
            import json

            from backend.providers import WeChatPayProvider

            class _FakeRequest:
                def __init__(self, headers, body):
                    self.headers = headers
                    self._body = body

                async def body(self):
                    return self._body

            usage_balance_before = enterprise.usage_balance
            bad_body = json.dumps({"out_trade_no": native_order_no, "status": "paid", "transaction_id": "wx-txn-1"}).encode()
            bad_request = _FakeRequest({"X-Mock-Signature": "not-a-real-signature"}, bad_body)
            try:
                asyncio.run(wechat_notify(bad_request, session))
                raise AssertionError("验签失败的回调应被拒绝")
            except HTTPException as error:
                assert error.status_code == 400
            session.refresh(enterprise)
            assert enterprise.usage_balance == usage_balance_before
            unpaid = session.scalar(select(PaymentRecord).where(PaymentRecord.order_no == native_order_no))
            assert unpaid.status == "pending"

            # Step G: 正确签名的回调 —— 入账、写账本、写 provider_trade_no/paid_at；重复回调幂等
            good_body = json.dumps({"out_trade_no": native_order_no, "status": "paid", "transaction_id": "wx-txn-1"}).encode()
            signature = hmac.new(WeChatPayProvider.MOCK_NOTIFY_SECRET.encode(), good_body, hashlib.sha256).hexdigest()
            first_notify = asyncio.run(wechat_notify(_FakeRequest({"X-Mock-Signature": signature}, good_body), session))
            assert first_notify["status"] == "paid" and first_notify["idempotent"] is False
            session.refresh(enterprise)
            assert enterprise.usage_balance == usage_balance_before + 88.0
            paid_row = session.scalar(select(PaymentRecord).where(PaymentRecord.order_no == native_order_no))
            assert paid_row.provider_trade_no == "wx-txn-1" and paid_row.paid_at is not None

            from backend.models import LedgerEntry
            ledger_row = session.scalar(select(LedgerEntry).where(LedgerEntry.business_id == native_order_no))
            assert ledger_row is not None and ledger_row.direction == "credit" and ledger_row.amount == 88.0

            second_notify = asyncio.run(wechat_notify(_FakeRequest({"X-Mock-Signature": signature}, good_body), session))
            assert second_notify["idempotent"] is True
            session.refresh(enterprise)
            assert enterprise.usage_balance == usage_balance_before + 88.0
            ledger_count = len(session.scalars(select(LedgerEntry).where(LedgerEntry.business_id == native_order_no)).all())
            assert ledger_count == 1

            # Step H: 查询接口 —— 企业只能查自己单位的订单，管理员不限
            status_view = get_payment(native_order_no, enterprise_user, session)
            assert status_view["status"] == "paid"
            try:
                get_payment(native_order_no, other_user, session)
                raise AssertionError("无关企业不应能查看该订单")
            except HTTPException as error:
                assert error.status_code == 403

            admin_list = list_payments(None, "", "", session)
            assert any(row["order_no"] == native_order_no for row in admin_list)

        print("wechat pay smoke: ok")
```

Also add `from fastapi import HTTPException` and `from sqlalchemy import select` and `from backend.models import Enterprise, PaymentRecord, User` at the top of `run()` if not already present from earlier tasks (they already are, from Task 1/4 — no change needed there).

- [ ] **Step 2: Run test to verify it fails**

Run: `python3 tests/wechat_pay_smoke.py`
Expected: FAIL — `ImportError: cannot import name 'wechat_notify' from 'backend.routers.payments'`.

- [ ] **Step 3: Rewrite `backend/routers/payments.py`**

Replace the entire file:

```python
import secrets
from datetime import datetime, timezone

from fastapi import APIRouter, Depends, HTTPException, Query, Request
from sqlalchemy import select
from sqlalchemy.orm import Session

from ..core.db import db
from ..core.rbac import assert_enterprise_scope, require_role
from ..core.security import current_user
from ..models import Enterprise, PaymentRecord, User
from ..providers import wechat_pay_provider
from ..schemas import PaymentCallbackIn, PaymentIn
from ..services import post_ledger_entry

router = APIRouter(prefix="/api", tags=["payments"])


def _apply_paid(session: Session, row: PaymentRecord) -> None:
    row.status = "paid"
    ent = session.get(Enterprise, row.enterprise_id)
    if row.account == "premium": ent.premium_balance += row.amount
    else: ent.usage_balance += row.amount
    post_ledger_entry(session, ent, row.account, "credit", row.amount, "payment", row.order_no, idempotency_key=row.order_no)
    session.commit()


@router.post("/payments", dependencies=[Depends(require_role("admin", "enterprise", detail="无权创建充值订单"))])
def create_payment(data:PaymentIn,user:User=Depends(current_user),session:Session=Depends(db)):
    assert_enterprise_scope(user, data.enterprise_id, "无权为该单位充值")
    if not session.get(Enterprise,data.enterprise_id): raise HTTPException(404,"投保单位不存在")
    if data.account == "premium": raise HTTPException(400, "保费账户充值请使用「账户充值」页面提交充值申请，走审核流程")
    order=f"PAY-{datetime.now().strftime('%Y%m%d%H%M%S')}-{secrets.token_hex(3).upper()}"
    if data.channel == "jsapi":
        if not user.wx_openid: raise HTTPException(400, "请先在小程序内完成微信授权绑定")
        result = wechat_pay_provider().create_jsapi_order(data.amount, order, user.wx_openid, "响帮帮保经云-平台服务费")
    else:
        result = wechat_pay_provider().create_native_order(data.amount, order, "响帮帮保经云-平台服务费")
    if not result.ok: raise HTTPException(502, result.message or "微信支付下单失败")
    row=PaymentRecord(order_no=order,enterprise_id=data.enterprise_id,account=data.account,amount=data.amount,status="pending",provider=result.provider,channel=data.channel,openid=user.wx_openid if data.channel=="jsapi" else None)
    session.add(row);session.commit()
    return {"order_no":order,"status":row.status,"channel":row.channel,**result.data,"request_id":result.request_id}

@router.post("/payments/callback")
def payment_callback(data:PaymentCallbackIn,session:Session=Depends(db)):
    row=session.scalar(select(PaymentRecord).where(PaymentRecord.order_no==data.order_no))
    if not row: raise HTTPException(404,"支付订单不存在")
    if row.status=="paid": return {"ok":True,"order_no":row.order_no,"status":row.status,"idempotent":True}
    if data.status=="paid":
        row.provider_trade_no=data.provider_trade_no
        row.paid_at=datetime.now(timezone.utc)
        _apply_paid(session,row)
    else:
        row.status=data.status; session.commit()
    return {"ok":True,"order_no":row.order_no,"status":row.status,"idempotent":False}

@router.post("/payments/wechat-notify")
async def wechat_notify(request:Request,session:Session=Depends(db)):
    payload=wechat_pay_provider().verify_notify(dict(request.headers), await request.body())
    if not payload: raise HTTPException(400,"签名校验失败")
    row=session.scalar(select(PaymentRecord).where(PaymentRecord.order_no==payload.get("out_trade_no","")))
    if not row: raise HTTPException(404,"支付订单不存在")
    if row.status=="paid": return {"ok":True,"order_no":row.order_no,"status":row.status,"idempotent":True}
    if payload.get("status")=="paid":
        row.provider_trade_no=payload.get("transaction_id","")
        row.paid_at=datetime.now(timezone.utc)
        _apply_paid(session,row)
    else:
        row.status=payload.get("status",row.status); session.commit()
    return {"ok":True,"order_no":row.order_no,"status":row.status,"idempotent":False}

@router.get("/payments/{order_no}")
def get_payment(order_no:str,user:User=Depends(current_user),session:Session=Depends(db)):
    row=session.scalar(select(PaymentRecord).where(PaymentRecord.order_no==order_no))
    if not row: raise HTTPException(404,"支付订单不存在")
    assert_enterprise_scope(user,row.enterprise_id,"无权查看该订单")
    return {"order_no":row.order_no,"status":row.status,"amount":row.amount,"account":row.account,"channel":row.channel,"paid_at":row.paid_at}

@router.get("/payments", dependencies=[Depends(require_role("admin", detail="仅总后台可查看支付记录"))])
def list_payments(enterprise_id:int|None=Query(None), status_value:str=Query("",alias="status"), channel:str=Query(""), session:Session=Depends(db)):
    stmt=select(PaymentRecord).order_by(PaymentRecord.created_at.desc())
    if enterprise_id: stmt=stmt.where(PaymentRecord.enterprise_id==enterprise_id)
    if status_value: stmt=stmt.where(PaymentRecord.status==status_value)
    if channel: stmt=stmt.where(PaymentRecord.channel==channel)
    rows=session.scalars(stmt).all()
    enterprise_names={e.id:e.name for e in session.query(Enterprise).all()}
    return [{"order_no":r.order_no,"enterprise_id":r.enterprise_id,"enterprise_name":enterprise_names.get(r.enterprise_id,""),"account":r.account,"amount":r.amount,"status":r.status,"provider":r.provider,"channel":r.channel,"provider_trade_no":r.provider_trade_no,"created_at":r.created_at,"paid_at":r.paid_at} for r in rows]

@router.get("/payments/reconcile", dependencies=[Depends(require_role("admin", detail="仅总后台可对账"))])
def payment_reconcile(session:Session=Depends(db)):
    return {"pending":session.query(PaymentRecord).filter(PaymentRecord.status=="pending").count(),"paid":session.query(PaymentRecord).filter(PaymentRecord.status=="paid").count(),"failed":session.query(PaymentRecord).filter(PaymentRecord.status=="failed").count()}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `python3 tests/wechat_pay_smoke.py`
Expected: `wechat pay smoke: ok`

- [ ] **Step 5: Run full regression**

Run: `python3 -m compileall -q backend && python3 tests/recharge_smoke.py && python3 tests/system_smoke.py && python3 tests/security_smoke.py && python3 tests/participation_lock_smoke.py`
Expected: all pass — `tests/recharge_smoke.py` still exercises `create_payment(..., account="usage", ...)` (line 271) and must keep returning `status: "pending"`, proving the rewire didn't break the existing caller.

- [ ] **Step 6: Commit**

```bash
git add backend/routers/payments.py tests/wechat_pay_smoke.py
git commit -m "feat: wire WeChat Pay into /api/payments (native+jsapi orders, signed notify, status, admin list)"
```

---

### Task 6: Router — expose default collection method on the recharge payment-account endpoint

**Files:**
- Modify: `backend/routers/recharge_requests.py:23-27`
- Modify: `tests/wechat_pay_smoke.py` (append Step I)

**Interfaces:**
- Consumes: `settings_service.get("USAGE_FEE_DEFAULT_METHOD", "wechat")` (Task 2).
- Produces: `GET /api/recharge/payment-account?account_type=usage` response now includes `default_method: "wechat"|"bank"` alongside the existing fields (or, if no bank account is configured for usage, a dict containing just `default_method`).

- [ ] **Step 1: Write the failing test**

In `tests/wechat_pay_smoke.py`, replace the final `print("wechat pay smoke: ok")` line with:

```python
            # Step I: 使用费默认收款方式对外可读，管理员可改
            from backend.routers.recharge_requests import recharge_payment_account
            from backend.services import settings as settings_service

            usage_account_view = recharge_payment_account("usage", "", session)
            assert usage_account_view["default_method"] == "wechat"
            settings_service.set_many({"USAGE_FEE_DEFAULT_METHOD": "bank"}, admin.id)
            usage_account_view_after = recharge_payment_account("usage", "", session)
            assert usage_account_view_after["default_method"] == "bank"
            settings_service.set_many({"USAGE_FEE_DEFAULT_METHOD": "wechat"}, admin.id)  # 恢复默认，不影响后续断言

        print("wechat pay smoke: ok")
```

- [ ] **Step 2: Run test to verify it fails**

Run: `python3 tests/wechat_pay_smoke.py`
Expected: FAIL — `KeyError: 'default_method'` (or `TypeError: 'NoneType' object is not subscriptable` if no usage account is configured yet in the test fixtures — either way, the field isn't there yet).

- [ ] **Step 3: Add `default_method` to the endpoint**

In `backend/routers/recharge_requests.py`, replace lines 23-27:

```python
@router.get("/recharge/payment-account", dependencies=[Depends(require_role("admin", "enterprise", detail="无权查看收款账户"))])
def recharge_payment_account(account_type: str = Query(...), insurer: str = Query(""), session: Session = Depends(db)):
    return recharge_payment_account_view(session, account_type, insurer)
```

with:

```python
@router.get("/recharge/payment-account", dependencies=[Depends(require_role("admin", "enterprise", detail="无权查看收款账户"))])
def recharge_payment_account(account_type: str = Query(...), insurer: str = Query(""), session: Session = Depends(db)):
    result = recharge_payment_account_view(session, account_type, insurer)
    if account_type != "usage":
        return result
    from ..services import settings as settings_service
    default_method = settings_service.get("USAGE_FEE_DEFAULT_METHOD", "wechat")
    return {**(result or {}), "default_method": default_method}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `python3 tests/wechat_pay_smoke.py`
Expected: `wechat pay smoke: ok`

- [ ] **Step 5: Run full regression**

Run: `python3 -m compileall -q backend && python3 tests/recharge_smoke.py && python3 tests/system_smoke.py`
Expected: all pass — confirm `recharge_smoke.py`'s existing `GET /recharge/payment-account` usages (premium path) are unaffected since the new field only applies when `account_type == "usage"`.

- [ ] **Step 6: Commit**

```bash
git add backend/routers/recharge_requests.py tests/wechat_pay_smoke.py
git commit -m "feat: expose admin-configurable default usage-fee collection method"
```

This completes the backend. Run the full backend verification matrix once more before moving to the frontend:

```bash
python3 -m compileall -q backend && git diff --check
python3 tests/system_smoke.py
python3 tests/security_smoke.py
python3 tests/participation_lock_smoke.py
python3 tests/recharge_smoke.py
python3 tests/salesperson_portal_smoke.py
python3 tests/settings_smoke.py
python3 tests/id_number_test.py
python3 tests/wechat_pay_config_test.py
python3 tests/wechat_pay_provider_test.py
python3 tests/wechat_pay_smoke.py
```

---

### Task 7: Web — payments API client + QR panel component

**Files:**
- Create: `web/src/api/payments.ts`
- Modify: `web/src/api/recharge.ts:7-13` (`RechargePaymentAccount`)
- Create: `web/src/components/recharge/WeChatPayPanel.vue`
- Modify: `web/package.json` (add `qrcode` dependency)

**Interfaces:**
- Produces: `createPayment(data) -> Promise<CreatePaymentResult>`, `getPaymentStatus(orderNo) -> Promise<PaymentStatus>`, `listPayments(params) -> Promise<PaymentRecordRow[]>`, `<WeChatPayPanel :enterprise-id :amount @paid @cancel>`. Task 8 imports all of these.

- [ ] **Step 1: Add the `qrcode` dependency**

Run: `cd web && npm install qrcode && npm install -D @types/qrcode`
Expected: `web/package.json` gains `qrcode` under `dependencies` and `@types/qrcode` under `devDependencies`.

- [ ] **Step 2: Extend `RechargePaymentAccount`**

In `web/src/api/recharge.ts`, replace the interface (lines 7-13):

```typescript
export interface RechargePaymentAccount {
  label: string
  bank_name: string
  account_no: string
  account_holder: string
  insurers: string[]
  default_method?: 'wechat' | 'bank'
}
```

- [ ] **Step 3: Create the payments API client**

Create `web/src/api/payments.ts`:

```typescript
import { client } from './client'

export interface CreatePaymentResult {
  order_no: string
  status: 'pending' | 'paid' | 'failed'
  channel: 'native' | 'jsapi'
  code_url?: string
  request_id: string
}

export function createPayment(data: { enterprise_id: number; account: 'premium' | 'usage'; amount: number; channel: 'native' | 'jsapi' }) {
  return client.post<CreatePaymentResult>('/payments', data).then((r) => r.data)
}

export interface PaymentStatus {
  order_no: string
  status: 'pending' | 'paid' | 'failed'
  amount: number
  account: 'premium' | 'usage'
  channel: 'native' | 'jsapi'
  paid_at: string | null
}

export function getPaymentStatus(orderNo: string) {
  return client.get<PaymentStatus>(`/payments/${orderNo}`).then((r) => r.data)
}

export interface PaymentRecordRow {
  order_no: string
  enterprise_id: number
  enterprise_name: string
  account: 'premium' | 'usage'
  amount: number
  status: 'pending' | 'paid' | 'failed'
  provider: string
  channel: 'native' | 'jsapi'
  provider_trade_no: string | null
  created_at: string
  paid_at: string | null
}

export function listPayments(params: { enterprise_id?: number; status?: string; channel?: string } = {}) {
  return client.get<PaymentRecordRow[]>('/payments', { params }).then((r) => r.data)
}
```

- [ ] **Step 4: Create the QR panel component**

Create `web/src/components/recharge/WeChatPayPanel.vue`:

```vue
<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import QRCode from 'qrcode'
import { ElMessage } from 'element-plus'
import { createPayment, getPaymentStatus } from '@/api/payments'

const props = defineProps<{ enterpriseId: number; amount: number }>()
const emit = defineEmits<{ paid: []; cancel: [] }>()

const loading = ref(false)
const orderNo = ref('')
const codeUrl = ref('')
const canvasRef = ref<HTMLCanvasElement | null>(null)
let pollTimer: ReturnType<typeof setInterval> | null = null

function stopPolling() {
  if (pollTimer) { clearInterval(pollTimer); pollTimer = null }
}

async function start() {
  if (!props.enterpriseId || props.amount <= 0) {
    ElMessage.error('请先选择投保单位并输入充值金额')
    return
  }
  loading.value = true
  try {
    const result = await createPayment({ enterprise_id: props.enterpriseId, account: 'usage', amount: props.amount, channel: 'native' })
    orderNo.value = result.order_no
    codeUrl.value = result.code_url || ''
    if (canvasRef.value && codeUrl.value) await QRCode.toCanvas(canvasRef.value, codeUrl.value, { width: 200 })
    pollTimer = setInterval(async () => {
      try {
        const status = await getPaymentStatus(orderNo.value)
        if (status.status === 'paid') {
          stopPolling()
          ElMessage.success('支付成功')
          emit('paid')
        }
      } catch {
        /* 轮询失败静默重试，直到用户取消或下次成功 */
      }
    }, 2000)
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    loading.value = false
  }
}

function cancel() {
  stopPolling()
  emit('cancel')
}

watch(() => [props.enterpriseId, props.amount], () => {
  stopPolling()
  orderNo.value = ''
  codeUrl.value = ''
})

onBeforeUnmount(stopPolling)

defineExpose({ start })
</script>

<template>
  <div class="wechat-pay-panel">
    <el-button v-if="!orderNo" type="primary" :loading="loading" @click="start">生成收款二维码</el-button>
    <div v-else class="qr-area">
      <canvas ref="canvasRef" />
      <p class="muted">请使用微信扫码支付 ¥{{ amount.toFixed(2) }}，支付成功后页面会自动刷新</p>
      <el-button size="small" @click="cancel">取消</el-button>
    </div>
  </div>
</template>

<style scoped>
.wechat-pay-panel {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 12px;
  padding: 12px 0;
}
.qr-area {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
}
.muted {
  color: var(--el-text-color-placeholder);
  font-size: 12.5px;
  text-align: center;
}
</style>
```

- [ ] **Step 5: Verify the type-check/build passes**

Run: `cd web && npm run build`
Expected: `vue-tsc -b` reports no errors and `vite build` completes (the new files aren't imported anywhere yet, so this only proves they're independently well-typed).

- [ ] **Step 6: Commit**

```bash
git add web/package.json web/package-lock.json web/src/api/payments.ts web/src/api/recharge.ts web/src/components/recharge/WeChatPayPanel.vue
git commit -m "feat(web): add WeChat payment API client and QR panel component"
```

---

### Task 8: Web — wire WeChat payment into the recharge center page

**Files:**
- Modify: `web/src/views/recharge/RechargeCenterView.vue` (full rewrite)

**Interfaces:**
- Consumes: `WeChatPayPanel` (Task 7), `createPayment`/`getPaymentStatus`/`listPayments`/`PaymentRecordRow` (Task 7), `RechargePaymentAccount.default_method` (Task 7), `GET /api/recharge/payment-account`'s `default_method` field (Task 6), `GET /api/payments` (Task 5).

- [ ] **Step 1: Manual verification plan (no automated frontend test runner in this project — `npm run build` is the gate)**

Before rewriting, note the manual check to run after Step 3: `cd web && npm run dev`, log in as `admin`/`admin123`, open 充值中心 (Recharge Center), click "＋ 发起充值", select 账户类型=系统服务费, confirm a "收款方式" radio appears defaulting to 微信支付, click "生成收款二维码" and confirm a QR canvas renders; switch the radio to 银行转账 and confirm the original upload-receipt form still works unchanged; as admin, confirm a new "微信支付记录" tab appears and loads (empty is fine, since no real payment exists yet).

- [ ] **Step 2: Rewrite the file**

Replace the entire contents of `web/src/views/recharge/RechargeCenterView.vue`:

```vue
<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import * as rechargeApi from '@/api/recharge'
import { listPayments } from '@/api/payments'
import type { PaymentRecordRow } from '@/api/payments'
import { recognizeReceiptAmount } from '@/api/ocr'
import type { RechargePaymentAccount } from '@/api/recharge'
import { listEnterprises } from '@/api/enterprises'
import type { Enterprise, RechargeRequest } from '@/api/types'
import { useAuthStore } from '@/stores/auth'
import { money, formatDateTime } from '@/utils/format'
import PageCard from '@/components/PageCard.vue'
import StatTile from '@/components/StatTile.vue'
import TablePagination from '@/components/TablePagination.vue'
import WeChatPayPanel from '@/components/recharge/WeChatPayPanel.vue'
import { usePagedList } from '@/composables/usePagedList'

const auth = useAuthStore()
const route = useRoute()
const activeTab = ref('requests')
const loading = ref(true)
const requests = ref<RechargeRequest[]>([])
const enterprises = ref<Enterprise[]>([])
const paymentOptions = ref<rechargeApi.PremiumPaymentOption[]>([])
const wechatRecords = ref<PaymentRecordRow[]>([])
const wechatRecordsLoading = ref(false)

async function load() {
  loading.value = true
  try {
    const tasks: Promise<unknown>[] = [
      rechargeApi.listRechargeRequests().then((r) => (requests.value = r)),
      rechargeApi.getRechargePaymentOptions().then((r) => (paymentOptions.value = r)).catch(() => (paymentOptions.value = [])),
    ]
    if (auth.isAdmin()) {
      tasks.push(listEnterprises().then((r) => (enterprises.value = r)))
    }
    await Promise.all(tasks)
  } finally {
    loading.value = false
  }
}
async function loadWechatRecords() {
  if (!auth.isAdmin()) return
  wechatRecordsLoading.value = true
  try {
    wechatRecords.value = await listPayments()
  } finally {
    wechatRecordsLoading.value = false
  }
}
watch(activeTab, (tab) => { if (tab === 'wechat') loadWechatRecords() })

// Deep-link from a dashboard balance / alert: open the recharge dialog already
// pointed at the enterprise and account that needs topping up, so the click
// lands on the exact thing to fix instead of a blank form.
onMounted(async () => {
  await load()
  const q = route.query
  if (q.enterprise_id || q.account_type) {
    openSubmit({
      enterprise_id: q.enterprise_id ? Number(q.enterprise_id) : undefined,
      account_type: q.account_type === 'usage' ? 'usage' : q.account_type === 'premium' ? 'premium' : undefined,
      insurer: typeof q.insurer === 'string' ? q.insurer : undefined,
    })
  }
})

const { page, pageSize, total: pagedTotal, paged } = usePagedList(requests)
const pendingCount = computed(() => requests.value.filter((r) => r.status === 'pending').length)

const STATUS_TEXT: Record<string, string> = { pending: '待确认', confirmed: '已到账', rejected: '已驳回' }
const STATUS_TYPE: Record<string, string> = { pending: 'warning', confirmed: 'success', rejected: 'danger' }
const PAY_STATUS_TEXT: Record<string, string> = { pending: '待支付', paid: '已支付', failed: '已失败' }
const PAY_STATUS_TYPE: Record<string, string> = { pending: 'warning', paid: 'success', failed: 'danger' }

// ---- submit ----
const submitVisible = ref(false)
const submitForm = reactive({
  enterprise_id: null as number | null,
  account_type: 'premium' as 'premium' | 'usage',
  method: 'wechat' as 'wechat' | 'bank',
  insurer: '',
  amount: 0,
  file: null as File | null,
})

// 收款账户（往哪里转账）由后端按账户类型解析——保费按保司、使用费按平台使用费
// 账户，企业端也可读。选择变化时实时拉取，让用户下单前就看到打款目标账户；
// 使用费还会带出 default_method，决定提交弹窗默认选中微信支付还是银行转账。
const paymentAccount = ref<RechargePaymentAccount | null>(null)
const paymentLoading = ref(false)
async function refreshPaymentAccount() {
  if (submitForm.account_type === 'premium' && !submitForm.insurer.trim()) {
    paymentAccount.value = null
    return
  }
  paymentLoading.value = true
  try {
    paymentAccount.value = await rechargeApi.getRechargePaymentAccount(submitForm.account_type, submitForm.insurer.trim())
    if (submitForm.account_type === 'usage') {
      submitForm.method = paymentAccount.value?.default_method ?? 'wechat'
    }
  } catch {
    paymentAccount.value = null
  } finally {
    paymentLoading.value = false
  }
}
watch(() => [submitForm.account_type, submitForm.insurer], refreshPaymentAccount)
function openSubmit(prefill?: { enterprise_id?: number; account_type?: 'premium' | 'usage'; insurer?: string }) {
  Object.assign(submitForm, {
    enterprise_id: prefill?.enterprise_id ?? (auth.isEnterprise() ? auth.user?.enterprise_id ?? null : null),
    account_type: prefill?.account_type ?? 'premium',
    method: 'wechat',
    insurer: prefill?.insurer ?? '',
    amount: 0,
    file: null,
  })
  submitVisible.value = true
  refreshPaymentAccount()
}
const ocrHint = ref('')
const ocrLoading = ref(false)
async function handleFileChange(e: Event) {
  const input = e.target as HTMLInputElement
  const file = input.files?.[0] ?? null
  submitForm.file = file
  ocrHint.value = ''
  // 图片回单尝试 OCR 自动带出金额（识别为便利功能，失败/未启用则静默，不影响手工填写）
  if (file && file.type.startsWith('image/')) {
    ocrLoading.value = true
    try {
      const res = await recognizeReceiptAmount(file)
      if (res.amount > 0) {
        submitForm.amount = res.amount
        ocrHint.value = res.mock ? `已识别金额 ${money(res.amount)}（模拟，请核对）` : `已识别金额 ${money(res.amount)}，请核对`
      }
    } catch {
      /* OCR 未启用或识别失败：静默，用户手工填写 */
    } finally {
      ocrLoading.value = false
    }
  }
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
function onWeChatPaid() {
  ElMessage.success('微信支付成功，使用费余额已到账')
  submitVisible.value = false
  load()
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
    <el-tabs v-model="activeTab">
      <el-tab-pane label="充值申请" name="requests">
        <div class="stat-grid">
          <StatTile label="待确认申请" :value="pendingCount" hint-type="warning" />
        </div>

        <PageCard title="充值记录" :count="requests.length">
          <template #actions>
            <el-button type="primary" @click="openSubmit()">＋ 发起充值</el-button>
          </template>
          <el-table :data="paged" size="small">
            <el-table-column v-if="auth.isAdmin()" prop="enterprise_name" label="投保单位" min-width="140" />
            <el-table-column label="账户类型" width="100">
              <template #default="{ row }">{{ row.account_type === 'premium' ? '保费' : '系统服务费' }}</template>
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
      </el-tab-pane>

      <el-tab-pane v-if="auth.isAdmin()" label="微信支付记录" name="wechat">
        <PageCard title="微信支付记录" :count="wechatRecords.length">
          <el-table v-loading="wechatRecordsLoading" :data="wechatRecords" size="small">
            <el-table-column prop="order_no" label="订单号" min-width="200" />
            <el-table-column prop="enterprise_name" label="投保单位" min-width="140" />
            <el-table-column label="金额" width="110">
              <template #default="{ row }">{{ money(row.amount) }}</template>
            </el-table-column>
            <el-table-column label="渠道" width="100">
              <template #default="{ row }">{{ row.channel === 'jsapi' ? '小程序' : '扫码' }}</template>
            </el-table-column>
            <el-table-column label="状态" width="100">
              <template #default="{ row }">
                <el-tag :type="PAY_STATUS_TYPE[row.status]" size="small">{{ PAY_STATUS_TEXT[row.status] }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="微信交易号" min-width="180">
              <template #default="{ row }">{{ row.provider_trade_no || '—' }}</template>
            </el-table-column>
            <el-table-column label="支付时间" width="160">
              <template #default="{ row }">{{ row.paid_at ? formatDateTime(row.paid_at) : '—' }}</template>
            </el-table-column>
          </el-table>
        </PageCard>
      </el-tab-pane>
    </el-tabs>

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
            <el-radio-button value="usage">系统服务费</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="submitForm.account_type === 'usage'" label="收款方式" required>
          <el-radio-group v-model="submitForm.method">
            <el-radio-button value="wechat">微信支付</el-radio-button>
            <el-radio-button value="bank">银行转账</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="submitForm.account_type === 'premium'" label="保司" required>
          <el-select v-model="submitForm.insurer" filterable allow-create default-first-option placeholder="请选择保司（已配置收款账户，也可直接输入）" style="width: 100%">
            <el-option v-for="opt in paymentOptions" :key="opt.insurer" :label="opt.insurer" :value="opt.insurer" />
          </el-select>
        </el-form-item>
        <el-form-item label="充值金额" required><el-input-number v-model="submitForm.amount" :min="0.01" :step="100" style="width: 100%" /></el-form-item>

        <template v-if="submitForm.account_type === 'usage' && submitForm.method === 'wechat'">
          <el-form-item label="微信支付">
            <WeChatPayPanel
              v-if="submitForm.enterprise_id"
              :enterprise-id="submitForm.enterprise_id"
              :amount="submitForm.amount"
              @paid="onWeChatPaid"
              @cancel="submitVisible = false"
            />
            <span v-else class="muted">请先选择投保单位</span>
          </el-form-item>
        </template>
        <template v-else>
          <el-form-item v-if="paymentAccount" label="收款账户">
            <div class="account-hint">
              <p><b>{{ paymentAccount.account_holder }}</b></p>
              <p>{{ paymentAccount.bank_name }} · {{ paymentAccount.account_no }}</p>
              <p v-if="paymentAccount.insurers.length > 1" class="muted">该账户同时用于：{{ paymentAccount.insurers.join('、') }}</p>
              <p class="muted">请按此收款账户转账后上传回单</p>
            </div>
          </el-form-item>
          <el-form-item v-else-if="!paymentLoading && (submitForm.account_type === 'usage' || submitForm.insurer.trim())" label="收款账户">
            <span class="muted">平台尚未配置该账户的收款信息，请联系平台后再转账。</span>
          </el-form-item>
          <el-form-item label="转账回单" required>
            <div style="width: 100%">
              <input type="file" accept=".pdf,.jpg,.jpeg,.png" @change="handleFileChange" />
              <div v-if="ocrLoading" class="muted" style="font-size: 12px; margin-top: 4px">正在识别金额…</div>
              <div v-else-if="ocrHint" style="font-size: 12px; margin-top: 4px; color: var(--el-color-success)">{{ ocrHint }}</div>
              <div v-else class="muted" style="font-size: 12px; margin-top: 4px">上传图片回单可自动识别金额（需在系统设置开启 OCR）</div>
            </div>
          </el-form-item>
        </template>
      </el-form>
      <template #footer>
        <el-button @click="submitVisible = false">取消</el-button>
        <el-button v-if="!(submitForm.account_type === 'usage' && submitForm.method === 'wechat')" type="primary" @click="submitRecharge">提交</el-button>
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

- [ ] **Step 3: Run the build**

Run: `cd web && npm run build`
Expected: `vue-tsc -b` and `vite build` both succeed with no type errors.

- [ ] **Step 4: Manual verification**

Follow the plan from Step 1 against `npm run dev` (or the built `web/dist` served by the backend). Confirm the wechat/bank radio, QR rendering, and admin-only records tab all behave as described.

- [ ] **Step 5: Commit**

```bash
git add web/src/views/recharge/RechargeCenterView.vue
git commit -m "feat(web): add WeChat payment flow and records tab to the recharge center"
```

---

### Task 9: Miniprogram — wire WeChat JSAPI payment into the billing page

**Files:**
- Modify: `miniprogram/pages/billing/billing.js`

**Interfaces:**
- Consumes: `app.request(path, options)` (existing helper, `miniprogram/app.js`), `POST /api/wechat/bind-openid` (Task 4), `POST /api/payments` with `channel:"jsapi"` (Task 5).

**Context:** The current `recharge()`/`rechargeManual()`/`rechargeByOcr()`/`doRecharge()` functions in `billing.js` call `POST /enterprises/{id}/recharge`, which `backend/routers/enterprises.py:67` restricts to `require_role("admin")` — the code comment there explicitly says this endpoint "used to let a logged-in enterprise user credit their own balance with zero payment verification... restricted to admin-only... until the real Payment Order + Ledger flow... replaces it." Since the miniprogram only ever logs in as `portal: 'enterprise'` (`miniprogram/app.js:24-32`), every tap of "立即充值" today returns a 403 for real users. This task replaces that dead flow with the real one.

- [ ] **Step 1: Manual verification plan (no automated test tooling for the miniprogram — verify via WeChat DevTools, per project convention)**

After Step 2, open the project in WeChat DevTools, log in as `enterprise`/`enterprise123`, go to 资金与发票 (billing), tap "立即充值" on the 平台使用费账户 card, enter an amount, confirm it calls `wx.login()` → binds openid → creates a jsapi order → invokes `wx.requestPayment()` (DevTools will show a mock payment confirmation dialog in the simulator). Confirm a cancelled payment shows "已取消支付" and a successful one (in the simulator) reloads the balance.

- [ ] **Step 2: Rewrite `billing.js`**

Replace the entire contents of `miniprogram/pages/billing/billing.js`:

```javascript
const app = getApp();
Page({
  data: { items: [], invoices: [], loading: true },
  onShow() { this.load(); },
  load() { return Promise.all([app.request('/billing', { silent: true }), app.request('/invoices', { silent: true })]).then(([items, invoices]) => this.setData({ items: items.map((item) => ({ ...item, balance_text: Number(item.balance || 0).toFixed(2), estimated_text: Number(item.estimated_daily || 0).toFixed(2), month_accrued_text: Number(item.month_accrued || 0).toFixed(2), total_accrued_text: Number(item.total_accrued || 0).toFixed(2) })), invoices: invoices.map((item) => ({ ...item, amount_text: Number(item.amount || 0).toFixed(2), status_label: ({ pending: '待审核', approved: '已审核', issued: '已开票', rejected: '已驳回' })[item.status] || item.status })), loading: false })).catch(() => this.setData({ loading: false })); },
  recharge(e) {
    const id = e.currentTarget.dataset.id;
    // 平台服务费在线缴纳：微信支付（JSAPI），支付成功由后端 wechat-notify 回调自动到账。
    wx.showModal({
      title: '平台使用费缴纳', editable: true, placeholderText: '请输入缴纳金额', confirmText: '微信支付',
      success: (res) => {
        if (!res.confirm) return;
        const amount = Number(res.content);
        if (!amount || amount <= 0) { wx.showToast({ title: '请输入有效金额', icon: 'none' }); return; }
        this.payWithWeChat(id, amount);
      }
    });
  },
  ensureOpenid() {
    if (app.globalData.user && app.globalData.user.wx_openid) return Promise.resolve(app.globalData.user.wx_openid);
    return new Promise((resolve, reject) => {
      wx.login({
        success: (loginRes) => {
          if (!loginRes.code) { reject(new Error('微信登录失败，请重试')); return; }
          app.request('/wechat/bind-openid', { method: 'POST', data: { code: loginRes.code } })
            .then((r) => {
              app.globalData.user = { ...(app.globalData.user || {}), wx_openid: r.wx_openid };
              wx.setStorageSync('user', app.globalData.user);
              resolve(r.wx_openid);
            })
            .catch(reject);
        },
        fail: () => reject(new Error('微信登录失败，请重试'))
      });
    });
  },
  payWithWeChat(enterpriseId, amount) {
    wx.showLoading({ title: '正在下单…' });
    this.ensureOpenid()
      .then(() => app.request('/payments', { method: 'POST', data: { enterprise_id: Number(enterpriseId), account: 'usage', amount, channel: 'jsapi' } }))
      .then((order) => {
        wx.hideLoading();
        wx.requestPayment({
          timeStamp: order.timeStamp,
          nonceStr: order.nonceStr,
          package: order.package,
          signType: order.signType || 'RSA',
          paySign: order.paySign,
          success: () => { wx.showToast({ title: '支付成功' }); this.load(); },
          fail: () => { wx.showToast({ title: '已取消支付', icon: 'none' }); }
        });
      })
      .catch((error) => { wx.hideLoading(); wx.showToast({ title: error.message || '下单失败，请重试', icon: 'none' }); });
  },
  invoice() {
    const account = this.data.items[0]; if (!account) { wx.showToast({ title: '暂无可开票账户', icon: 'none' }); return; }
    wx.showModal({ title: '发票抬头', editable: true, placeholderText: account.enterprise_name, success: (titleResult) => { if (!titleResult.confirm) return; const title = String(titleResult.content || account.enterprise_name).trim(); if (!title) { wx.showToast({ title: '请填写发票抬头', icon: 'none' }); return; }
      wx.showModal({ title: '开票金额', editable: true, placeholderText: '请输入开票金额', success: (amountResult) => { if (!amountResult.confirm) return; const amount = Number(amountResult.content); if (!amount || amount <= 0) { wx.showToast({ title: '请输入有效金额', icon: 'none' }); return; }
        app.request('/invoices', { method: 'POST', data: { enterprise_id: account.id, account: 'premium', amount, title, tax_no: '', email: '' } }).then(() => { wx.showToast({ title: '发票申请已提交' }); this.load(); });
      } });
    } });
  },
  onShareAppMessage() { return app.share('/pages/billing/billing', 'from=share'); }
});
```

(No changes needed to `billing.wxml` — the existing `<button ... bindtap="recharge">立即充值</button>` for `item.account_type!=='premium'` already wires to the `recharge` handler above by name.)

- [ ] **Step 3: Static sanity check**

Run: `node --check miniprogram/pages/billing/billing.js`
Expected: no output (valid JS syntax) — the project has no other automated check for miniprogram JS.

- [ ] **Step 4: Manual verification**

Follow the plan from Step 1 in WeChat DevTools.

- [ ] **Step 5: Commit**

```bash
git add miniprogram/pages/billing/billing.js
git commit -m "feat(miniprogram): replace dead admin-only recharge call with real WeChat JSAPI payment"
```

---

## Final Verification (run once, after all 9 tasks)

```bash
python3 -m compileall -q backend && git diff --check
python3 tests/system_smoke.py
python3 tests/security_smoke.py
python3 tests/participation_lock_smoke.py
python3 tests/recharge_smoke.py
python3 tests/salesperson_portal_smoke.py
python3 tests/settings_smoke.py
python3 tests/id_number_test.py
python3 tests/wechat_pay_config_test.py
python3 tests/wechat_pay_provider_test.py
python3 tests/wechat_pay_smoke.py
cd web && npm run build && cd ..
node --check miniprogram/pages/billing/billing.js
python3 -m alembic heads   # must print a single head
```

Then, per `CLAUDE.md`'s merge gate: run `python3 scripts/pg_migration_check.py` against real PostgreSQL before merging (requires Neon credentials — a step for the user/executing engineer to run once, not automatable here), and record the task in `docs/ai-handoffs/wechat-merchant-usage-fee.md` per the project's handoff protocol before merging to `main`.

## Explicitly Out of Scope (per the design spec)

- WeChat payment for the `premium` account.
- Periodic (daily/monthly) summary report generation — reports are derived from the ledger/order list, not a separate generated artifact.
- A new miniprogram login system — this plan reuses the existing `portal: 'enterprise'` login.
- Refunds.
