package com.xbb.fund.internal;

import com.xbb.fund.api.AccountType;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EscrowAccountRepository extends JpaRepository<EscrowAccount, AccountType> { }
