package com.xbb.web;

import com.xbb.TestcontainersConfig;
import com.xbb.engagement.api.EngagementApi;
import com.xbb.engagement.internal.EngagementApprovedOrgRepository;
import com.xbb.engagement.internal.PostedJobRepository;
import com.xbb.identity.TestCodeAccessor;
import com.xbb.identity.TestPlatformOps;
import com.xbb.identity.api.IdentityApi;
import com.xbb.job.api.JobApi;
import com.xbb.org.api.OrgApi;
import com.xbb.org.internal.Organization;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 新增列表端点的守卫。
 *
 * <p>列表接口和按 id 单查有一个本质区别:**单查是"你知道 id 才查得到",
 * 列表是"我主动把一批数据给你"**。归属条件漏一个,泄露的不是一条而是全部,
 * 而且界面上完全看不出来——数据照常显示,只是多了别人的。
 *
 * <p>所以这里每条都成对验:我能看到自己的 **且** 看不到别人的。
 * 只验前半段的话,一个 `findAll()` 也能让测试通过。
 *
 * <p>手机号用 166 号段、信用代码用 …6xx,与其它测试不重叠(测试隔离靠手工分号段,
 * 这是记录在案的已知弱点)。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfig.class, TestCodeAccessor.class, TestPlatformOps.class})
