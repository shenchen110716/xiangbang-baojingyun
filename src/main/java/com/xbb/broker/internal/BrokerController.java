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
}
