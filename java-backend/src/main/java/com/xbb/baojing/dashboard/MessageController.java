package com.xbb.baojing.dashboard;

import com.xbb.baojing.claim.ClaimMapper;
import com.xbb.baojing.common.User;
import com.xbb.baojing.enterprise.Enterprise;
import com.xbb.baojing.enterprise.EnterpriseMapper;
import com.xbb.baojing.insured.InsuredPersonMapper;
import com.xbb.baojing.position.WorkPositionMapper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/api")
public class MessageController {
    private final EnterpriseMapper enterpriseMapper;
    private final InsuredPersonMapper personMapper;
    private final ClaimMapper claimMapper;
    private final WorkPositionMapper positionMapper;

    public MessageController(EnterpriseMapper enterpriseMapper, InsuredPersonMapper personMapper, ClaimMapper claimMapper, WorkPositionMapper positionMapper) {
        this.enterpriseMapper = enterpriseMapper;
        this.personMapper = personMapper;
        this.claimMapper = claimMapper;
        this.positionMapper = positionMapper;
    }

    public record MessageItem(String id, String type, String title, String content, String createdAt, String path) {}

    @GetMapping("/messages")
    public List<MessageItem> messages(User user) {
        List<Integer> enterpriseIds = "enterprise".equals(user.getRole()) && user.getEnterpriseId() != null
                ? List.of(user.getEnterpriseId())
                : enterpriseMapper.search(null, null, null).stream().map(Enterprise::getId).toList();
        String now = LocalDateTime.now().toString();
        List<MessageItem> rows = new ArrayList<>();
        for (int enterpriseId : enterpriseIds) {
            Enterprise enterprise = enterpriseMapper.findById(enterpriseId);
            if (enterprise == null) continue;
            long activeCount = personMapper.search(enterpriseId, null).stream().filter(p -> Set.of("active", "pending").contains(p.getStatus())).count();
            double usageDaily = activeCount * (enterprise.getUsageFeeDaily() > 0 ? enterprise.getUsageFeeDaily() : 0.1);
            if (usageDaily > 0 && enterprise.getUsageBalance() / usageDaily <= (enterprise.getAlertDays() > 0 ? enterprise.getAlertDays() : 3)) {
                rows.add(new MessageItem("balance-" + enterpriseId, "warning", "使用费账户余额预警",
                        String.format("%s余额预计可用 %.1f 天", enterprise.getName(), enterprise.getUsageBalance() / usageDaily), now, "/pages/billing/billing"));
            }
            long pending = personMapper.search(enterpriseId, null).stream().filter(p -> "pending".equals(p.getStatus())).count();
            if (pending > 0) rows.add(new MessageItem("pending-" + enterpriseId, "todo", "员工待审核", pending + " 名员工正在等待参保审核", now, "/pages/employees/employees"));
            long supplements = claimMapper.search(enterpriseId, "supplement").size();
            if (supplements > 0) rows.add(new MessageItem("claim-" + enterpriseId, "danger", "理赔材料待补充", supplements + " 件理赔需要补充材料", now, "/pages/claims/claims"));
            long pendingPositions = positionMapper.search(enterpriseId).stream().filter(p -> Set.of("pending", "supplement").contains(p.getStatus())).count();
            if (pendingPositions > 0) rows.add(new MessageItem("position-" + enterpriseId, "todo", "岗位定类进度", pendingPositions + " 个岗位待审核或补充材料", now, "/pages/positions/positions"));
        }
        if (rows.isEmpty()) rows.add(new MessageItem("welcome", "success", "当前没有待办", "所有参保、账户和理赔业务运行正常", now, "/pages/home/home"));
        return rows;
    }
}
