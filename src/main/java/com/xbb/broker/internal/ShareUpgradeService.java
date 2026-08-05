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
        broker.assignStation(resolveStation(sharerUserId));
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
     * 新业务员归哪个站:**继承分享人自己所属的服务站**;
     * 继承不到就归平台默认服务站(参数中心配)。
     *
     * <p>默认站配成 0 表示暂不归站,由平台事后指派 —— 那种情况服务站那一档不分成,
     * 钱留在池子里,不会凭空发给某个站。
     */
    private Long resolveStation(long sharerUserId) {
        Long inherited = invitations.findByWorkerUserId(sharerUserId)
                .flatMap(inv -> brokers.findById(inv.getBrokerUserId()))
                .map(Broker::getStationOrgId)
                .orElse(null);
        if (inherited != null) {
            return inherited;
        }
        long fallback = opsApi.settingInt(SettingKeys.BROKER_DEFAULT_STATION_ORG_ID, 0);
        if (fallback <= 0) {
            return null;
        }
        // 默认站可能被改成一个不存在的编号。归到不存在的站上,
        // 分账时那一档会静默失败 —— 宁可不归站
        return stations.existsById(fallback) ? fallback : null;
    }
}
