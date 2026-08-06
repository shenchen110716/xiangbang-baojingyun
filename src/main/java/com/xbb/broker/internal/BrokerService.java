package com.xbb.broker.internal;

import com.xbb.broker.api.BrokerApi;
import com.xbb.broker.api.BrokerRegistered;
import com.xbb.broker.api.CommissionPaid;
import com.xbb.broker.api.WorkerBound;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xbb.identity.api.IdentityApi;
import com.xbb.identity.api.Role;
import org.springframework.security.access.AccessDeniedException;
import com.xbb.fund.api.AccountType;
import com.xbb.fund.api.FundApi;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Service
class BrokerService implements BrokerApi {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(BrokerService.class);

    private final BrokerRepository brokers;
    private final StationRepository stations;
    private final StationJointRepository joints;
    private final StationRateRepository stationRates;
    private final StationRateChangeRepository rateChanges;
    private final CommissionSchemeRepository schemes;
    private final CommissionSchemeChangeRepository schemeChanges;
    /** 按「类目 + 地区」取佣金比例,从细到粗回退。 */
    private final CommissionRateResolver rateResolver;
    private final CommissionRateRepository rates;
    private final CommissionRateChangeRepository rateChangeRepo;
    private final StationCooperationRepository cooperations;
    private final CooperationOperatorRepository operators;
    private final com.xbb.org.api.OrgApi orgApi;
    private final ShareUpgradeService shareUpgrades;
    private final BrokerOriginRepository origins;
    private final BrokerChangeLogRepository changeLogs;
    private final com.xbb.ops.api.OpsApi opsApi;
    private final org.springframework.beans.factory.ObjectProvider<BrokerDemotionTask> demotionTask;
    private final InvitationRepository invitations;
    private final CommissionRepository commissions;
    private final BrokerVerifiedUserRepository verifiedUsers;
    private final BrokerOutboxRepository outbox;
    private final ObjectMapper json;
    private final IdentityApi identityApi;
    private final FundApi fundApi;

    BrokerService(BrokerRepository brokers, InvitationRepository invitations, CommissionRepository commissions,
                  BrokerVerifiedUserRepository verifiedUsers,
                     BrokerOutboxRepository outbox, ObjectMapper json,
                       IdentityApi identityApi, FundApi fundApi,
                  StationRepository stations, StationJointRepository joints,
                  StationRateRepository stationRates, StationRateChangeRepository rateChanges,
                  CommissionSchemeRepository schemes, CommissionSchemeChangeRepository schemeChanges,
                  CommissionRateResolver rateResolver,
                  CommissionRateRepository rates,
                  CommissionRateChangeRepository rateChangeRepo,
                  StationCooperationRepository cooperations, CooperationOperatorRepository operators,
                  com.xbb.org.api.OrgApi orgApi,
                  ShareUpgradeService shareUpgrades, BrokerOriginRepository origins,
                  BrokerChangeLogRepository changeLogs,
                  com.xbb.ops.api.OpsApi opsApi,
                  org.springframework.beans.factory.ObjectProvider<BrokerDemotionTask> demotionTask) {
        this.demotionTask = demotionTask;
        this.stations = stations;
        this.joints = joints;
        this.stationRates = stationRates;
        this.rateChanges = rateChanges;
        this.schemes = schemes;
        this.schemeChanges = schemeChanges;
        this.rateResolver = rateResolver;
        this.rates = rates;
        this.rateChangeRepo = rateChangeRepo;
        this.cooperations = cooperations;
        this.operators = operators;
        this.orgApi = orgApi;
        this.shareUpgrades = shareUpgrades;
        this.origins = origins;
        this.changeLogs = changeLogs;
        this.opsApi = opsApi;
        this.brokers = brokers;
        this.invitations = invitations;
        this.commissions = commissions;
        this.verifiedUsers = verifiedUsers;
        this.outbox = outbox;
        this.json = json;
        this.identityApi = identityApi;
        this.fundApi = fundApi;
    }

    /**
     * 平台运维操作,要求 {@link Role#PLATFORM_OPS}。
     *
     * <p>这不是归属校验的替代品,而是它缺席时唯一说得通的东西:这几个动作的
     * "主人"是平台自己,不是某个用户。角色每次向身份域现查,不读 JWT 声明,
     * 这样收回权限立刻生效(理由同 OutboxOpsController)。
     */
    private void requirePlatformOps(long callerUserId) {
        if (!identityApi.hasRole(callerUserId, Role.PLATFORM_OPS)) {
            throw new AccessDeniedException("需要平台运维权限");
        }
    }

    private String serialize(Object event) {
        try {
            return json.writeValueAsString(event);
        } catch (Exception e) {
            // 序列化不了就别让这步业务成功——事件发不出去,下游永远补不回来
            throw new IllegalStateException("事件无法序列化: " + event, e);
        }
    }

    @Override
    @Transactional("brokerTransactionManager")
    public void registerBroker(long userId) {
        if (verifiedUsers.findById(userId).isEmpty()) {
            throw new IllegalStateException("需要完成实名认证才能注册经纪人");
        }
        if (brokers.existsById(userId)) {
            throw new IllegalStateException("已经是经纪人,不可重复注册");
        }
        brokers.save(new Broker(userId));
        origins.save(new BrokerOrigin(userId, BrokerOrigin.Origin.SELF, null, null));
        BrokerRegistered registered = new BrokerRegistered(userId, Instant.now());
        outbox.save(new BrokerOutboxEvent(java.util.UUID.randomUUID().toString(),
                BrokerRegistered.class.getName(), serialize(registered)));
    }

