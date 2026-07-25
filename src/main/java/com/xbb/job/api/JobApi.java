package com.xbb.job.api;

import com.xbb.job.internal.Job;
import java.util.Optional;

public interface JobApi {

    record JobView(long id, long orgId, String title, String description, long wageCents, Job.Status status) { }

    long postJob(long orgId, String title, String description, long wageCents, long callerUserId);

    Optional<JobView> findJob(long jobId);
}
