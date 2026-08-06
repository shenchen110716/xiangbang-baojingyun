package com.xbb.fund.internal;

import com.xbb.fund.api.AccountType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 监管账户的加减与流水。
 *
 * <p><b>账户按单位分账</b>(老板 2026-08-06):每家机构一行余额,扣款只能扣自己那行。
 * {@code orgId} 传 null 表示平台自己的账户。
 *
 * <p>不带 orgId 的重载全部委托给 {@code (null, …)} —— 它们是**同一条实现**,
 * 不是第二条写入路径。这个项目已经在"同一个概念两条写入路径"上栽过两次。
 */
@Service
class EscrowService {

    private final EscrowAccountRepository accounts;
    private final EscrowLedgerRepository ledger;

    EscrowService(EscrowAccountRepository accounts, EscrowLedgerRepository ledger) {
        this.accounts = accounts;
        this.ledger = ledger;
    }

    // ─────────────── 入账 ───────────────

    @Transactional("fundTransactionManager")
    public long credit(AccountType type, long amountCents, String reason) {
        return credit(null, type, amountCents, reason, null);
    }

    @Transactional("fundTransactionManager")
    public long credit(AccountType type, long amountCents, String reason, String idempotencyKey) {
        return credit(null, type, amountCents, reason, idempotencyKey);
    }

    @Transactional("fundTransactionManager")
    public long credit(Long orgId, AccountType type, long amountCents,
                       String reason, String idempotencyKey) {
        // **入账时账户不存在就建。**机构第一次充值时它当然还不存在;
        // 要求"先开户再充值"只是多一步没有业务含义的操作
        EscrowAccount account = loadOrCreate(orgId, type);
        if (idempotencyKey != null && ledger.existsByIdempotencyKey(idempotencyKey)) {
            return account.getBalanceCents();   // 这笔加过了
        }
        long before = account.getBalanceCents();
        long after = account.credit(amountCents);
        accounts.save(account);
        ledger.save(new EscrowLedgerEntry(orgId, type, before, amountCents, after, reason, idempotencyKey));
        return after;
    }

    // ─────────────── 出账 ───────────────

    @Transactional("fundTransactionManager")
    public long debit(AccountType type, long amountCents, String reason) {
        return debit(null, type, amountCents, reason, null);
    }

    @Transactional("fundTransactionManager")
    public long debit(AccountType type, long amountCents, String reason, String idempotencyKey) {
        return debit(null, type, amountCents, reason, idempotencyKey);
    }

    @Transactional("fundTransactionManager")
    public long debit(Long orgId, AccountType type, long amountCents,
                      String reason, String idempotencyKey) {
        // **出账时不建账户。**一个从没入过账的账户余额是 0,建出来也只能被拒;
        // 建了反而会让"这家单位有账户"这件事变成假的
        EscrowAccount account = load(orgId, type);
        if (idempotencyKey != null && ledger.existsByIdempotencyKey(idempotencyKey)) {
            return account.getBalanceCents();   // 这笔付过了
        }
        long before = account.getBalanceCents();
        long after = account.debit(amountCents);
        accounts.save(account);
        // 出金记负数变动额,流水读起来方向一目了然
        ledger.save(new EscrowLedgerEntry(orgId, type, before, -amountCents, after, reason, idempotencyKey));
        return after;
    }

    // ─────────────── 查询 ───────────────

    @Transactional(transactionManager = "fundTransactionManager", readOnly = true)
    public long balanceOf(AccountType type) {
        return balanceOf(null, type);
    }

    /** 账户还没建过时返回 0 —— 那和"余额是 0"在业务上是一回事。 */
    @Transactional(transactionManager = "fundTransactionManager", readOnly = true)
    public long balanceOf(Long orgId, AccountType type) {
        return find(orgId, type).map(EscrowAccount::getBalanceCents).orElse(0L);
    }

    private java.util.Optional<EscrowAccount> find(Long orgId, AccountType type) {
        return orgId == null
                ? accounts.findByOrgIdIsNullAndAccountType(type)
                : accounts.findByOrgIdAndAccountType(orgId, type);
    }

    private EscrowAccount load(Long orgId, AccountType type) {
        return find(orgId, type).orElseThrow(() -> new IllegalStateException(
                "%s还没有%s账户,余额为 0,无法出金"
                        .formatted(orgId == null ? "平台" : "单位 #" + orgId, type)));
    }

    private EscrowAccount loadOrCreate(Long orgId, AccountType type) {
        return find(orgId, type).orElseGet(() -> accounts.save(new EscrowAccount(orgId, type)));
    }
}
