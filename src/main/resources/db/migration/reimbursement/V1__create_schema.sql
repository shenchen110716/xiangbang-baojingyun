CREATE SCHEMA IF NOT EXISTS reimbursement;

-- §6.5.2:"报销**打款仍走资金域**(唯一动钱者),报销域只管流程与审批。"
CREATE TABLE reimbursement.reimbursement (
    id              BIGSERIAL PRIMARY KEY,
    applicant_user_id BIGINT     NOT NULL,
    amount_cents    BIGINT       NOT NULL,
    reason          VARCHAR(300) NOT NULL,
    invoice_no      VARCHAR(100),
    status          VARCHAR(20)  NOT NULL DEFAULT 'SUBMITTED',
    reject_reason   VARCHAR(300),
    approver_user_id BIGINT,
    paid_at         TIMESTAMPTZ,
    version         BIGINT       NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

GRANT USAGE ON SCHEMA reimbursement TO reimbursement_user;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA reimbursement TO reimbursement_user;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA reimbursement TO reimbursement_user;
