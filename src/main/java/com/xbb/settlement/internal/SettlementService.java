package com.xbb.settlement.internal;

import com.xbb.settlement.api.SettlementApi;
import com.xbb.settlement.api.SettlementVoided;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xbb.identity.api.IdentityApi;
import com.xbb.identity.api.Role;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Service
class SettlementService implements SettlementApi {

    private final SettlementRepository settlements;
    private final SettlementOutboxRepository outbox;
    private final ObjectMapper json;
    private final IdentityApi identityApi;

    SettlementService(SettlementRepository settlements,
                     SettlementOutboxRepository outbox, ObjectMapper json,
                       IdentityApi identityApi) {
        this.settlements = settlements;
        this.outbox = outbox;
        this.json = json;
        this.identityApi = identityApi;
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
    @Transactional("settlementTransactionManager")
    public void voidSettlement(long settlementId, String reason, long callerUserId) {
        requirePlatformOps(callerUserId);
        Settlement settlement = settlements.findById(settlementId)
                .orElseThrow(() -> new IllegalArgumentException("结算记录不存在"));
        settlement.voidSettlement(reason);
        settlements.save(settlement);
        SettlementVoided voided = new SettlementVoided(settlementId, reason, Instant.now());
        outbox.save(new SettlementOutboxEvent(java.util.UUID.randomUUID().toString(),
                SettlementVoided.class.getName(), serialize(voided)));
    }

    @Override
    @Transactional(transactionManager = "settlementTransactionManager", readOnly = true)
    public Optional<SettlementView> findById(long settlementId) {
        return settlements.findById(settlementId).map(this::toView);
    }

    @Override
    @Transactional(transactionManager = "settlementTransactionManager", readOnly = true)
    public Optional<SettlementView> findByApplicationId(long applicationId) {
        return settlements.findByApplicationId(applicationId).map(this::toView);
    }

    private SettlementView toView(Settlement s) {
        return new SettlementView(
                s.getId(), s.getApplicationId(), s.getJobId(), s.getWorkerUserId(),
                s.getAmountCents(), s.getStatus(), s.getVoidReason());
    }

    @Override
    @Transactional(transactionManager = "settlementTransactionManager", readOnly = true)
    public List<SettlementView> listMySettlements(long workerUserId) {
        return settlements.findByWorkerUserIdOrderByIdDesc(workerUserId).stream()
                .map(s -> new SettlementView(s.getId(), s.getApplicationId(), s.getJobId(),
                        s.getWorkerUserId(), s.getAmountCents(), s.getStatus(), s.getVoidReason()))
                .toList();
    }
}
