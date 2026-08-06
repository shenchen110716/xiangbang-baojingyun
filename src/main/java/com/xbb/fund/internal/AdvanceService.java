package com.xbb.fund.internal;

import com.xbb.fund.api.AdvanceApi;
import com.xbb.identity.api.IdentityApi;
import com.xbb.identity.api.Role;
import com.xbb.ops.api.OpsApi;
import com.xbb.ops.api.SettingKeys;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * 借支与还款(老系统 M8「借押保」)。
 *
 * <p>老系统的三条规则原样保留:借支不超可用额度、每笔留痕、发工资时从工资直接抵扣。
 */
@Service
class AdvanceService implements AdvanceApi {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AdvanceService.class);

    private final AdvanceRepository advances;
    private final AdvanceRepaymentRepository repayments;
    private final IdentityApi identityApi;
    /** 判断"这个人是不是这家单位的法人代表"。**现查不缓存**(铁律 5)。 */
    private final com.xbb.org.api.OrgApi orgApi;
    private final OpsApi opsApi;

    AdvanceService(AdvanceRepository advances, AdvanceRepaymentRepository repayments,
                   IdentityApi identityApi, com.xbb.org.api.OrgApi orgApi, OpsApi opsApi) {
        this.advances = advances;
        this.repayments = repayments;
        this.identityApi = identityApi;
        this.orgApi = orgApi;
        this.opsApi = opsApi;
    }

    private void requirePlatformOps(long callerUserId) {
        if (!identityApi.hasRole(callerUserId, Role.PLATFORM_OPS)) {
            throw new AccessDeniedException("需要平台运维权限");
        }
    }

    /**
     * 批一笔借支。
     *
     * <p><b>额度上限从参数中心读,不写死。</b>各地工价差很多,写死的额度要么卡住高薪岗位,
     * 要么给低薪岗位批出还不起的钱 —— 而改一个写死的数字要发一次版。
     */
    @Override
    @Transactional("fundTransactionManager")
    public long grantAdvance(long workerUserId, long amountCents, String reason, long callerUserId) {
        return grantAdvance(null, workerUserId, amountCents, reason, callerUserId);
    }

    /**
     * 用工单位批一笔借支。老板 2026-08-06:借支属于机构端。
     *
     * <p>{@code orgId} 传 null 表示平台自己垫 —— 那条仍然只有平台运维能走。
     *
     * <p><b>批了之后只能从这家单位的结算里扣回来。</b>
     * 不记归属的话,甲公司批的借支会从乙公司给同一个工人的付款里扣走。
     */
    @Override
    @Transactional("fundTransactionManager")
    public long grantAdvance(Long orgId, long workerUserId, long amountCents,
                              String reason, long callerUserId) {
        if (orgId == null) {
            requirePlatformOps(callerUserId);
        } else if (!orgApi.isLegalRepOf(orgId, callerUserId)
                && !identityApi.hasRole(callerUserId, Role.PLATFORM_OPS)) {
            // 替别家批借支等于替别家承诺了一笔要从工资里扣回来的钱
            throw new AccessDeniedException(
                    "只有单位 #" + orgId + " 的法人代表或平台运维可以批借支");
        }
        if (amountCents <= 0) {
            throw new IllegalArgumentException("借支金额必须为正数");
        }
        if (reason == null || reason.isBlank()) {
            // 借支是平台先垫钱,事后要说得清为什么垫。没有理由的借支在对账时是无解的
            throw new IllegalArgumentException("请填写借支事由");
        }

        long limit = opsApi.settingInt(SettingKeys.ADVANCE_MAX_OUTSTANDING_CENTS, 300_000);
        long outstanding = outstandingOf(workerUserId);
        if (outstanding + amountCents > limit) {
            // 老系统那条"借支不超可用额度"。**连同已欠的一起算** ——
            // 只看单笔的话,借十次小额就绕过了上限
            throw new IllegalStateException(String.format(
                    "超出借支额度:已欠 %d 元,本次 %d 元,上限 %d 元",
                    outstanding / 100, amountCents / 100, limit / 100));
        }

        Advance saved = advances.save(
                new Advance(workerUserId, amountCents, reason.trim(), callerUserId, orgId));
        log.info("借支已批:advance={} worker={} 金额={}分 已欠合计={}分 上限={}分",
                saved.getId(), workerUserId, amountCents, outstanding + amountCents, limit);
        return saved.getId();
    }

    /**
     * 从工资里抵扣。**由资金域在建待发放单时调用,和建单在同一个事务里** ——
     * 分开两个事务的话,中间崩掉就会出现"扣了但没建单"或"建了单没扣"。
     *
     * <p>先借的先还。返回实际抵扣总额,可能小于工资(欠得少)也可能等于工资(欠得多)。
     *
     * @return 本次从这张结算单里扣掉的总额(分)
     */
    /**
     * @param settlementOrgId 这笔结算的出资单位;为 null 时只有平台垫的借支可扣
     */
    long deductFromSalary(long workerUserId, long settlementId, long salaryCents,
                           Long settlementOrgId) {
        if (salaryCents <= 0) {
            return 0;
        }
        // **只扣这家单位批的,加上平台垫的。**不过滤的话,甲公司批的借支
        // 会从乙公司给同一个工人的付款里扣走 —— 甲的钱没出、乙的工人少拿了,
        // 而两边都不会报错,只有工人自己发现工资少了才会问
        List<Advance> active = advances.findDeductible(
                workerUserId, Advance.Status.ACTIVE, settlementOrgId);
        long remaining = salaryCents;
        long deducted = 0;

        for (Advance advance : active) {
            if (remaining <= 0) {
                break;
            }
            long applied = advance.repay(remaining);
            advances.save(advance);
            repayments.save(AdvanceRepayment.fromSalary(advance.getId(), applied, settlementId));
            remaining -= applied;
            deducted += applied;
        }

        if (deducted > 0) {
            log.info("工资抵扣借支:worker={} settlement={} 应发={}分 抵扣={}分 实发={}分",
                    workerUserId, settlementId, salaryCents, deducted, salaryCents - deducted);
        }
        return deducted;
    }

    /** 登记线下还款。 */
    @Override
    @Transactional("fundTransactionManager")
    public void recordManualRepayment(long advanceId, long amountCents, long callerUserId) {
        requirePlatformOps(callerUserId);
        Advance advance = advances.findById(advanceId)
                .orElseThrow(() -> new IllegalArgumentException("借支记录不存在"));
        // **先判状态再判金额。**倒过来的话,给一笔已结清的借支登记还款会得到
        // "还款金额 1 元超过未还的 0 元" —— 技术上拦住了,但没告诉人真正的原因是它已经结清了
        if (advance.getStatus() != Advance.Status.ACTIVE) {
            throw new IllegalStateException(
                    "这笔借支已" + (advance.getStatus() == Advance.Status.CLEARED ? "结清" : "撤销") + ",不能再还款");
        }
        if (amountCents > advance.getOutstandingCents()) {
            // 还多了通常是登记时手滑多打一位。让它过去的话,数据库的 CHECK 会拦下来,
            // 但报错是"违反约束",看不出是什么问题
            throw new IllegalArgumentException(String.format(
                    "还款金额 %d 元超过未还的 %d 元", amountCents / 100, advance.getOutstandingCents() / 100));
        }
        long applied = advance.repay(amountCents);
        advances.save(advance);
        repayments.save(AdvanceRepayment.manual(advanceId, applied, callerUserId));
        log.info("登记线下还款:advance={} 金额={}分 剩余={}分", advanceId, applied, advance.getOutstandingCents());
    }

    @Override
    @Transactional("fundTransactionManager")
    public void cancelAdvance(long advanceId, long callerUserId) {
        requirePlatformOps(callerUserId);
        Advance advance = advances.findById(advanceId)
                .orElseThrow(() -> new IllegalArgumentException("借支记录不存在"));
        advance.cancel();
        advances.save(advance);
    }

    @Override
    @Transactional(transactionManager = "fundTransactionManager", readOnly = true)
    public long outstandingOf(long workerUserId) {
        return advances.findByWorkerUserIdAndStatusOrderByIdAsc(workerUserId, Advance.Status.ACTIVE)
                .stream().mapToLong(Advance::getOutstandingCents).sum();
    }

    @Override
    @Transactional(transactionManager = "fundTransactionManager", readOnly = true)
    public List<AdvanceView> listMine(long workerUserId) {
        return advances.findByWorkerUserIdOrderByIdDesc(workerUserId).stream()
                .map(AdvanceService::toView).toList();
    }

    @Override
    @Transactional(transactionManager = "fundTransactionManager", readOnly = true)
    public List<AdvanceView> listOf(long workerUserId, long callerUserId) {
        // 查别人的借支要平台运维。**看不见时返回空而不是抛异常** ——
        // 抛异常会顺带确认这个人有没有借支记录(见铁律 5.1)
        if (workerUserId != callerUserId && !identityApi.hasRole(callerUserId, Role.PLATFORM_OPS)) {
            return List.of();
        }
        return listMine(workerUserId);
    }

    @Override
    @Transactional(transactionManager = "fundTransactionManager", readOnly = true)
    public Optional<AdvanceView> findById(long advanceId, long callerUserId) {
        return advances.findById(advanceId)
                .filter(a -> a.getWorkerUserId() == callerUserId
                        || identityApi.hasRole(callerUserId, Role.PLATFORM_OPS))
                .map(AdvanceService::toView);
    }

    @Override
    @Transactional(transactionManager = "fundTransactionManager", readOnly = true)
    public List<RepaymentView> repaymentsOf(long advanceId, long callerUserId) {
        if (findById(advanceId, callerUserId).isEmpty()) {
            return List.of();
        }
        return repayments.findByAdvanceIdOrderByIdAsc(advanceId).stream()
                .map(r -> new RepaymentView(r.getId(), r.getAdvanceId(), r.getAmountCents(),
                        r.getSource().name(), r.getSettlementId(), r.getCreatedAt()))
                .toList();
    }

    private static AdvanceView toView(Advance a) {
        return new AdvanceView(a.getId(), a.getWorkerUserId(), a.getAmountCents(),
                a.getOutstandingCents(), a.getStatus().name(), a.getReason(),
                a.getCreatedAt(), a.getClearedAt());
    }
}
