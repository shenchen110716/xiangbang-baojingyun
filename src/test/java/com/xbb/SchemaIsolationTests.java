package com.xbb;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Import(TestcontainersConfig.class)
class SchemaIsolationTests {

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        TestcontainersConfig.registerProperties(registry);
    }

    /** 应用真实使用的数据源(application.yml 配置的 identity_user)。 */
    @Autowired
    @Qualifier("identityDataSource")
    DataSource appDataSource;

    @BeforeAll
    static void createForeignSchema() throws SQLException {
        // 以管理员身份造一个"别的域"的 schema,模拟 Plan 2 之后的邻居
        try (Connection admin = DriverManager.getConnection(
                     TestcontainersConfig.PG.getJdbcUrl(),
                     TestcontainersConfig.PG.getUsername(),
                     TestcontainersConfig.PG.getPassword());
             Statement st = admin.createStatement()) {
            st.execute("CREATE SCHEMA IF NOT EXISTS ledger");
            st.execute("CREATE TABLE IF NOT EXISTS ledger.account("
                    + "id BIGSERIAL PRIMARY KEY, balance_cents BIGINT)");
            // 刻意不给 identity_user 任何 ledger 权限
        }
    }

    @Test
    void 应用数据源可以读本域表() throws Exception {
        try (Connection c = appDataSource.getConnection();
             Statement st = c.createStatement()) {
            assertThat(st.executeQuery("SELECT count(*) FROM identity.app_user").next()).isTrue();
        }
    }

    @Test
    void 应用数据源跨域读取必须被数据库拒绝() {
        assertThatThrownBy(() -> {
            try (Connection c = appDataSource.getConnection();
                 Statement st = c.createStatement()) {
                st.executeQuery("SELECT count(*) FROM ledger.account");
            }
        }).isInstanceOf(SQLException.class)
          .hasMessageContaining("permission denied");
    }

    @Test
    void 应用数据源不是超级用户() throws Exception {
        try (Connection c = appDataSource.getConnection();
             Statement st = c.createStatement()) {
            var rs = st.executeQuery("SELECT current_user");
            rs.next();
            assertThat(rs.getString(1)).isEqualTo("identity_user");
        }
    }
}
