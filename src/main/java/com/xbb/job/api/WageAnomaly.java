package com.xbb.job.api;

/**
 * 薪资合理性质疑的结果(§5.1 防线②)。
 *
 * <p>放在 api 包而不是 internal:它出现在 {@link JobApi#checkWageAnomaly} 的签名上,
 * 是对外契约的一部分。原先定义在 `internal.WageAnomalyDetector` 里被
 * ModularityTests 拦下——"Module 'voice' depends on non-exposed type"。
 * 这个拦截是对的:内部类型泄漏进公开签名,等于把实现细节焊进了调用方。
 */
public record WageAnomaly(String reason) { }
