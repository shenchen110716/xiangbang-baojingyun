from sqlalchemy import select, text
from sqlalchemy.orm import Session


def backfill_insured_id_numbers(connection, batch_size: int = 500) -> int:
    """story-p3-compliance AC1：回填 insured_people 的 hash/cipher 列。

    幂等可重跑：只挑「明文（TRIM 后）非空且 hash 或 cipher 缺失」的行；
    分批 SELECT … LIMIT 扫描，逐行 UPDATE。注意：Alembic 下整个迁移仍是
    **单事务**（分批只是避免一次性把全表读进内存，并非分事务提交）；
    生产大表的迁移窗口评估见交接文档 openapi-p3-compliance.md。
    被 Alembic 迁移（PostgreSQL 权威路径）、SQLite 启动桥和测试共用。
    返回本次回填的行数（重跑应为 0）。

    密钥防呆（review-p3 B2）：在非 SQLite 库上禁止使用开发缺省
    ID_ENCRYPTION_KEY 回填——错钥写满 hash/cipher 后，幂等判据只看
    「非空」，换对密钥重跑是 0 行，投毒不可自愈。
    """
    from .config import ID_ENCRYPTION_KEY, _DEV_ID_KEY
    from .id_number import id_encrypt, id_hash

    dialect = getattr(getattr(connection, "dialect", None), "name", "")
    if dialect != "sqlite" and ID_ENCRYPTION_KEY == _DEV_ID_KEY:
        raise RuntimeError(
            "ID_ENCRYPTION_KEY 未设置（仍为开发默认值），拒绝在非本地 SQLite 库上"
            "回填 insured_people 身份证号 hash/cipher：错误密钥写入后无法自愈"
            "（回填幂等判据只看列非空，换对密钥重跑为 0 行）。"
            "请先 export ID_ENCRYPTION_KEY=<正式密钥> 再执行 alembic upgrade。")

    total = 0
    while True:
        rows = connection.execute(text(
            "SELECT id, id_number FROM insured_people "
            "WHERE id_number IS NOT NULL AND TRIM(id_number) != '' "
            "AND (id_number_hash IS NULL OR id_number_hash = '' "
            "     OR id_number_cipher IS NULL OR id_number_cipher = '') "
            "ORDER BY id LIMIT :n"), {"n": batch_size}).fetchall()
        if not rows:
            return total
        for row_id, plain in rows:
            connection.execute(text(
                "UPDATE insured_people SET id_number_hash = :h, "
                "id_number_cipher = :c WHERE id = :i"),
                {"h": id_hash(plain), "c": id_encrypt(plain), "i": row_id})
        total += len(rows)


