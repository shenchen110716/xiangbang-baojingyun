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
        return credit(type, amountCents, reason, null);
    }

    /**
     * 带幂等键的入账:同一个键只会真正加一次。
     *
     * <p>此前只有 debit 有幂等键,credit 没有 —— 这个不对称很危险:
     * 重复出账被键挡住,重复入账却**凭空造钱**,而且账面上看不出异常
     * (余额多了一笔,流水里两条长得一样的入账)。
     * 内部调用方本来都有业务键可用;真正逼出这一条的是把入账开成 HTTP 端点:
     * 网络超时重试、用户双击,都会变成又造一笔。
     */
    @Transactional("fundTransactionManager")
    public long credit(AccountType type, long amountCents, String reason, String idempotencyKey) {
        EscrowAccount account = load(type);
        if (idempotencyKey != null && ledger.existsByIdempotencyKey(idempotencyKey)) {
            return account.getBalanceCents();   // 这笔加过了
        }
        long before = account.getBalanceCents();
        long after = account.credit(amountCents);
        accounts.save(account);
        ledger.save(new EscrowLedgerEntry(type, before, amountCents, after, reason, idempotencyKey));
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
