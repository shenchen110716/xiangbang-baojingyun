package com.xbb.org.api;

/**
 * 组织的审核状态。
 *
 * <p><b>为什么单独有这个枚举。</b>它出现在 {@link OrgApi.OrgView} 里,
 * 而此前那里用的是 {@code Organization.Status} —— internal 包里的类型。
 * 任何域只要读一下 {@code view.status()} 就等于引用了组织域的内部实现,
 * ModularityTests 会当场拦下(2026-08-07 审计时就撞上了)。
 *
 * <p><b>同样的泄漏在 {@code OrgSummary} 上修过一次</b>(改成了 boolean approved),
 * 但 OrgView 上一直留着 —— 修一处不等于修完。
 *
 * <p>枚举名和 {@code Organization.Status} 逐字相同,所以 <b>JSON 输出不变</b>,
 * 前端那四处读 {@code o.status === 'APPROVED'} 的地方不受影响。
 */
public enum OrgStatus { PENDING, APPROVED, REJECTED }
