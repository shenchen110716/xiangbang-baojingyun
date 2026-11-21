"""明文证件号出口审计（story-p1-readonly AC4；review-p1 m5 自 routers 抽出）。

任何把明文证件号交到外部系统手里的出口（列表/详情/保单成员/导出任务的
明文口径、明文导出文件的下载链接发放）都必须写 audit_logs：机器调用
user_id=NULL，detail 带 key_id/request_id（沿用设计 §7.3 的审计姿态）。
insured / policies 两个路由模块统一从这里取，不再横向 import 私有函数。
"""
from fastapi import Request
from sqlalchemy.orm import Session

from ...models import AuditLog
from .auth import OpenApiPrincipal

SENSITIVE_SCOPE = "insured:read_sensitive"


def audit_sensitive(session: Session, principal: OpenApiPrincipal,
                    request: Request, detail: str) -> None:
    session.add(AuditLog(
        user_id=None, action="openapi_read_sensitive",
        object_type="insured_person", object_id="",
        detail=f"key_id={principal.key_id} "
               f"request_id={getattr(request.state, 'openapi_request_id', '')} "
               f"{detail}"))
    session.commit()
