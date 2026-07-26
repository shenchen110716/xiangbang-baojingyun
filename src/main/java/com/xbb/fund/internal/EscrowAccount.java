package com.xbb.fund.internal;

import com.xbb.fund.api.AccountType;
import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "escrow_account", schema = "fund")
public class EscrowAccount {

    @Id
    @Enumerated(EnumType.STRING)
    @Column(name = "account_type", length = 20)
    private AccountType accountType;

    @Column(name = "balance_cents", nullable = false)
    private long balanceCents;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Version
    private long version;

    protected EscrowAccount() { }

    public long credit(long amountCents) {
        requirePositive(amountCents);
        this.balanceCents += amountCents;
        this.updatedAt = Instant.now();
        return this.balanceCents;
    }

    public long debit(long amountCents) {
        requirePositive(amountCents);
        // 资金安全底线:监管账户余额不能为负,宁可拒绝出金
        if (this.balanceCents < amountCents) {
            throw new IllegalStateException("监管账户余额不足,当前 %d 分,需要 %d 分"
                    .formatted(this.balanceCents, amountCents));
        }
        this.balanceCents -= amountCents;
        this.updatedAt = Instant.now();
        return this.balanceCents;
    }

    private static void requirePositive(long amountCents) {
        if (amountCents <= 0) throw new IllegalArgumentException("金额必须为正");
    }

    public AccountType getAccountType() { return accountType; }
    public long getBalanceCents() { return balanceCents; }
}
