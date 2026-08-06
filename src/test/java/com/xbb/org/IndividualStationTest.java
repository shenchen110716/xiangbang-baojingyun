package com.xbb.org;

import com.xbb.TestcontainersConfig;
import com.xbb.identity.TestCodeAccessor;
import com.xbb.identity.TestPlatformOps;
import com.xbb.identity.api.IdentityApi;
import com.xbb.job.api.JobApi;
import com.xbb.org.api.OrgApi;
import com.xbb.org.api.OrgType;
import com.xbb.org.api.SubjectType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * 服务站可以是公司,也可以是个人(老板 2026-08-06)。
 *
 * <p>要害是**个人没有统一社会信用代码**。原来那列是 NOT NULL UNIQUE,
 * 个人根本注册不进来;绕过去让人随便填一个的话,唯一索引被污染,
 * 事后分不清哪个代码是真的。
 *
 * <p>同时守组织地址一路传到岗位视图 —— 求职端卡片要显示"在哪上班",
 * 缺它的时候界面只能显示空白。
 *
 * <p>号段 13001,信用代码 …t xx。
 */
@SpringBootTest
@Import({TestcontainersConfig.class, TestCodeAccessor.class, TestPlatformOps.class})
class IndividualStationTest {

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        TestcontainersConfig.registerProperties(registry);
    }

    @Autowired IdentityApi identityApi;
    @Autowired TestCodeAccessor codes;
    @Autowired TestPlatformOps.Accessor ops;
    @Autowired OrgApi orgApi;
    @Autowired JobApi jobApi;

    private long verified(String phone, String name, String idNo) {
        long id = identityApi.loginByPhone(phone, codes.issue(phone)).userId();
        identityApi.verifyRealName(id, name, idNo);
        return id;
    }

    @Test
    void 个人服务站不用填统一社会信用代码() {
        long person = verified("13001000001", "个人站长", "110101199001080001");
        AtomicLong h = new AtomicLong();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                h.set(orgApi.createIndividualStation("城东个人服务站", person,
                        "苏州市姑苏区人民路 1 号", ops.userId())));

        OrgApi.OrgView v = orgApi.findById(h.get(), ops.userId()).orElseThrow();
        assertThat(v.subjectType()).isEqualTo(SubjectType.INDIVIDUAL);
        // 个人主体**必须没有代码**。只写"可以为空"的话,填一个也能过,
        // 于是同一个概念有两种表示,取数的地方迟早漏判一种
        assertThat(v.creditCode()).isNull();
        assertThat(v.legalRepUserId()).isEqualTo(person);
    }

    @Test
    void 一个人只能有一个个人服务站() {
        long person = verified("13001000002", "重复站长", "110101199001080002");
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                orgApi.createIndividualStation("甲站", person, "地址甲", ops.userId()));

        // 允许多个的话,同一个人名下几个站,佣金归属和结算主体全都对不上
        assertThatThrownBy(() ->
                orgApi.createIndividualStation("乙站", person, "地址乙", ops.userId()))
                .hasMessageContaining("已经有");
    }

    @Test
    void 公司主体仍然必须填代码() {
        long rep = verified("13001000003", "公司法人", "110101199001080003");
        // 实名副本经 outbox 异步到达组织域。不等的话会先撞上"法人代表未实名认证",
        // 那时**测试是红的，但红在一个和本条无关的原因上**
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThatThrownBy(() ->
                        orgApi.submit(OrgType.ENTERPRISE, "没代码的公司", "  ", rep))
                        .hasMessageContaining("信用代码"));
    }

    @Test
    void 企业不能是个人主体() {
        // 用工主体是个人的话,劳务合同、完税凭证、保证金全都没有落脚点。
        // 这条挡在数据库上(organization_individual_only_station_ck),
        // 因为应用层漏判一次没有任何症状
        long rep = verified("13001000004", "个体户", "110101199001080004");
        assertThatThrownBy(() ->
                orgApi.createIndividualOrg(OrgType.ENTERPRISE, "个人企业", rep, "地址", ops.userId()))
                .hasMessageContaining("服务站");
    }

    @Test
    void 单位名称与地址传到岗位视图() throws Exception {
        long rep = verified("13001000005", "厂长", "110101199001080005");
        AtomicLong h = new AtomicLong();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                h.set(orgApi.submitWithAddress(OrgType.FACTORY, "苏州智铭达精密科技",
                        "9111000000000t01X", rep, "苏州市工业园区星湖街 328 号")));
        long orgId = h.get();
        orgApi.approve(orgId, ops.userId());

        AtomicLong jobHolder = new AtomicLong();
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                jobHolder.set(jobApi.postJob(orgId, "箱体焊铆", "焊接与压弯", 1800, rep)));

        JobApi.JobView v = jobApi.findJob(jobHolder.get()).orElseThrow();
        // **缺这两个字段时,求职端卡片只能显示空白** ——
        // 而岗位域不能直接读组织域(铁律 3),只能靠副本
        assertThat(v.orgName()).isEqualTo("苏州智铭达精密科技");
        assertThat(v.orgAddress()).isEqualTo("苏州市工业园区星湖街 328 号");
    }

    @Test
    void 旧事件重放时名称为空不会让副本写不进去() {
        // OrganizationApproved 的 name/address 是后加的字段。
        // **加之前已经落库的 outbox 载荷里没有它们**,重放时 Jackson 给 null。
        // 副本表要容忍 null,否则一次重放就把整条中继卡死
        long rep = verified("13001000006", "老数据法人", "110101199001080006");
        AtomicLong h = new AtomicLong();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                h.set(orgApi.submit(OrgType.FACTORY, "没地址的老单位", "9111000000000t02X", rep)));
        orgApi.approve(h.get(), ops.userId());

        AtomicLong jobHolder = new AtomicLong();
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                jobHolder.set(jobApi.postJob(h.get(), "老岗位", "描述", 1500, rep)));
        JobApi.JobView v = jobApi.findJob(jobHolder.get()).orElseThrow();
        assertThat(v.orgName()).isEqualTo("没地址的老单位");
        assertThat(v.orgAddress()).isNull();
    }
}
