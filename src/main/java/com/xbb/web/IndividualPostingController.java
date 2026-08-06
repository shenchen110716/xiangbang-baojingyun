package com.xbb.web;

import com.xbb.broker.api.BrokerApi;
import com.xbb.broker.api.RateCategory;
import com.xbb.job.api.JobApi;
import com.xbb.security.AuthenticatedUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 个人发单(老板 2026-08-06)。只填总价,员工价和佣金按「类目 + 地区」的比例算。
 *
 * <p><b>为什么这个端点在 web 层而不在岗位域里。</b>
 * 拆总价要问经纪人域,而 {@code job → broker} 会闭合一个模块环:
 * <pre>attendance → engagement → job → broker → fund → settlement → attendance</pre>
 * 控制器放进 {@code com.xbb.job.internal} 就算岗位域,一样成环 ——
 * 所以组装放在 web 层,它本来就不受依赖白名单约束。
 *
 * <p><b>这个环是 ModularityTests 抓出来的。</b>我先只看了 broker 的直接依赖里
 * 没有 job 就断定不成环,漏了传递路径。
 */
@RestController
@RequestMapping("/api/job")
class IndividualPostingController {

    private final JobApi jobApi;
    private final BrokerApi brokerApi;

    IndividualPostingController(JobApi jobApi, BrokerApi brokerApi) {
        this.jobApi = jobApi;
        this.brokerApi = brokerApi;
    }

    record PostByIndividualRequest(
            @NotBlank(message = "请填写标题") String title,
            @NotBlank(message = "请填写描述") String description,
            @Positive(message = "总价必须为正") long totalPriceCents,
            @NotBlank(message = "请选择地区") String regionCode,
            String workAddress) { }

    /**
     * 分账**在发单时定死**,结算时不再重算 ——
     * 工人是看着"这单 900 元"才接的,中途有人改比例不该让他少拿。
     *
     * <p>没配比例时这里会抛异常:与其发出一张结算时才卡住的单,不如现在就拦。
     */
    @PostMapping("/individual")
    ResponseEntity<Map<String, Long>> postByIndividual(
            @AuthenticationPrincipal AuthenticatedUser caller,
            @RequestBody @Valid PostByIndividualRequest req) {
        var split = brokerApi.splitTotalPrice(
                RateCategory.JOB, req.regionCode(), req.totalPriceCents());
        long id = jobApi.postJobByIndividual(caller.userId(), req.title(), req.description(),
                req.totalPriceCents(), req.regionCode(), req.workAddress(),
                split.workerCents(), split.commissionCents(),
                split.dispatchRetainCents(), split.dispatchOrgId());
        return ResponseEntity.ok(Map.of("id", id));
    }
}
