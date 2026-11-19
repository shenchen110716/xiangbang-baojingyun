"""对外开放平台路由族 /openapi/v1/*（签名 v2 + 统一包）。

所有子路由必须：route_class=OpenApiRoute（统一包 + 请求日志）、依赖
services.openapi.auth.require_scope（签名/IP/限流/scope）、查询显式
WHERE enterprise_id = principal.enterprise_id（AC16 红线）。后续 story 的
insured/declaration/account 路由加到这里 include。
"""
from fastapi import APIRouter

from .common import E_BIZ, OpenApiError, OpenApiRoute
from .meta import router as meta_router

router = APIRouter()
router.include_router(meta_router)

# /openapi/v1 下未匹配路径的兜底：没有它，404 会走 FastAPI 默认的
# {"detail": "Not Found"}，不符合统一包契约（review-p0 minor-5）。
# 注意必须在业务子路由之后 include，FastAPI 按注册顺序匹配。
_fallback = APIRouter(prefix="/openapi/v1", route_class=OpenApiRoute)


@_fallback.api_route("/{_unmatched:path}",
                     methods=["GET", "POST", "PUT", "PATCH", "DELETE"],
                     include_in_schema=False)
async def _openapi_not_found(_unmatched: str):
    raise OpenApiError(404, E_BIZ, "接口不存在")


router.include_router(_fallback)
