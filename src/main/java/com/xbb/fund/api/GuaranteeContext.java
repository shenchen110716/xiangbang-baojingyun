package com.xbb.fund.api;

/** §8.1 接口契约,照抄不自己发明。 */
public record GuaranteeContext(long userId, long jobId, int creditScore,
                                long jobSalaryCents, int completedJobs) { }
