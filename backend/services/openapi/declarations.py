"""批量参停保申报（story-p2-write AC3–AC9）。

异步受理模型：POST 只做校验与落库（受理即拒的项当场 rejected+reason），
同步返回 declaration_id；人员真正生效/停保仍走既有人工确认闸口
（PATCH /api/insured/{id}/status），确认那一刻由 on_insured_status_changed
钩子把申报项推到终态并投递 webhook 事件。**绝不在开放面同步生效**——
生效意味着开始计费，这是人工拍板的硬边界。

复用既有业务语义（红线：不复制业务规则）：
- 余额门禁走 require_usage_funded（participation_lock），绝不自算余额；
  对外按 AC4(c) 的口径整单 422（/api 面是 403，开放面错误契约不同，捕获
  后换壳不换判定）。
- 参保落库 = draft 建档 + 置 pending + record_operation(enrollment)，与
  /api/insured/batch-enroll 完全同一套语义；draft/stopped 既有人员复用
  行而不建重复档（import_insured_file 的复保先例）。
- 校验项（证件号格式/最小年龄/姓名-证件号一致性）用 core/id_number 原语
  按 routers/insured.py 的同一公式薄适配——不 import router（会循环）。

机器调用没有 users 行：record_operation 的 submitted_by 与
_notify_lock_once_per_day 的 AuditLog.user_id 都已允许 NULL，传 id=None
的 shim 即可（§7.3 机器审计先例）。
"""
from datetime import datetime, timezone
from types import SimpleNamespace
from typing import Optional

from fastapi import HTTPException
from sqlalchemy import select
from sqlalchemy.orm import Session

from ...core.business_time import business_today
from ...core.id_number import age_on, birth_date_from_id, id_hash, is_valid_id_number
from ...models import (
    Enterprise, InsuredPerson, OpenApiDeclaration, OpenApiDeclarationItem,
    WorkPosition,
)
from ..participation_lock import require_usage_funded
from ..timeliness_recalc import record_operation
from .webhook_outbox import emit_event

# 注意：不在顶层 import .serializers——它顶层引用 routers.openapi.common，
# 而本模块被 routers/insured.py（确认钩子）顶层引用，会形成
# routers.insured → 本模块 → serializers → routers.openapi 包初始化 →
# routers/openapi/declarations.py → 本模块（半成品）的导入期循环。

MAX_DECLARATION_ITEMS = 500
MIN_INSURE_AGE = 16   # 与 routers/insured.py 同一条业务规则（未满 16 不可参保）

_IN_FORCE_STATUSES = ("pending", "active")


def _machine_user() -> SimpleNamespace:
    """开放平台机器调用的 user shim：AuditLog.user_id / submitted_by 可空。"""
    return SimpleNamespace(id=None)


def _gate_balance(session: Session, enterprise_id: int) -> None:
    """AC4(c)：使用费余额门禁，整单 422。判定 100% 走 require_usage_funded
    （code guard 硬边界），只把 /api 面的 403 换成开放面的 422 错误壳。"""
    enterprise = session.get(Enterprise, enterprise_id)
    if enterprise is None:
        raise HTTPException(404, "资源不存在")
    try:
        require_usage_funded(session, enterprise, _machine_user())
    except HTTPException as exc:
        # review-p2 minor：只换壳余额门禁本身的 403；其他 HTTPException
        # （如未来内部逻辑的 500/404）原样上抛，不伪装成业务 422。
        if exc.status_code != 403:
            raise
        raise HTTPException(422, exc.detail) from None


def _validate_batch_size(items: list) -> None:
    if not items:
        raise HTTPException(422, "items 不能为空")
    if len(items) > MAX_DECLARATION_ITEMS:
        raise HTTPException(422, f"单次申报最多 {MAX_DECLARATION_ITEMS} 人，请分批提交")


