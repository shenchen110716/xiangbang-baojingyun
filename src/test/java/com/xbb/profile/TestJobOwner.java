package com.xbb.profile;

import com.xbb.profile.internal.ApprovedOrg;
import com.xbb.profile.internal.ProfileApprovedOrgRepository;
import com.xbb.profile.internal.PostedJobRef;
import com.xbb.profile.internal.PostedJobRefRepository;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import java.time.Instant;

/**
 * 取"这个岗位的法人代表是谁",供需要改岗位画像的测试用。
 *
 * <p>岗位画像现在只有所属组织的法人代表能改。走完整流程的用例本来就有真实归属
 * (JobPosted / OrganizationApproved 已落副本),直接返回真实法人代表;
 * 而那些为了测算法而**虚构 jobId** 的用例,岗位在 job 域压根不存在,
 * 这里给它补一份归属——**不覆盖已有的**,否则会把真实归属改掉。
 */
@TestConfiguration
public class TestJobOwner {

    @Bean
    Accessor jobOwnerAccessor(PostedJobRefRepository jobs, ProfileApprovedOrgRepository orgs) {
        return new Accessor(jobs, orgs);
    }

    public static class Accessor {

        private final PostedJobRefRepository jobs;
        private final ProfileApprovedOrgRepository orgs;

        Accessor(PostedJobRefRepository jobs, ProfileApprovedOrgRepository orgs) {
            this.jobs = jobs;
            this.orgs = orgs;
        }

        /** 返回有权修改该岗位画像的用户 id。 */
        public long of(long jobId) {
            PostedJobRef ref = jobs.findById(jobId).orElse(null);
            if (ref != null) {
                return orgs.findById(ref.getOrgId())
                        .orElseThrow(() -> new IllegalStateException("岗位 " + jobId + " 的组织副本还没到"))
                        .getLegalRepUserId();
            }
            // 虚构岗位:补一份归属,id 用 7_9xx 段,不与业务用例重叠
            long orgId = 7_900_000L + jobId % 100_000L;
            long legalRep = 7_800_000L + jobId % 100_000L;
            orgs.save(new ApprovedOrg(orgId, legalRep, Instant.now()));
            jobs.save(new PostedJobRef(jobId, orgId));
            return legalRep;
        }
    }
}
