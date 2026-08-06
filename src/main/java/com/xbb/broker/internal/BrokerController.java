package com.xbb.broker.internal;

import jakarta.validation.Valid;
import com.xbb.broker.api.BrokerApi;
import com.xbb.security.AuthenticatedUser;
import jakarta.validation.constraints.Positive;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/broker")
class BrokerController {

    private final BrokerApi brokerApi;

    BrokerController(BrokerApi brokerApi) {
        this.brokerApi = brokerApi;
    }

    record BindRequest(@Positive long workerUserId) { }

    @PostMapping("/register")
    ResponseEntity<Void> register(@AuthenticationPrincipal AuthenticatedUser caller) {
        brokerApi.registerBroker(caller.userId());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/bind")
    ResponseEntity<Map<String, Long>> bind(@AuthenticationPrincipal AuthenticatedUser caller,
                                            @RequestBody @Valid BindRequest req) {
        long invitationId = brokerApi.bindWorker(caller.userId(), req.workerUserId());
        return ResponseEntity.ok(Map.of("id", invitationId));
    }

    @GetMapping("/commission/{id}")
    ResponseEntity<BrokerApi.CommissionView> getCommission(@PathVariable long id,
                                                            @AuthenticationPrincipal AuthenticatedUser caller) {
        return brokerApi.findCommission(id, caller.userId()).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PutMapping("/commission/{id}/pay")
    ResponseEntity<Void> payCommission(@PathVariable long id,
                                        @AuthenticationPrincipal AuthenticatedUser caller) {
        brokerApi.payCommission(id, caller.userId());
        return ResponseEntity.noContent().build();
    }

    // 400/409 等错误映射统一收在 com.xbb.web.GlobalExceptionHandler

    // ─────────────── 服务站与业务员网络(平台侧) ───────────────
    // 角色校验一律在服务层。控制器不重复判断 —— 两处判断迟早分叉。

    record StationPercentRequest(Integer percent, @jakarta.validation.constraints.NotBlank String reason) { }
    record AssignRequest(Long targetId, @jakarta.validation.constraints.NotBlank String reason) { }

    @GetMapping("/stations")
    ResponseEntity<List<BrokerApi.StationView>> stations(@AuthenticationPrincipal AuthenticatedUser caller) {
        return ResponseEntity.ok(brokerApi.listStations(caller.userId()));
    }

    /**
     * 设服务站佣金比例。**请求体里 percent 省略或为 null = 跟随平台默认**,
     * 不是"设成 0"。这两者含义完全不同,界面上也要分开表达。
     */
    @PutMapping("/stations/{orgId}/percent")
    ResponseEntity<Void> setStationPercent(@PathVariable long orgId,
                                           @RequestBody @Valid StationPercentRequest req,
                                           @AuthenticationPrincipal AuthenticatedUser caller) {
        brokerApi.setStationPercent(orgId, req.percent(), req.reason(), caller.userId());
        return ResponseEntity.noContent().build();
    }

    /** 业务员列表。stationOrgId 省略时返回全部。 */
    @GetMapping("/salesmen")
    ResponseEntity<List<BrokerApi.BrokerNodeView>> salesmen(
            @RequestParam(required = false) Long stationOrgId,
            @AuthenticationPrincipal AuthenticatedUser caller) {
        return ResponseEntity.ok(brokerApi.listBrokers(stationOrgId, caller.userId()));
    }

    /** 改挂靠服务站。targetId 为 null = 从服务站摘除。 */
    @PutMapping("/salesmen/{userId}/station")
    ResponseEntity<Void> assignStation(@PathVariable long userId, @RequestBody @Valid AssignRequest req,
                                       @AuthenticationPrincipal AuthenticatedUser caller) {
        brokerApi.assignStation(userId, req.targetId(), req.reason(), caller.userId());
        return ResponseEntity.noContent().build();
    }

    /** 改上级。targetId 为 null = 变成根业务员(根不参与降级)。 */
    @PutMapping("/salesmen/{userId}/parent")
    ResponseEntity<Void> assignParent(@PathVariable long userId, @RequestBody @Valid AssignRequest req,
                                      @AuthenticationPrincipal AuthenticatedUser caller) {
        brokerApi.assignParent(userId, req.targetId(), req.reason(), caller.userId());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/salesmen/changes")
    ResponseEntity<List<BrokerApi.BrokerChangeView>> changes(
            @RequestParam(required = false) Long brokerUserId,
            @AuthenticationPrincipal AuthenticatedUser caller) {
        return ResponseEntity.ok(brokerApi.brokerChanges(brokerUserId, caller.userId()));
    }

    /** 手动跑一次降级。定时任务每天凌晨 3 点自动跑,这里是补跑/验证入口。 */
    @PostMapping("/salesmen/run-demotion")
    ResponseEntity<Map<String, Integer>> runDemotion(@AuthenticationPrincipal AuthenticatedUser caller) {
        return ResponseEntity.ok(Map.of("processed", brokerApi.runDemotionNow(caller.userId())));
    }

    // ─────────────── 服务站间联合(老系统 M10 §3.4) ───────────────

    record ApplyJointRequest(
            @jakarta.validation.constraints.Positive(message = "请选择发起方服务站") long fromOrgId,
            @jakarta.validation.constraints.Positive(message = "请选择联合方服务站") long toOrgId,
            @jakarta.validation.constraints.Min(value = 1, message = "分成比例必须在 1% 到 99% 之间")
            @jakarta.validation.constraints.Max(value = 99, message = "分成比例必须在 1% 到 99% 之间")
            int ratePercent) { }

    /** 发起联合申请。只有发起方站长能发 —— 这一步是在决定把自己的佣金分出去。 */
    @PostMapping("/joints")
    ResponseEntity<java.util.Map<String, Long>> applyJoint(
            @RequestBody @jakarta.validation.Valid ApplyJointRequest req,
            @AuthenticationPrincipal AuthenticatedUser caller) {
        long id = brokerApi.applyJoint(req.fromOrgId(), req.toOrgId(), req.ratePercent(), caller.userId());
        return ResponseEntity.ok(java.util.Map.of("id", id));
    }

    /** 确认联合。**只有被邀请方站长能确认**,否则那个两步流程形同虚设。 */
    @PutMapping("/joints/{id}/confirm")
    ResponseEntity<Void> confirmJoint(@PathVariable long id,
                                      @AuthenticationPrincipal AuthenticatedUser caller) {
        brokerApi.confirmJoint(id, caller.userId());
        return ResponseEntity.noContent().build();
    }

    /** 撤回未确认的申请(发起方)。 */
    @PutMapping("/joints/{id}/cancel")
    ResponseEntity<Void> cancelJoint(@PathVariable long id,
                                     @AuthenticationPrincipal AuthenticatedUser caller) {
        brokerApi.cancelJoint(id, caller.userId());
        return ResponseEntity.noContent().build();
    }

    /** 解除已生效的联合。任一方都可以 —— 合作是双方的。 */
    @PutMapping("/joints/{id}/end")
    ResponseEntity<Void> endJoint(@PathVariable long id,
                                  @AuthenticationPrincipal AuthenticatedUser caller) {
        brokerApi.endJoint(id, caller.userId());
        return ResponseEntity.noContent().build();
    }

    /** 某服务站相关的全部联合。分成比例是两家的商业约定,路人拿到空列表(铁律 5.1)。 */
    @GetMapping("/joints/station/{orgId}")
    ResponseEntity<java.util.List<BrokerApi.StationJointView>> listJoints(
            @PathVariable long orgId, @AuthenticationPrincipal AuthenticatedUser caller) {
        return ResponseEntity.ok(brokerApi.listJoints(orgId, caller.userId()));
    }

    // ─────────────── 分成比例(按业务类目) ───────────────

    record RateRequest(
            /** 传 null 表示设平台默认(对所有没单独设过的站生效)。 */
            Long stationOrgId,
            @jakarta.validation.constraints.NotBlank(message = "请选择业务类目") String category,
            @jakarta.validation.constraints.Min(value = 0, message = "分成比例必须在 0 到 100 之间")
            @jakarta.validation.constraints.Max(value = 100, message = "分成比例必须在 0 到 100 之间")
            int percent,
            @jakarta.validation.constraints.NotBlank(message = "请填写调整原因") String reason) { }

    @PutMapping("/rates")
    ResponseEntity<Void> setRate(@RequestBody @jakarta.validation.Valid RateRequest req,
                                 @AuthenticationPrincipal AuthenticatedUser caller) {
        brokerApi.setStationRate(req.stationOrgId(), req.category(), req.percent(),
                req.reason(), caller.userId());
        return ResponseEntity.noContent().build();
    }

    /** 平台默认那几档。**排在 /rates/{orgId} 之前**,否则 "defaults" 会被当成路径变量。 */
    record CommissionRateRequest(
            @jakarta.validation.constraints.NotBlank(message = "请选择业务类目") String category,
            /** 国标行政区划代码。**留空 = 全国兜底。** */
            String regionCode,
            int commissionPct, int dispatchRetainPct,
            /** 收留存的派遣公司。留了比例就必须指定,否则那笔钱挂不到任何收款方。 */
            Long dispatchOrgId,
            @jakarta.validation.constraints.NotBlank(message = "请填写调整原因") String reason) { }

    /**
     * 配总价模式的佣金比例(类目 + 地区)。
     *
     * <p><b>改比例只影响之后发的单</b> —— 已发出的单在发单时就把分账定死了。
     */
    @PutMapping("/commission-rates")
    ResponseEntity<Void> setCommissionRate(@RequestBody @jakarta.validation.Valid CommissionRateRequest req,
                                            @AuthenticationPrincipal AuthenticatedUser caller) {
        brokerApi.setCommissionRate(req.category(), req.regionCode(), req.commissionPct(),
                req.dispatchRetainPct(), req.dispatchOrgId(), req.reason(), caller.userId());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/commission-rates")
    ResponseEntity<java.util.List<BrokerApi.CommissionRateView>> listCommissionRates(
            @AuthenticationPrincipal AuthenticatedUser caller) {
        return ResponseEntity.ok(brokerApi.listCommissionRates(caller.userId()));
    }

    @GetMapping("/rates/defaults")
    ResponseEntity<java.util.List<BrokerApi.StationRateView>> defaultRates(
            @AuthenticationPrincipal AuthenticatedUser caller) {
        return ResponseEntity.ok(brokerApi.listStationRates(null, caller.userId()));
    }

    @GetMapping("/rates/{orgId}")
    ResponseEntity<java.util.List<BrokerApi.StationRateView>> stationRates(
            @PathVariable long orgId, @AuthenticationPrincipal AuthenticatedUser caller) {
        return ResponseEntity.ok(brokerApi.listStationRates(orgId, caller.userId()));
    }

    // ─────────────── 分享与业务员产生 ───────────────

    record ShareRequest(
            @jakarta.validation.constraints.NotBlank(message = "请指定分享类型") String targetType,
            @jakarta.validation.constraints.Positive(message = "请指定分享对象") long targetId) { }

    record AttributeRequest(
            @jakarta.validation.constraints.NotBlank(message = "缺少分享码") String code) { }

    record GrantRequest(
            @jakarta.validation.constraints.Positive(message = "请选择服务站") long stationOrgId,
            @jakarta.validation.constraints.Positive(message = "请选择用户") long userId) { }

    /** 分享岗位/商品,拿到分享码。同一个人重复分享同一个东西返回同一个码。 */
    @PostMapping("/shares")
    ResponseEntity<java.util.Map<String, String>> share(
            @RequestBody @jakarta.validation.Valid ShareRequest req,
            @AuthenticationPrincipal AuthenticatedUser caller) {
        return ResponseEntity.ok(java.util.Map.of(
                "code", brokerApi.share(caller.userId(), req.targetType(), req.targetId())));
    }

    /**
     * 我是通过某个分享码进来的。**归属唯一** —— 已归属别人的不会被改,
     * 这时返回 {@code attributed:false},不是错误。
     */
    @PostMapping("/shares/attribute")
    ResponseEntity<java.util.Map<String, Boolean>> attribute(
            @RequestBody @jakarta.validation.Valid AttributeRequest req,
            @AuthenticationPrincipal AuthenticatedUser caller) {
        return ResponseEntity.ok(java.util.Map.of(
                "attributed", brokerApi.attributeShare(req.code(), caller.userId())));
    }

    /** 站长授权某人成为本站业务员。 */
    @PostMapping("/salesmen/grant")
    ResponseEntity<Void> grant(@RequestBody @jakarta.validation.Valid GrantRequest req,
                               @AuthenticationPrincipal AuthenticatedUser caller) {
        brokerApi.grantBroker(req.stationOrgId(), req.userId(), caller.userId());
        return ResponseEntity.noContent().build();
    }

    /** 这个人凭什么是业务员。本人或平台运维可见。 */
    @GetMapping("/salesmen/{userId}/origin")
    ResponseEntity<BrokerApi.BrokerOriginView> origin(
            @PathVariable long userId, @AuthenticationPrincipal AuthenticatedUser caller) {
        return brokerApi.brokerOrigin(userId, caller.userId())
                .map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    // ─────────────── 服务站与用工单位的合作 ───────────────

    record ApplyCoopRequest(
            @jakarta.validation.constraints.Positive(message = "请选择服务站") long stationOrgId,
            @jakarta.validation.constraints.Positive(message = "请选择用工单位") long partnerOrgId,
            boolean initiatedByStation) { }

    record OperatorRequest(
            @jakarta.validation.constraints.Positive(message = "请选择用户") long userId) { }

    @PostMapping("/cooperations")
    ResponseEntity<java.util.Map<String, Long>> applyCoop(
            @RequestBody @jakarta.validation.Valid ApplyCoopRequest req,
            @AuthenticationPrincipal AuthenticatedUser caller) {
        long id = brokerApi.applyCooperation(req.stationOrgId(), req.partnerOrgId(),
                req.initiatedByStation(), caller.userId());
        return ResponseEntity.ok(java.util.Map.of("id", id));
    }

    /** 确认合作。**只有被申请的那一方能确认**,否则两步流程形同虚设。 */
    @PutMapping("/cooperations/{id}/confirm")
    ResponseEntity<Void> confirmCoop(@PathVariable long id,
                                     @AuthenticationPrincipal AuthenticatedUser caller) {
        brokerApi.confirmCooperation(id, caller.userId());
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/cooperations/{id}/cancel")
    ResponseEntity<Void> cancelCoop(@PathVariable long id,
                                    @AuthenticationPrincipal AuthenticatedUser caller) {
        brokerApi.cancelCooperation(id, caller.userId());
        return ResponseEntity.noContent().build();
    }

    /** 解除合作。会连带解绑该合作下的操作员。 */
    @PutMapping("/cooperations/{id}/end")
    ResponseEntity<Void> endCoop(@PathVariable long id,
                                 @AuthenticationPrincipal AuthenticatedUser caller) {
        brokerApi.endCooperation(id, caller.userId());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/cooperations/org/{orgId}")
    ResponseEntity<java.util.List<BrokerApi.CooperationView>> listCoops(
            @PathVariable long orgId, @AuthenticationPrincipal AuthenticatedUser caller) {
        return ResponseEntity.ok(brokerApi.listCooperations(orgId, caller.userId()));
    }

    /** 指派操作员。只有站长能派,且合作要已生效。 */
    @PostMapping("/cooperations/{id}/operators")
    ResponseEntity<java.util.Map<String, Long>> assignOperator(
            @PathVariable long id, @RequestBody @jakarta.validation.Valid OperatorRequest req,
            @AuthenticationPrincipal AuthenticatedUser caller) {
        return ResponseEntity.ok(java.util.Map.of(
                "id", brokerApi.assignOperator(id, req.userId(), caller.userId())));
    }

    @DeleteMapping("/cooperations/{id}/operators/{userId}")
    ResponseEntity<Void> revokeOperator(@PathVariable long id, @PathVariable long userId,
                                        @AuthenticationPrincipal AuthenticatedUser caller) {
        brokerApi.revokeOperator(id, userId, caller.userId());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/cooperations/{id}/operators")
    ResponseEntity<java.util.List<BrokerApi.OperatorView>> listOperators(
            @PathVariable long id, @AuthenticationPrincipal AuthenticatedUser caller) {
        return ResponseEntity.ok(brokerApi.listOperators(id, caller.userId()));
    }

    // ─────────────── 按类目的整套分配方案 ───────────────

    record SchemeRequest(
            Long stationOrgId,
            @jakarta.validation.constraints.NotBlank(message = "请选择业务类目") String category,
            int activePct, int platformPct, int passivePct, int stationPct,
            int passiveStepPct, long minPayoutCents,
            @jakarta.validation.constraints.NotBlank(message = "请填写调整原因") String reason) { }

    @PutMapping("/schemes")
    ResponseEntity<Void> setScheme(@RequestBody @jakarta.validation.Valid SchemeRequest req,
                                   @AuthenticationPrincipal AuthenticatedUser caller) {
        brokerApi.setScheme(req.stationOrgId(), req.category(), req.activePct(), req.platformPct(),
                req.passivePct(), req.stationPct(), req.passiveStepPct(), req.minPayoutCents(),
                req.reason(), caller.userId());
        return ResponseEntity.noContent().build();
    }

    /** 平台默认那几套。**排在 /{orgId} 之前**,否则 "defaults" 会被当成路径变量。 */
    @GetMapping("/schemes/defaults")
    ResponseEntity<java.util.List<BrokerApi.SchemeView>> defaultSchemes(
            @AuthenticationPrincipal AuthenticatedUser caller) {
        return ResponseEntity.ok(brokerApi.listSchemes(null, caller.userId()));
    }

    @GetMapping("/schemes/{orgId}")
    ResponseEntity<java.util.List<BrokerApi.SchemeView>> stationSchemes(
            @PathVariable long orgId, @AuthenticationPrincipal AuthenticatedUser caller) {
        return ResponseEntity.ok(brokerApi.listSchemes(orgId, caller.userId()));
    }
}
