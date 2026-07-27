package com.xbb;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 把 outbox 积压暴露成健康检查项。
 *
 * <p>此前这套东西完全不可观测:某个域的中继卡住、事件堆积,外部看不出任何异常——
 * 而在这个系统里"事件发不出去"意味着**有人的工资单永远不会生成**。
 * 靠人翻日志发现,等于靠运气。
 *
 * <p>有卡死事件(重试超阈值)就报 DOWN,让它进入既有的告警通道,
 * 而不是等对账时才发现。
 */
@Component("outbox")
public class OutboxHealthIndicator implements HealthIndicator {

    private final OutboxAdmin admin;

    OutboxHealthIndicator(OutboxAdmin admin) {
        this.admin = admin;
    }

    @Override
    public Health health() {
        var stuck = admin.stuckEvents();
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("卡死事件数", stuck.size());
        if (!stuck.isEmpty()) {
            Map<String, Long> byDomain = new LinkedHashMap<>();
            stuck.forEach(e -> byDomain.merge(e.domain(), 1L, Long::sum));
            detail.put("按域分布", byDomain);
            detail.put("说明", "这些事件重试已超阈值,下游永远收不到。资金链路上意味着有钱没到账。");
            return Health.down().withDetails(detail).build();
        }
        return Health.up().withDetails(detail).build();
    }
}
