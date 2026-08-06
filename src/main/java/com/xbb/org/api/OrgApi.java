package com.xbb.org.api;

import com.xbb.org.internal.Organization;
import java.util.List;
import java.util.Optional;

public interface OrgApi {

    /** @param legalRepUserId 可能为 null —— 平台刚设立、还没指派站长的服务站 */
    /**
     * @param creditCode <b>个人主体为 null</b> —— 他没有统一社会信用代码
     * @param address 可能为 null —— 老数据没有地址
     */
    record OrgView(long id, com.xbb.org.api.OrgType type, String name, String creditCode,
                    Long legalRepUserId, Organization.Status status,
                    SubjectType subjectType, String address) { }

    long submit(com.xbb.org.api.OrgType type, String name, String creditCode, long legalRepUserId);

    /** 带地址提交。求职端要显示"在哪上班",没有地址的话卡片上只能是空白。 */
    long submitWithAddress(com.xbb.org.api.OrgType type, String name, String creditCode,
                            long legalRepUserId, String address);

    /**
     * 平台设立**个人**服务站。个人没有统一社会信用代码,所以不收这一项。
     *
     * <p>和平台设立公司服务站不同的是:个人主体**必须当场指定是谁** ——
     * "个人主体"指的就是那个人,没有人的个人服务站不知道在说谁。
     */
    long createIndividualStation(String name, long personUserId, String address, long callerUserId);

    /**
     * 通用的个人主体建立入口。**只有服务站能是个人** ——
     * 传别的类型会被拒,用工主体是个人的话劳务合同、完税凭证、保证金全都没有落脚点。
     */
    long createIndividualOrg(com.xbb.org.api.OrgType type, String name, long personUserId,
                                String address, long callerUserId);

    /**
     * 平台直接设立服务站。**建出来就是已审核、且还没有站长。**
     *
     * <p>服务站是平台自己的经营网点,不是入驻商户:平台先规划好点位,再决定派谁去管。
     * 按"用户提交 → 平台审核"那条路的话,得先找到一个人、让他去提交申请,点位才存在 ——
     * 顺序反了,而且换站长时没有任何办法。
     *
     * <p>工厂与企业不走这条,仍然是用户提交、平台审核。
     */
    long createStation(String name, String creditCode, long callerUserId);

    /**
     * 指派或更换站长。**可以改**,每次变更留痕。
     *
     * <p>换站长会改变谁能设分成比例、谁能签联合协议,所以要求填原因,
     * 事后查得到是谁在什么时候换的(老系统 M10 §4.3"先留痕后变更")。
     *
     * @param newMasterUserId 新站长;传 null 表示撤下当前站长,该站暂时无人管理
     */
    void assignStationMaster(long orgId, Long newMasterUserId, String reason, long callerUserId);

    record MasterChangeView(long id, long orgId, Long oldUserId, Long newUserId,
                            long changedBy, String reason, java.time.Instant changedAt) { }

    List<MasterChangeView> stationMasterChanges(long orgId, long callerUserId);

    void approve(long orgId, long callerUserId);

    void reject(long orgId, long callerUserId);

    /**
      * 组织详情。含统一社会信用代码与法人代表,只有法人代表本人或平台运维看得到。
      *
      * <p>招聘信息本身是公开的(见 JobApi),但"这家组织的信用代码和法人是谁"不是 ——
      * 那是把人和企业对应起来的东西。
      */
    Optional<OrgView> findById(long orgId, long callerUserId);

    /**
     * 这个人是不是这个组织的法人代表 / 站长。
     *
     * <p>**存在的理由:别的域需要判断归属,但不该因此拿到整个组织。**
     * findById 会连信用代码一起给出去,而调用方只想要一个是非。
     * 我第一版是拿一个假的"内部身份"去调 findById 绕过归属校验 ——
     * 那是在别处开洞:任何人读到那个常量就能照抄一份。
     *
     * <p>只回答是非,不泄露任何组织信息,所以对所有调用方开放。
     */
    boolean isLegalRepOf(long orgId, long userId);

    /** 组织类型与状态。合作/联合要据此判断"对方是不是用工单位"。 */
    Optional<OrgSummary> summaryOf(long orgId);

    /**
     * 不含信用代码与法人的公开摘要。
     *
     * <p>**用 approved 而不是把 Organization.Status 暴露出来** ——
     * 那个枚举在 org.internal 里,放进 api 包等于把内部类型漏给所有调用方,
     * 以后想加一个状态就会牵动别的域。ModularityTests 当场抓住了这一点。
     * 调用方真正关心的只有"能不能用",一个布尔就够。
     */
    record OrgSummary(long id, com.xbb.org.api.OrgType type, String name, boolean approved) { }

    /** 某人作为法人代表的组织列表。 */
    List<OrgView> listByLegalRep(long legalRepUserId);

    /**
     * 这个人是不是**任何一家已审核组织**的法人代表。
     *
     * <p>调用方要的只是这个布尔值。让它自己去 {@code listByLegalRep} 里筛
     * {@code status == APPROVED} 的话,就得引用 {@code Organization.Status} ——
     * 那是内部类型,ModularityTests 会拦(2026-08-07 审计时就撞上了)。
     *
     * <p><b>现查不缓存</b>(铁律 5):组织被驳回或法人换人之后,
     * 原来那个人立刻就不该再有这个身份。
     */
    boolean isApprovedLegalRepOfAny(long userId);

    /**
     * 待审核组织队列。要 {@link com.xbb.identity.api.Role#PLATFORM_OPS} ——
     * 这是平台的活儿,没有"归属"可查,只能靠角色(见铁律 5)。
     */
    List<OrgView> listPending(long callerUserId);
}
