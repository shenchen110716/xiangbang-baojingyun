package com.xbb.fund.internal;

import com.xbb.fund.api.AccountType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class EscrowService {

    private final EscrowAccountRepository accounts;
    private final EscrowLedgerRepository ledger;

    EscrowService(EscrowAccountRepository accounts, EscrowLedgerRepository ledger) {
        this.accounts = accounts;
        this.ledger = ledger;
    }

    @Transactional("fundTransactionManager")
    public long credit(AccountType type, long amountCents, String reason) {
        EscrowAccount account = load(type);
        long before = account.getBalanceCents();
        long after = account.credit(amountCents);
        accounts.save(account);
        ledger.save(new EscrowLedgerEntry(type, before, amountCents, after, reason, null));
        return after;
    }

    @Transactional("fundTransactionManager")
    public long debit(AccountType type, long amountCents, String reason) {
        return debit(type, amountCents, reason, null);
    }

    /**
     * 带幂等键的出账:同一个键只会真正扣一次。
     *
     * <p>为什么必须有:调用方(如报销域)与资金域是两个事务。钱出去之后调用方那边
     * 失败回滚,单据退回可再次审批——不带键的话就会再扣一次,而且流水里没有业务键,
     * 事后根本认不出哪笔是重复的。
     */
    @Transactional("fundTransactionManager")
    public long debit(AccountType type, long amountCents, String reason, String idempotencyKey) {
        EscrowAccount account = load(type);
        if (idempotencyKey != null && ledger.existsByIdempotencyKey(idempotencyKey)) {
            return account.getBalanceCents();   // 这笔付过了
        }
        long before = account.getBalanceCents();
        long after = account.debit(amountCents);
        accounts.save(account);
        // 出金记负数变动额,流水读起来方向一目了然
        ledger.save(new EscrowLedgerEntry(type, before, -amountCents, after, reason, idempotencyKey));
        return after;
    }

    @Transactional(transactionManager = "fundTransactionManager", readOnly = true)
    public long balanceOf(AccountType type) {
        return load(type).getBalanceCents();
    }

    private EscrowAccount load(AccountType type) {
        return accounts.findById(type)
                .orElseThrow(() -> new IllegalStateException("监管账户不存在: " + type));
    }
}