    @Override
    @Transactional("brokerTransactionManager")
    public long bindWorker(long brokerUserId, long workerUserId) {
        if (!brokers.existsById(brokerUserId)) {
            throw new IllegalStateException("调用者不是经纪人");
        }
        if (verifiedUsers.findById(workerUserId).isEmpty()) {
            throw new IllegalStateException("工人需要完成实名认证才能被绑定");
        }
        // 唯一约束(worker_user_id UNIQUE)在数据库层兜底并发抢绑;
        // 这里先应用层查一次给出更友好的错误信息,DataIntegrityViolationException
        // 交给 com.xbb.web.GlobalExceptionHandler 兜底处理并发窗口内的漏网之鱼。
        if (invitations.findByWorkerUserId(workerUserId).isPresent()) {
            throw new IllegalStateException("该工人已经绑定过经纪人");
        }
        Invitation invitation = invitations.save(new Invitation(brokerUserId, workerUserId));
        WorkerBound bound = new WorkerBound(invitation.getId(), brokerUserId, workerUserId, Instant.now());
        outbox.save(new BrokerOutboxEvent(java.util.UUID.randomUUID().toString(),
                WorkerBound.class.getName(), serialize(bound)));
        return invitation.getId();
    }

    @Override
    @Transactional("brokerTransactionManager")
    public void payCommission(long commissionId, long callerUserId) {
        requirePlatformOps(callerUserId);
        Commission commission = commissions.findById(commissionId)
                .orElseThrow(() -> new IllegalArgumentException("佣金记录不存在"));
        // **钱必须真的从平台账户出去。** 之前这里只把记录标成已付、发个事件,
        // 没有任何账户被扣:平台对外承诺付出的比账上实际出的多,账实不符;
        // 而 CommissionPaid 还没有任何订阅者,也就没人会去补这一步。
        // §4.1 决策#1:资金域是唯一动钱者,所以走它的接口,不自己记账。
        fundApi.spendFromAccount(AccountType.PLATFORM_REVENUE, commission.getAmountCents(),
                "经纪人佣金 commission#" + commissionId, "commission-" + commissionId);

        commission.pay();
        commissions.save(commission);

        // **事件只对"发给人的"那几档发。**
        // 服务站档与联合档是发给组织的,brokerUserId 天然是 null,
        // 而 CommissionPaid 的该字段是 long —— 直接传进去会拆箱 NPE,
        // 结果是**服务站挣的佣金一付就炸,永远付不出去**。
        //
        // 这条路以前没暴露过:主动佣金(给人的)一直是好的,
        // 而服务站那一档是后来才有的,没人真的去付过。
        // 发生 NPE 的是发事件这一步,钱其实已经从平台账户出去了 ——
        // 事务会回滚,但这类"半步失败"最难查:界面只报一个 500。
        Long payee = commission.getBrokerUserId();
        if (payee != null) {
            CommissionPaid paid = new CommissionPaid(
                    commissionId, payee, commission.getAmountCents(), Instant.now());
            outbox.save(new BrokerOutboxEvent(java.util.UUID.randomUUID().toString(),
                    CommissionPaid.class.getName(), serialize(paid)));
        } else {
            log.info("服务站/联合档佣金已支付,不发 CommissionPaid(它按人聚合):commission={} 站={}",
                    commissionId, commission.getStationOrgId());
        }
    }

    @Override
    @Transactional(transactionManager = "brokerTransactionManager", readOnly = true)
    public Optional<BrokerView> findBroker(long userId) {
        return Optional.of(new BrokerView(userId, brokers.existsById(userId)));
    }

    @Override
    @Transactional(transactionManager = "brokerTransactionManager", readOnly = true)
    public Optional<CommissionView> findCommission(long commissionId, long callerUserId) {
        return commissions.findById(commissionId)
                // 挣这笔佣金的人本人,或平台运维。看不到时返回空而不是"无权访问" ——
                // 后者会顺带确认这条记录存在
                .filter(c -> (c.getBrokerUserId() != null && c.getBrokerUserId() == callerUserId)
                        || identityApi.hasRole(callerUserId, com.xbb.identity.api.Role.PLATFORM_OPS))
                .map(c -> new CommissionView(
                c.getId(), c.getBrokerUserId(), c.getWorkerUserId(), c.getSettlementId(),
                c.getAmountCents(), c.getStatus()));
    }

    // ─────────────── 分享与业务员产生 ───────────────

    @Override
    public String share(long sharerUserId, String targetType, long targetId) {
        return shareUpgrades.share(sharerUserId, targetType, targetId);
    }

    @Override
    public boolean attributeShare(String code, long convertedUserId) {
        return shareUpgrades.attribute(code, convertedUserId);
    }

    /**
     * 站长授权某人成为业务员,直接挂在本站下。
     *
     * <p>**只有站长本人能授权** —— 这是在往自己站里加一个能分佣金的人。
     * 平台运维也放行:出问题时要有人能兜底。
     */
    @Override
    @Transactional("brokerTransactionManager")
    public void grantBroker(long stationOrgId, long userId, long callerUserId) {
        if (!isStationLegalRep(stationOrgId, callerUserId)
                && !identityApi.hasRole(callerUserId, Role.PLATFORM_OPS)) {
            throw new IllegalStateException("只有服务站站长可以授权业务员");
        }
        if (verifiedUsers.findById(userId).isEmpty()) {
            // 业务员要被记进佣金归属、要能收钱,没实名的话这些都无从追溯
            throw new IllegalStateException("需要完成实名认证才能成为业务员");
        }
        Broker existing = brokers.findById(userId).orElse(null);
        if (existing != null) {
            // 已经是业务员:不重复建,但**可以把他划到这个站下** ——
            // 抛异常的话,站长面对一个"已是别站业务员"的人完全没有办法
            existing.assignStation(stationOrgId);
            brokers.save(existing);
            log.info("业务员 {} 已存在,改划到服务站 {}", userId, stationOrgId);
            return;
        }
        Broker broker = new Broker(userId);
        broker.assignStation(stationOrgId);
        brokers.save(broker);
        origins.save(new BrokerOrigin(userId, BrokerOrigin.Origin.STATION_GRANT,
                stationOrgId, callerUserId));
        BrokerRegistered registered = new BrokerRegistered(userId, Instant.now());
        outbox.save(new BrokerOutboxEvent(java.util.UUID.randomUUID().toString(),
                BrokerRegistered.class.getName(), serialize(registered)));
        log.info("站长授权业务员:user={} 服务站={} 授权人={}", userId, stationOrgId, callerUserId);
    }