def _reject_reason_for_enrollment(session: Session, enterprise_id: int,
                                  name: str, id_number: str,
                                  id_type: str) -> Optional[str]:
    """受理即拒的 per-item 校验（AC4 a/b）；返回 None = 通过。"""
    if id_type != "id_card":
        return "暂不支持的证件类型（目前仅支持身份证 id_card）"
    if not is_valid_id_number(id_number):
        return "身份证号格式不正确"
    birth = birth_date_from_id(id_number)
    if birth is None or age_on(birth, business_today()) < MIN_INSURE_AGE:
        return f"被保险人未满 {MIN_INSURE_AGE} 周岁，不可参保"
    # 姓名-证件号一致性（数据质量拦截）。review-p2 M1：只查本企业名下的
    # 登记记录，且拒绝文案固定措辞、绝不回显系统里的姓名——跨租户查询 +
    # 回显真名会把这个接口变成全库「证件号→姓名」试探预言机。
    mismatch = session.scalar(
        select(InsuredPerson.id).where(
            InsuredPerson.enterprise_id == enterprise_id,
            InsuredPerson.id_number_hash == id_hash(id_number),
            InsuredPerson.name != name).limit(1))
    if mismatch is not None:
        return "该证件号与本企业已登记的姓名不一致，请核对后重新提交"
    # 重复在保（AC4b）：同企业同证件号已有待生效/在保人员。
    duplicate = session.scalar(
        select(InsuredPerson.id).where(
            InsuredPerson.enterprise_id == enterprise_id,
            InsuredPerson.id_number_hash == id_hash(id_number),
            InsuredPerson.status.in_(_IN_FORCE_STATUSES)).limit(1))
    if duplicate is not None:
        return "该人员在本企业已在保或待生效，请勿重复申报"
    return None


def _position_or_422(session: Session, enterprise_id: int,
                     position_id: Optional[int]) -> Optional[WorkPosition]:
    """AC4(e)：岗位必须是本企业已审核岗位；不存在与不属本企业同一句话，
    不泄漏他企业岗位的存在性。"""
    if not position_id:
        return None
    position = session.get(WorkPosition, position_id)
    if (position is None or position.enterprise_id != enterprise_id
            or position.status != "approved"):
        raise HTTPException(422, "岗位不存在或不属于当前企业（仅限本企业已审核通过的岗位）")
    return position


def accept_enrollment_declaration(session: Session, principal,
                                  items: list[dict]) -> OpenApiDeclaration:
    """参保申报受理（AC3–AC5）。只 flush 不 commit（review-p2 M3）：唯一的
    commit 由路由侧 ctx.save() 做——幂等快照与业务副作用同一事务落库，
    中间崩溃两者一起消失，key 可重试，绝不出现「有副作用没快照」。"""
    _validate_batch_size(items)
    _gate_balance(session, principal.enterprise_id)
    # 岗位整单预检（AC4e）：任何一项岗位非法都整单 422——岗位是客户端配置
    # 错误，不是名单数据质量问题，混在 per-item 拒绝里会被当成「这个人不行」。
    positions = {
        item.get("position_id"): _position_or_422(
            session, principal.enterprise_id, item.get("position_id"))
        for item in items if item.get("position_id")
    }

    declaration = OpenApiDeclaration(
        enterprise_id=principal.enterprise_id, key_id=principal.key_id,
        kind="enrollment", status="accepted", item_count=len(items))
    session.add(declaration)
    session.flush()

    now = datetime.now(timezone.utc)
    seen: set[tuple] = set()
    accepted = 0
    for item in items:
        name = (item.get("name") or "").strip()
        id_number = (item.get("id_number") or "").strip()
        id_type = (item.get("id_type") or "id_card").strip()
        row = OpenApiDeclarationItem(
            declaration_id=declaration.id,
            enterprise_id=principal.enterprise_id,
            external_person_id=(item.get("external_person_id") or "").strip(),
            name=name, id_number=id_number)
        session.add(row)
        # 批内去重（AC4d）：同（证件号, 证件类型, 期望生效日期）只受理第一条。
        dedupe_key = (id_number, id_type, item.get("expected_effective_at") or "")
        if dedupe_key in seen:
            reason = "批内重复申报，已自动去重（仅受理第一条）"
        else:
            seen.add(dedupe_key)
            reason = _reject_reason_for_enrollment(
                session, principal.enterprise_id, name, id_number, id_type)
        if reason:
            row.status = "rejected"
            row.reason = reason
            row.finished_at = now
            continue
        position = positions.get(item.get("position_id"))
        person = _enroll_person(session, principal.enterprise_id, name,
                                id_number, item.get("phone") or "", position)
        row.person_id = person.id
        accepted += 1

    # 全部项受理即拒 → 申报单直接终态 failed；否则 processing 等人工确认。
    if accepted:
        declaration.status = "processing"
    else:
        declaration.status = "failed"
        declaration.finished_at = now
    session.flush()   # review-p2 M3：不 commit，等路由 ctx.save 单次提交
    return declaration


