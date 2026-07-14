from sqlalchemy.orm import Session


def run_sqlite_bridge_migrations(s: Session, database_url: str) -> None:
    if not database_url.startswith("sqlite"):
        return
    # 兼容旧版本地 SQLite 数据库；新建的 PostgreSQL 库由 create_all 建表。
    columns = {row[1] for row in s.connection().exec_driver_sql("PRAGMA table_info(users)")}
    if "enterprise_id" not in columns:
        s.connection().exec_driver_sql("ALTER TABLE users ADD COLUMN enterprise_id INTEGER")
    for column, definition in [("phone", "VARCHAR(30) DEFAULT ''"), ("status", "VARCHAR(30) DEFAULT 'active'"), ("is_owner", "BOOLEAN DEFAULT 0")]:
        if column not in columns: s.connection().exec_driver_sql(f"ALTER TABLE users ADD COLUMN {column} {definition}")
    enterprise_columns = {row[1] for row in s.connection().exec_driver_sql("PRAGMA table_info(enterprises)")}
    if "agent_id" not in enterprise_columns: s.connection().exec_driver_sql("ALTER TABLE enterprises ADD COLUMN agent_id INTEGER")
    if "usage_fee_daily" not in enterprise_columns: s.connection().exec_driver_sql("ALTER TABLE enterprises ADD COLUMN usage_fee_daily FLOAT DEFAULT 0.1")
    if "alert_days" not in enterprise_columns: s.connection().exec_driver_sql("ALTER TABLE enterprises ADD COLUMN alert_days INTEGER DEFAULT 3")
    commission_columns = {row[1] for row in s.connection().exec_driver_sql("PRAGMA table_info(agent_commissions)")}
    if "mode" not in commission_columns: s.connection().exec_driver_sql("ALTER TABLE agent_commissions ADD COLUMN mode VARCHAR(20) DEFAULT 'rebate'")
    if "markup_amount" not in commission_columns: s.connection().exec_driver_sql("ALTER TABLE agent_commissions ADD COLUMN markup_amount FLOAT DEFAULT 0")
    if "sale_price" not in commission_columns: s.connection().exec_driver_sql("ALTER TABLE agent_commissions ADD COLUMN sale_price FLOAT DEFAULT 0")
    plan_columns = {row[1] for row in s.connection().exec_driver_sql("PRAGMA table_info(insurance_plans)")}
    if "billing_mode" not in plan_columns: s.connection().exec_driver_sql("ALTER TABLE insurance_plans ADD COLUMN billing_mode VARCHAR(20) DEFAULT 'monthly'")
    if "effective_mode" not in plan_columns: s.connection().exec_driver_sql("ALTER TABLE insurance_plans ADD COLUMN effective_mode VARCHAR(20) DEFAULT 'next_day'")
    if "insurer_email" not in plan_columns: s.connection().exec_driver_sql("ALTER TABLE insurance_plans ADD COLUMN insurer_email VARCHAR(160) DEFAULT ''")
    if "profit_amount" not in plan_columns:
        s.connection().exec_driver_sql("ALTER TABLE insurance_plans ADD COLUMN profit_amount FLOAT DEFAULT 0")
        s.connection().exec_driver_sql("UPDATE insurance_plans SET profit_amount=price*commission_rate")
    s.connection().exec_driver_sql("UPDATE agent_commissions SET mode='price', sale_price=COALESCE((SELECT price*(1-commission_rate)+profit_amount FROM insurance_plans WHERE insurance_plans.id=agent_commissions.plan_id),0)+COALESCE(markup_amount,0) WHERE mode='markup'")
    insured_columns = {row[1] for row in s.connection().exec_driver_sql("PRAGMA table_info(insured_people)")}
    if "id_number" not in insured_columns: s.connection().exec_driver_sql("ALTER TABLE insured_people ADD COLUMN id_number VARCHAR(40) DEFAULT ''")
    if "position_id" not in insured_columns: s.connection().exec_driver_sql("ALTER TABLE insured_people ADD COLUMN position_id INTEGER")
    position_columns = {row[1] for row in s.connection().exec_driver_sql("PRAGMA table_info(work_positions)")}
    if "actual_employer_id" not in position_columns: s.connection().exec_driver_sql("ALTER TABLE work_positions ADD COLUMN actual_employer_id INTEGER")
    if "created_by" not in position_columns: s.connection().exec_driver_sql("ALTER TABLE work_positions ADD COLUMN created_by INTEGER")
    claim_columns = {row[1] for row in s.connection().exec_driver_sql("PRAGMA table_info(claims)")}
    for column, definition in [("accident_at","VARCHAR(30) DEFAULT ''"),("accident_place","VARCHAR(200) DEFAULT ''"),("accident_type","VARCHAR(60) DEFAULT '工伤事故'"),("hospital","VARCHAR(160) DEFAULT ''"),("diagnosis","TEXT DEFAULT ''"),("medical_cost","FLOAT DEFAULT 0"),("contact_name","VARCHAR(80) DEFAULT ''"),("contact_phone","VARCHAR(30) DEFAULT ''"),("insurer_report_no","VARCHAR(100) DEFAULT ''"),("current_handler","VARCHAR(80) DEFAULT '平台理赔专员'"),("deadline","VARCHAR(30) DEFAULT ''"),("sla_deadline","VARCHAR(30) DEFAULT ''"),("approved_amount","FLOAT DEFAULT 0"),("paid_at","VARCHAR(30) DEFAULT ''"),("rejection_reason","TEXT DEFAULT ''"),("review_note","TEXT DEFAULT ''"),("risk_level","VARCHAR(20) DEFAULT 'normal'")]:
        if column not in claim_columns: s.connection().exec_driver_sql(f"ALTER TABLE claims ADD COLUMN {column} {definition}")
    document_columns = {row[1] for row in s.connection().exec_driver_sql("PRAGMA table_info(claim_documents)")}
    if "review_note" not in document_columns: s.connection().exec_driver_sql("ALTER TABLE claim_documents ADD COLUMN review_note TEXT DEFAULT ''")
    policy_columns = {row[1] for row in s.connection().exec_driver_sql("PRAGMA table_info(policies)")}
    if "document_url" not in policy_columns: s.connection().exec_driver_sql("ALTER TABLE policies ADD COLUMN document_url TEXT DEFAULT ''")
    if "document_name" not in policy_columns: s.connection().exec_driver_sql("ALTER TABLE policies ADD COLUMN document_name VARCHAR(200) DEFAULT ''")
