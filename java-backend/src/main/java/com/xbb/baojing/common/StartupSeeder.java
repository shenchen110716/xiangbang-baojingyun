package com.xbb.baojing.common;

import com.xbb.baojing.enterprise.Enterprise;
import com.xbb.baojing.enterprise.EnterpriseMapper;
import com.xbb.baojing.recharge.InsurerAccount;
import com.xbb.baojing.recharge.RechargeService;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/** Ports backend/core/seed.py — creates the default admin account (and a
 * demo enterprise + enterprise account) on first boot, and backfills a
 * 单位主管 (is_owner) for any legacy enterprise that doesn't have one. Runs
 * after Flyway migrations (both are driven by Spring's startup sequence;
 * CommandLineRunner beans run after the context, including
 * DataSource/Flyway initialization, is fully up). */
@Component
public class StartupSeeder implements CommandLineRunner {
    private final UserMapper userMapper;
    private final EnterpriseMapper enterpriseMapper;
    private final PasswordEncoder passwordEncoder;
    private final AppProperties props;
    private final RechargeService rechargeService;

    public StartupSeeder(UserMapper userMapper, EnterpriseMapper enterpriseMapper, PasswordEncoder passwordEncoder,
                          AppProperties props, RechargeService rechargeService) {
        this.userMapper = userMapper;
        this.enterpriseMapper = enterpriseMapper;
        this.passwordEncoder = passwordEncoder;
        this.props = props;
        this.rechargeService = rechargeService;
    }

    @Override
    public void run(String... args) {
        if (userMapper.findByUsername("admin") == null) {
            User admin = new User();
            admin.setUsername("admin");
            admin.setPasswordHash(passwordEncoder.encode(props.getAdminPassword()));
            admin.setName("响帮帮管理员");
            admin.setRole("admin");
            admin.setCreatedAt(LocalDateTime.now());
            userMapper.insert(admin);
        }

        if (userMapper.findByUsername("enterprise") == null) {
            Enterprise demo = enterpriseMapper.findFirst();
            if (demo == null) {
                demo = new Enterprise();
                demo.setName("演示参保单位");
                demo.setKind("企业");
                demo.setContact("演示管理员");
                demo.setStatus("active");
                demo.setCreatedAt(LocalDateTime.now());
                enterpriseMapper.insert(demo);
            }
            User enterpriseUser = new User();
            enterpriseUser.setUsername("enterprise");
            enterpriseUser.setPasswordHash(passwordEncoder.encode(props.getEnterprisePassword()));
            enterpriseUser.setName(demo.getName() + "管理员");
            enterpriseUser.setRole("enterprise");
            enterpriseUser.setEnterpriseId(demo.getId());
            enterpriseUser.setOwner(true);
            enterpriseUser.setCreatedAt(LocalDateTime.now());
            userMapper.insert(enterpriseUser);
        }

        List<Integer> enterpriseIds = userMapper.findDistinctEnterpriseIdsWithUsers();
        for (Integer enterpriseId : enterpriseIds) {
            boolean hasOwner = userMapper.findOwners(enterpriseId).stream().findAny().isPresent();
            if (!hasOwner) {
                List<User> operators = userMapper.findOperators(enterpriseId);
                if (!operators.isEmpty()) {
                    User owner = operators.get(operators.size() - 1); // oldest (findOperators orders id DESC)
                    owner.setOwner(true);
                    userMapper.update(owner);
                }
            }
        }

        migratePremiumBalances();
    }

    /** Ports backend/core/migrations.py::migrate_premium_balances — Enterprise.premiumBalance
     * stops being read/written; any legacy nonzero balance is backfilled once into a shared
     * placeholder InsurerAccount so it isn't silently lost. Idempotent: enterprises that
     * already have EnterprisePremiumAccount rows are skipped, safe to rerun every startup. */
    private void migratePremiumBalances() {
        InsurerAccount placeholder = null;
        for (Enterprise enterprise : enterpriseMapper.search(null, null, null)) {
            if (enterprise.getPremiumBalance() == 0) continue;
            if (rechargeService.hasPremiumAccounts(enterprise.getId())) continue;
            if (placeholder == null) placeholder = rechargeService.getOrCreatePlaceholderAccount();
            rechargeService.seedLegacyBalance(enterprise.getId(), placeholder.getId(), enterprise.getPremiumBalance());
        }
    }
}