def _enroll_person(session: Session, enterprise_id: int, name: str,
                   id_number: str, phone: str,
                   position: Optional[WorkPosition]) -> InsuredPerson:
    """draft→pending 的落库语义，与 /api/insured/batch-enroll 同一套：
    置 pending + record_operation(enrollment)。既有 draft/stopped 人员复用
    行（stopped→pending 即复保申报，import_insured_file 先例）。"""
    person = session.scalar(
        select(InsuredPerson).where(
            InsuredPerson.enterprise_id == enterprise_id,
            InsuredPerson.id_number_hash == id_hash(id_number),
            InsuredPerson.status.in_(("draft", "stopped")))
        .order_by(InsuredPerson.id.desc()).limit(1))
    if person is None:
        person = InsuredPerson(
            enterprise_id=enterprise_id, name=name, phone=phone,
            id_number=id_number, occupation="", occupation_class="3类",
            status="draft")
        session.add(person)
    if phone:
        person.phone = phone
    if position is not None:
        person.position_id = position.id
        person.occupation = position.name
        person.occupation_class = position.occupation_class
    person.status = "pending"
    session.flush()
    record_operation(session, user=_machine_user(), person=person,
                     operation_type="enrollment")
    return person


def accept_termination_declaration(session: Session, principal,
                                   items: list[dict]) -> OpenApiDeclaration:
    """停保申报受理（AC7，同模型）：只记录意图，真正停保走人工确认
    （insured_status → stopped 时 terminate_person_policy，复用既有逻辑）。
    只 flush 不 commit（review-p2 M3，commit 由路由 ctx.save 统一做）。"""
    _validate_batch_size(items)
    # 停保同属参停保操作，同一把余额门禁（/api 的 insured_status 同语义）。
    _gate_balance(session, principal.enterprise_id)

    declaration = OpenApiDeclaration(
        enterprise_id=principal.enterprise_id, key_id=principal.key_id,
        kind="termination", status="accepted", item_count=len(items))
    session.add(declaration)
    session.flush()

    now = datetime.now(timezone.utc)
    seen_person_ids: set[int] = set()
    accepted = 0
    for item in items:
        id_number = (item.get("id_number") or "").strip()
        row = OpenApiDeclarationItem(
            declaration_id=declaration.id,
            enterprise_id=principal.enterprise_id,
            external_person_id=(item.get("external_person_id") or "").strip(),
            id_number=id_number)
        session.add(row)
        person = _find_termination_target(session, principal.enterprise_id,
                                          item.get("person_id"), id_number)
        reason = None
        if person is None:
            # 跨租户与不存在同一句话（404 模糊口径，AC17）。
            reason = "人员不存在"
        elif person.status not in _IN_FORCE_STATUSES:
            reason = "该人员当前不在保或待生效，无法申报停保"
        elif person.id in seen_person_ids:
            reason = "批内重复申报，已自动去重（仅受理第一条）"
        if person is not None:
            row.person_id = person.id
            row.name = person.name
            if not row.id_number:
                row.id_number = person.decrypted_id_number()
        if reason:
            row.status = "rejected"
            row.reason = reason
            row.finished_at = now
            continue
        seen_person_ids.add(person.id)
        accepted += 1

    if accepted:
        declaration.status = "processing"
    else:
        declaration.status = "failed"
        declaration.finished_at = now
    session.flush()   # review-p2 M3：不 commit，等路由 ctx.save 单次提交
    return declaration


