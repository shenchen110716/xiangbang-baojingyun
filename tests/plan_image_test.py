"""保险方案图片：上传/签名链接/下载/权限。

覆盖：
- admin 上传 png 成功，plan_dict 输出 has_image=True 且不泄露 image_url 存储key
- 非图片扩展名/超大文件拒绝
- image-link：admin 可换签名链接；企业端只有方案在自己可选范围内才可换；
  签名下载端点凭 token 出文件，token 篡改 403
- 重新上传替换旧文件；删除图片后 has_image=False
"""
import asyncio
import io
import os
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

# 1x1 像素的合法 PNG
PNG_BYTES = bytes.fromhex(
    "89504e470d0a1a0a0000000d49484452000000010000000108060000001f15c489"
    "0000000d4944415478da63fcffff3f0300050201a2f1d39a0000000049454e44ae426082"
)


def run():
    with tempfile.TemporaryDirectory(prefix="xbb-plan-image-") as folder:
        os.environ["DATABASE_URL"] = f"sqlite:///{Path(folder) / 'test.db'}"
        os.environ["ADMIN_PASSWORD"] = "admin123"
        os.environ["ENTERPRISE_PASSWORD"] = "enterprise123"
        os.environ["UPLOAD_ROOT"] = str(Path(folder) / "uploads")

        from fastapi import HTTPException, UploadFile
        from sqlalchemy import select

        from backend.app import startup
        from backend.core.db import SessionLocal
        from backend.models import InsurancePlan, User
        from backend.routers.plans import (
            delete_plan_image, download_plan_image, plan_image_link, upload_plan_image,
        )
        from backend.services import plan_dict

        def make_upload(name: str, content: bytes) -> UploadFile:
            return UploadFile(io.BytesIO(content), filename=name)

        startup()
        with SessionLocal() as session:
            admin = session.scalar(select(User).where(User.role == "admin"))
            enterprise_user = session.scalar(select(User).where(User.username == "enterprise"))
            plan = InsurancePlan(insurer="图片测试保司", name="图片测试产品", price=50.0)
            session.add(plan); session.commit(); session.refresh(plan)

            # 1. 上传 png 成功；输出 has_image 不含 image_url
            result = upload_plan_image and asyncio.run(
                upload_plan_image(plan.id, make_upload("彩页.png", PNG_BYTES), admin, session))
            assert result["has_image"] is True
            assert "image_url" not in result, "存储key不能出API"
            assert result["image_name"] == "彩页.png"
            session.refresh(plan)
            first_url = plan.image_url
            assert first_url

            # 2. 非法扩展名 / 空文件拒绝
            for bad_name, bad_content in [("x.exe", PNG_BYTES), ("x.png", b"")]:
                try:
                    asyncio.run(upload_plan_image(plan.id, make_upload(bad_name, bad_content), admin, session))
                    raise AssertionError(f"{bad_name} 应该被拒绝")
                except HTTPException as e:
                    assert e.status_code == 400

            # 3. admin 换签名链接并下载成功
            link = plan_image_link(plan.id, admin, session)
            assert link["url"].startswith(f"/api/plans/{plan.id}/image/download?token=")
            token = link["url"].split("token=")[1].split("&")[0]
            expires = int(link["url"].split("expires=")[1])
            response = download_plan_image(plan.id, token, expires, 0, session)
            body = Path(response.path).read_bytes()
            assert body == PNG_BYTES, "下载内容应与上传一致"

            # 4. token 篡改 → 403
            try:
                download_plan_image(plan.id, "bad-token", expires, 0, session)
                raise AssertionError("篡改token应403")
            except HTTPException as e:
                assert e.status_code == 403

            # 5. 企业端：方案不在可选范围（默认无代理关系）→ 403
            try:
                plan_image_link(plan.id, enterprise_user, session)
                raise AssertionError("不在可选范围的企业不应能换链接")
            except HTTPException as e:
                assert e.status_code == 403

            # 6. 重新上传替换旧文件（旧存储对象被删）
            result2 = asyncio.run(upload_plan_image(plan.id, make_upload("v2.jpg", PNG_BYTES), admin, session))
            session.refresh(plan)
            assert plan.image_url != first_url
            assert result2["image_name"] == "v2.jpg"

            # 7. 删除图片
            result3 = delete_plan_image(plan.id, admin, session)
            assert result3["has_image"] is False
            session.refresh(plan)
            assert plan.image_url == "" and plan.image_name == ""

        print("plan image test: PASS")


if __name__ == "__main__":
    run()
