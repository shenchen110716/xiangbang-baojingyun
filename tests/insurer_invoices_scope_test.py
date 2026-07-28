"""发票管理范围隔离: insurer only sees invoices for enterprises with a policy under its own plans."""
import os
import secrets
import sys
import tempfile
from pathlib import Path

os.environ["DATABASE_URL"] = f"sqlite:///{tempfile.mktemp(suffix='.db')}"

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from fastapi.testclient import TestClient  # noqa: E402

from backend.app import app, startup  # noqa: E402
from backend.core.db import SessionLocal  # noqa: E402
from backend.core.security import pwd  # noqa: E402
from backend.models import Enterprise, Insurer, InsurancePlan, Invoice, Policy, User  # noqa: E402

startup()
client = TestClient(app)


def _setup():
    """幂等地复用保司A/保司B/方案A/方案B（同一个 insurer_a.id 才能跟只创建
    一次的 invoice_insurer_a 用户对得上），每次调用只新开一组投保单位A/B
    +保单+发票，给各个测试用例互不干扰的发票 id。"""
    with SessionLocal() as s:
        insurer_a = s.query(Insurer).filter(Insurer.name == "发票保司A").first()
        if not insurer_a:
            insurer_a = Insurer(name="发票保司A"); s.add(insurer_a); s.flush()
        insurer_b = s.query(Insurer).filter(Insurer.name == "发票保司B").first()
        if not insurer_b:
            insurer_b = Insurer(name="发票保司B"); s.add(insurer_b); s.flush()
        plan_a = s.query(InsurancePlan).filter(InsurancePlan.insurer_id == insurer_a.id).first()
        if not plan_a:
            plan_a = InsurancePlan(insurer="发票保司A", name="方案A", insurer_id=insurer_a.id); s.add(plan_a); s.flush()
        plan_b = s.query(InsurancePlan).filter(InsurancePlan.insurer_id == insurer_b.id).first()
        if not plan_b:
            plan_b = InsurancePlan(insurer="发票保司B", name="方案B", insurer_id=insurer_b.id); s.add(plan_b); s.flush()

        suffix = secrets.token_hex(4)
        enterprise_a = Enterprise(name=f"发票测试企业A-{suffix}")
        enterprise_b = Enterprise(name=f"发票测试企业B-{suffix}")
        s.add(enterprise_a); s.add(enterprise_b); s.flush()
        s.add(Policy(policy_no=f"POL-INV-A-{suffix}", enterprise_id=enterprise_a.id, plan_id=plan_a.id, premium=100))
        s.add(Policy(policy_no=f"POL-INV-B-{suffix}", enterprise_id=enterprise_b.id, plan_id=plan_b.id, premium=100))
        invoice_a = Invoice(enterprise_id=enterprise_a.id, account="premium", amount=100)
        invoice_b = Invoice(enterprise_id=enterprise_b.id, account="premium", amount=100)
        s.add(invoice_a); s.add(invoice_b)
        s.flush()
        invoice_a_id, invoice_b_id = invoice_a.id, invoice_b.id
        if not s.query(User).filter(User.username == "invoice_insurer_a").first():
            s.add(User(username="invoice_insurer_a", password_hash=pwd.hash("test1234"), name="保司A", role="insurer", insurer_id=insurer_a.id))
        s.commit()
    return invoice_a_id, invoice_b_id


def test_insurer_sees_no_invoices_for_other_insurers_enterprise():
    """跨保司隔离：每次 _setup() 都会顺带建一份保司A自己名下的企业/发票
    （给下面上传/下载两个测试复用同一份 setup），所以这里不能再断言整份
    列表为空，只能断言"看不到保司B名下企业的发票"这个真正要测的隔离点。"""
    _setup()
    login = client.post("/api/auth/login", json={"username": "invoice_insurer_a", "password": "test1234", "portal": "insurer"})
    headers = {"Authorization": f"Bearer {login.json()['access_token']}"}
    resp = client.get("/api/invoices", headers=headers)
    assert resp.status_code == 200
    enterprise_names = {item["enterprise_name"] for item in resp.json()}
    assert not any(name.startswith("发票测试企业B") for name in enterprise_names), \
        "不应该看到别的保司名下企业的发票"


def test_insurer_can_upload_document_for_own_enterprise_but_not_others():
    invoice_a_id, invoice_b_id = _setup()
    login = client.post("/api/auth/login", json={"username": "invoice_insurer_a", "password": "test1234", "portal": "insurer"})
    headers = {"Authorization": f"Bearer {login.json()['access_token']}"}

    # 名下投保单位（企业A）的发票：可以上传。
    resp = client.post(f"/api/invoices/{invoice_a_id}/document/upload", headers=headers,
                        files={"file": ("invoice.pdf", b"%PDF-1.4 fake", "application/pdf")})
    assert resp.status_code == 200, resp.text
    payload = resp.json()
    assert payload["document_download_url"], "上传成功后应该返回下载链接"

    # 别家保司名下企业B的发票：403，不能碰。
    resp2 = client.post(f"/api/invoices/{invoice_b_id}/document/upload", headers=headers,
                         files={"file": ("invoice.pdf", b"%PDF-1.4 fake", "application/pdf")})
    assert resp2.status_code == 403, resp2.text


def test_uploaded_invoice_document_is_downloadable_via_signed_url():
    invoice_a_id, _ = _setup()
    login = client.post("/api/auth/login", json={"username": "invoice_insurer_a", "password": "test1234", "portal": "insurer"})
    headers = {"Authorization": f"Bearer {login.json()['access_token']}"}
    upload = client.post(f"/api/invoices/{invoice_a_id}/document/upload", headers=headers,
                          files={"file": ("发票.pdf", b"%PDF-1.4 fake content", "application/pdf")})
    assert upload.status_code == 200, upload.text
    download_url = upload.json()["document_download_url"]
    resp = client.get(download_url)
    assert resp.status_code == 200, resp.text
    assert resp.content == b"%PDF-1.4 fake content"

    # 拒绝格式不支持的文件。
    bad = client.post(f"/api/invoices/{invoice_a_id}/document/upload", headers=headers,
                       files={"file": ("invoice.txt", b"not a pdf", "text/plain")})
    assert bad.status_code == 400, bad.text


def run():
    test_insurer_sees_no_invoices_for_other_insurers_enterprise()
    print("test_insurer_sees_no_invoices_for_other_insurers_enterprise: OK")
    test_insurer_can_upload_document_for_own_enterprise_but_not_others()
    print("test_insurer_can_upload_document_for_own_enterprise_but_not_others: OK")
    test_uploaded_invoice_document_is_downloadable_via_signed_url()
    print("test_uploaded_invoice_document_is_downloadable_via_signed_url: OK")
    print("\nAll insurer invoices scope tests: PASS")


if __name__ == "__main__":
    run()
