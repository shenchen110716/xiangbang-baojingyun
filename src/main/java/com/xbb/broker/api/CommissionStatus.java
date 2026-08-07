package com.xbb.broker.api;

/**
 * Commission 的状态,<b>api 包这一侧的副本</b>。
 *
 * <p>此前 api 里直接引用 {@code CommissionStatus} —— 那是 internal 的类型。
 * 任何域读一下 {@code view.status()} 就等于引用了本域的内部实现。
 *
 * <p>常量名和内部枚举**逐字相同**,所以 JSON 输出不变,前端不受影响。
 * (2026-08-07 审计:同一形状在 7 个域里都有,ApiPackagePurityTest 一次扫出来。)
 */
public enum CommissionStatus { PENDING, PAID }
