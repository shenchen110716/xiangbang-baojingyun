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

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected EscrowLedgerEntry() { }

    public EscrowLedgerEntry(AccountType accountType, long beforeCents, long amountCents,
                              long afterCents, String reason) {
        this.accountType = accountType;
        this.beforeCents = beforeCents;
        this.amountCents = amountCents;
        this.afterCents = afterCents;
        this.reason = reason;
    }

    public Long getId() { return id; }
    public AccountType getAccountType() { return accountType; }
    public long getBeforeCents() { return beforeCents; }
    public long getAmountCents() { return amountCents; }
    public long getAfterCents() { return afterCents; }
    public String getReason() { return reason; }
}
