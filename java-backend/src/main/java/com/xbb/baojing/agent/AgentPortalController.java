package com.xbb.baojing.agent;

import com.xbb.baojing.common.Rbac;
import com.xbb.baojing.common.User;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 业务员门户的 fail-closed 只读镜像，对应 Python backend/routers/agent_portal.py。
 *
 * 唯一 load-bearing 的鉴权事实（§17.1）：agent_id 只来自登录身份 user.getId()，
 * 绝不接受请求参数——“一个查询参数能移动的身份，就是有人会忘记校验的身份”。
 * 所有端点仅 salesperson 可访问。
 *
 * 只镜像结算单与打款两条只读路径（Mapper.findByAgent 已按 agent_id 收口）。佣金明细/
 * 汇总/导出依赖 agent_portal_query 的共享查询与 XLSX，属有状态业务逻辑，按 Phase 6
 * 范围决策仍只在 Python 侧。 */
@RestController
@RequestMapping("/api")
public class AgentPortalController {
    private final AgentCommissionStatementMapper statementMapper;
    private final AgentCommissionPaymentMapper paymentMapper;

    public AgentPortalController(AgentCommissionStatementMapper statementMapper,
                                 AgentCommissionPaymentMapper paymentMapper) {
        this.statementMapper = statementMapper;
        this.paymentMapper = paymentMapper;
    }

    @GetMapping("/agent-portal/statements")
    public Map<String, List<AgentCommissionStatement>> statements(User user) {
        Rbac.requireRole(user, "仅业务员账号可访问", "salesperson");
        return Map.of("items", statementMapper.findByAgent(user.getId()));
    }

    @GetMapping("/agent-portal/payments")
    public Map<String, List<AgentCommissionPayment>> payments(User user) {
        Rbac.requireRole(user, "仅业务员账号可访问", "salesperson");
        return Map.of("items", paymentMapper.findByAgent(user.getId()));
    }
}
