"""services/ocr 冒烟：设置门禁、mock 样例、百度智能云真实模式路由（token 缓存、
身份证字段解析、票据金额启发式提取）。自带隔离临时 SQLite。"""
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

    # 启用后、未配百度密钥 → mock 样例，mock=True，身份证号校验通过
    S.set_many({"OCR_ENABLED": "1"}, user_id=1)
    res = ocr.recognize_id_card(b"\xff\xd8fake-image-bytes")
    assert res["mock"] is True and res["name"] and res["id_number"]
    assert is_valid_id_number(res["id_number"]), "mock 身份证号应通过校验"
    print("enabled, no baidu key -> mock sample (valid id) OK")

    # 空图片
    try:
        ocr.recognize_id_card(b"")
        assert False
    except ocr.OcrError:
        pass
    print("empty image -> OcrError OK")

    # ---- 百度 access_token 换取与缓存 ----
    # 先测缺密钥报错（用真实的 _fetch_access_token，尚未打桩）
    ocr._token_cache.clear()
    try:
        ocr._get_access_token()
        assert False
    except ocr.OcrError as e:
        assert "系统设置" in str(e)
    print("missing baidu keys -> OcrError OK")

    S.set_many({"BAIDU_OCR_API_KEY": "test-api-key", "BAIDU_OCR_SECRET_KEY": "test-secret-key"}, user_id=1)
    token_calls = {"n": 0}

    def fake_fetch_token():
        token_calls["n"] += 1
        return {"access_token": f"tok-{token_calls['n']}", "expires_in": 2592000}

    ocr._fetch_access_token = fake_fetch_token
    ocr._token_cache.clear()
    t1 = ocr._get_access_token()
    t2 = ocr._get_access_token()
    assert t1 == t2 == "tok-1" and token_calls["n"] == 1, "有效期内应复用缓存的 token，不重复换取"
    print("access_token cached across calls OK")

    # ---- 真实模式：身份证识别字段解析（打桩百度接口返回） ----
    os.environ["INTEGRATION_MODE"] = "real"

    def fake_idcard_post(url, form):
        return {"words_result": {
            "姓名": {"words": "王五"},
            "公民身份号码": {"words": "340104199005213241"},
            "性别": {"words": "男"},
            "出生": {"words": "1990年5月21日"},
        }}
    ocr._baidu_post = fake_idcard_post
    res2 = ocr.recognize_id_card(b"img")
    assert res2["mock"] is False
    assert res2["name"] == "王五" and res2["id_number"] == "340104199005213241"
    assert res2["gender"] == "男" and res2["birth"] == "1990-5-21", res2["birth"]
    print("real idcard field parsing OK")

    # 百度返回 error_code 时应转为 OcrError，而不是把原始错误结构透传给前端
    def fake_idcard_error(url, form):
        return {"error_code": 216201, "error_msg": "image format error"}
    ocr._baidu_post = fake_idcard_error
    try:
        ocr.recognize_id_card(b"img")
        assert False
    except ocr.OcrError as e:
        assert "image format error" in str(e)
    print("real idcard error_code -> OcrError OK")

    # ---- 真实模式：票据金额启发式提取（打桩百度通用票据识别返回） ----
    def fake_receipt_post(url, form):
        return {"words_result": [
            {"words": "响帮帮保经云"},
            {"words": "转账凭证"},
            {"words": "金额：￥8,888.88元"},
            {"words": "交易时间：2026-07-22"},
        ]}
    ocr._baidu_post = fake_receipt_post
    amt = ocr.recognize_receipt_amount(b"img")
    assert amt["mock"] is False and amt["amount"] == 8888.88, amt
    print("real receipt amount heuristic (金额 keyword line) OK")

    # 没有千位逗号分隔的 4 位以上金额（真实回单最常见的写法，如"5000.00"）
    # 之前的正则只支持逗号分组格式，会把 "5000.00" 错误截断成 "500"。
    def fake_receipt_post_no_comma(url, form):
        return {"words_result": [
            {"words": "转账凭证"},
            {"words": "金额：5000.00元"},
        ]}
    ocr._baidu_post = fake_receipt_post_no_comma
    amt_no_comma = ocr.recognize_receipt_amount(b"img")
    assert amt_no_comma["amount"] == 5000.00, amt_no_comma
    print("real receipt amount heuristic (no comma separator, 4+ digits) OK")

    # 没有"金额/合计"关键字行时，退而求其次抓第一个金额格式的数字
    def fake_receipt_post_fallback(url, form):
        return {"words_result": [
            {"words": "无关文字"},
            {"words": "¥666.00"},
        ]}
    ocr._baidu_post = fake_receipt_post_fallback
    amt2 = ocr.recognize_receipt_amount(b"img")
    assert amt2["amount"] == 666.00, amt2
    print("real receipt amount heuristic (fallback) OK")

    # 识别不出金额时报错，不是让调用方拿到 0 或崩溃
    def fake_receipt_post_none(url, form):
        return {"words_result": [{"words": "无金额信息"}]}
    ocr._baidu_post = fake_receipt_post_none
    try:
        ocr.recognize_receipt_amount(b"img")
        assert False
    except ocr.OcrError:
        pass
    print("real receipt amount not found -> OcrError OK")

    os.environ["INTEGRATION_MODE"] = "mock"
    mock_amt = ocr.recognize_receipt_amount(b"img")
    assert mock_amt["mock"] is True and mock_amt["amount"] > 0
    print("mock mode receipt amount OK")

    # ---- 营业执照识别（新增投保单位自动带出单位全称/统一社会信用代码） ----
    mock_license = ocr.recognize_business_license(b"img")
    assert mock_license["mock"] is True and mock_license["name"]
    print("mock mode business license OK")

    os.environ["INTEGRATION_MODE"] = "real"

    def fake_license_post(url, form):
        return {"words_result": {
            "单位名称": {"words": "响帮帮无忧保测试企业有限公司"},
            "社会信用代码": {"words": "91320100MA1XXXXX1X"},
        }}
    ocr._baidu_post = fake_license_post
    real_license = ocr.recognize_business_license(b"img")
    assert real_license["mock"] is False
    assert real_license["name"] == "响帮帮无忧保测试企业有限公司"
    assert real_license["credit_code"] == "91320100MA1XXXXX1X"
    print("real business license field parsing OK")

    def fake_license_error(url, form):
        return {"error_code": 216201, "error_msg": "image format error"}
    ocr._baidu_post = fake_license_error
    try:
        ocr.recognize_business_license(b"img")
        assert False
    except ocr.OcrError as e:
        assert "image format error" in str(e)
    print("real business license error_code -> OcrError OK")

    def fake_license_no_name(url, form):
        return {"words_result": {"社会信用代码": {"words": "91320100MA1XXXXX1X"}}}
    ocr._baidu_post = fake_license_no_name
    try:
        ocr.recognize_business_license(b"img")
        assert False
    except ocr.OcrError:
        pass
    print("real business license missing name -> OcrError OK")

    os.environ["INTEGRATION_MODE"] = "mock"

    print("ocr_smoke: ALL GREEN")


if __name__ == "__main__":
    run()
