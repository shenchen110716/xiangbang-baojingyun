"""/openapi/v1 统一响应、错误码分段与请求管线（story-p0-foundation AC8/AC9）。

统一包：{"code": 0|错误码, "message", "data", "request_id"}。错误码分段：
40100 签名/认证、40300 权限/scope/IP、42200 业务与参数、42900 限流、
50000 系统。HTTP 状态 = 分段前三位（code // 100），所以 err() 不传
http_status 也能落对。

实现选择 APIRoute 子类而不是全局 exception_handler / middleware：
- 只有 /openapi/v1 走统一包，/api 与 SPA 完全不受影响（不碰公共面）；
- 依赖解析阶段抛出的异常（签名、限流都在依赖里）同样会被
  get_route_handler 的包装捕获，middleware 做不到按路由族生效还不污染
  CORS/静态文件链路；
- 审计日志要求「每次调用（含失败）都有一行」，finally 落盘是唯一能同时
  覆盖成功、业务错和 500 的位置（AC8）。
"""
import logging
import time as _time
from typing import Callable, Optional
from uuid import uuid4

from fastapi import HTTPException, Request, Response
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from fastapi.routing import APIRoute
from starlette.concurrency import run_in_threadpool

logger = logging.getLogger("backend.openapi")

E_SIGN = 40100   # 签名/时间戳/nonce/密钥状态
E_PERM = 40300   # scope / IP 白名单 / 越权
E_BIZ = 42200    # 参数校验与业务规则
E_RATE = 42900   # 限流
E_SYS = 50000    # 未处理异常


class OpenApiError(Exception):
    """开放平台内部统一抛的业务异常；管线翻译成统一包。"""

    def __init__(self, http_status: int, code: int, message: str,
                 headers: Optional[dict] = None):
        super().__init__(message)
        self.http_status = http_status
        self.code = code
        self.message = message
        self.headers = headers


def ok(data, request_id: str) -> dict:
    return {"code": 0, "message": "ok", "data": data, "request_id": request_id}


def err(code: int, message: str, request_id: Optional[str] = None,
        http_status: Optional[int] = None, headers: Optional[dict] = None) -> JSONResponse:
    return JSONResponse(
        {"code": code, "message": message, "data": None,
         "request_id": request_id or uuid4().hex},
        status_code=http_status or code // 100,
        headers=headers,
    )


def paginate(query, cursor: Optional[str], limit: Optional[int],
             default: int = 50, cap: int = 200):
    """游标分页：按 id 倒序，cursor = 上一页最后一行的 id。

    返回 (rows, next_cursor)。next_cursor 为 None 表示没有下一页。
    """
    try:
        size = int(limit) if limit else default
    except (TypeError, ValueError):
        raise OpenApiError(422, E_BIZ, "无效的分页大小") from None
    size = max(1, min(size, cap))

    entity = query.column_descriptions[0]["entity"]
    page = query.order_by(entity.id.desc())
    if cursor:
        try:
            last_id = int(cursor)
        except (TypeError, ValueError):
            raise OpenApiError(422, E_BIZ, "无效的分页游标") from None
        page = page.filter(entity.id < last_id)
    rows = page.limit(size).all()
    next_cursor = str(rows[-1].id) if len(rows) == size else None
    return rows, next_cursor


def _http_code(status: int) -> int:
    return {401: E_SIGN, 403: E_PERM, 429: E_RATE,
            400: E_BIZ, 404: E_BIZ, 409: E_BIZ, 422: E_BIZ}.get(status, E_SYS)


class OpenApiRoute(APIRoute):
    """生成 request_id、把一切异常翻译成统一包、finally 落请求日志。"""

    def get_route_handler(self) -> Callable:
        original = super().get_route_handler()

        async def handler(request: Request) -> Response:
            request_id = uuid4().hex
            request.state.openapi_request_id = request_id
            started = _time.perf_counter()
            status = 500
            try:
                response = await original(request)
                status = response.status_code
                return response
            except OpenApiError as exc:
                status = exc.http_status
                return err(exc.code, exc.message, request_id,
                           exc.http_status, exc.headers)
            except RequestValidationError:
                status = 422
                return err(E_BIZ, "参数校验失败", request_id, 422)
            except HTTPException as exc:
                status = exc.status_code
                return err(_http_code(exc.status_code), str(exc.detail),
                           request_id, exc.status_code,
                           getattr(exc, "headers", None))
            except Exception:
                # 50000：响应里不回显任何内部细节/traceback（AC9），但
                # 服务端必须留全量堆栈，否则按 request_id 无从排障
                # （review-p0 M4）。
                logger.exception("openapi unhandled error request_id=%s %s %s",
                                 request_id, request.method, request.url.path)
                status = 500
                return err(E_SYS, "服务器内部错误", request_id, 500)
            finally:
                latency_ms = int((_time.perf_counter() - started) * 1000)
                from ...services.openapi.request_log import write_request_log
                # 同步 DB 写放线程池，不阻塞事件循环（review-p0 minor-4）。
                await run_in_threadpool(
                    write_request_log, request, request_id, status, latency_ms)

        return handler
