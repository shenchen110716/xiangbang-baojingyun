package com.xbb.attendance;

import com.xbb.TestcontainersConfig;
import com.xbb.attendance.api.AttendanceApi;
import com.xbb.attendance.internal.EngagedWorkerRepository;
import com.xbb.engagement.api.EngagementApi;
import com.xbb.identity.TestCodeAccessor;
import com.xbb.identity.TestPlatformOps;
import com.xbb.identity.api.IdentityApi;
import com.xbb.job.api.JobApi;
import com.xbb.org.api.OrgApi;
import com.xbb.org.api.OrgType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * 考勤域的守卫。
 *
 * <p>考勤直接决定发多少钱,所以这里守的都是**钱会算错**的路径:
 * 重复录入、已确认被静默改掉、别人替你录、草稿被当成定稿计薪。
 *
 * <p>号段 173,信用代码 …9xx。
 */
@SpringBootTest
@Import({TestcontainersConfig.class, TestCodeAccessor.class, TestPlatformOps.class})
class AttendanceTest {

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        TestcontainersConfig.registerProperties(registry);
    }

    @Autowired IdentityApi identityApi;
    @Autowired TestCodeAccessor codes;
    @Autowired TestPlatformOps.Accessor ops;
    @Autowired OrgApi orgApi;
    @Autowired JobApi jobApi;
    @Autowired EngagementApi engagementApi;
    @Autowired AttendanceApi attendanceApi;
    @Autowired EngagedWorkerRepository engaged;

    private static final LocalDate DAY = LocalDate.of(2026, 3, 2);

    private long verified(String phone, String name, String idNo) {
        long id = identityApi.loginByPhone(phone, codes.issue(phone)).userId();
        identityApi.verifyRealName(id, name, idNo);
        return id;
    }

    /** 搭一条到"已录用"的链路,并等考勤域的副本落地。 */
    private record Scene(long boss, long worker, long jobId, long applicationId) { }

    private Scene scene(String bossPhone, String bossId, String workerPhone, String workerId, String code) {
        long boss = verified(bossPhone, "老板", bossId);
        long worker = verified(workerPhone, "工人", workerId);

        AtomicLong orgHolder = new AtomicLong();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                orgHolder.set(orgApi.submit(OrgType.FACTORY, "考勤厂" + code, code, boss)));
        long orgId = orgHolder.get();
        orgApi.approve(orgId, ops.userId());

        AtomicLong jobHolder = new AtomicLong();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                jobHolder.set(jobApi.postJob(orgId, "考勤岗", "描述", 20_000L, boss)));
        long jobId = jobHolder.get();

        AtomicLong appHolder = new AtomicLong();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                appHolder.set(engagementApi.apply(jobId, worker)));
        long applicationId = appHolder.get();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                engagementApi.acceptApplication(applicationId, boss));

        // 录用事件经 outbox 异步到达考勤域
        await().atMost(Duration.ofSeconds(20)).until(() -> engaged.findById(applicationId).isPresent());
        return new Scene(boss, worker, jobId, applicationId);
    }

    @Test
    void 同一天重复录入只会有一条_金额不会翻倍() {
        Scene s = scene("17300000001", "110101199001010901", "17300000002", "110101199001010902",
                "91110000000000901X");

        attendanceApi.upsert(s.applicationId(), DAY, 480, null, null, "IMPORT", null, "首次导入", s.boss());
        attendanceApi.upsert(s.applicationId(), DAY, 600, null, null, "IMPORT", null, "重复导入", s.boss());

        // **这是防重复计薪的根。** 少了唯一约束,导入两次就是两份工时、工资直接翻倍,
        // 而账面上看不出异常 —— 两条记录各自都是合法的。
        var list = attendanceApi.listByApplication(s.applicationId(), s.boss());
        assertThat(list).hasSize(1);
        assertThat(list.get(0).minutes()).as("重复导入应当覆盖而不是新增").isEqualTo(600);
    }

    @Test
    void 已确认的考勤不能直接改_要先撤回() {
        Scene s = scene("17300000003", "110101199001010903", "17300000004", "110101199001010904",
                "91110000000000902X");
        long id = attendanceApi.upsert(s.applicationId(), DAY, 480, null, null, "MANUAL", null, "录入", s.boss());
        attendanceApi.confirm(id, s.boss());

        // 确认过的可能已经算进工资单,静默改掉会让工资单和考勤对不上,而且没人知道差在哪
        assertThatThrownBy(() -> attendanceApi.upsert(s.applicationId(), DAY, 600, null, null,
                "MANUAL", null, "偷偷改", s.boss()))
                .hasMessageContaining("已确认");

        attendanceApi.reopen(id, "工厂反馈工时有误", s.boss());
        attendanceApi.upsert(s.applicationId(), DAY, 600, null, null, "MANUAL", null, "订正", s.boss());
        assertThat(attendanceApi.listByApplication(s.applicationId(), s.boss()).get(0).minutes())
                .isEqualTo(600);
    }

    @Test
    void 计薪只认已确认的工时() {
        Scene s = scene("17300000005", "110101199001010905", "17300000006", "110101199001010906",
                "91110000000000903X");
        long d1 = attendanceApi.upsert(s.applicationId(), DAY, 480, null, null, "IMPORT", null, "第一天", s.boss());
        attendanceApi.upsert(s.applicationId(), DAY.plusDays(1), 300, null, null, "IMPORT", null, "第二天", s.boss());

        assertThat(attendanceApi.confirmedMinutes(s.applicationId()))
                .as("都没确认时应当是 0").isZero();

        attendanceApi.confirm(d1, s.boss());
        // 草稿态还可能被订正,拿它计薪等于按未定稿的数字发钱
        assertThat(attendanceApi.confirmedMinutes(s.applicationId())).isEqualTo(480);
    }

    @Test
    void 不是法人代表不能录考勤() {
        Scene s = scene("17300000007", "110101199001010907", "17300000008", "110101199001010908",
                "91110000000000904X");
        long outsider = verified("17300000009", "路人", "110101199001010909");

        assertThatThrownBy(() -> attendanceApi.upsert(s.applicationId(), DAY, 480, null, null,
                "MANUAL", null, "越权录入", outsider))
                .hasMessageContaining("法人代表");
        // 连工人自己也不能给自己录 —— 那等于自己给自己算工资
        assertThatThrownBy(() -> attendanceApi.upsert(s.applicationId(), DAY, 480, null, null,
                "MANUAL", null, "自己录", s.worker()))
                .hasMessageContaining("法人代表");
    }

    @Test
    void 工人能看自己的考勤但看不到别人的() {
        Scene a = scene("17300000010", "110101199001010910", "17300000011", "110101199001010911",
                "91110000000000905X");
        Scene b = scene("17300000012", "110101199001010912", "17300000013", "110101199001010913",
                "91110000000000906X");
        attendanceApi.upsert(a.applicationId(), DAY, 480, null, null, "IMPORT", null, "甲", a.boss());
        attendanceApi.upsert(b.applicationId(), DAY, 300, null, null, "IMPORT", null, "乙", b.boss());

        var mine = attendanceApi.listMine(a.worker(), DAY.minusDays(1), DAY.plusDays(1));
        // 成对验:看得到自己的 **且** 看不到别人的
        assertThat(mine).hasSize(1);
        assertThat(mine.get(0).workerUserId()).isEqualTo(a.worker());
    }

    @Test
    void 批量导入一条失败不影响其它条() {
        Scene s = scene("17300000014", "110101199001010914", "17300000015", "110101199001010915",
                "91110000000000907X");

        var results = attendanceApi.upsertBatch(List.of(
                new AttendanceApi.BatchRow(s.applicationId(), DAY, 480, "正常"),
                new AttendanceApi.BatchRow(s.applicationId(), DAY.plusDays(1), 9999, "工时越界"),
                new AttendanceApi.BatchRow(99_999_999L, DAY, 480, "履约单不存在"),
                new AttendanceApi.BatchRow(s.applicationId(), DAY.plusDays(2), 300, "正常")
        ), "IMPORT", "月度导入", s.boss());

        // 一个错行毁掉整批是最难受的失败方式 —— 运营不知道哪条错,只能全部重来
        assertThat(results).hasSize(4);
        assertThat(results.stream().filter(AttendanceApi.UpsertResult::created).count()).isEqualTo(2);
        assertThat(results.stream().filter(r -> r.error() != null).count()).isEqualTo(2);
        // 成功的那两条确实进库了
        assertThat(attendanceApi.listByApplication(s.applicationId(), s.boss())).hasSize(2);
    }

    @Test
    void 变更留痕能查到改前改后与操作人() {
        Scene s = scene("17300000016", "110101199001010916", "17300000017", "110101199001010917",
                "91110000000000908X");
        long id = attendanceApi.upsert(s.applicationId(), DAY, 480, null, null, "MANUAL", null, "初次", s.boss());
        attendanceApi.upsert(s.applicationId(), DAY, 540, null, null, "MANUAL", null, "加班补录", s.boss());

        var changes = attendanceApi.changesOf(id, s.boss());
        assertThat(changes).hasSizeGreaterThanOrEqualTo(2);
        var latest = changes.get(0);
        assertThat(latest.oldValue()).isEqualTo("480");
        assertThat(latest.newValue()).isEqualTo("540");
        assertThat(latest.changedBy()).isEqualTo(s.boss());
        assertThat(latest.reason()).isEqualTo("加班补录");
    }

    @Test
    void 未录用的履约单不能录考勤() {
        // 副本里没有 = 还没录用。这时录考勤是在给一个没上岗的人记工时
        assertThatThrownBy(() -> attendanceApi.upsert(88_888_888L, DAY, 480, null, null,
                "MANUAL", null, "凭空录入", ops.userId()))
                .hasMessageContaining("尚未录用");
    }

    @Test
    void 单日工时越界被拒() {
        Scene s = scene("17300000018", "110101199001010918", "17300000019", "110101199001010919",
                "91110000000000909X");
        assertThatThrownBy(() -> attendanceApi.upsert(s.applicationId(), DAY, 1441, null, null,
                "MANUAL", null, "越界", s.boss()))
                .hasMessageContaining("1440");
        assertThatThrownBy(() -> attendanceApi.upsert(s.applicationId(), DAY, -1, null, null,
                "MANUAL", null, "负数", s.boss()))
                .hasMessageContaining("0");
    }
}
