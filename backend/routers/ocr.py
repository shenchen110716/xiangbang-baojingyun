from fastapi import APIRouter, Depends, File, HTTPException, UploadFile

from ..core.security import current_user
from ..models import User
from ..services.ocr import OcrError, recognize_business_license, recognize_id_card, recognize_receipt_amount

router = APIRouter(prefix="/api", tags=["ocr"])

_ALLOWED = {"image/jpeg", "image/jpg", "image/png", "image/heic", "image/heif", "image/webp"}


@router.post("/ocr/id-card")
async def ocr_id_card(file: UploadFile = File(...), user: User = Depends(current_user)):
    """上传身份证正面照，返回识别出的姓名/身份证号，供新增参保人自动填充。"""
    if file.content_type and file.content_type.split(";", 1)[0].lower() not in _ALLOWED:
        raise HTTPException(400, "仅支持 JPG/PNG 图片")
    content = await file.read()
    if len(content) > 10 * 1024 * 1024:
        raise HTTPException(400, "图片不能超过 10MB")
    try:
        return recognize_id_card(content)
    except OcrError as exc:
        raise HTTPException(400, str(exc))


@router.post("/ocr/business-license")
async def ocr_business_license(file: UploadFile = File(...), user: User = Depends(current_user)):
    """上传营业执照照片，识别单位全称/统一社会信用代码，供新增投保单位自动填充。"""
    if file.content_type and file.content_type.split(";", 1)[0].lower() not in _ALLOWED:
        raise HTTPException(400, "仅支持 JPG/PNG 图片")
    content = await file.read()
    if len(content) > 10 * 1024 * 1024:
        raise HTTPException(400, "图片不能超过 10MB")
    try:
        return recognize_business_license(content)
    except OcrError as exc:
        raise HTTPException(400, str(exc))


@router.post("/ocr/receipt-amount")
async def ocr_receipt_amount(file: UploadFile = File(...), user: User = Depends(current_user)):
    """上传转账回单/发票图片，识别金额，供充值时自动带出（到账仍需人工确认）。"""
    if file.content_type and file.content_type.split(";", 1)[0].lower() not in _ALLOWED:
        raise HTTPException(400, "仅支持 JPG/PNG 图片")
    content = await file.read()
    if len(content) > 10 * 1024 * 1024:
        raise HTTPException(400, "图片不能超过 10MB")
    try:
        return recognize_receipt_amount(content)
    except OcrError as exc:
        raise HTTPException(400, str(exc))
