package com.xbb.fund.internal;

import com.xbb.fund.api.AccountType;
import jakarta.persistence.*;
import java.time.Instant;

/** 三段式流水(§2.5 的 before→amount→after):"对账以账本为准"。 */
@Entity
@Table(name = "escrow_ledger", schema = "fund")
public class EscrowLedgerEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 这笔流水属于哪家单位。<b>null = 平台自己。</b>
     * 不带的话按单位对账时只能靠 reason 文本去猜是谁的钱。
     */
    @Column(name = "org_id")
    private Long orgId;


    @Enumerated(EnumType.STRING)
    @Column(name = "account_type", nullable = false, length = 20)
    private AccountType accountType;

    @Column(name = "before_cents", nullable = false)
    private long beforeCents;

    @Column(name = "amount_cents", nullable = false)
    private long amountCents;

    @Column(name = "after_cents", nullable = false)
    private long afterCents;

    @Column(nullable = false, length = 200)
    private String reason;

    /** 业务幂等键(如 "reimbursement-42")。为空表示这笔没有外部业务键。 */
    @Column(name = "idempotency_key", length = 100)
    private String idempotencyKey;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected EscrowLedgerEntry() { }

    public EscrowLedgerEntry(Long orgId, AccountType accountType, long beforeCents, long amountCents,
                              long afterCents, String reason, String idempotencyKey) {
        this.orgId = orgId;
        this.accountType = accountType;
        this.beforeCents = beforeCents;
        this.amountCents = amountCents;
        this.afterCents = afterCents;
        this.reason = reason;
        this.idempotencyKey = idempotencyKey;
    }

    public Long getId() { return id; }
    public AccountType getAccountType() { return accountType; }
    /** @return null 表示平台自己 */
    public Long getOrgId() { return orgId; }
    public long getBeforeCents() { return beforeCents; }
    public long getAmountCents() { return amountCents; }
    public long getAfterCents() { return afterCents; }
    public String getReason() { return reason; }
}
