package com.xbb.baojing.recharge;

import com.xbb.baojing.plan.PricingService;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** Ports backend/services/recharge.py — the account-pooling primitives:
 * resolving which collection account an insurer name is currently linked
 * to, and reading/creating an enterprise's balance within a pooled account. */
@Service
public class RechargeService {
    private final InsurerAccountMapper accountMapper;
    private final InsurerAccountLinkMapper linkMapper;
    private final EnterprisePremiumAccountMapper premiumAccountMapper;

    public RechargeService(InsurerAccountMapper accountMapper, InsurerAccountLinkMapper linkMapper, EnterprisePremiumAccountMapper premiumAccountMapper) {
        this.accountMapper = accountMapper;
        this.linkMapper = linkMapper;
        this.premiumAccountMapper = premiumAccountMapper;
    }

    public InsurerAccount resolveAccountForInsurer(String insurer) {
        InsurerAccountLink link = linkMapper.findByInsurer(insurer);
        if (link == null) return null;
        InsurerAccount account = accountMapper.findById(link.getAccountId());
        return account != null && "active".equals(account.getStatus()) ? account : null;
    }

    public List<String> insurersForAccount(Integer accountId) {
        return linkMapper.findInsurersByAccount(accountId);
    }

    /** Populates the response-only `insurers` field on the given account and returns it,
     * mirroring insurer_account_dict() in Python (which merges serialize(item) with an
     * "insurers" key). */
    public InsurerAccount withInsurers(InsurerAccount account) {
        account.setInsurers(insurersForAccount(account.getId()));
        return account;
    }

    public EnterprisePremiumAccount getOrCreatePremiumAccount(Integer enterpriseId, Integer accountId) {
        EnterprisePremiumAccount row = premiumAccountMapper.findByEnterpriseAndAccount(enterpriseId, accountId);
        if (row != null) return row;
        row = new EnterprisePremiumAccount();
        row.setEnterpriseId(enterpriseId);
        row.setAccountId(accountId);
        row.setBalance(0);
        premiumAccountMapper.insert(row);
        return row;
    }

    public record PremiumAccountRow(Integer accountId, String label, List<String> insurers, double balance) {}

    public List<PremiumAccountRow> premiumAccountsForEnterprise(Integer enterpriseId) {
        List<PremiumAccountRow> result = new ArrayList<>();
        for (EnterprisePremiumAccount row : premiumAccountMapper.findByEnterprise(enterpriseId)) {
            InsurerAccount account = accountMapper.findById(row.getAccountId());
            if (account == null) continue;
            result.add(new PremiumAccountRow(row.getAccountId(), account.getLabel(), insurersForAccount(row.getAccountId()), PricingService.amount(row.getBalance())));
        }
        return result;
    }

    /** Idempotent, run-every-startup backfill: for any enterprise with a nonzero legacy
     * premiumBalance and no EnterprisePremiumAccount rows yet, seed one against a shared
     * placeholder InsurerAccount. Ports backend/core/migrations.py::migrate_premium_balances. */
    public InsurerAccount getOrCreatePlaceholderAccount() {
        InsurerAccount placeholder = accountMapper.findByLabel("未分类（历史余额）");
        if (placeholder != null) return placeholder;
        placeholder = new InsurerAccount();
        placeholder.setLabel("未分类（历史余额）");
        placeholder.setBankName("");
        placeholder.setAccountNo("");
        placeholder.setAccountHolder("");
        placeholder.setStatus("paused");
        placeholder.setCreatedAt(LocalDateTime.now());
        accountMapper.insert(placeholder);
        return placeholder;
    }

    public boolean hasPremiumAccounts(Integer enterpriseId) {
        return !premiumAccountMapper.findByEnterprise(enterpriseId).isEmpty();
    }

    public void seedLegacyBalance(Integer enterpriseId, Integer placeholderAccountId, double legacyBalance) {
        EnterprisePremiumAccount row = new EnterprisePremiumAccount();
        row.setEnterpriseId(enterpriseId);
        row.setAccountId(placeholderAccountId);
        row.setBalance(legacyBalance);
        premiumAccountMapper.insert(row);
    }
}
