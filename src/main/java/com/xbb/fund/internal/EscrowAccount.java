package com.xbb.fund.internal;

import com.xbb.fund.api.AccountType;
import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "escrow_account", schema = "fund")
public class EscrowAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 账户归属的单位。<b>null = 平台自己的账户。</b>
     *
     * <p>沿用本代码库已有的写法(commission_scheme / commission_rate 都是
     * "NULL 表示平台默认"),而不是用 0 当哨兵 —— 0 是个合法的组织 id。
     */
    @Column(name = "org_id")
    private Long orgId;

    @Enumerated(EnumType.STRING)
    @Column(name = "account_type", length = 20, nullable = false)
    private AccountType accountType;

    @Column(name = "balance_cents", nullable = false)
    private long balanceCents;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Version
    private long version;

    protected EscrowAccount() { }

    /** @param orgId null 表示平台自己的账户 */
    EscrowAccount(Long orgId, AccountType accountType) {
        this.orgId = orgId;
        this.accountType = accountType;
    }

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
            // 报错里带上是谁的账户 —— 按单位分账之后,只说"余额不足"
            // 会让人去查错那一家的账
            throw new IllegalStateException("%s的%s账户余额不足,当前 %d 分,需要 %d 分"
                    .formatted(orgId == null ? "平台" : "单位 #" + orgId,
                            accountType, this.balanceCents, amountCents));
        }
        this.balanceCents -= amountCents;
        this.updatedAt = Instant.now();
        return this.balanceCents;
    }

    private static void requirePositive(long amountCents) {
        if (amountCents <= 0) throw new IllegalArgumentException("金额必须为正");
    }

    public AccountType getAccountType() { return accountType; }
    /** @return null 表示平台自己的账户 */
    public Long getOrgId() { return orgId; }
    public long getBalanceCents() { return balanceCents; }
}
