package com.xbb.baojing.timeliness;

import com.xbb.baojing.common.User;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 及时率结果的 fail-closed 只读镜像，对应 Python GET /api/timeliness/details
 * (backend/routers/timeliness.py::timeliness_details) 的鉴权边界。
 *
 * 返回范围过滤后的 current 结果行。/summary 的比率聚合与 /export 的 XLSX 是有状态
 * 业务逻辑，按 Phase 6 范围决策仍只在 Python 侧；Java 镜像的价值在于数据一致性与
 * 只读鉴权对齐，两者与 /details 共用同一 scoped 读取，此处即证明该边界一致。 */
@RestController
@RequestMapping("/api")
public class TimelinessController {
    private final TimelinessResultAccess access;

    public TimelinessController(TimelinessResultAccess access) {
        this.access = access;
    }

    @GetMapping("/timeliness/details")
    public Map<String, List<EmploymentTimelinessResult>> details(User user) {
        return Map.of("items", access.currentResults(user));
    }
}
