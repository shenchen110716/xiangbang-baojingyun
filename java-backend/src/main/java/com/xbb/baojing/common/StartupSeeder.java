package com.xbb.baojing.common;

import com.xbb.baojing.enterprise.Enterprise;
import com.xbb.baojing.enterprise.EnterpriseMapper;
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

    public StartupSeeder(UserMapper userMapper, EnterpriseMapper enterpriseMapper, PasswordEncoder passwordEncoder, AppProperties props) {
        this.userMapper = userMapper;
        this.enterpriseMapper = enterpriseMapper;
        this.passwordEncoder = passwordEncoder;
        this.props = props;
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
    }
}
