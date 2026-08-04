package com.xbb.broker.internal;

import com.xbb.ops.api.OpsApi;
import com.xbb.ops.api.SettingKeys;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 业务员长期不活跃降级。
 *
 * <p>照搬老系统 {@code BrokerDemotionTask} 的语义,两处不同(都是事先说好的):
 * <ul>
 *   <li><b>无下级的不物理删除</b>,改标 {@code DEMOTED}。老系统直接 delete,
 *       删掉之后他名下已产生的佣金归属就断了,出纠纷查不回来 ——
 *       而 M10 文档自己要求"归属变更全程留痕"。</li>
 *   <li><b>每一步都写变更记录</b>,操作人记 null 表示系统自动。</li>
 * </ul>
 *
 * <p>相同的地方:
 * <ul>
 *   <li>有下级的**不摘除,只架空**:下级上提到他的上级,他自己重置活跃时间。
 *       等于给了一轮缓刑 —— 这是老系统刻意的设计,不是 bug。</li>
 *   <li><b>根业务员豁免。</b>老系统用 {@code parBrokerId != 0} 表达,这里是
 *       {@code parentUserId IS NOT NULL}。</li>
 * </ul>
 */
@Component
class BrokerDemotionTask {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(BrokerDemotionTask.class);

    private final BrokerRepository brokers;
    private final OpsApi opsApi;
    private final ObjectProvider<BrokerDemotionTask> self;
    private final BrokerChangeLogRepository changeLogs;

    BrokerDemotionTask(BrokerRepository brokers, OpsApi opsApi,
                       ObjectProvider<BrokerDemotionTask> self,
                       BrokerChangeLogRepository changeLogs) {
        this.brokers = brokers;
        this.opsApi = opsApi;
        this.self = self;
        this.changeLogs = changeLogs;
    }

    /**
     * 每天凌晨 3 点跑一次。
     *
     * <p>**必须经 self 调用**:直接写 {@code run()} 会绕过 Spring 代理、
     * {@code @Transactional} 静默失效(铁律 4 记过,11 个中继都栽在这上面)。
     * 这里每个业务员各自一个事务,所以调用的是 demoteOne 而不是整个 run。
     */
    @Scheduled(cron = "0 0 3 * * ?")
    void scheduled() {
        self.getObject().run();
    }

    /** 返回本次处理的人数。手动触发端点也走这里。 */
    int run() {
        long days = opsApi.settingInt(SettingKeys.BROKER_DEMOTION_DAYS, 90);
        if (days <= 0) {
            log.warn("业务员降级天数配置为 {},本次跳过。要停用降级请把它设成一个很大的数,而不是 0", days);
            return 0;
        }
        Instant threshold = Instant.now().minus(days, ChronoUnit.DAYS);
        List<Broker> candidates = brokers
                .findByLastActiveAtBeforeAndParentUserIdIsNotNullAndStatus(threshold, Broker.Status.ACTIVE);

        log.info("业务员降级任务开始:阈值 {} 天,候选 {} 人", days, candidates.size());
        int done = 0;
        for (Broker b : candidates) {
            try {
                // 一人一个事务:某一个撞乐观锁不该让整批停摆,下次跑还会捞到他。
                self.getObject().demoteOne(b.getUserId());
                done++;
            } catch (RuntimeException e) {
                log.error("业务员 {} 降级失败,留待下次重试", b.getUserId(), e);
            }
        }
        log.info("业务员降级任务结束:成功处理 {} / {} 人", done, candidates.size());
        return done;
    }

    @Transactional(transactionManager = "brokerTransactionManager", propagation = Propagation.REQUIRES_NEW)
    void demoteOne(long userId) {
        Broker b = brokers.findById(userId).orElse(null);
        // 重新读一遍并复查条件:从捞出候选到这一刻,他可能已经活跃过或被人工调整过。
        // 不复查的话会把刚刚活跃的人降掉。
        if (b == null || b.isRoot() || b.getStatus() != Broker.Status.ACTIVE) {
            return;
        }
        List<Broker> children = brokers.findByParentUserId(userId);

        if (!children.isEmpty()) {
            // 有下级:架空而不是摘除 —— 下级上提到他的上级,他自己重置活跃时间(缓刑一轮)。
            Long grandParent = b.getParentUserId();
            for (Broker child : children) {
                Long oldParent = child.getParentUserId();
                child.assignParent(grandParent);
                brokers.save(child);
                changeLogs.save(new BrokerChangeLog(child.getUserId(),
                        BrokerChangeLog.ChangeType.PARENT,
                        String.valueOf(oldParent), String.valueOf(grandParent),
                        null, "上级 #" + userId + " 长期不活跃被架空,自动上提"));
            }
            b.touch();
            brokers.save(b);
            changeLogs.save(new BrokerChangeLog(userId, BrokerChangeLog.ChangeType.STATUS,
                    "ACTIVE", "ACTIVE", null,
                    "长期不活跃,已架空并上提 " + children.size() + " 名下级,活跃时间重置"));
            log.info("业务员 {} 被架空:{} 名下级上提到 {}", userId, children.size(), grandParent);
        } else {
            // 无下级:标记降级。**不删** —— 删掉他名下已产生的佣金归属就断了。
            b.demote();
            brokers.save(b);
            changeLogs.save(new BrokerChangeLog(userId, BrokerChangeLog.ChangeType.STATUS,
                    "ACTIVE", "DEMOTED", null, "长期不活跃且无下级,自动降级"));
            log.info("业务员 {} 已降级(无下级)", userId);
        }
    }
}
