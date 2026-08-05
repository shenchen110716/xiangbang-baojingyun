package com.xbb.broker.internal;

import com.xbb.broker.api.CommissionGenerated;
import com.xbb.fund.api.AccountType;
import com.xbb.fund.api.FundApi;
import com.xbb.fund.api.FundsDisbursed;
import com.xbb.ops.api.OpsApi;
import com.xbb.ops.api.SettingKeys;
import org.springframework.context.event.EventListener;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

// 佣金触发源:原先订阅 settlement.SettlementPaid,现在 settlement 已不再直接发钱
// (结算⊥资金拆分,见 Plan6),真正"钱已经付了"的信号来自 fund.FundsDisbursed。
// 显式命名:settlement 域也有个同名类 FundEventListener,默认 bean 名会撞车
@Component("brokerFundEventListener")
class FundEventListener {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(FundEventListener.class);

    /** 沿经纪人树向上的层数上限。数据里万一有环,这里不设限就是死循环。 */
    private static final int MAX_CHAIN_DEPTH = 50;

    private final InvitationRepository invitations;
    private final CommissionRepository commissions;
    private final BrokerRepository brokers;
    private final StationRepository stations;
    private final StationJointRepository joints;
    private final CommissionSchemeRepository schemes;
    private final CommissionBaseRepository bases;
    private final BrokerOutboxRepository outbox;
    private final OpsApi opsApi;
    private final FundApi fundApi;
    private final ObjectMapper json;

    FundEventListener(InvitationRepository invitations, CommissionRepository commissions,
                      BrokerRepository brokers, StationRepository stations,
                      StationJointRepository joints, CommissionSchemeRepository schemes,
                      CommissionBaseRepository bases,
                      BrokerOutboxRepository outbox, OpsApi opsApi, FundApi fundApi, ObjectMapper json) {
        this.invitations = invitations;
        this.commissions = commissions;
        this.brokers = brokers;
        this.stations = stations;
        this.joints = joints;
        this.schemes = schemes;
        this.bases = bases;
        this.outbox = outbox;
        this.opsApi = opsApi;
        this.fundApi = fundApi;
        this.json = json;
    }

    private String serialize(Object event) {
        try {
            return json.writeValueAsString(event);
        } catch (Exception e) {
            // 序列化不了就别让这步业务成功——事件发不出去,下游永远补不回来
            throw new IllegalStateException("事件无法序列化: " + event, e);
        }
    }

    /**
     * `@EventListener` 而非 AFTER_COMMIT:该事件由资金域的 outbox 中继投递,
     * 中继在自己的事务里 publish——用 AFTER_COMMIT 的话本方法要等中继事务提交后才跑,
     * 那时 outbox 行已是 PUBLISHED,这里再抛异常事件就永久丢了。
     */
    @EventListener
    @Transactional(transactionManager = "brokerTransactionManager", propagation = Propagation.REQUIRES_NEW)
    void on(FundsDisbursed event) {
        // 中继是至少一次投递:同一笔发放会重复到达。
        // 现在一笔结算会生成多条分账,所以"这笔算过没有"要看有没有**任何一条**。
        if (!commissions.findAllBySettlementId(event.settlementId()).isEmpty()) {
            return;
        }
        // 工人没绑经纪人是正常路径,不是错误——直接不生成佣金记录,不抛异常。
        invitations.findByWorkerUserId(event.payeeUserId()).ifPresent(invitation ->
                splitAndRecord(event, invitation.getBrokerUserId()));
    }

    private void splitAndRecord(FundsDisbursed event, long directBrokerId) {
        Broker direct = brokers.findById(directBrokerId).orElse(null);
        Long stationOrgId = direct == null ? null : direct.getStationOrgId();

        // **佣金基数是浮动工资,不是发放总额。**
        // 老系统 JobComputerService 就是拿 floatSalary 算的 —— 用总额会让佣金随基本工资一起涨。
        // 副本还没到(两个事件顺序不保证)时退回用发放金额,和改动前一致。
        long base = bases.findById(event.settlementId())
                .map(CommissionBase::getBaseCents)
                .orElseGet(() -> {
                    log.warn("结算 {} 的佣金基数副本尚未到达,本次退回按发放金额 {} 分计算",
                            event.settlementId(), event.amountCents());
                    return event.amountCents();
                });

        // **目前只有岗位结算会走到这里** —— 代发单全部来自岗位结算,
        // 商城和培训还不产生代发。等它们真的开始发钱时,类目要从事件里带过来,
        // 而不是在这里猜
        CommissionSplitter.Rates rates = ratesFor(stationOrgId, com.xbb.broker.api.RateCategory.JOB);
        CommissionSplitter.Split split = CommissionSplitter.split(
                base, directBrokerId, stationOrgId, ancestorsOf(direct), rates, jointsOf(stationOrgId));

        for (CommissionSplitter.Share s : split.shares()) {
            // **JOINT 也是发给组织的**,不能落进"发给经纪人"那条 ——
            // 那样 broker_user_id 会是 null,数据库的归属约束会当场拒绝,
            // 而这条路径只有真的有联合服务站时才会走到
            Commission saved = s.tier() == CommissionSplitter.Tier.STATION
                    ? commissions.save(Commission.toStation(
                            s.stationOrgId(), event.payeeUserId(), event.settlementId(), s.amountCents()))
                    : s.tier() == CommissionSplitter.Tier.JOINT
                    ? commissions.save(Commission.toJoint(
                            s.stationOrgId(), event.payeeUserId(), event.settlementId(), s.amountCents()))
                    : commissions.save(Commission.toBroker(
                            s.brokerUserId(), event.payeeUserId(), event.settlementId(),
                            s.amountCents(), Commission.Tier.valueOf(s.tier().name()), s.chainDepth()));

            // 事件只对"给人的"发:CommissionGenerated 的消费方(报表、通知)按人聚合
            if (s.brokerUserId() != null) {
                CommissionGenerated generated = new CommissionGenerated(
                        saved.getId(), s.brokerUserId(), s.amountCents(), Instant.now());
                outbox.save(new BrokerOutboxEvent(java.util.UUID.randomUUID().toString(),
                        CommissionGenerated.class.getName(), serialize(generated)));
            }
        }

        // 平台那一份直接入账,不进佣金表 —— 账本在资金域,这里只是分账明细。
        if (split.platformCents() > 0) {
            fundApi.topUp(AccountType.PLATFORM_REVENUE, split.platformCents(),
                    "佣金分账 · 平台 settlement#" + event.settlementId());
        }

        log.info("佣金分账完成: settlement={} 基数={}分(发放{}分) 平台={}分 分账{}笔",
                event.settlementId(), base, event.amountCents(),
                split.platformCents(), split.shares().size());
    }

