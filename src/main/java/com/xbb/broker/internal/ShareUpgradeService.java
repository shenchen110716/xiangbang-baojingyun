package com.xbb.broker.internal;

import com.xbb.ops.api.OpsApi;
import com.xbb.ops.api.SettingKeys;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 分享归因与业务员自动升级。
 *
 * <p>员工把岗位或商品分享出去,对方经此报名/成交后,分享人自动升级为业务员。
 * 这是老系统 M10「多级裂变」的入口 —— 改动前只有已是经纪人的人才能绑定工人,
 * 普通员工没有任何变成业务员的路径。
 */
@Service
public class ShareUpgradeService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ShareUpgradeService.class);

    private final ShareRepository shares;
    private final ShareConversionRepository conversions;
    private final BrokerRepository brokers;
    private final BrokerOriginRepository origins;
    private final BrokerVerifiedUserRepository verifiedUsers;
    private final InvitationRepository invitations;
    private final StationRepository stations;
    private final OpsApi opsApi;

    ShareUpgradeService(ShareRepository shares, ShareConversionRepository conversions,
                        BrokerRepository brokers, BrokerOriginRepository origins,
                        BrokerVerifiedUserRepository verifiedUsers, InvitationRepository invitations,
                        StationRepository stations, OpsApi opsApi) {
        this.shares = shares;
        this.conversions = conversions;
        this.brokers = brokers;
        this.origins = origins;
        this.verifiedUsers = verifiedUsers;
        this.invitations = invitations;
        this.stations = stations;
        this.opsApi = opsApi;
    }

    /**
     * 生成分享码。**同一个人重复分享同一个东西复用同一条** ——
     * 每次点分享都新建一条的话,同一个人会有一堆等价的分享码,
     * 归因统计时每条各算各的,谁也说不清这个人到底带来了几单。
     */
    @Transactional("brokerTransactionManager")
    public String share(long sharerUserId, String targetType, long targetId) {
        String type = targetType == null ? "" : targetType.trim().toUpperCase();
        if (type.isBlank()) {
            throw new IllegalArgumentException("请指定分享类型");
        }
        return shares.findBySharerUserIdAndTargetTypeAndTargetId(sharerUserId, type, targetId)
                .map(Share::getCode)
                .orElseGet(() -> {
                    String code = newCode(sharerUserId, type, targetId);
                    shares.save(new Share(sharerUserId, type, targetId, code));
                    return code;
                });
    }

    /**
     * 分享码。用业务键拼出来而不是随机数:同一次分享在重试后应该得到同一个码,
     * 而随机数会让重试产生第二条等价的分享。
     */
    private static String newCode(long sharerUserId, String type, long targetId) {
        return "S%d%s%d".formatted(sharerUserId, type.charAt(0), targetId);
    }

    /**
     * 记一条归因:某人通过某个分享码进来了。
     *
     * <p><b>归属唯一</b>(老系统 M10 §4.1):一个人只能被归因给一个分享人。
     * 已经归因过的人再点别人的分享链接,归属不变 ——
     * 允许改的话,两个人分享给同一个人时两边都算业绩,佣金会被重复计算。
     *
     * @return 是否新建了归因(false 表示这个人已经归属别人了)
     */
    @Transactional("brokerTransactionManager")
    public boolean attribute(String code, long convertedUserId) {
        Share share = shares.findByCode(code).orElse(null);
        if (share == null) {
            log.info("分享码不存在,忽略归因:code={}", code);
            return false;
        }
        if (share.getSharerUserId() == convertedUserId) {
            // 自己点自己的分享链接。放过去的话,分享一下再自己报名就能把自己升成业务员
            log.info("分享人和被分享人是同一个人,忽略:user={}", convertedUserId);
            return false;
        }
        if (conversions.findByConvertedUserId(convertedUserId).isPresent()) {
            return false;
        }
        try {
            conversions.save(new ShareConversion(share.getId(), convertedUserId));
            return true;
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            // 唯一索引兜底:并发下两条归因同时插入,数据库裁决
            log.info("并发归因,已有归属:user={}", convertedUserId);
            return false;
        }
    }

    /**
     * 某人达成了一笔"算数"的业务(报名或成交),看要不要让他的分享人升级。
     *
     * <p>门槛来自参数中心:{@code 0} 表示**对方报名即升级**(不等成交),
     * 否则要凑满 N 单成交。岗位与商品**合并计数**。
     *
     * @param referenceId 对应的业务单据(报名单或订单),用于对账
     * @param onApplication 这次触发是"报名"(true)还是"成交"(false)
     */
    @Transactional("brokerTransactionManager")
    public void onDeal(long convertedUserId, long referenceId, boolean onApplication) {
        ShareConversion conversion = conversions.findByConvertedUserId(convertedUserId).orElse(null);
        if (conversion == null) {
            return;   // 不是分享来的,正常路径
        }
        long threshold = opsApi.settingInt(SettingKeys.BROKER_UPGRADE_DEAL_THRESHOLD, 1);

        // 门槛 0 = 报名即算;门槛 >0 = 只有成交才算。
        // 反过来的话,门槛设成 2 却在报名时就计数,等于把"成交"偷换成"报名"
        boolean countable = onApplication ? threshold == 0 : threshold > 0;
        if (!countable) {
            return;
        }
        if (!conversion.count(referenceId)) {
            return;   // 已经计过了。中继是至少一次投递,同一笔会重复到达
        }
        conversions.save(conversion);

        Share share = shares.findById(conversion.getShareId()).orElse(null);
        if (share == null) {
            log.error("归因 {} 指向的分享 {} 不存在,无法升级", conversion.getId(), conversion.getShareId());
            return;
        }
        maybeUpgrade(share.getSharerUserId(), conversion, Math.max(threshold, 1));
    }

    /**
     * 实名之后回补升级。
     *
     * <p><b>为什么需要这条。</b>经纪人域的已实名副本是异步从身份域来的。
     * 副本还没到时成交事件先到,升级会被"未实名"挡下 —— 而归因已经标记为已计数,
     * **那次升级机会就永久错过了**:如果这个分享人只带来一单,他再也升不上去。
     *
     * <p>轻载时副本总是先到,所以这个缺陷在小范围测试里看不出来;
     * 223 个测试一起跑、outbox 排队变长时才露出来。
     */
    @Transactional("brokerTransactionManager")
    public void onVerified(long userId) {
        if (brokers.existsById(userId)) {
            return;
        }
        // 站长的实名副本可能晚于指派到达。补上,否则"归默认站长"那条规则一直不生效
        for (Station st : stations.findAllByOrderByOrgIdAsc()) {
            if (st.getLegalRepUserId() == userId) {
                ensureStationMasterIsBroker(st.getOrgId(), userId);
                return;
            }
        }
        long threshold = Math.max(opsApi.settingInt(SettingKeys.BROKER_UPGRADE_DEAL_THRESHOLD, 1), 1);
        long counted = countedOf(userId);
        if (counted < threshold) {
            return;
        }
        Broker broker = new Broker(userId);
        attachToTree(broker);
        brokers.save(broker);
        origins.save(new BrokerOrigin(userId, BrokerOrigin.Origin.AUTO_UPGRADE, null, null));
        log.info("实名后回补升级:user={} 已成交={}单 归属服务站={}",
                userId, counted, broker.getStationOrgId());
    }

    private void maybeUpgrade(long sharerUserId, ShareConversion trigger, long needed) {
        // 建立归属关系:不论升不升级,这个人是分享人带来的,佣金要算给他
        if (invitations.findByWorkerUserId(trigger.getConvertedUserId()).isEmpty()) {
            invitations.save(new Invitation(sharerUserId, trigger.getConvertedUserId()));
        }
        if (brokers.existsById(sharerUserId)) {
            return;   // 已经是业务员,只记归属
        }
        if (verifiedUsers.findById(sharerUserId).isEmpty()) {
            // 没实名的人升不了 —— 业务员要被记进佣金归属、要能收钱
            log.info("分享人 {} 未实名,暂不升级为业务员", sharerUserId);
            return;
        }
        long counted = countedOf(sharerUserId);
        if (counted < needed) {
            log.info("分享人 {} 已成交 {} 单,还差 {} 单升级", sharerUserId, counted, needed - counted);
            return;
        }

        Broker broker = new Broker(sharerUserId);
        attachToTree(broker);
        brokers.save(broker);
        origins.save(new BrokerOrigin(sharerUserId, BrokerOrigin.Origin.AUTO_UPGRADE,
                trigger.getId(), null));
        log.info("业务员自动升级:user={} 成交={}单 归属服务站={}",
                sharerUserId, counted, broker.getStationOrgId());
    }

    /** 这个人名下所有分享带来的、已计数的成交数。**合并计数**:岗位和商品算在一起。 */
    private long countedOf(long sharerUserId) {
        List<Long> shareIds = shares.findAll().stream()
                .filter(s -> s.getSharerUserId() == sharerUserId)
                .map(Share::getId).toList();
        if (shareIds.isEmpty()) {
            return 0;
        }
        return conversions.findByShareIdIn(shareIds).stream()
                .filter(c -> c.getStatus() == ShareConversion.Status.COUNTED)
                .count();
    }

    /**
     * **自动上树。**新业务员挂到把他带进来的那个业务员下面,并继承对方的服务站。
     *
     * <p><b>父节点不能不设。</b>没有父节点就是根业务员,
     * **被动佣金那几档永远不会往上分** —— 而多级裂变的价值恰恰就在这里。
     * 我第一版只设了服务站没设父节点,等于把裂变做成了一层。
     *
     * <p>服务站按优先级往下落:
     * <ol>
     *   <li>随父节点继承(上树的自然结果)</li>
     *   <li>平台默认服务站(参数中心配置)</li>
     *   <li>没配时自动分配 —— 挑当前业务员最少的站</li>
     * </ol>
     *
     * <p>最后那级存在的理由:不归站的业务员在分账时**服务站那一档不会分成**,
     * 那笔钱留在池子里 —— 看着没出错,实际是有人该拿的钱没拿到,要等对账才发现。
     */
    private void attachToTree(Broker broker) {
        long userId = broker.getUserId();
        // 把这个人带进来的业务员 = 他在树上的父节点
        Broker parent = invitations.findByWorkerUserId(userId)
                .flatMap(inv -> brokers.findById(inv.getBrokerUserId()))
                .orElse(null);
        if (parent != null) {
            broker.assignParent(parent.getUserId());
            if (parent.getStationOrgId() != null) {
                broker.assignStation(parent.getStationOrgId());
                return;
            }
        }
        // 树上没有位置:**直接划给默认站长**。
        // 只给服务站不给父节点的话,这个人在树上仍是孤儿,被动佣金依旧分不上去
        Long station = fallbackStation();
        broker.assignStation(station);
        if (parent == null && station != null) {
            Long master = masterOf(station);
            if (master != null && master != broker.getUserId() && brokers.existsById(master)) {
                broker.assignParent(master);
            }
        }
    }

    /** 某个服务站的站长。副本里站长为 0 表示"暂时无人管理"。 */
    private Long masterOf(long stationOrgId) {
        return stations.findById(stationOrgId)
                .map(Station::getLegalRepUserId)
                .filter(id -> id > 0)
                .orElse(null);
    }

    /**
     * 没有归因的业务也要有主:**归默认站长**。
     *
     * <p>员工不经分享、自己直接报名或下单时,这一单原本没有任何归属 ——
     * 佣金那几档全都不分,钱留在池子里。让它归默认站长,平台的经营网点才对得上账。
     *
     * <p>只在**完全没有归属**时才建:已经有邀请关系的不动,
     * 否则会把别人带来的人抢过来(归属唯一,见 attribute 的注释)。
     */
    @Transactional("brokerTransactionManager")
    public void ensureAttributed(long workerUserId) {
        if (invitations.findByWorkerUserId(workerUserId).isPresent()) {
            return;
        }
        if (conversions.findByConvertedUserId(workerUserId).isPresent()) {
            return;   // 分享来的,等成交时按分享那条路归因
        }
        Long station = fallbackStation();
        if (station == null) {
            return;   // 一个服务站都没有,没得可归
        }
        Long master = masterOf(station);
        if (master == null || master == workerUserId || !brokers.existsById(master)) {
            // 默认站还没指派站长,或站长本人就是这个工人,或站长还不是业务员。
            // 硬建一条指向不存在业务员的归属,分账时会静默失败
            return;
        }
        invitations.save(new Invitation(master, workerUserId));
        log.info("无归因业务归默认站长:worker={} 站长={} 服务站={}", workerUserId, master, station);
    }

    /** 没能从树上继承到服务站时:先看平台默认,再自动分配。 */
    private Long fallbackStation() {
        long configured = opsApi.settingInt(SettingKeys.BROKER_DEFAULT_STATION_ORG_ID, 0);
        if (configured > 0) {
            if (stations.existsById(configured)) {
                return configured;
            }
            // 配成了一个不存在的编号。归到不存在的站上,分账时那一档会静默失败,
            // 所以退回自动分配,并**吼一声** —— 这是配置错了,得有人去改
            log.error("平台默认服务站 {} 不存在,本次改为自动分配。请到参数设置里改正", configured);
        }
        return leastLoadedStation();
    }

    /**
     * 站长自动成为本站业务员。
     *
     * <p>站长本来就在佣金树的顶端:「无归属业务归默认站长」那条规则要求他是业务员,
     * 不是的话规则会**静默跳过** —— 规则写了却不起作用,比没写更糟。
     *
     * <p>已经是业务员的只改归属站(可能是从别的站调过来的),不重复建。
     * 没实名的先跳过,等实名副本落地时由 {@link #onVerified} 补上。
     */
    @Transactional("brokerTransactionManager")
    public void ensureStationMasterIsBroker(long stationOrgId, long masterUserId) {
        Broker existing = brokers.findById(masterUserId).orElse(null);
        if (existing != null) {
            if (!java.util.Objects.equals(existing.getStationOrgId(), stationOrgId)) {
                existing.assignStation(stationOrgId);
                brokers.save(existing);
                log.info("站长 {} 已是业务员,归属站改为 {}", masterUserId, stationOrgId);
            }
            return;
        }
        if (verifiedUsers.findById(masterUserId).isEmpty()) {
            log.info("站长 {} 的实名副本尚未到达,暂不登记为业务员,实名后自动补", masterUserId);
            return;
        }
        Broker broker = new Broker(masterUserId);
        broker.assignStation(stationOrgId);
        brokers.save(broker);
        origins.save(new BrokerOrigin(masterUserId, BrokerOrigin.Origin.STATION_GRANT,
                stationOrgId, null));
        log.info("站长自动登记为业务员:user={} 服务站={}", masterUserId, stationOrgId);
    }

    /** 当前业务员最少的服务站;一个站都没有时返回 null。 */
    private Long leastLoadedStation() {
        return stations.findAllByOrderByOrgIdAsc().stream()
                .min(java.util.Comparator
                        .comparingLong((Station st) ->
                                brokers.findByStationOrgIdOrderByUserIdAsc(st.getOrgId()).size())
                        .thenComparingLong(Station::getOrgId))
                .map(Station::getOrgId)
                .orElse(null);
    }
}
