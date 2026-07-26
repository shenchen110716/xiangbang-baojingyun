package com.xbb.fund.internal;

import com.xbb.fund.api.AccountType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EscrowLedgerRepository extends JpaRepository<EscrowLedgerEntry, Long> {

    List<EscrowLedgerEntry> findByAccountTypeOrderByIdAsc(AccountType accountType);
}