    @Override
    @Transactional(transactionManager = "brokerTransactionManager", readOnly = true)
    public java.util.Optional<BrokerOriginView> brokerOrigin(long userId, long callerUserId) {
        // 本人或平台运维。别人凭什么是业务员,不该给无关的人看(铁律 5.1)
        if (userId != callerUserId && !identityApi.hasRole(callerUserId, Role.PLATFORM_OPS)) {
            return java.util.Optional.empty();
        }
        return origins.findById(userId)
                .map(o -> new BrokerOriginView(o.getUserId(), o.getOrigin().name(),
                        o.getSourceRef(), o.getGrantedBy(), o.getCreatedAt()));
    }

    // ─────────────── 按业务类目的分成比例 ───────────────

    /**
     * 设分成比例。{@code stationOrgId} 为 null 表示设平台默认。
     *
     * <p>**要平台运维。**这是在改钱怎么分,不该由服务站自己说了算 ——
     * 站长能改自己的比例的话,这个数字就没有约束力了。
     */
    @Override
    @Transactional("brokerTransactionManager")
    public void setStationRate(Long stationOrgId, String category, int percent,
                               String reason, long callerUserId) {
        if (!identityApi.hasRole(callerUserId, Role.PLATFORM_OPS)) {
            throw new org.springframework.security.access.AccessDeniedException("需要平台运维权限");
        }
        if (percent < 0 || percent > 100) {
            throw new IllegalArgumentException("分成比例必须在 0 到 100 之间");
        }
        if (category == null || category.isBlank()) {
            throw new IllegalArgumentException("请选择业务类目");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("请填写调整原因");
        }
        if (stationOrgId != null && stations.findById(stationOrgId).isEmpty()) {
            throw new IllegalArgumentException("服务站不存在或副本尚未落地");
        }
        String cat = category.trim().toUpperCase();

        StationRate existing = (stationOrgId == null
                ? stationRates.findByStationOrgIdIsNullAndCategory(cat)
                : stationRates.findByStationOrgIdAndCategory(stationOrgId, cat)).orElse(null);

        Integer old = existing == null ? null : existing.getPercent();
        rateChanges.save(new StationRateChange(stationOrgId, cat, old, percent, callerUserId, reason.trim()));

        if (existing == null) {
            stationRates.save(new StationRate(stationOrgId, cat, percent, callerUserId));
        } else {
            existing.change(percent, callerUserId);
            stationRates.save(existing);
        }
        // **同步到真正决定钱怎么分的那张表。**
        //
        // 分账时读的只有 commission_scheme(见 FundEventListener),
        // station_rate 现在只剩展示和历史。不同步的话,运营在
        // 「平台默认分成比例」上改完、界面提示"已更新",而分账一分钱没变 ——
        // 差额要等对账才发现,那时钱已经发出去了。
        //
        // 这个项目已经栽过一次一模一样的:旧入口写 station_percent、
        // 新入口写 station_rate、读的时候优先新表。当时的教训是
        // **"同一个概念只能有一条写入路径"**,这里补上那条路径。
        syncStationPctToScheme(stationOrgId, cat, percent, reason.trim(), callerUserId);

        log.info("分成比例变更:站={} 类目={} {} → {}% 操作人={}",
                stationOrgId == null ? "平台默认" : stationOrgId, cat, old, percent, callerUserId);
    }

    /**
     * 把「服务站那一档」写进分配方案。
     *
     * <p>方案还不存在时,**其余五档从平台默认继承**,而不是凭空造一套 ——
     * 凭空造等于替老板决定了主动佣金给多少,而他改的只是服务站那一档。
     */
    private void syncStationPctToScheme(Long stationOrgId, String cat, int stationPct,
                                        String reason, long callerUserId) {
        try {
            doSyncStationPct(stationOrgId, cat, stationPct, reason, callerUserId);
        } catch (IllegalArgumentException e) {
            // **这里不能让底层那句话直接冒出去。**它说的是"三者相加超过 100",
            // 而运营在界面上只填了一个数字,看到那句话不知道该改什么。
            //
            // 旧的「服务站比例」允许 0~100 随便填,那是它单独存一张表时的自由 ——
            // 而分账模型里平台、被动、服务站在同一块剩余里分。
            // 也就是说**旧入口一直能表达一个兑现不了的值**,只是以前写进去也没人读。
            throw new IllegalArgumentException(
                    "服务站设成 " + stationPct + "% 放不下:" + e.getMessage()
                    + "。要么调低这一档,要么用下面的整套方案把平台/被动一起改", e);
        }
    }

    private void doSyncStationPct(Long stationOrgId, String cat, int stationPct,
                                  String reason, long callerUserId) {
        CommissionScheme existing = (stationOrgId == null
                ? schemes.findByStationOrgIdIsNullAndCategory(cat)
                : schemes.findByStationOrgIdAndCategory(stationOrgId, cat)).orElse(null);

        if (existing != null) {
            String before = existing.summary();
            existing.apply(existing.getActivePct(), existing.getPlatformPct(),
                    existing.getPassivePct(), stationPct,
                    existing.getPassiveStepPct(), existing.getMinPayoutCents(), callerUserId);
            schemes.save(existing);
            schemeChanges.save(new CommissionSchemeChange(stationOrgId, cat, before,
                    existing.summary(), callerUserId, reason));
            return;
        }

        CommissionScheme base = schemes.findByStationOrgIdIsNullAndCategory(cat).orElse(null);
        CommissionScheme created = base == null
                // 平台默认也没有:用和迁移里一致的兜底值,只把服务站那一档换成新值
                ? new CommissionScheme(stationOrgId, cat, 60, 20, 30, stationPct, 30, 100, callerUserId)
                : new CommissionScheme(stationOrgId, cat, base.getActivePct(), base.getPlatformPct(),
                        base.getPassivePct(), stationPct, base.getPassiveStepPct(),
                        base.getMinPayoutCents(), callerUserId);
        schemes.save(created);
        schemeChanges.save(new CommissionSchemeChange(stationOrgId, cat, null,
                created.summary(), callerUserId, reason));
    }