def _find_termination_target(session: Session, enterprise_id: int,
                             person_id: Optional[int],
                             id_number: str) -> Optional[InsuredPerson]:
    if person_id:
        person = session.get(InsuredPerson, person_id)
        # 他企业人员与不存在同答（不泄漏存在性）。
        if person is None or person.enterprise_id != enterprise_id:
            return None
        return person
    if id_number:
        # review-p2 minor：优先取在保/待生效行——同证件号可能另有后建的
        # draft 行（如申报被拒后的建档残留），按 id 倒序会让 draft 遮蔽
        # 真正在保的行，把「可停保的人」误判成「不在保」。
        person = session.scalar(
            select(InsuredPerson).where(
                InsuredPerson.enterprise_id == enterprise_id,
                InsuredPerson.id_number_hash == id_hash(id_number),
                InsuredPerson.status.in_(_IN_FORCE_STATUSES))
            .order_by(InsuredPerson.id.desc()).limit(1))
        if person is not None:
            return person
        return session.scalar(
            select(InsuredPerson).where(
                InsuredPerson.enterprise_id == enterprise_id,
                InsuredPerson.id_number_hash == id_hash(id_number))
            .order_by(InsuredPerson.id.desc()).limit(1))
    return None


# ---------------------------------------------------------------------------
# 人工确认钩子（AC6/AC7）：人员状态翻转时在 commit 前调用，申报项终态翻转、
# webhook 事件与状态变更同一事务落库。


def apply_person_status(session: Session, person: InsuredPerson,
                        new_status: str, *,
                        enforce_transition: bool = True) -> None:
    """人员状态翻转的唯一入口（review-p2 M2）。

    所有翻转路径——管理端 PATCH /api/insured/{id}/status、待确认停保批量
    执行（pending_terminations）、人员日期修正（update_person）——都必须经
    此赋值，保证 on_insured_status_changed 钩子（申报项终态 + webhook 事件）
    绝不因某条路径直接 `person.status = ...` 而被绕过（否则申报永远卡在
    processing、事件永不投递）。

    enforce_transition=False 供既有管理端路径使用：它们各有历史语义（批量
    停保强制执行、日期修正可隐式复保），本收敛只补钩子、不收紧状态机——
    旧行为不得变（review-p2 红线）。同状态赋值是 no-op。
    """
    previous = person.status
    if previous == new_status:
        return
    if enforce_transition:
        from ..policy_members import assert_status_transition
        assert_status_transition(previous, new_status)
    person.status = new_status
    on_insured_status_changed(session, person, previous, new_status)


def on_insured_status_changed(session: Session, person: InsuredPerson,
                              previous_status: str, new_status: str) -> None:
    if previous_status == new_status:
        return
    now = datetime.now(timezone.utc)
    if new_status == "active":
        external_id = _close_pending_items(
            session, person, kind="enrollment", to_status="succeeded", now=now)
        emit_event(session, person.enterprise_id, "insured.activated", {
            "person_id": person.id,
            "enterprise_id": person.enterprise_id,
            "external_person_id": external_id,
            "status": "active",
        })
    elif new_status == "stopped":
        # 生效前停保：悬挂的参保申报项作废（partial_succeeded/failed 由此可达）。
        _close_pending_items(session, person, kind="enrollment",
                             to_status="rejected",
                             reason="参保确认前已停保", now=now)
        external_id = _close_pending_items(
            session, person, kind="termination", to_status="succeeded", now=now)
        emit_event(session, person.enterprise_id, "insured.stopped", {
            "person_id": person.id,
            "enterprise_id": person.enterprise_id,
            "external_person_id": external_id,
            "status": "stopped",
        })


