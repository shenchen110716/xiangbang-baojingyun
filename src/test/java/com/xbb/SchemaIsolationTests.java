package com.xbb;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 铁律 1(独立 schema、最小权限)的守卫。
 *
 * <p>之前这个类只验了 20 个域里的 **identity 一个**,其余 19 个域的数据库用户
 * 有没有被误授权限完全没有覆盖——而文档写的是"CI 强制,不靠自觉"。
 * 现在对每个域都生成一组用例。
 */
@SpringBootTest
@Import(TestcontainersConfig.class)
class SchemaIsolationTests {

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        TestcontainersConfig.registerProperties(registry);
    }

    /** 独立写一份域清单,好让它和配置不一致时也会失败(见最后一条用例)。 */
    private static final List<String> DOMAINS = List.of(
            "identity", "org", "job", "engagement", "settlement", "fund", "broker", "profile",
            "matching", "review", "agreement", "voice", "talent", "notification", "content",
            "collab", "reimbursement", "mall", "ops", "reporting", "attendance");

    @Autowired ApplicationContext context;

    private DataSource dataSourceOf(String domain) {
        return context.getBean(domain + "DataSource", DataSource.class);
    }

    private static String queryOne(DataSource ds, String sql) throws SQLException {
        try (Connection c = ds.getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            assertThat(rs.next()).as("查询 %s 应有结果行", sql).isTrue();
            return rs.getString(1);
        }
    }

    @TestFactory
    Stream<DynamicTest> 每个域的数据源都必须是它自己的受限用户() {
        return DOMAINS.stream().map(domain -> DynamicTest.dynamicTest(domain, () -> {
            assertThat(queryOne(dataSourceOf(domain), "SELECT current_user"))
                    .isEqualTo(domain + "_user");
            // 超级用户会绕过一切 schema 授权,那样铁律 1 等于不存在
            assertThat(queryOne(dataSourceOf(domain), "SELECT current_setting('is_superuser')"))
                    .isEqualTo("off");
        }));
    }

    @TestFactory
    Stream<DynamicTest> 每个域都读不到别的域的表() {
        return DOMAINS.stream().map(domain -> DynamicTest.dynamicTest(domain, () -> {
            DataSource ds = dataSourceOf(domain);
            for (String other : DOMAINS) {
                if (other.equals(domain)) {
                    continue;
                }
                // 每个域都有 flyway_schema_history,拿它当探针最稳
                assertThatThrownBy(() -> queryOne(ds,
                        "SELECT 1 FROM " + other + ".flyway_schema_history LIMIT 1"))
                        .as("%s 竟然能读 %s 的表", domain, other)
                        .isInstanceOf(SQLException.class)
                        .hasMessageContaining("permission denied");
            }
        }));
    }

    @Test
    void 域清单必须覆盖所有已配置的数据源() {
        // 漏写一个域,上面两组用例就会静默跳过它——这条防的正是"清单忘了更新"
        List<String> configured = List.of(context.getBeanNamesForType(DataSource.class));
        assertThat(configured)
                .as("有数据源没被隔离用例覆盖")
                .containsExactlyInAnyOrderElementsOf(DOMAINS.stream().map(d -> d + "DataSource").toList());
    }
}
