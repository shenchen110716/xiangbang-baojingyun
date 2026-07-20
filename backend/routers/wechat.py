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