def _close_pending_items(session: Session, person: InsuredPerson, *, kind: str,
                         to_status: str, now: datetime,
                         reason: str = "") -> str:
    """把该人员名下 pending 的指定类申报项推到终态；返回 external_person_id
    （回传给事件；多项时取第一条非空）。顺带检查所属申报单是否全部终态。"""
    rows = session.execute(
        select(OpenApiDeclarationItem, OpenApiDeclaration)
        .join(OpenApiDeclaration,
              OpenApiDeclarationItem.declaration_id == OpenApiDeclaration.id)
        .where(OpenApiDeclarationItem.person_id == person.id,
               OpenApiDeclarationItem.status == "pending",
               # 防御性显式企业过滤（红线）。
               OpenApiDeclarationItem.enterprise_id == person.enterprise_id,
               OpenApiDeclaration.kind == kind)).all()
    external_id = ""
    declarations = {}
    for item, declaration in rows:
        item.status = to_status
        item.reason = reason
        item.finished_at = now
        if not external_id and item.external_person_id:
            external_id = item.external_person_id
        declarations[declaration.id] = declaration
    for declaration in declarations.values():
        _maybe_finish_declaration(session, declaration, now)
    return external_id


def _maybe_finish_declaration(session: Session,
                              declaration: OpenApiDeclaration,
                              now: datetime) -> None:
    """全部项终态 → 申报单翻 succeeded / partial_succeeded / failed，并投递
    declaration.completed（AC6）。"""
    # SessionLocal 是 autoflush=False：_close_pending_items 刚改完的 item
    # 状态还只在 ORM 身上，不 flush 的话下面的 SELECT 仍读到旧的 pending，
    # 申报单会永远卡在 processing。
    session.flush()
    statuses = set(session.scalars(
        select(OpenApiDeclarationItem.status).where(
            OpenApiDeclarationItem.declaration_id == declaration.id)))
    if "pending" in statuses:
        return
    if "rejected" not in statuses:
        declaration.status = "succeeded"
    elif "succeeded" in statuses:
        declaration.status = "partial_succeeded"
    else:
        declaration.status = "failed"
    declaration.finished_at = now
    emit_event(session, declaration.enterprise_id, "declaration.completed", {
        "declaration_id": declaration.id,
        "kind": declaration.kind,
        "status": declaration.status,
        "item_count": declaration.item_count,
    })


# ---------------------------------------------------------------------------
# 序列化（AC3/AC8）。


def serialize_declaration(declaration: OpenApiDeclaration) -> dict:
    return {
        "declaration_id": declaration.id,
        "kind": declaration.kind,
        "status": declaration.status,
        "item_count": declaration.item_count,
        "created_at": (declaration.created_at.isoformat()
                       if declaration.created_at else None),
        "finished_at": (declaration.finished_at.isoformat()
                        if declaration.finished_at else None),
    }


def serialize_declaration_item(item: OpenApiDeclarationItem) -> dict:
    from .serializers import mask_id_number_openapi  # 延迟 import（见顶部说明）
    return {
        "external_person_id": item.external_person_id,
        "person_id": item.person_id,
        "name": item.name,
        # 明细固定脱敏口径（对外明文只存在于 insured:read_sensitive 的拉取面）。
        "id_number": mask_id_number_openapi(item.id_number),
        "status": item.status,
        "reason": item.reason or None,
        "finished_at": item.finished_at.isoformat() if item.finished_at else None,
    }