    /**
     * 归集站当前生效的联合(老系统 M10 §3.4)。
     *
     * <p>**只取 ACTIVE。**待确认的还没生效、已解除的属于历史 ——
     * 把它们算进来就是按一个并不存在的约定分钱。
     */
    private List<CommissionSplitter.Joint> jointsOf(Long stationOrgId) {
        if (stationOrgId == null) {
            return List.of();
        }
        return joints.findByFromOrgIdAndStatus(stationOrgId, StationJoint.Status.ACTIVE).stream()
                .map(j -> new CommissionSplitter.Joint(j.getToOrgId(), j.getRatePercent()))
                .toList();
    }

    /**
     * 取这个类目下的**整套分配方案**。三级取数,一条路径:
     * 该站在这个类目上的覆盖 → 平台在这个类目上的默认 → 全局兜底参数。
     *
     * <p>此前只有服务站那一档按类目分,其余五档全局共用一套 —— 而岗位、商品、培训的
     * 分账结构本来就不同(商品可能没有被动佣金,培训可能主动佣金极高),
     * 用同一套主动/被动比例去分,任何一个类目都是错的,且只有对账才看得出来。
     *
     * <p>最后那级兜底保留着:方案表理论上不会缺行(迁移已种下三个类目的平台默认),
     * 但真缺了的话**宁可用参数中心的旧口径,也不要分出一堆 0**。
     */
    private CommissionSplitter.Rates ratesFor(Long stationOrgId, String category) {
        CommissionScheme scheme = null;
        if (stationOrgId != null) {
            scheme = schemes.findByStationOrgIdAndCategory(stationOrgId, category).orElse(null);
        }
        if (scheme == null) {
            scheme = schemes.findByStationOrgIdIsNullAndCategory(category).orElse(null);
        }
        if (scheme != null) {
            return new CommissionSplitter.Rates(
                    scheme.getActivePct(), scheme.getPlatformPct(), scheme.getPassivePct(),
                    scheme.getPassiveStepPct(), scheme.getStationPct(), scheme.getMinPayoutCents());
        }
        log.error("类目 {} 没有分配方案(站={}),本次退回参数中心的全局口径。请到后台补一套",
                category, stationOrgId);
        return new CommissionSplitter.Rates(
                (int) opsApi.settingInt(SettingKeys.COMMISSION_ACTIVE_PERCENT, 60),
                (int) opsApi.settingInt(SettingKeys.COMMISSION_PLATFORM_PERCENT, 20),
                (int) opsApi.settingInt(SettingKeys.COMMISSION_PASSIVE_PERCENT, 30),
                (int) opsApi.settingInt(SettingKeys.COMMISSION_PASSIVE_STEP_PERCENT, 30),
                (int) opsApi.settingInt(SettingKeys.COMMISSION_STATION_PERCENT, 50),
                opsApi.settingInt(SettingKeys.COMMISSION_MIN_PAYOUT_CENTS, 100));
    }

    /**
     * 直接经纪人往上的祖先链,由近及远。
     *
     * <p>带深度上限与去重:配置界面挡住了新建的环,但**已经在库里的环挡不住**,
     * 而沿链走的代码不能假设数据无环 —— 那正是死循环的来源。
     */
    private List<Long> ancestorsOf(Broker direct) {
        List<Long> chain = new ArrayList<>();
        if (direct == null) {
            return chain;
        }
        java.util.Set<Long> seen = new java.util.HashSet<>();
        seen.add(direct.getUserId());
        Long cursor = direct.getParentUserId();
        while (cursor != null && chain.size() < MAX_CHAIN_DEPTH) {
            if (!seen.add(cursor)) {
                log.error("经纪人链条出现闭环,已在 {} 处截断。这笔分账会少给上层,请人工核查", cursor);
                break;
            }
            chain.add(cursor);
            cursor = brokers.findById(cursor).map(Broker::getParentUserId).orElse(null);
        }
        return chain;
    }
}