    @Override
    @Transactional(transactionManager = "brokerTransactionManager", readOnly = true)
    public List<StationRateView> listStationRates(Long stationOrgId, long callerUserId) {
        boolean isMaster = stationOrgId != null && isStationLegalRep(stationOrgId, callerUserId);
        if (!isMaster && !identityApi.hasRole(callerUserId, Role.PLATFORM_OPS)) {
            // 分成比例是这个站挣多少钱的依据,不该给无关的人看(铁律 5.1)
            return List.of();
        }
        List<StationRate> rows = stationOrgId == null
                ? stationRates.findByStationOrgIdIsNull()
                : stationRates.findByStationOrgId(stationOrgId);
        return rows.stream()
                .map(r -> new StationRateView(r.getStationOrgId(), r.getCategory(),
                        r.getPercent(), r.getUpdatedAt()))
                .toList();
    }

    // ─────────────── 按类目的整套分配方案 ───────────────

    /**
     * 设整套方案。**要平台运维** —— 这是在改钱怎么分,不该由服务站自己说了算。
     *
     * <p>校验(各档 0–100、三档之和不超 100)在实体的 apply 里,
     * 让每一条写入路径都过同一道关。
     */
    @Override
    @Transactional("brokerTransactionManager")
    public void setScheme(Long stationOrgId, String category, int activePct, int platformPct,
                          int passivePct, int stationPct, int passiveStepPct, long minPayoutCents,
                          String reason, long callerUserId) {
        if (!identityApi.hasRole(callerUserId, Role.PLATFORM_OPS)) {
            throw new org.springframework.security.access.AccessDeniedException("需要平台运维权限");
        }
        if (category == null || category.isBlank()) {
            throw new IllegalArgumentException("请选择业务类目");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("请填写调整原因");
        }
        if (stationOrgId != null && stations.findById(stationOrgId).isEmpty()) {
            throw new IllegalArgumentException("服务站不存在或副本尚未落地");
        }
        String cat = category.trim().toUpperCase();

        CommissionScheme existing = (stationOrgId == null
                ? schemes.findByStationOrgIdIsNullAndCategory(cat)
                : schemes.findByStationOrgIdAndCategory(stationOrgId, cat)).orElse(null);
        String before = existing == null ? null : existing.summary();

        CommissionScheme saved;
        if (existing == null) {
            saved = schemes.save(new CommissionScheme(stationOrgId, cat, activePct, platformPct,
                    passivePct, stationPct, passiveStepPct, minPayoutCents, callerUserId));
        } else {
            existing.apply(activePct, platformPct, passivePct, stationPct,
                    passiveStepPct, minPayoutCents, callerUserId);
            saved = schemes.save(existing);
        }
        schemeChanges.save(new CommissionSchemeChange(stationOrgId, cat, before,
                saved.summary(), callerUserId, reason.trim()));
        log.info("分配方案变更:站={} 类目={} {} → {} 操作人={}",
                stationOrgId == null ? "平台默认" : stationOrgId, cat,
                before == null ? "(新建)" : before, saved.summary(), callerUserId);
    }

    @Override
    @Transactional(transactionManager = "brokerTransactionManager", readOnly = true)
    public List<SchemeView> listSchemes(Long stationOrgId, long callerUserId) {
        boolean isMaster = stationOrgId != null && isStationLegalRep(stationOrgId, callerUserId);
        if (!isMaster && !identityApi.hasRole(callerUserId, Role.PLATFORM_OPS)) {
            // 分配方案是这个站挣多少钱的依据,不该给无关的人看(铁律 5.1)
            return List.of();
        }
        List<CommissionScheme> rows = stationOrgId == null
                ? schemes.findByStationOrgIdIsNull()
                : schemes.findByStationOrgId(stationOrgId);
        return rows.stream()
                .map(x -> new SchemeView(x.getStationOrgId(), x.getCategory(), x.getActivePct(),
                        x.getPlatformPct(), x.getPassivePct(), x.getStationPct(),
                        x.getPassiveStepPct(), x.getMinPayoutCents(), x.getUpdatedAt()))
                .toList();
    }

    // ─────────────── 服务站与用工单位的合作(老系统 M9) ───────────────

    /**
     * 发起合作申请。
     *
     * <p>对方必须是**已审核的企业或工厂** —— 服务站之间的关系走"联合"那条路,
     * 两者的分账含义不同,混起来会让钱走错档。
     */
    @Override
    @Transactional("brokerTransactionManager")
    public long applyCooperation(long stationOrgId, long partnerOrgId,
                                 boolean initiatedByStation, long callerUserId) {
        stations.findById(stationOrgId)
                .orElseThrow(() -> new IllegalArgumentException("服务站不存在或副本尚未落地"));
        var partner = orgApi.summaryOf(partnerOrgId)
                .orElseThrow(() -> new IllegalArgumentException("对方组织不存在"));
        if (partner.type() == com.xbb.org.api.OrgType.SERVICE_STATION) {
            throw new IllegalArgumentException("服务站之间请走「联合」,不是「合作」");
        }
        if (!partner.approved()) {
            throw new IllegalStateException("对方组织尚未通过审核");
        }
        // 发起方必须是自己那一边的负责人 —— 否则任何人都能替别人签下合作
        if (initiatedByStation) {
            requireStationLegalRep(stationOrgId, callerUserId, "发起合作");
        } else {
            requirePartnerLegalRep(partnerOrgId, callerUserId, "发起合作");
        }

        try {
            var saved = cooperations.save(new StationCooperation(
                    stationOrgId, partnerOrgId, initiatedByStation, callerUserId));
            log.info("合作申请:服务站 {} ↔ 用工单位 {} 发起方={}",
                    stationOrgId, partnerOrgId, initiatedByStation ? "服务站" : "用工单位");
            return saved.getId();
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            // 唯一索引兜底。老系统在应用层"已申请则拦截重复",并发下无效
            throw new IllegalStateException("已经有一条待确认或生效中的合作,不能重复发起");
        }
    }

