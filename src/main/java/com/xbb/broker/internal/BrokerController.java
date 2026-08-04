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
    ResponseEntity<BrokerApi.CommissionView> getCommission(@PathVariable long id) {
        return brokerApi.findCommission(id).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
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
}
