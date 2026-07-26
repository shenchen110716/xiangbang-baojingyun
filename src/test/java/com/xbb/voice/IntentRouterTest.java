package com.xbb.voice;

import com.xbb.voice.internal.IntentRegistry;
import com.xbb.voice.internal.IntentRegistry.Role;
import com.xbb.voice.internal.IntentRouter;
import com.xbb.voice.internal.IntentRouter.Routing;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IntentRouterTest {

    private final IntentRouter router = new IntentRouter();

    @Test
    void 工人说发薪在路由层就被挡下不进入任何域() {
        // §7.3 的核心场景:"图形界面里工人根本看不到'发薪'按钮;
        // 语音界面里他可以说出任何话"
        Routing routing = router.route("salary.disburse", 0.95, Role.WORKER);

        assertThat(routing).isInstanceOf(Routing.Denied.class);
    }

    @Test
    void 组织法人说发薪放行() {
        Routing routing = router.route("salary.disburse", 0.95, Role.ORG_LEGAL_REP);

        assertThat(routing).isInstanceOf(Routing.Dispatch.class);
    }

    @Test
    void 注册表外的意图不放行而是反问() {
        // LLM 不能自由发明意图,否则意图空间爆炸、路由不可控
        Routing routing = router.route("给我打钱到这张卡", 0.99, Role.WORKER);

        assertThat(routing).isInstanceOf(Routing.Clarify.class);
    }

    @Test
    void 置信度不足时反问澄清而不是猜() {
        Routing routing = router.route("job.publish", 0.4, Role.ORG_LEGAL_REP);

        assertThat(routing).isInstanceOf(Routing.Clarify.class);
    }

    @Test
    void 发单是L2需要回读确认() {
        Routing routing = router.route("job.publish", 0.95, Role.ORG_LEGAL_REP);

        assertThat(routing).isInstanceOf(Routing.Dispatch.class);
        assertThat(((Routing.Dispatch) routing).intent().risk())
                .isEqualTo(IntentRegistry.RiskLevel.L2);
    }

    @Test
    void 查询类是L1可以直接执行() {
        Routing routing = router.route("salary.query", 0.95, Role.WORKER);

        assertThat(routing).isInstanceOf(Routing.Dispatch.class);
        assertThat(((Routing.Dispatch) routing).intent().risk())
                .isEqualTo(IntentRegistry.RiskLevel.L1);
    }

    @Test
    void 权限判定先于置信度之外的一切业务逻辑() {
        // 即使置信度很高、意图合法,角色不对也一律不放行
        assertThat(router.route("job.publish", 1.0, Role.WORKER))
                .isInstanceOf(Routing.Denied.class);
    }

    @Test
    void 所有注册意图都声明了角色与风险等级() {
        // 防止以后加意图时漏配权限——漏配等于默认放行,在语音场景是危险的
        assertThat(IntentRegistry.all()).allSatisfy(intent -> {
            assertThat(intent.allowedRoles()).isNotEmpty();
            assertThat(intent.risk()).isNotNull();
            assertThat(intent.samples()).isNotEmpty();
        });
    }
}