class ListEndpointsTest {

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        TestcontainersConfig.registerProperties(registry);
    }

    @Autowired MockMvc mvc;
    @Autowired IdentityApi identityApi;
    @Autowired TestCodeAccessor codes;
    @Autowired TestPlatformOps.Accessor ops;
    @Autowired OrgApi orgApi;
    @Autowired JobApi jobApi;
    @Autowired EngagementApi engagementApi;
    @Autowired EngagementApprovedOrgRepository engagementOrgs;
    @Autowired PostedJobRepository postedJobs;
    @Autowired com.xbb.engagement.internal.EngagementVerifiedUserRepository verifiedUsers;

    private record User(long id, String token) { }

    private User verified(String phone, String name, String idNo) {
        var r = identityApi.loginByPhone(phone, codes.issue(phone));
        identityApi.verifyRealName(r.userId(), name, idNo);
        await().atMost(Duration.ofSeconds(15)).until(() -> verifiedUsers.findById(r.userId()).isPresent());
        return new User(r.userId(), r.token());
    }

    private long approvedOrg(long legalRep, String name, String creditCode) {
        AtomicLong holder = new AtomicLong();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                holder.set(orgApi.submit(com.xbb.org.api.OrgType.FACTORY, name, creditCode, legalRep)));
        long orgId = holder.get();
        orgApi.approve(orgId, ops.userId());
        await().atMost(Duration.ofSeconds(15)).until(() -> engagementOrgs.findById(orgId).isPresent());
        return orgId;
    }

    private long postedJob(long orgId, String title, long caller) {
        AtomicLong holder = new AtomicLong();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                holder.set(jobApi.postJob(orgId, title, "描述", 20000L, caller)));
        long jobId = holder.get();
        await().atMost(Duration.ofSeconds(15)).until(() -> postedJobs.findById(jobId).isPresent());
        return jobId;
    }

    @Test
    void 我的组织只返回我的_看不到别人的() throws Exception {
        User a = verified("16600000001", "组织甲", "110101199001010011");
        User b = verified("16600000002", "组织乙", "110101199001010022");
        long orgA = approvedOrg(a.id(), "甲公司", "91110000000000601X");
        long orgB = approvedOrg(b.id(), "乙公司", "91110000000000602X");

        mvc.perform(get("/api/org/mine").header("Authorization", "Bearer " + a.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == " + orgA + ")]").exists())
                .andExpect(jsonPath("$[?(@.id == " + orgB + ")]").doesNotExist());
    }

    @Test
    void 待审核队列要平台角色_普通用户拿不到() throws Exception {
        User u = verified("16600000003", "普通丙", "110101199001010033");
        mvc.perform(get("/api/org/pending").header("Authorization", "Bearer " + u.token()))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void 我的岗位只返回我名下组织的() throws Exception {
        User a = verified("16600000004", "岗位甲", "110101199001010044");
        User b = verified("16600000005", "岗位乙", "110101199001010055");
        long orgA = approvedOrg(a.id(), "甲厂", "91110000000000604X");
        long orgB = approvedOrg(b.id(), "乙厂", "91110000000000605X");
        long jobA = postedJob(orgA, "甲的岗位", a.id());
        long jobB = postedJob(orgB, "乙的岗位", b.id());

        mvc.perform(get("/api/job/mine").header("Authorization", "Bearer " + a.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == " + jobA + ")]").exists())
                .andExpect(jsonPath("$[?(@.id == " + jobB + ")]").doesNotExist());
    }

    @Test
    void 没有组织的用户拿到空列表而不是报错() throws Exception {
        // orgIds 为空时若直接丢给 `in ()`,不同数据库表现不一致,可能直接抛异常。
        User u = verified("16600000006", "无组织丁", "110101199001010066");
        mvc.perform(get("/api/job/mine").header("Authorization", "Bearer " + u.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void 开放岗位列表能看到已发布的岗位() throws Exception {
        User a = verified("16600000007", "开放甲", "110101199001010077");
        long org = approvedOrg(a.id(), "开放厂", "91110000000000607X");
        long jobId = postedJob(org, "可报名岗位", a.id());

        mvc.perform(get("/api/job/open?limit=100").header("Authorization", "Bearer " + a.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == " + jobId + ")]").exists());
    }

    @Test
    void 我的报名只返回我的() throws Exception {
        User boss = verified("16600000008", "老板戊", "110101199001010088");
        User w1 = verified("16600000009", "工人己", "110101199001010099");
        User w2 = verified("16600000010", "工人庚", "110101199001010100");
        long org = approvedOrg(boss.id(), "报名厂", "91110000000000608X");
        long jobId = postedJob(org, "两人报名的岗位", boss.id());
        long a1 = engagementApi.apply(jobId, w1.id());
        long a2 = engagementApi.apply(jobId, w2.id());

        mvc.perform(get("/api/engagement/mine").header("Authorization", "Bearer " + w1.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == " + a1 + ")]").exists())
                .andExpect(jsonPath("$[?(@.id == " + a2 + ")]").doesNotExist());
    }

    @Test
    void 应聘者名单只有法人代表能看_别人被拒() throws Exception {
        User boss = verified("16600000011", "老板辛", "110101199001010111");
        User stranger = verified("16600000012", "路人壬", "110101199001010122");
        User worker = verified("16600000013", "工人癸", "110101199001010133");
        long org = approvedOrg(boss.id(), "应聘厂", "91110000000000611X");
        long jobId = postedJob(org, "有人应聘的岗位", boss.id());
        long appId = engagementApi.apply(jobId, worker.id());

        // 法人代表看得到
        mvc.perform(get("/api/engagement/job/" + jobId + "/applicants")
                        .header("Authorization", "Bearer " + boss.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == " + appId + ")]").exists());

        // 不相干的人看不到。少了这条,任何登录用户报个 jobId 就能拿到谁在应聘。
        mvc.perform(get("/api/engagement/job/" + jobId + "/applicants")
                        .header("Authorization", "Bearer " + stranger.token()))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void mine路径不会被当成id路径变量() throws Exception {
        // /mine 若排在 /{id} 后面,Spring 会把 "mine" 当 id 解析,得到 400 而不是列表。
        // 这条纯粹守路由顺序 —— 换个人重排方法就可能悄悄失效。
        User u = verified("16600000014", "路由子", "110101199001010144");
        for (String path : new String[]{"/api/org/mine", "/api/job/mine",
                "/api/engagement/mine", "/api/settlement/mine", "/api/fund/payouts/mine"}) {
            mvc.perform(get(path).header("Authorization", "Bearer " + u.token()))
                    .andExpect(status().isOk());
        }
    }

    @Test
    void 未登录访问列表端点一律401() throws Exception {
        for (String path : new String[]{"/api/org/mine", "/api/org/pending", "/api/job/mine",
                "/api/engagement/mine", "/api/settlement/mine", "/api/fund/payouts/mine"}) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
        }
        assertThat(true).isTrue();
    }
}
