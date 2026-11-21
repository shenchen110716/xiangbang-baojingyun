"""异步导出任务（story-p1-readonly AC7）：入队 + 后台执行生成 XLSX。

执行挂在 P0 的统一后台任务循环上（background.register_periodic_job，
30s tick，PG 下 advisory lock 保证单实例），不另起线程。文件经
core/storage.save_bytes 落私有存储（本地 uploads/ 或 S3），对外只经
短时签名 URL 下发（routers/openapi/insured.py 的 export-files 端点）。

脱敏口径在入队时固化进 params_json（sensitive 由那一刻 key 的 scope
决定），后台执行不回查 scope；但**换下载链接时**会按当前 scope 重新
校验（routers/openapi/insured.py，review-p1 M2）——降权 key 拿不到旧
明文文件。文件内证件号规则与 API 响应完全一致（AC4/AC7）。

可靠性与留存（review-p1 M2/M3）：
- 卡 processing 超时的任务每 tick 回收：先重置 pending 重跑一次（导出
  只读幂等），再超时则置 failed——进程重启不再留下永远 processing 的孤儿；
- done/failed 任务超过留存期后删除落盘文件并置 expired，明文 XLSX 不在
  磁盘上无限留存。
"""
import json
import logging
import secrets
from datetime import datetime, timedelta, timezone
from io import BytesIO
from typing import Optional

from sqlalchemy import select
from sqlalchemy.orm import Session

from ...core import storage
from ...models import ExportTask, InsuredPerson
from .background import register_periodic_job

logger = logging.getLogger("backend.openapi")

# 每个 tick 最多处理的任务数：导出是低频动作（限流 10 次/小时/key），
# 单 tick 批量封顶防止积压时一个 tick 跑太久挡住清理任务。
MAX_TASKS_PER_TICK = 5

# review-p1 M3：processing 超过此时长视为上一次执行已随进程死亡。
# 后台循环是单实例顺序执行（PG advisory lock / 单进程 asyncio），不存在
# 「还在跑就被回收」的并发窗口。
PROCESSING_TIMEOUT_SECONDS = 15 * 60
# 内部重试标记（只进 error 列、不对外——路由层仅在 failed/expired 透出 error）。
TIMEOUT_RETRY_MARKER = "__timeout_retry__"
TIMEOUT_FAILED_MESSAGE = "导出任务执行超时，请重新发起导出"

# review-p1 m2：对外只给通用失败文案，原始异常进服务端日志（按 task.id 关联）。
GENERIC_FAILED_MESSAGE = "导出执行失败，请重试或联系平台"

# review-p1 M2：明文 XLSX 不能在磁盘上无限留存；到期删文件、任务置 expired。
EXPORT_RETENTION_SECONDS = 48 * 3600
EXPIRED_MESSAGE = "导出文件已过期，请重新发起导出"

EXPORT_COLUMNS = ["人员ID", "姓名", "手机号", "证件号", "状态", "状态说明",
                  "岗位ID", "工种", "职业类别", "建档时间"]


def _aware(value: Optional[datetime]) -> Optional[datetime]:
    """SQLite 读回的 DateTime(timezone=True) 可能是 naive，按 UTC 补齐。"""
    if value is not None and value.tzinfo is None:
        return value.replace(tzinfo=timezone.utc)
    return value


def _safe_cell(value):
    """XLSX 公式注入防护（review-p1 m1）：openpyxl 对 = 开头的字符串按公式
    写入；姓名/手机号是录入数据，恶意值会在客户 Excel 里执行。以
    = + - @ 开头的字符串统一加 ' 前缀转义成文本。"""
    if isinstance(value, str) and value[:1] in ("=", "+", "-", "@"):
        return "'" + value
    return value


def enqueue_export(session: Session, principal, task_type: str,
                   params: dict) -> ExportTask:
    """创建导出任务（pending）；params 已含脱敏口径快照（serializers.export_params）。"""
    task = ExportTask(
        enterprise_id=principal.enterprise_id,
        key_id=principal.key_id,
        task_type=task_type,
        params_json=json.dumps(params, ensure_ascii=False)
        if not isinstance(params, str) else params,
        status="pending",
    )
    session.add(task)
    session.commit()
    return task


