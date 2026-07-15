from sqlalchemy import func, select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from ..models import EnterprisePremiumAccount, InsuredPerson, InsurerAccountLink, PendingTermination
from .notify import notify_enterprise


def scan_premium_shortfalls(session: Session, enterprise_id: int | None = None) -> list[PendingTermination]:
    """Lazy scan: no scheduled job in this codebase, so this runs whenever an
    admin-facing page that needs fresh data loads (dashboard, the pending-
    terminations list). Idempotent — running it twice in a row does not
    create duplicate pending records, and it auto-dismisses any pending
    record whose account has since been recharged back to a positive
    balance. Returns only the records newly created by THIS call, so the
    caller can fire notifications for exactly those without re-notifying
    on every subsequent scan."""
    stmt = select(EnterprisePremiumAccount).where(EnterprisePremiumAccount.balance <= 0)
    if enterprise_id is not None:
        stmt = stmt.where(EnterprisePremiumAccount.enterprise_id == enterprise_id)
    shortfall_rows = session.scalars(stmt).all()
    shortfall_keys = {(row.enterprise_id, row.account_id) for row in shortfall_rows}

    # auto-dismiss: any pending record whose account is no longer in shortfall
    dismiss_stmt = select(PendingTermination).where(PendingTermination.status == "pending")
    if enterprise_id is not None:
        dismiss_stmt = dismiss_stmt.where(PendingTermination.enterprise_id == enterprise_id)
    for existing in session.scalars(dismiss_stmt).all():
        if (existing.enterprise_id, existing.account_id) not in shortfall_keys:
            existing.status = "dismissed"
            from ..core.business_time import business_now
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
        if already_pending:
            continue
        insurers = [x for x, in session.execute(select(InsurerAccountLink.insurer).where(InsurerAccountLink.account_id == row.account_id)).all()]
        if not insurers:
            continue
        affected_count = session.scalar(
            select(func.count())
            .select_from(InsuredPerson)
            .where(
                InsuredPerson.enterprise_id == row.enterprise_id,
                InsuredPerson.status == "active",
            )
        ) or 0
        if affected_count == 0:
            continue
        item = PendingTermination(
            enterprise_id=row.enterprise_id, account_id=row.account_id,
            affected_insurers=",".join(insurers), affected_count=affected_count,
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
