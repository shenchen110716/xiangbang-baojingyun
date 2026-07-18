"""services/ocr 冒烟：设置门禁、mock 样例、真实模式路由。自带隔离临时 SQLite。"""
import os
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

_tmp = tempfile.mkdtemp()
os.environ["DATABASE_URL"] = f"sqlite:///{_tmp}/ocr.db"
os.environ["ID_ENCRYPTION_KEY"] = "test-enc-key"

from backend.core.db import Base, engine
from backend.core.id_number import is_valid_id_number
from backend.models import SystemSetting  # noqa: F401
from backend.services import ocr, settings as S


def run():
    Base.metadata.create_all(bind=engine)

    # 未启用 → 报错并引导去系统设置
    try:
        ocr.recognize_id_card(b"\xff\xd8fake")
        assert False, "未启用应报错"
    except ocr.OcrError as e:
        assert "系统设置" in str(e)
    print("gate off -> OcrError OK")

    # 启用后 → mock 样例，mock=True，身份证号校验通过
    S.set_many({"OCR_ENABLED": "1"}, user_id=1)
    res = ocr.recognize_id_card(b"\xff\xd8fake-image-bytes")
    assert res["mock"] is True and res["name"] and res["id_number"]
    assert is_valid_id_number(res["id_number"]), "mock 身份证号应通过校验"
    print("enabled -> mock sample (valid id) OK")

    # 空图片
    try:
        ocr.recognize_id_card(b"")
        assert False
    except ocr.OcrError:
        pass
    print("empty image -> OcrError OK")

    # 真实模式路由：配 URL + INTEGRATION_MODE=real 时走 _call_real（打桩）
    S.set_many({"OCR_PROVIDER_URL": "https://ocr.example/id"}, user_id=1)
    os.environ["INTEGRATION_MODE"] = "real"
    called = {}
    def fake_real(url, image):
        called["url"] = url
        return {"name": "王五", "id_number": "340104199005213241", "gender": "男", "birth": "1990-05-21", "mock": False}
    ocr._call_real = fake_real
    res2 = ocr.recognize_id_card(b"img")
    assert res2["mock"] is False and res2["name"] == "王五" and called["url"] == "https://ocr.example/id"
    os.environ["INTEGRATION_MODE"] = "mock"
    print("real routing -> _call_real OK")

    print("ocr_smoke: ALL GREEN")


if __name__ == "__main__":
    run()