    /** 确认合作。**只有被申请的那一方能确认**,否则那个两步流程形同虚设。 */
    @Override
    @Transactional("brokerTransactionManager")
    public void confirmCooperation(long cooperationId, long callerUserId) {
        StationCooperation coop = cooperations.findById(cooperationId)
                .orElseThrow(() -> new IllegalArgumentException("合作申请不存在"));
        if (coop.isInitiatedByStation()) {
            requirePartnerLegalRep(coop.getPartnerOrgId(), callerUserId, "确认合作");
        } else {
            requireStationLegalRep(coop.getStationOrgId(), callerUserId, "确认合作");
        }
        coop.confirm(callerUserId);
        cooperations.save(coop);
        log.info("合作已确认:{} ↔ {}", coop.getStationOrgId(), coop.getPartnerOrgId());
    }

    /** 撤回未确认的申请。只有发起方能撤。 */
    @Override
    @Transactional("brokerTransactionManager")
    public void cancelCooperation(long cooperationId, long callerUserId) {
        StationCooperation coop = cooperations.findById(cooperationId)
                .orElseThrow(() -> new IllegalArgumentException("合作申请不存在"));
        if (coop.isInitiatedByStation()) {
            requireStationLegalRep(coop.getStationOrgId(), callerUserId, "撤回合作申请");
        } else {
            requirePartnerLegalRep(coop.getPartnerOrgId(), callerUserId, "撤回合作申请");
        }
        coop.cancel();
        cooperations.save(coop);
    }

    /** 解除已生效的合作。**任一方都可以** —— 合作是双方的,不该只有一方能退出。 */
    @Override
    @Transactional("brokerTransactionManager")
    public void endCooperation(long cooperationId, long callerUserId) {
        StationCooperation coop = cooperations.findById(cooperationId)
                .orElseThrow(() -> new IllegalArgumentException("合作不存在"));
        boolean isParty = isStationLegalRep(coop.getStationOrgId(), callerUserId)
                || isPartnerLegalRep(coop.getPartnerOrgId(), callerUserId)
                || identityApi.hasRole(callerUserId, Role.PLATFORM_OPS);
        if (!isParty) {
            throw new IllegalStateException("只有合作双方的负责人可以解除合作");
        }
        coop.end();
        cooperations.save(coop);

        // **合作没了,操作员的授权也就没了。**留着的话,那个人还挂着一份
        // 指向已结束合作的授权 —— 而授权是用来判断"他能不能替这家办事"的
        int revoked = 0;
        for (CooperationOperator op : operators.findByCooperationIdAndActiveTrue(cooperationId)) {
            op.revoke();
            operators.save(op);
            revoked++;
        }
        log.info("合作已解除:{} ↔ {},连带解绑操作员 {} 人",
                coop.getStationOrgId(), coop.getPartnerOrgId(), revoked);
    }

    @Override
    @Transactional(transactionManager = "brokerTransactionManager", readOnly = true)
    public List<CooperationView> listCooperations(long orgId, long callerUserId) {
        // 合作关系是两家的商业约定,不该给无关的人看(铁律 5.1)
        if (!isStationLegalRep(orgId, callerUserId) && !isPartnerLegalRep(orgId, callerUserId)
                && !identityApi.hasRole(callerUserId, Role.PLATFORM_OPS)) {
            return List.of();
        }
        var mine = new java.util.ArrayList<StationCooperation>();
        mine.addAll(cooperations.findByStationOrgIdOrderByIdDesc(orgId));
        mine.addAll(cooperations.findByPartnerOrgIdOrderByIdDesc(orgId));
        return mine.stream()
                .map(c -> new CooperationView(c.getId(), c.getStationOrgId(), c.getPartnerOrgId(),
                        c.getStatus().name(), c.isInitiatedByStation(), c.getCreatedAt(),
                        c.getConfirmedAt(), c.getEndedAt()))
                .toList();
    }

    /**
     * 指派操作员。
     *
     * <p>**只有服务站站长能派,且只能在已生效的合作上派** ——
     * 合作还没谈成就先派人,那个人拿着的是一份不存在的授权。
     */
    @Override
    @Transactional("brokerTransactionManager")
    public long assignOperator(long cooperationId, long userId, long callerUserId) {
        StationCooperation coop = cooperations.findById(cooperationId)
                .orElseThrow(() -> new IllegalArgumentException("合作不存在"));
        requireStationLegalRep(coop.getStationOrgId(), callerUserId, "指派操作员");
        if (coop.getStatus() != StationCooperation.Status.ACTIVE) {
            throw new IllegalStateException("只有已生效的合作可以指派操作员");
        }
        if (verifiedUsers.findById(userId).isEmpty()) {
            // 操作员要代表服务站对外办事,没实名的话出了事追溯不到人
            throw new IllegalStateException("操作员需要完成实名认证");
        }
        var existing = operators.findByCooperationIdAndUserIdAndActiveTrue(cooperationId, userId);
        if (existing.isPresent()) {
            return existing.get().getId();   // 幂等:重复指派同一个人
        }
        var saved = operators.save(new CooperationOperator(cooperationId, userId, callerUserId));
        log.info("指派操作员:合作={} 用户={} 指派人={}", cooperationId, userId, callerUserId);
        return saved.getId();
    }

