"""扫码参保：免登录公开入口。

个人缴纳/单位缴纳扫码后落到这里——不认识任何登录态，靠 URL 里的
position_id/mode/token 三元组通过 core.enroll_tokens 校验。提交只落一张
PositionEnrollSubmission，不直接生成 InsuredPerson：本轮范围明确收窄为
"先不判断 HR 资金、不做支付成功自动参保"，一律等人工审核（另见
routers/positions.py 的审核队列，扫码参保-后台任务）通过后才落地。
"""
import time
from collections import defaultdict

from fastapi import APIRouter, Depends, HTTPException, Request
from sqlalchemy.orm import Session

from ..core.business_time import business_today
from ..core.db import db
from ..core.enroll_tokens import verify_enroll_token
from ..core.id_number import age_on, birth_date_from_id, id_encrypt, is_valid_id_number
from ..models import InsurancePlan, PositionEnrollSubmission, WorkPosition
from ..schemas import EnrollSubmitIn

router = APIRouter(prefix="/api/enroll", tags=["enroll"])

MIN_ENROLL_AGE = 16

# 公开端点，两层限流：按二维码本身（同一张码别被高频刷）、按来源 IP（同一个人
# 别用同一张码狂刷）。跟 enterprises.py 的 apply_enterprise 同一个思路。
_TOKEN_WINDOW_SECONDS = 3600
_TOKEN_MAX_PER_WINDOW = 20
_IP_WINDOW_SECONDS = 3600
_IP_MAX_PER_WINDOW = 5
_token_attempts: dict[str, list[float]] = defaultdict(list)
_ip_attempts: dict[str, list[float]] = defaultdict(list)


def _client_ip(request: Request) -> str:
    forwarded = request.headers.get("x-forwarded-for", "")
    if forwarded:
        return forwarded.split(",")[0].strip()
    return request.client.host if request.client else "unknown"


def _check_rate_limit(key: str, attempts: dict, window: int, max_count: int) -> bool:
    now = time.time()
    bucket = attempts[key]
    bucket[:] = [t for t in bucket if now - t < window]
    if len(bucket) >= max_count:
        return False
    bucket.append(now)
    return True


def _resolve_position(position_id: int, mode: str, token: str, session: Session) -> WorkPosition:
    # 统一回"该岗位当前不可参保"，不区分"岗位不存在/未审核/该方式没开/token不对"——
    # 少给扫描者/探测者一点信息，跟 dev/code 端点故意回 404 而不是 401/403 同一个考虑。
    not_available = HTTPException(404, "该岗位当前不可参保")
    if mode not in ("personal", "employer"):
        raise not_available
    item = session.get(WorkPosition, position_id)
    if not item or item.status != "approved":
        raise not_available
    enabled = item.enable_personal_pay if mode == "personal" else item.enable_employer_pay
    if not enabled:
        raise not_available
    if not verify_enroll_token(item.id, mode, item.enroll_token_version, token):
        raise not_available
    return item


@router.get("/{position_id}/{mode}/{token}")
def enroll_info(position_id: int, mode: str, token: str, session: Session = Depends(db)):
    item = _resolve_position(position_id, mode, token, session)
    plan = session.get(InsurancePlan, item.plan_id) if item.plan_id else None
    return {
        "position_name": item.name,
        "actual_employer": item.actual_employer,
        "payment_mode": mode,
        "plan_name": plan.name if plan else "",
        "plan_price": plan.price if plan else None,
        "plan_billing_mode": plan.billing_mode if plan else None,
    }


@router.post("/{position_id}/{mode}/{token}")
def enroll_submit(position_id: int, mode: str, token: str, data: EnrollSubmitIn, request: Request, session: Session = Depends(db)):
    if data.website.strip():
        # 蜜罐字段被填了：判定为机器人，假装成功但什么都不落库。
        return {"message": "提交成功，请等待审核"}
    item = _resolve_position(position_id, mode, token, session)
    token_key = f"{position_id}:{mode}:{token}"
    if not _check_rate_limit(token_key, _token_attempts, _TOKEN_WINDOW_SECONDS, _TOKEN_MAX_PER_WINDOW):
        raise HTTPException(429, "该二维码近期提交次数过多，请稍后再试")
    if not _check_rate_limit(_client_ip(request), _ip_attempts, _IP_WINDOW_SECONDS, _IP_MAX_PER_WINDOW):
        raise HTTPException(429, "提交过于频繁，请稍后再试")
    name = data.name.strip()
    id_number = data.id_number.strip()
    if not name or not id_number:
        raise HTTPException(400, "请填写姓名和身份证号")
    if not is_valid_id_number(id_number):
        raise HTTPException(400, "身份证号格式或校验位不正确")
    birth = birth_date_from_id(id_number)
    if birth is None or age_on(birth, business_today()) < MIN_ENROLL_AGE:
        raise HTTPException(400, f"未满 {MIN_ENROLL_AGE} 周岁，不可参保")
    submission = PositionEnrollSubmission(
        position_id=item.id,
        payment_mode=mode,
        name=name,
        id_number_cipher=id_encrypt(id_number),
        phone=data.phone.strip(),
        payment_status="pending" if mode == "personal" else "not_required",
        review_status="pending",
    )
    session.add(submission)
    session.commit()
    return {"message": "提交成功", "submission_id": submission.id, "payment_mode": mode}
