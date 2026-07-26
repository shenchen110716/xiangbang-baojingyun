package com.xbb.job.api;

import java.time.Instant;

/**
 * 岗位关闭(§4.2 岗位生命周期)。招满自动关,或法人代表手动关。
 *
 * <p>下游关心的是"别再把它推出去、别再让人报名":匹配域据此下架,履约域据此拦报名。
 */
public record JobClosed(String eventId, long jobId, long orgId, Reason reason, Instant occurredAt) {

    public enum Reason { HEADCOUNT_FILLED, CLOSED_BY_ORG }

    public static JobClosed of(long jobId, long orgId, Reason reason, Instant occurredAt) {
        return new JobClosed(java.util.UUID.randomUUID().toString(), jobId, orgId, reason, occurredAt);
    }
}
