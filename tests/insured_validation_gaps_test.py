"""Regression: 批量添加/表格导入的身份证号校验位+年龄校验缺口（用户反馈
2026-07-30 第 4 条："员工姓名和身份证号码需要校验准确性及真实性"）.

单个添加/编辑（add_person/update_person）一直都校验身份证号校验位
（is_valid_id_number）和最低参保年龄（_assert_min_age）。审计发现
bulk_add_people 两项都完全没查，import_insured_file 只查了校验位、没查
年龄——同一份数据换个入口就能绕过校验。这里补齐后用这份测试锁住。
"""
import io
import os
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

INVALID_CHECKSUM_ID = "330106199003077001"  # 格式对、日期对，校验位错一位
UNDERAGE_ID = "330106201503077001"          # 校验位对，但按当前日期不满 16 周岁


def run():
    with tempfile.TemporaryDirectory(prefix="xbb-insured-validation-") as folder:
        os.environ["DATABASE_URL"] = f"sqlite:///{Path(folder) / 'test.db'}"
        os.environ["ADMIN_PASSWORD"] = "admin123"
        os.environ["ENTERPRISE_PASSWORD"] = "enterprise123"

        from sqlalchemy import select

        from backend.app import startup
        from backend.core.db import SessionLocal
        from backend.models import Enterprise, User, WorkPosition
        from backend.routers.insured import bulk_add_people, import_insured_file
        from backend.schemas import BulkPersonIn, BulkPersonRow

        startup()
        with SessionLocal() as session:
            admin = session.scalar(select(User).where(User.role == "admin"))
            enterprise = Enterprise(name="校验缺口测试企业", kind="企业", contact="c", phone="p",
                                   status="active", usage_balance=999999.0)
            session.add(enterprise); session.commit(); session.refresh(enterprise)
            position = WorkPosition(enterprise_id=enterprise.id, name="校验缺口测试岗位",
                                   occupation_class="1-4类", status="approved")
            session.add(position); session.commit(); session.refresh(position)

            # 1. bulk_add_people 现在应该拒绝校验位错误的身份证号。
            bad_checksum = bulk_add_people(
                BulkPersonIn(enterprise_id=enterprise.id, position_id=position.id, rows=[
                    BulkPersonRow(name="张三", id_number=INVALID_CHECKSUM_ID),
                ]), admin, session)
            assert bad_checksum["ok"] is False, "批量添加应该拒绝校验位错误的身份证号"
            assert any("校验位" in e["message"] or "格式" in e["message"] for e in bad_checksum["errors"]), bad_checksum

            # 2. bulk_add_people 现在应该拒绝未满 16 周岁的身份证号。
            underage = bulk_add_people(
                BulkPersonIn(enterprise_id=enterprise.id, position_id=position.id, rows=[
                    BulkPersonRow(name="李四", id_number=UNDERAGE_ID),
                ]), admin, session)
            assert underage["ok"] is False, "批量添加应该拒绝未满 16 周岁的身份证号"
            assert any("16" in e["message"] for e in underage["errors"]), underage

            # 3. 合法数据仍然能正常批量添加，确认没有误伤。
            ok = bulk_add_people(
                BulkPersonIn(enterprise_id=enterprise.id, position_id=position.id, rows=[
                    BulkPersonRow(name="王五", id_number="330106199003077005"),
                ]), admin, session)
            assert ok["ok"] is True, ok

        print("  bulk_add_people checksum/age gaps closed ok")

        with SessionLocal() as session:
            admin = session.scalar(select(User).where(User.role == "admin"))
            enterprise = session.scalar(select(Enterprise).where(Enterprise.name == "校验缺口测试企业"))
            position = session.scalar(select(WorkPosition).where(WorkPosition.enterprise_id == enterprise.id))

            def _csv(name: str, id_number: str) -> bytes:
                return f"姓名,身份证号,手机号\n{name},{id_number},13800000001\n".encode("utf-8-sig")

            class _Upload:
                def __init__(self, content: bytes, filename: str):
                    self._content = content
                    self.filename = filename
                    self.size = len(content)
                async def read(self):
                    return self._content

            import asyncio

            # 4. import_insured_file 已经查了校验位，这里补的是年龄——之前完全没查。
            result = asyncio.run(import_insured_file(
                kind="enrollment", enterprise_id=enterprise.id, position_id=position.id,
                file=_Upload(_csv("赵六", UNDERAGE_ID), "underage.csv"),
                user=admin, session=session,
            ))
            assert result["ok"] is False, "表格导入应该拒绝未满 16 周岁的身份证号"
            assert any("16" in e["message"] for e in result["errors"]), result

        print("  import_insured_file age gap closed ok")
    print("insured validation gaps test: PASS")


if __name__ == "__main__":
    run()
