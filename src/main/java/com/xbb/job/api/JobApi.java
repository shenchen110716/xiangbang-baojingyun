package com.xbb.job.api;

import com.xbb.job.internal.Application;
import com.xbb.job.internal.Job;
import java.util.Optional;

public interface JobApi {

    record JobView(long id, long orgId, String title, String description, long wageCents, Job.Status status) { }

    record ApplicationView(long id, long jobId, long applicantUserId, Application.Status status) { }

    long postJob(long orgId, String title, String description, long wageCents, long callerUserId);

    long apply(long jobId, long applicantUserId);

    Optional<JobView> findJob(long jobId);
}
