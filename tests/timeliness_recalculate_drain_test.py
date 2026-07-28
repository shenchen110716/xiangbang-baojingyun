"""Regression: 参停保及时率导入后百分比卡在 0%/明显偏低（用户反馈 2026-07-28 第 7 条）.

Root cause: POST /timeliness/recalculate (the endpoint both the "重算" button
and the post-import auto-trigger call) ran process_outbox() exactly once,
which only claims/processes up to `limit` (default 100) queued rows. Any
recalculate that needs to process more than 100 facts — completely normal
for a real bulk import — silently left the rest "pending" outbox rows,
so EmploymentTimelinessResult never gets written for them and the summary
percentages stay wrong (understated or stuck at 0%) until someone happens
to reload the summary/detail page enough times for drain_due()'s smaller
per-request quota (default 50) to slowly catch up.

This proves the fix: recalculate now loops process_outbox() until the
outbox queue is actually empty, in one call, regardless of size.
"""
import os
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))


def run():
    with tempfile.TemporaryDirectory(prefix="xbb-timeliness-drain-") as folder:
        os.environ["DATABASE_URL"] = f"sqlite:///{Path(folder) / 'test.db'}"
        os.environ["ADMIN_PASSWORD"] = "admin123"
        os.environ["ENTERPRISE_PASSWORD"] = "enterprise123"

        from sqlalchemy import func, select

        from backend.app import startup
        from backend.core.business_time import business_now
        from backend.core.db import SessionLocal
        from backend.models import TimelinessOutbox, User
        from backend.routers.timeliness import timeliness_recalculate

        startup()
        with SessionLocal() as session:
            admin = session.scalar(select(User).where(User.role == "admin"))

            # 直接灌 250 条待处理队列（> process_outbox 单次上限 100，需要至少
            # 3 次内部批处理才能排空）。employment_fact_id 用不存在的假 id 也没
            # 关系——recalculate() 对找不到的 fact 会直接优雅地跳过（services/
            # timeliness_recalc.py:171-175 `if not fact: return []`），这里只
            # 测"队列会不会被一次调用真正排空"这一件事，不需要真实的用工事实。
            fake_fact_ids = range(900001, 900251)
            for fact_id in fake_fact_ids:
                session.add(TimelinessOutbox(employment_fact_id=fact_id, reason="test-seed",
                                              status="pending", created_at=business_now()))
            session.commit()

            pending_before = session.scalar(select(func.count(TimelinessOutbox.id)).where(TimelinessOutbox.status == "pending"))
            assert pending_before == 250, f"测试前提：应该有 250 条待处理，实际 {pending_before}"

            result = timeliness_recalculate(True, admin, session)

            assert result["processed"] == 250, f"一次 recalculate 调用应该处理完全部 250 条，实际 processed={result['processed']}"
            pending_after = session.scalar(select(func.count(TimelinessOutbox.id)).where(TimelinessOutbox.status == "pending"))
            assert pending_after == 0, f"队列应该被完全排空，实际还剩 {pending_after} 条 pending"

    print("timeliness recalculate drain test: PASS")


if __name__ == "__main__":
    run()