    @Override
    @Transactional("brokerTransactionManager")
    public void revokeOperator(long cooperationId, long userId, long callerUserId) {
        StationCooperation coop = cooperations.findById(cooperationId)
                .orElseThrow(() -> new IllegalArgumentException("合作不存在"));
        requireStationLegalRep(coop.getStationOrgId(), callerUserId, "解绑操作员");
        CooperationOperator op = operators
                .findByCooperationIdAndUserIdAndActiveTrue(cooperationId, userId)
                .orElseThrow(() -> new IllegalArgumentException("这个人不是该合作的操作员"));
        op.revoke();
        operators.save(op);
    }

    @Override
    @Transactional(transactionManager = "brokerTransactionManager", readOnly = true)
    public List<OperatorView> listOperators(long cooperationId, long callerUserId) {
        StationCooperation coop = cooperations.findById(cooperationId).orElse(null);
        if (coop == null) {
            return List.of();
        }
        boolean isParty = isStationLegalRep(coop.getStationOrgId(), callerUserId)
                || isPartnerLegalRep(coop.getPartnerOrgId(), callerUserId)
                || identityApi.hasRole(callerUserId, Role.PLATFORM_OPS);
        if (!isParty) {
            return List.of();
        }
        return operators.findByCooperationIdAndActiveTrue(cooperationId).stream()
                .map(o -> new OperatorView(o.getId(), o.getCooperationId(), o.getUserId(),
                        o.isActive(), o.getCreatedAt()))
                .toList();
    }

    private boolean isPartnerLegalRep(long orgId, long callerUserId) {
        // 走 org 域的窄接口:只问"是不是法人",不把整个组织(含信用代码)拿过来。
        // 第一版我拿一个假的"内部身份"去调 findById 绕过归属校验 ——
        // 那是在别处开洞,任何人读到那个常量就能照抄
        return orgApi.isLegalRepOf(orgId, callerUserId);
    }

    private void requirePartnerLegalRep(long orgId, long callerUserId, String action) {
        if (!isPartnerLegalRep(orgId, callerUserId)) {
            throw new IllegalStateException("只有用工单位的法人代表可以" + action);
        }
    }



    // ─────────────── 服务站间联合(老系统 M10 §3.4) ───────────────

    /**
     * 发起联合申请。
     *
     * <p>**只有发起方服务站的法人代表能发** —— 这一步是在决定把自己的佣金分一部分出去。
     * 少了这条,任何人都能替别人的服务站签下分成协议。
     */
    @Override
    @Transactional("brokerTransactionManager")
    public long applyJoint(long fromOrgId, long toOrgId, int ratePercent, long callerUserId) {
        Station from = requireStationLegalRep(fromOrgId, callerUserId, "发起联合");
        stations.findById(toOrgId)
                .orElseThrow(() -> new IllegalArgumentException("对方不是已审核的服务站"));

        // 反向已经联合过也要拦:A→B 和 B→A 同时存在会让两边互相分成,
        // 钱在两个站之间来回切,总额虽不超但明细没人看得懂
        if (joints.findByFromOrgIdAndToOrgIdAndStatus(toOrgId, fromOrgId, StationJoint.Status.ACTIVE).isPresent()) {
            throw new IllegalStateException("对方已经和你联合,不需要再发起");
        }

        StationJoint joint = new StationJoint(fromOrgId, toOrgId, ratePercent, callerUserId);
        try {
            StationJoint saved = joints.save(joint);
            log.info("联合申请:{} → {} 比例={}% 发起人={}", from.getName(), toOrgId, ratePercent, callerUserId);
            return saved.getId();
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            // 唯一索引兜底。老系统在应用层"已申请则拦截重复",那在并发下无效 ——
            // 两边同时查到"没有"然后都插入
            throw new IllegalStateException("已经有一条待确认或生效中的联合,不能重复发起");
        }
    }

    /**
     * 确认联合。
     *
     * <p>**只有被邀请方的法人代表能确认。**否则发起方可以自己给自己确认,
     * 这个"申请—确认"的两步就完全没有意义了。
     */
    @Override
    @Transactional("brokerTransactionManager")
    public void confirmJoint(long jointId, long callerUserId) {
        StationJoint joint = joints.findById(jointId)
                .orElseThrow(() -> new IllegalArgumentException("联合申请不存在"));
        requireStationLegalRep(joint.getToOrgId(), callerUserId, "确认联合");
        joint.confirm(callerUserId);
        joints.save(joint);
        log.info("联合已确认:{} → {} 比例={}%", joint.getFromOrgId(), joint.getToOrgId(), joint.getRatePercent());
    }

    /** 撤回未确认的申请。只有发起方能撤。 */
    @Override
    @Transactional("brokerTransactionManager")
    public void cancelJoint(long jointId, long callerUserId) {
        StationJoint joint = joints.findById(jointId)
                .orElseThrow(() -> new IllegalArgumentException("联合申请不存在"));
        requireStationLegalRep(joint.getFromOrgId(), callerUserId, "撤回联合申请");
        joint.cancel();
        joints.save(joint);
    }

    /** 解除已生效的联合。**任一方都可以** —— 合作是双方的,不该只有一方能退出。 */
    @Override
    @Transactional("brokerTransactionManager")
    public void endJoint(long jointId, long callerUserId) {
        StationJoint joint = joints.findById(jointId)
                .orElseThrow(() -> new IllegalArgumentException("联合不存在"));
        boolean isParty = isStationLegalRep(joint.getFromOrgId(), callerUserId)
                || isStationLegalRep(joint.getToOrgId(), callerUserId)
                || identityApi.hasRole(callerUserId, Role.PLATFORM_OPS);
        if (!isParty) {
            throw new IllegalStateException("只有联合双方的法人代表可以解除联合");
        }
        joint.end();
        joints.save(joint);
        log.info("联合已解除:{} → {}", joint.getFromOrgId(), joint.getToOrgId());
    }

