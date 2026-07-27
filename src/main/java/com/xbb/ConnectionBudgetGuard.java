package com.xbb;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * 启动时把连接预算算给人看。
 *
 * <p>每个域一个独立 DataSource,单实例的连接上限是 **域数 × 每域上限**。
 * 这个乘法此前没有任何地方写出来,而它决定了能不能水平扩容:
 * 20 个域 × 3 = 60,PostgreSQL 默认 max_connections 是 100,
 * **两个实例就超上限**——扩容会以"连不上数据库"的形式失败,
 * 而不是以"配置不足"的形式提示。
 *
 * <p>这里不做拦截(数据库真实上限我读不到),只把算式打出来,
 * 让"要调 max_connections"这件事在部署前就被看见。
 */
@Component
public class ConnectionBudgetGuard implements ApplicationListener<ApplicationReadyEvent> {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(ConnectionBudgetGuard.class);

    private final Environment env;

    ConnectionBudgetGuard(Environment env) {
        this.env = env;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        int perDomain = env.getProperty("xbb.datasource.max-pool-size-per-domain", Integer.class, 3);
        int domains = event.getApplicationContext()
                .getBeanNamesForType(javax.sql.DataSource.class).length;
        int perInstance = domains * perDomain;
        log.info("连接预算:{} 个域 × 每域 {} 条 = 单实例最多 {} 条。"
                        + "数据库的 max_connections 必须 ≥ 实例数 × {},否则扩容会直接连不上。"
                        + "(minimumIdle=0,所以这是上限不是常驻量)",
                domains, perDomain, perInstance, perInstance);
    }
}
