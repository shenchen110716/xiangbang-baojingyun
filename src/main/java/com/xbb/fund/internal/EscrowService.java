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
        ledger.save(new EscrowLedgerEntry(type, before, amountCents, after, reason));
        return after;
    }

    @Transactional("fundTransactionManager")
    public long debit(AccountType type, long amountCents, String reason) {
        EscrowAccount account = load(type);
        long before = account.getBalanceCents();
        long after = account.debit(amountCents);
        accounts.save(account);
        // 出金记负数变动额,流水读起来方向一目了然
        ledger.save(new EscrowLedgerEntry(type, before, -amountCents, after, reason));
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