def _build_insured_xlsx(session: Session, task: ExportTask, params: dict) -> bytes:
    from .serializers import (
        insured_persons_query, parse_updated_since, serialize_insured_person,
    )

    import openpyxl

    sensitive = bool(params.get("sensitive"))
    query = insured_persons_query(
        session, task.enterprise_id,
        status=params.get("status"),
        position_id=params.get("position_id"),
        updated_since=parse_updated_since(params.get("updated_since")))

    # write_only：流式落盘，不把整本工作簿驻留内存（review-p1 m4）。
    book = openpyxl.Workbook(write_only=True)
    sheet = book.create_sheet(title="参保人员名单")
    sheet.append(EXPORT_COLUMNS)
    for person in query.order_by(InsuredPerson.id.asc()).yield_per(500):
        row = serialize_insured_person(person, sensitive=sensitive)
        sheet.append([_safe_cell(v) for v in (
            row["id"], row["name"], row["phone"], row["id_number"],
            row["status"], row["status_label"], row["position_id"],
            row["occupation"], row["occupation_class"], row["created_at"],
        )])
    buffer = BytesIO()
    book.save(buffer)
    return buffer.getvalue()


def _recover_stuck_tasks(session: Session) -> None:
    """review-p1 M3：捞超时的 processing。第一次重置 pending 重跑（导出
    只读幂等），已重试过仍超时则置 failed——避免崩溃循环下无限重跑。"""
    cutoff = datetime.now(timezone.utc) - timedelta(seconds=PROCESSING_TIMEOUT_SECONDS)
    stuck = session.scalars(
        select(ExportTask).where(ExportTask.status == "processing")).all()
    changed = False
    for task in stuck:
        started = _aware(task.created_at)
        if started is not None and started >= cutoff:
            continue
        if task.error == TIMEOUT_RETRY_MARKER:
            task.status = "failed"
            task.error = TIMEOUT_FAILED_MESSAGE
            task.finished_at = datetime.now(timezone.utc)
            logger.warning("openapi export task %s timed out twice, failed", task.id)
        else:
            task.status = "pending"
            task.error = TIMEOUT_RETRY_MARKER
            logger.warning("openapi export task %s stuck in processing, retrying", task.id)
        changed = True
    if changed:
        session.commit()


def run_export_tasks(session: Session) -> None:
    """后台任务：回收卡死任务，再挨个执行 pending 导出；单任务失败落
    failed 不拖垮循环。"""
    _recover_stuck_tasks(session)
    pending = session.scalars(
        select(ExportTask).where(ExportTask.status == "pending")
        .order_by(ExportTask.id.asc()).limit(MAX_TASKS_PER_TICK)).all()
    for task in pending:
        # 注意不清 error：重试任务的 TIMEOUT_RETRY_MARKER 要留到成功
        # （error=""）或失败（error=通用文案）才被覆盖，processing 期间
        # 再次崩溃时 _recover_stuck_tasks 据此判定「已重试过」。
        task.status = "processing"
        session.commit()
        try:
            params = json.loads(task.params_json or "{}")
            if task.task_type != "insured_persons":
                raise ValueError(f"unknown export task_type: {task.task_type}")
            content = _build_insured_xlsx(session, task, params)
            key = (f"openapi-exports/{task.enterprise_id}/"
                   f"{task.id}-{secrets.token_hex(8)}.xlsx")
            task.file_url = storage.save_bytes(key, content)
            task.status = "done"
            task.error = ""
        except Exception:
            session.rollback()
            task.status = "failed"
            # 对外只给通用文案：str(exc) 常含内部路径/表名（review-p1 m2），
            # 细节进服务端日志，按 task.id 关联。
            task.error = GENERIC_FAILED_MESSAGE
            logger.exception("openapi export task %s failed", task.id)
        task.finished_at = datetime.now(timezone.utc)
        session.commit()


def cleanup_export_tasks(session: Session) -> None:
    """review-p1 M2：done/failed 超过留存期的任务删落盘文件并置 expired；
    之后 export-tasks/{id} 对其返回 status=expired，签名下载端点 404。"""
    cutoff = datetime.now(timezone.utc) - timedelta(seconds=EXPORT_RETENTION_SECONDS)
    rows = session.scalars(
        select(ExportTask).where(ExportTask.status.in_(("done", "failed")))).all()
    for task in rows:
        finished = _aware(task.finished_at) or _aware(task.created_at)
        if finished is None or finished >= cutoff:
            continue
        if task.file_url:
            try:
                storage.delete(task.file_url)   # 幂等，忽略缺失
            except Exception:
                logger.exception("openapi export file cleanup failed task=%s", task.id)
                continue    # 文件还在就先不置 expired，下个 tick 重试
        task.file_url = ""
        task.status = "expired"
        task.error = EXPIRED_MESSAGE
        session.commit()


register_periodic_job("run_openapi_export_tasks", run_export_tasks)
register_periodic_job("cleanup_openapi_export_tasks", cleanup_export_tasks)