    @Override
    @Transactional(transactionManager = "brokerTransactionManager", readOnly = true)
    public List<StationJointView> listJoints(long orgId, long callerUserId) {
        // 联合关系里有分成比例 —— 那是两家的商业约定,不该给无关的人看(铁律 5.1)
        if (!isStationLegalRep(orgId, callerUserId)
                && !identityApi.hasRole(callerUserId, Role.PLATFORM_OPS)) {
            return List.of();
        }
        return joints.findByFromOrgIdOrToOrgIdOrderByIdDesc(orgId, orgId).stream()
                .map(j -> new StationJointView(j.getId(), j.getFromOrgId(), j.getToOrgId(),
                        j.getRatePercent(), j.getStatus().name(),
                        j.getCreatedAt(), j.getConfirmedAt(), j.getEndedAt()))
                .toList();
    }

    private boolean isStationLegalRep(long orgId, long callerUserId) {
        return stations.findById(orgId).map(st -> st.getLegalRepUserId() == callerUserId).orElse(false);
    }

    private Station requireStationLegalRep(long orgId, long callerUserId, String action) {
        Station station = stations.findById(orgId)
                .orElseThrow(() -> new IllegalArgumentException("服务站不存在或尚未通过审核"));
        if (station.getLegalRepUserId() != callerUserId) {
            // 说"站长"而不是"服务站法人代表":界面上、老板口中、这份代码的别处
            // 用的都是"站长"。同一个角色在报错里换个叫法,用户会以为是另一种权限
            throw new IllegalStateException("只有服务站站长可以" + action);
        }
        return station;
    }

    // ─────────────── 服务站与业务员网络 ───────────────

    @Override
    @Transactional(transactionManager = "brokerTransactionManager", readOnly = true)
    public List<StationView> listStations(long callerUserId) {
        requirePlatformOps(callerUserId);
        int platformDefault = (int) opsApi.settingInt(
                com.xbb.ops.api.SettingKeys.COMMISSION_STATION_PERCENT, 50);
        // **比例从费率表读,不读 station.station_percent 那个老字段。**
        // 视图和分账必须看同一个数:视图读老字段、分账读费率表的话,
        // 界面会显示"没单独设过"而实际设过了 —— 而那种不一致要等对账才发现。
        //
        // 这里显示的是**岗位**类目;商品与培训在各站的"管理"里单独看
        String jobCat = com.xbb.broker.api.RateCategory.JOB;
        Integer platformJob = stationRates.findByStationOrgIdIsNullAndCategory(jobCat)
                .map(StationRate::getPercent).orElse(null);
        int fallback = platformJob == null ? platformDefault : platformJob;

        return stations.findAllByOrderByOrgIdAsc().stream()
                .map(st -> {
                    Integer own = stationRates
                            .findByStationOrgIdAndCategory(st.getOrgId(), jobCat)
                            .map(StationRate::getPercent).orElse(null);
                    return new StationView(st.getOrgId(), st.getName(), st.getLegalRepUserId(),
                            own, own == null ? fallback : own, st.getApprovedAt(),
                            brokers.findByStationOrgIdOrderByUserIdAsc(st.getOrgId()).size());
                })
                .toList();
    }

    @Override
    @Transactional("brokerTransactionManager")
    public void setStationPercent(long stationOrgId, Integer percent, String reason, long callerUserId) {
        // **老入口,保留是为了不破坏既有调用方;但它现在写的是新的费率表。**
        //
        // 分成比例改成按类目分设之后,这里一度成了第二个写入口:
        // 老入口写 station.station_percent,新入口写 station_rate,
        // 而取数**优先读 station_rate** —— 于是运营在老入口改了比例、界面提示成功,
        // 实际却不生效。这类"改了没反应"最难查,因为哪一步都没报错。
        //
        // percent 传 null 的语义是"跟随平台默认",对应到新模型就是删掉该站的覆盖。
        if (percent == null) {
            if (!identityApi.hasRole(callerUserId, Role.PLATFORM_OPS)) {
                throw new org.springframework.security.access.AccessDeniedException("需要平台运维权限");
            }
            stationRates.findByStationOrgIdAndCategory(stationOrgId, com.xbb.broker.api.RateCategory.JOB)
                    .ifPresent(r -> {
                        rateChanges.save(new StationRateChange(stationOrgId,
                                com.xbb.broker.api.RateCategory.JOB, r.getPercent(), -1,
                                callerUserId, reason == null || reason.isBlank() ? "改为跟随平台默认" : reason));
                        stationRates.delete(r);
                    });
            return;
        }
        setStationRate(stationOrgId, com.xbb.broker.api.RateCategory.JOB, percent, reason, callerUserId);
    }

    @Override
    @Transactional(transactionManager = "brokerTransactionManager", readOnly = true)
    public List<BrokerNodeView> listBrokers(Long stationOrgId, long callerUserId) {
        requirePlatformOps(callerUserId);
        List<Broker> rows = stationOrgId == null
                ? brokers.findAll()
                : brokers.findByStationOrgIdOrderByUserIdAsc(stationOrgId);
        return rows.stream().map(b -> new BrokerNodeView(b.getUserId(), b.getStationOrgId(),
                        b.getParentUserId(), b.getLastActiveAt(), b.getStatus().name(),
                        brokers.findByParentUserId(b.getUserId()).size()))
                .toList();
    }

    @Override
    @Transactional("brokerTransactionManager")
    public void assignStation(long brokerUserId, Long stationOrgId, String reason, long callerUserId) {
        requirePlatformOps(callerUserId);
        requireReason(reason);
        if (stationOrgId != null && stations.findById(stationOrgId).isEmpty()) {
            throw new IllegalArgumentException("服务站不存在");
        }
        Broker b = requireBroker(brokerUserId);
        Long old = b.getStationOrgId();
        b.assignStation(stationOrgId);
        brokers.save(b);
        logChange(brokerUserId, BrokerChangeLog.ChangeType.STATION, old, stationOrgId, callerUserId, reason);
    }

