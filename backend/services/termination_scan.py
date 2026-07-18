from sqlalchemy import or_, select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from ..core.business_time import business_now
from ..models import (
    Enterprise,
    EnterprisePremiumAccount,
    InsurancePlan,
    InsuredPerson,
    InsurerAccountLink,
    PendingTermination,
    Policy,
    PolicyMember,
)
from .notify import notify_enterprise


def affected_coverage_for_account(
    session: Session,
    enterprise_id: int,
    account_id: int,
) -> tuple[list[str], list[tuple[InsuredPerson, PolicyMember]]]:
    """Return people and the exact live coverage funded by this account.

    Account balance is pooled by insurer mapping. The live ``PolicyMember``
    is authoritative; callers that terminate coverage must use this exact row
    rather than re-selecting by person at a later time.
    """
    insurers = sorted({
        insurer
        for insurer, in session.execute(
            select(InsurerAccountLink.insurer).where(
                InsurerAccountLink.account_id == account_id,
            )
        ).all()
    })
    if not insurers:
        return [], []

    now = business_now()
    latest_coverage_id = (
        select(PolicyMember.id)
        .where(
            PolicyMember.person_id == InsuredPerson.id,
            PolicyMember.effective_at <= now,
            or_(PolicyMember.terminated_at.is_(None), PolicyMember.terminated_at > now),
        )
        .order_by(PolicyMember.id.desc())
        .limit(1)
        .correlate(InsuredPerson)
        .scalar_subquery()
    )
    coverage = session.execute(
        select(InsuredPerson, PolicyMember)
        .join(PolicyMember, PolicyMember.id == latest_coverage_id)
        .join(Policy, PolicyMember.policy_id == Policy.id)
        .join(InsurancePlan, Policy.plan_id == InsurancePlan.id)
        .where(
            InsuredPerson.enterprise_id == enterprise_id,
            Policy.status == "active",
            InsurancePlan.insurer.in_(insurers),
        )
        .order_by(InsuredPerson.id)
    ).all()
    return insurers, list(coverage)


def affected_people_for_account(
    session: Session,
    enterprise_id: int,
    account_id: int,
) -> tuple[list[str], list[InsuredPerson]]:
    insurers, coverage = affected_coverage_for_account(session, enterprise_id, account_id)
    return insurers, [person for person, _member in coverage]


def scan_premium_shortfalls(session: Session, enterprise_id: int | None = None) -> list[PendingTermination]:
    """Lazy scan: no scheduled job in this codebase, so this runs whenever an
    admin-facing page that needs fresh data loads (dashboard, the pending-
    terminations list). Idempotent — running it twice in a row does not
    create duplicate pending records, and it auto-dismisses any pending
    record whose account has since been recharged back to a positive
    balance. Returns only the records newly created by THIS call, so the
    caller can fire notifications for exactly those without re-notifying
    on every subsequent scan.

    Shortfall is judged on the AVAILABLE balance (充值总额 − 已消耗保费), the same
    figure the portals show, not the raw recharge total — so an account whose
    accrued premium has overtaken its recharges is caught even if it was funded
    once. available is computed (not a stored column), so this can't be a SQL
    filter; premium_account_view does the per-account accrual."""
    from .policies import premium_account_view

    enterprises_stmt = select(Enterprise)
    if enterprise_id is not None:
        enterprises_stmt = enterprises_stmt.where(Enterprise.id == enterprise_id)
    shortfall_rows = []
    for enterprise in session.scalars(enterprises_stmt).all():
        for view in premium_account_view(session, enterprise):
            if view["available"] <= 0:
                row = session.scalar(select(EnterprisePremiumAccount).where(
                    EnterprisePremiumAccount.enterprise_id == enterprise.id,
                    EnterprisePremiumAccount.account_id == view["account_id"]))
                if row is not None:
                    shortfall_rows.append(row)
    shortfall_keys = {(row.enterprise_id, row.account_id) for row in shortfall_rows}

    # auto-dismiss: any pending record whose account is no longer in shortfall
    dismiss_stmt = select(PendingTermination).where(PendingTermination.status == "pending")
    if enterprise_id is not None:
        dismiss_stmt = dismiss_stmt.where(PendingTermination.enterprise_id == enterprise_id)
    for existing in session.scalars(dismiss_stmt).all():
        if (existing.enterprise_id, existing.account_id) not in shortfall_keys:
            existing.status = "dismissed"
            existing.dismissed_at = business_now()

    created: list[PendingTermination] = []
    for row in shortfall_rows:
        already_pending = session.scalar(
            select(PendingTermination).where(
                PendingTermination.enterprise_id == row.enterprise_id,
                PendingTermination.account_id == row.account_id,
                PendingTermination.status == "pending",
            )
        )
        insurers, affected_people = affected_people_for_account(
            session,
            row.enterprise_id,
            row.account_id,
        )
        affected_count = len(affected_people)
        if affected_count == 0:
            if already_pending:
                already_pending.status = "dismissed"
                already_pending.dismissed_at = business_now()
            continue
        insurer_snapshot = ",".join(insurers)
        if already_pending:
            already_pending.affected_insurers = insurer_snapshot
            already_pending.affected_count = affected_count
            continue
        item = PendingTermination(
            enterprise_id=row.enterprise_id, account_id=row.account_id,
            affected_insurers=insurer_snapshot, affected_count=affected_count,
        )
        try:
            # The pre-insert lookup is only an optimization. The partial
            # unique index is the concurrent-scan authority; contain a loser
            # in a SAVEPOINT so other scan work can still commit.
            with session.begin_nested():
                session.add(item)
                session.flush()
        except IntegrityError:
            continue
        created.append(item)

    session.commit()
    for item in created:
        session.refresh(item)
        notify_enterprise(
            session,
            item.enterprise_id,
            "premium_shortfall_warning",
            {"insurers": item.affected_insurers, "affected_count": item.affected_count},
        )
    return created