def run_sqlite_bridge_migrations(s: Session, database_url: str) -> None:
    if not database_url.startswith("sqlite"):
        return
    # 兼容旧版本地 SQLite 数据库；新建的 PostgreSQL 库由 create_all 建表。
    columns = {row[1] for row in s.connection().exec_driver_sql("PRAGMA table_info(users)")}
    if "enterprise_id" not in columns:
        s.connection().exec_driver_sql("ALTER TABLE users ADD COLUMN enterprise_id INTEGER")
    for column, definition in [("phone", "VARCHAR(30) DEFAULT ''"), ("status", "VARCHAR(30) DEFAULT 'active'"), ("is_owner", "BOOLEAN DEFAULT 0"), ("enterprise_role", "VARCHAR(30)")]:
        if column not in columns: s.connection().exec_driver_sql(f"ALTER TABLE users ADD COLUMN {column} {definition}")
    if "wx_openid" not in columns: s.connection().exec_driver_sql("ALTER TABLE users ADD COLUMN wx_openid VARCHAR(64)")
    # 保司端账号（v4.2）：旧本地库没有 insurer_id，启动期 seed 查 users 会崩
    if "insurer_id" not in columns: s.connection().exec_driver_sql("ALTER TABLE users ADD COLUMN insurer_id INTEGER")
    s.connection().exec_driver_sql(
        "UPDATE users SET enterprise_role=CASE WHEN is_owner THEN 'owner' ELSE 'project_manager' END "
        "WHERE role='enterprise' AND enterprise_role IS NULL"
    )
    from ..models import UserEmployerScope
    UserEmployerScope.__table__.create(bind=s.connection(), checkfirst=True)
    # v4.2 Phase 2 用工事实表；空表创建，不回填任何真实入离职时间（§16）。
    from ..models import EmploymentFact, EmploymentFactMatch, EmploymentFeedbackBatch
    for table in (EmploymentFeedbackBatch, EmploymentFact, EmploymentFactMatch):
        table.__table__.create(bind=s.connection(), checkfirst=True)
    # v4.2 Phase 3 及时率表；结果由重算派生，不回填历史（§16、§20.6）。
    from ..models import EmploymentTimelinessResult, ParticipationOperation, TimelinessOutbox
    for table in (ParticipationOperation, EmploymentTimelinessResult, TimelinessOutbox):
        table.__table__.create(bind=s.connection(), checkfirst=True)
    # v4.2 Phase 5 业务员佣金结算账本；只建空表，不回填历史结算（金额只能来自真实计提）。
    from ..models import (
        AgentCommissionPayment, AgentCommissionPaymentAllocation,
        AgentCommissionStatement, AgentCommissionStatementItem,
    )
    for table in (AgentCommissionStatement, AgentCommissionStatementItem,
                  AgentCommissionPayment, AgentCommissionPaymentAllocation):
        table.__table__.create(bind=s.connection(), checkfirst=True)
    enterprise_columns = {row[1] for row in s.connection().exec_driver_sql("PRAGMA table_info(enterprises)")}
    if "agent_id" not in enterprise_columns: s.connection().exec_driver_sql("ALTER TABLE enterprises ADD COLUMN agent_id INTEGER")
    if "usage_fee_daily" not in enterprise_columns: s.connection().exec_driver_sql("ALTER TABLE enterprises ADD COLUMN usage_fee_daily FLOAT DEFAULT 0.1")
    if "alert_days" not in enterprise_columns: s.connection().exec_driver_sql("ALTER TABLE enterprises ADD COLUMN alert_days INTEGER DEFAULT 3")
    # 开放平台 P2（story-p2-write AC15）：余额预警阈值，可空=不预警。
    if "usage_alert_threshold" not in enterprise_columns: s.connection().exec_driver_sql("ALTER TABLE enterprises ADD COLUMN usage_alert_threshold FLOAT")
    # 平台端系统设置 key-value 表（存储/短信/OCR 配置）。
    from ..models import SystemSetting
    SystemSetting.__table__.create(bind=s.connection(), checkfirst=True)
    enrollment_email_columns = {row[1] for row in s.connection().exec_driver_sql("PRAGMA table_info(enrollment_emails)")}
    if enrollment_email_columns:
        for column, definition in [("receipt_status", "VARCHAR(20) DEFAULT 'pending'"), ("receipt_note", "TEXT DEFAULT ''"), ("receipt_at", "DATETIME"), ("receipt_by", "INTEGER"), ("data_date", "VARCHAR(20) DEFAULT ''")]:
            if column not in enrollment_email_columns: s.connection().exec_driver_sql(f"ALTER TABLE enrollment_emails ADD COLUMN {column} {definition}")
    invoice_columns = {row[1] for row in s.connection().exec_driver_sql("PRAGMA table_info(invoices)")}
    if invoice_columns and "invoice_type" not in invoice_columns: s.connection().exec_driver_sql("ALTER TABLE invoices ADD COLUMN invoice_type VARCHAR(40) DEFAULT '增值税普通发票'")
    commission_columns = {row[1] for row in s.connection().exec_driver_sql("PRAGMA table_info(agent_commissions)")}
    if "mode" not in commission_columns: s.connection().exec_driver_sql("ALTER TABLE agent_commissions ADD COLUMN mode VARCHAR(20) DEFAULT 'rebate'")
    if "markup_amount" not in commission_columns: s.connection().exec_driver_sql("ALTER TABLE agent_commissions ADD COLUMN markup_amount FLOAT DEFAULT 0")
    if "sale_price" not in commission_columns: s.connection().exec_driver_sql("ALTER TABLE agent_commissions ADD COLUMN sale_price FLOAT DEFAULT 0")
    plan_columns = {row[1] for row in s.connection().exec_driver_sql("PRAGMA table_info(insurance_plans)")}
    if "billing_mode" not in plan_columns: s.connection().exec_driver_sql("ALTER TABLE insurance_plans ADD COLUMN billing_mode VARCHAR(20) DEFAULT 'monthly'")
    if "effective_mode" not in plan_columns: s.connection().exec_driver_sql("ALTER TABLE insurance_plans ADD COLUMN effective_mode VARCHAR(20) DEFAULT 'next_day'")
    if "insurer_email" not in plan_columns: s.connection().exec_driver_sql("ALTER TABLE insurance_plans ADD COLUMN insurer_email VARCHAR(160) DEFAULT ''")
    if "image_url" not in plan_columns: s.connection().exec_driver_sql("ALTER TABLE insurance_plans ADD COLUMN image_url TEXT DEFAULT ''")
    if "image_name" not in plan_columns: s.connection().exec_driver_sql("ALTER TABLE insurance_plans ADD COLUMN image_name VARCHAR(200) DEFAULT ''")
    if "profit_amount" not in plan_columns:
        s.connection().exec_driver_sql("ALTER TABLE insurance_plans ADD COLUMN profit_amount FLOAT DEFAULT 0")
        s.connection().exec_driver_sql("UPDATE insurance_plans SET profit_amount=price*commission_rate")
    s.connection().exec_driver_sql("UPDATE agent_commissions SET mode='price', sale_price=COALESCE((SELECT price*(1-commission_rate)+profit_amount FROM insurance_plans WHERE insurance_plans.id=agent_commissions.plan_id),0)+COALESCE(markup_amount,0) WHERE mode='markup'")
    insured_columns = {row[1] for row in s.connection().exec_driver_sql("PRAGMA table_info(insured_people)")}
    if "id_number" not in insured_columns: s.connection().exec_driver_sql("ALTER TABLE insured_people ADD COLUMN id_number VARCHAR(40) DEFAULT ''")
    if "position_id" not in insured_columns: s.connection().exec_driver_sql("ALTER TABLE insured_people ADD COLUMN position_id INTEGER")
    # story-p3-compliance 双写列（Alembic 权威迁移 = openapi_p3_compliance）：
    # hash 查重 + cipher 展示解密；回填幂等，每次启动跑一遍兜底存量行。
    if "id_number_hash" not in insured_columns: s.connection().exec_driver_sql("ALTER TABLE insured_people ADD COLUMN id_number_hash VARCHAR(64) DEFAULT ''")
    if "id_number_cipher" not in insured_columns: s.connection().exec_driver_sql("ALTER TABLE insured_people ADD COLUMN id_number_cipher TEXT")
    s.connection().exec_driver_sql("CREATE INDEX IF NOT EXISTS ix_insured_people_id_number_hash ON insured_people (id_number_hash)")
    backfill_insured_id_numbers(s.connection())
    position_columns = {row[1] for row in s.connection().exec_driver_sql("PRAGMA table_info(work_positions)")}
    if "actual_employer_id" not in position_columns: s.connection().exec_driver_sql("ALTER TABLE work_positions ADD COLUMN actual_employer_id INTEGER")
    if "created_by" not in position_columns: s.connection().exec_driver_sql("ALTER TABLE work_positions ADD COLUMN created_by INTEGER")
    if "enable_personal_pay" not in position_columns: s.connection().exec_driver_sql("ALTER TABLE work_positions ADD COLUMN enable_personal_pay BOOLEAN DEFAULT 0")
    if "enable_employer_pay" not in position_columns: s.connection().exec_driver_sql("ALTER TABLE work_positions ADD COLUMN enable_employer_pay BOOLEAN DEFAULT 0")
    if "enroll_token_version" not in position_columns: s.connection().exec_driver_sql("ALTER TABLE work_positions ADD COLUMN enroll_token_version INTEGER DEFAULT 0")
    s.connection().exec_driver_sql(
        "CREATE TABLE IF NOT EXISTS position_enroll_submissions ("
        "id INTEGER PRIMARY KEY AUTOINCREMENT,"
        "position_id INTEGER NOT NULL REFERENCES work_positions(id),"
        "payment_mode VARCHAR(20) NOT NULL,"
        "name VARCHAR(80) DEFAULT '',"
        "id_number_cipher TEXT DEFAULT '',"
        "phone VARCHAR(30) DEFAULT '',"
        "payment_status VARCHAR(20) DEFAULT 'not_required',"
        "order_no VARCHAR(60) DEFAULT '',"
        "review_status VARCHAR(20) DEFAULT 'pending',"
        "review_note TEXT DEFAULT '',"
        "reviewed_by INTEGER REFERENCES users(id),"
        "reviewed_at DATETIME,"
        "insured_person_id INTEGER REFERENCES insured_people(id),"
        "created_at DATETIME DEFAULT CURRENT_TIMESTAMP"
        ")"
    )
    claim_columns = {row[1] for row in s.connection().exec_driver_sql("PRAGMA table_info(claims)")}
    for column, definition in [("accident_at","VARCHAR(30) DEFAULT ''"),("accident_place","VARCHAR(200) DEFAULT ''"),("accident_type","VARCHAR(60) DEFAULT '工伤事故'"),("hospital","VARCHAR(160) DEFAULT ''"),("diagnosis","TEXT DEFAULT ''"),("medical_cost","FLOAT DEFAULT 0"),("contact_name","VARCHAR(80) DEFAULT ''"),("contact_phone","VARCHAR(30) DEFAULT ''"),("insurer_report_no","VARCHAR(100) DEFAULT ''"),("current_handler","VARCHAR(80) DEFAULT '平台理赔专员'"),("deadline","VARCHAR(30) DEFAULT ''"),("sla_deadline","VARCHAR(30) DEFAULT ''"),("approved_amount","FLOAT DEFAULT 0"),("paid_at","VARCHAR(30) DEFAULT ''"),("rejection_reason","TEXT DEFAULT ''"),("review_note","TEXT DEFAULT ''"),("risk_level","VARCHAR(20) DEFAULT 'normal'")]:
        if column not in claim_columns: s.connection().exec_driver_sql(f"ALTER TABLE claims ADD COLUMN {column} {definition}")
    document_columns = {row[1] for row in s.connection().exec_driver_sql("PRAGMA table_info(claim_documents)")}
    if "review_note" not in document_columns: s.connection().exec_driver_sql("ALTER TABLE claim_documents ADD COLUMN review_note TEXT DEFAULT ''")
    policy_columns = {row[1] for row in s.connection().exec_driver_sql("PRAGMA table_info(policies)")}
    if "document_url" not in policy_columns: s.connection().exec_driver_sql("ALTER TABLE policies ADD COLUMN document_url TEXT DEFAULT ''")
    if "document_name" not in policy_columns: s.connection().exec_driver_sql("ALTER TABLE policies ADD COLUMN document_name VARCHAR(200) DEFAULT ''")
    invoice_columns = {row[1] for row in s.connection().exec_driver_sql("PRAGMA table_info(invoices)")}
    if "document_url" not in invoice_columns: s.connection().exec_driver_sql("ALTER TABLE invoices ADD COLUMN document_url TEXT DEFAULT ''")
    if "document_name" not in invoice_columns: s.connection().exec_driver_sql("ALTER TABLE invoices ADD COLUMN document_name VARCHAR(200) DEFAULT ''")
    ledger_columns = {row[1] for row in s.connection().exec_driver_sql("PRAGMA table_info(ledger_entries)")}
    if "account_id" not in ledger_columns: s.connection().exec_driver_sql("ALTER TABLE ledger_entries ADD COLUMN account_id INTEGER")
    payment_columns = {row[1] for row in s.connection().exec_driver_sql("PRAGMA table_info(payment_records)")}
    if payment_columns:
        for column, definition in [("channel", "VARCHAR(20) DEFAULT 'native'"), ("openid", "VARCHAR(64)"), ("provider_trade_no", "VARCHAR(80)"), ("paid_at", "DATETIME")]:
            if column not in payment_columns: s.connection().exec_driver_sql(f"ALTER TABLE payment_records ADD COLUMN {column} {definition}")
    # 开放平台 P0：新表由 create_all 覆盖；但 integration_nonces 在旧库里
    # 已存在，create_all 会整表跳过，seen_at 清理索引要在这里补。表不存在
    # 时跳过（部分测试库只建业务子集表）。
    nonce_columns = {row[1] for row in s.connection().exec_driver_sql("PRAGMA table_info(integration_nonces)")}
    if nonce_columns:
        s.connection().exec_driver_sql(
            "CREATE INDEX IF NOT EXISTS ix_integration_nonces_seen_at ON integration_nonces (seen_at)")


def migrate_premium_balances(s: Session) -> None:
    # SYSTEM-DESIGN-V4.md 之外的补充设计（保司分账户充值 spec）：Enterprise.
    # premium_balance 停止读写，历史余额一次性回填到一个占位 InsurerAccount
    # 下，之后由管理员在后台手动改配到具体保司账户。幂等：已经有
    # EnterprisePremiumAccount 记录的企业跳过，可以在每次启动时安全重跑。
    from ..models import Enterprise, EnterprisePremiumAccount, InsurerAccount

    placeholder = s.scalar(select(InsurerAccount).where(InsurerAccount.label == "未分类（历史余额）"))
    for enterprise in s.scalars(select(Enterprise).where(Enterprise.premium_balance != 0)):
        if s.scalar(select(EnterprisePremiumAccount).where(EnterprisePremiumAccount.enterprise_id == enterprise.id)):
            continue
        if not placeholder:
            placeholder = InsurerAccount(label="未分类（历史余额）", bank_name="", account_no="", account_holder="", status="paused")
            s.add(placeholder); s.flush()
        s.add(EnterprisePremiumAccount(enterprise_id=enterprise.id, account_id=placeholder.id, balance=enterprise.premium_balance))
    s.commit()