    @Override
    @Transactional("brokerTransactionManager")
    public void assignParent(long brokerUserId, Long parentUserId, String reason, long callerUserId) {
        requirePlatformOps(callerUserId);
        requireReason(reason);
        Broker b = requireBroker(brokerUserId);
        if (parentUserId != null) {
            requireBroker(parentUserId);
            requireNoCycle(brokerUserId, parentUserId);
        }
        Long old = b.getParentUserId();
        b.assignParent(parentUserId);
        brokers.save(b);
        logChange(brokerUserId, BrokerChangeLog.ChangeType.PARENT, old, parentUserId, callerUserId, reason);
    }

    /**
     * 从候选上级往根走,路上撞见自己就是成环。
     *
     * <p>加深度上限是因为**数据里已经有环的话这个循环本身会卡死** ——
     * 用来防环的代码不能假设数据无环。
     */
    private void requireNoCycle(long brokerUserId, long candidateParentId) {
        Long cursor = candidateParentId;
        for (int depth = 0; cursor != null && depth < 100; depth++) {
            if (cursor == brokerUserId) {
                throw new IllegalArgumentException("不能把业务员挂到自己的下级名下,会形成闭环");
            }
            cursor = brokers.findById(cursor).map(Broker::getParentUserId).orElse(null);
        }
        if (cursor != null) {
            throw new IllegalStateException("上级链条超过 100 层,疑似已存在闭环,请先人工核查");
        }
    }

    @Override
    @Transactional("brokerTransactionManager")
    public void touchBroker(long brokerUserId) {
        brokers.findById(brokerUserId).ifPresent(b -> { b.touch(); brokers.save(b); });
    }

    @Override
    @Transactional(transactionManager = "brokerTransactionManager", readOnly = true)
    public List<BrokerChangeView> brokerChanges(Long brokerUserId, long callerUserId) {
        requirePlatformOps(callerUserId);
        List<BrokerChangeLog> rows = brokerUserId == null
                ? changeLogs.findTop100ByOrderByChangedAtDesc()
                : changeLogs.findByBrokerUserIdOrderByChangedAtDesc(brokerUserId);
        return rows.stream().map(c -> new BrokerChangeView(c.getId(), c.getBrokerUserId(),
                c.getChangeType().name(), c.getOldValue(), c.getNewValue(),
                c.getChangedBy(), c.getChangedAt(), c.getReason())).toList();
    }

    private Broker requireBroker(long userId) {
        return brokers.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("业务员不存在: " + userId));
    }

    private static void requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("必须填写变更理由");
        }
    }

    void logChange(long brokerUserId, BrokerChangeLog.ChangeType type,
                   Object oldValue, Object newValue, Long operator, String reason) {
        changeLogs.save(new BrokerChangeLog(brokerUserId, type,
                oldValue == null ? null : String.valueOf(oldValue),
                newValue == null ? null : String.valueOf(newValue),
                operator, reason));
    }

    @Override
    public int runDemotionNow(long callerUserId) {
        requirePlatformOps(callerUserId);
        // 不在这里开事务:任务内部按人各自开 REQUIRES_NEW,
        // 外面再包一层大事务会让"一人一事务"失去意义(一个失败全回滚)。
        return demotionTask.getObject().run();
    }

    @Override
    @Transactional("brokerTransactionManager")
    public void setCommissionRate(String category, String regionCode, int commissionPct,
                                   int dispatchRetainPct, Long dispatchOrgId,
                                   String reason, long callerUserId) {
        if (!identityApi.hasRole(callerUserId, Role.PLATFORM_OPS)) {
            throw new org.springframework.security.access.AccessDeniedException("需要平台运维权限");
        }
        if (category == null || category.isBlank()) {
            throw new IllegalArgumentException("请选择业务类目");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("请填写调整原因");
        }
        String cat = category.trim().toUpperCase();
        String region = regionCode == null || regionCode.isBlank() ? null : regionCode.trim();

        CommissionRate existing = (region == null
                ? rates.findByCategoryAndRegionCodeIsNull(cat)
                : rates.findByCategoryAndRegionCode(cat, region)).orElse(null);
        String before = existing == null ? null : existing.summary();

        CommissionRate saved;
        if (existing == null) {
            saved = rates.save(new CommissionRate(cat, region, commissionPct,
                    dispatchRetainPct, dispatchOrgId, callerUserId));
        } else {
            existing.apply(commissionPct, dispatchRetainPct, dispatchOrgId, callerUserId);
            saved = rates.save(existing);
        }
        rateChangeRepo.save(new CommissionRateChange(cat, region, before,
                saved.summary(), callerUserId, reason.trim()));
        log.info("佣金比例变更:类目={} 地区={} {} → {} 操作人={}",
                cat, region == null ? "全国" : region, before, saved.summary(), callerUserId);
    }

    @Override
    @Transactional(transactionManager = "brokerTransactionManager", readOnly = true)
    public List<CommissionRateView> listCommissionRates(long callerUserId) {
        if (!identityApi.hasRole(callerUserId, Role.PLATFORM_OPS)) {
            // 列表接口挡住路人时回空列表(铁律 5.1)
            return List.of();
        }
        return rates.findAllByOrderByCategoryAscRegionCodeAsc().stream()
                .map(r -> new CommissionRateView(r.getCategory(), r.getRegionCode(),
                        r.getCommissionPct(), r.getDispatchRetainPct(),
                        r.getDispatchOrgId(), r.getUpdatedAt()))
                .toList();
    }

    @Override
    @Transactional(transactionManager = "brokerTransactionManager", readOnly = true)
    public TotalPriceSplit splitTotalPrice(String category, String regionCode, long totalPriceCents) {
        CommissionRate rate = rateResolver.resolve(
                category == null ? com.xbb.broker.api.RateCategory.JOB : category.trim().toUpperCase(),
                regionCode);
        TotalPricePlan plan = TotalPricePlan.of(
                totalPriceCents, rate.getCommissionPct(), rate.getDispatchRetainPct());
        return new TotalPriceSplit(plan.totalPriceCents(), plan.workerCents(),
                plan.commissionCents(), plan.dispatchRetainCents(), plan.stationPoolCents(),
                rate.getDispatchOrgId());
    }
}
