package com.xbb.fund.internal;

import com.xbb.fund.api.AccountType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface EscrowAccountRepository extends JpaRepository<EscrowAccount, Long> {

    /** 某家单位的账户。 */
    Optional<EscrowAccount> findByOrgIdAndAccountType(Long orgId, AccountType accountType);

    /** 平台自己的账户(org_id IS NULL)。 */
    Optional<EscrowAccount> findByOrgIdIsNullAndAccountType(AccountType accountType);

    List<EscrowAccount> findByOrgIdOrderByAccountTypeAsc(Long orgId);
}
