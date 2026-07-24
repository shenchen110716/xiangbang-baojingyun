package com.xbb.settlement;

import com.xbb.TestcontainersConfig;
import com.xbb.identity.TestCodeAccessor;
import com.xbb.identity.api.IdentityApi;
import com.xbb.job.api.JobApi;
import com.xbb.org.api.OrgApi;
import com.xbb.org.internal.Organization;
import com.xbb.settlement.api.SettlementApi;
import com.xbb.settlement.internal.Settlement;
import com.xbb.settlement.internal.SettlementRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

@SpringBootTest
@Import({TestcontainersConfig.class, TestCodeAccessor.class})
class SettlementServiceTest {

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        TestcontainersConfig.registerProperties(registry);
    }

    @Autowired IdentityApi identityApi;
    @Autowired TestCodeAccessor codes;
    @Autowired OrgApi orgApi;
    @Autowired JobApi jobApi;
    @Autowired SettlementApi settlementApi;
    @Autowired SettlementRepository settlements;

    private long verifiedUser(String phone, String realName, String idNumber) {
        long userId = identityApi.loginByPhone(phone, codes.issue(phone)).userId();
        identityApi.verifyRealName(userId, realName, idNumber);
        return userId;
    }

    private long pendingSettlement(String legalRepPhone, String applicantPhone, String suffix, long wageCents) {
        long legalRep = verifiedUser(legalRepPhone, "法人" + suffix, "1101011990010" + suffix + "001");
        AtomicLong orgIdHolder = new AtomicLong();
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                orgIdHolder.set(orgApi.submit(Organization.Type.FACTORY, suffix + "号工厂", "9111000000000" + suffix + "9X", legalRep)));
        long orgId = orgIdHolder.get();
        orgApi.approve(orgId);

        AtomicLong jobIdHolder = new AtomicLong();
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                jobIdHolder.set(jobApi.postJob(orgId, suffix + "号岗位", "描述", wageCents, legalRep)));
        long jobId = jobIdHolder.get();

        long applicant = verifiedUser(applicantPhone, "应聘者" + suffix, "1101011990010" + suffix + "002");
        long applicationId = jobApi.apply(jobId, applicant);
        jobApi.acceptApplication(applicationId, legalRep);

        await().atMost(Duration.ofSeconds(5)).until(() -> settlements.findByApplicationId(applicationId).isPresent());
        return settlements.findByApplicationId(applicationId).orElseThrow().getId();
    }

    @Test
    void 待结算记录可以支付() {
        long settlementId = pendingSettlement("13100000001", "13100000002", "a1", 3000);

        settlementApi.pay(settlementId);

        assertThat(settlementApi.findById(settlementId).orElseThrow().status()).isEqualTo(Settlement.Status.PAID);
    }

    @Test
    void 已支付不可重复支付() {
        long settlementId = pendingSettlement("13100000003", "13100000004", "a2", 3100);
        settlementApi.pay(settlementId);

        assertThatThrownBy(() -> settlementApi.pay(settlementId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("待结算");
    }

    @Test
    void 待结算记录可以作废() {
        long settlementId = pendingSettlement("13100000005", "13100000006", "a3", 3200);

        settlementApi.voidSettlement(settlementId, "岗位取消");

        var view = settlementApi.findById(settlementId).orElseThrow();
        assertThat(view.status()).isEqualTo(Settlement.Status.VOIDED);
        assertThat(view.voidReason()).isEqualTo("岗位取消");
    }

    @Test
    void 已支付的记录不可作废() {
        long settlementId = pendingSettlement("13100000007", "13100000008", "a4", 3300);
        settlementApi.pay(settlementId);

        assertThatThrownBy(() -> settlementApi.voidSettlement(settlementId, "误发"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("待结算");
    }

    @Test
    void 并发支付只有一次能成功() throws InterruptedException {
        long settlementId = pendingSettlement("13100000009", "13100000010", "a5", 3400);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        List<Exception> failures = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger successCount = new AtomicInteger();

        Runnable payTask = () -> {
            ready.countDown();
            awaitLatch(go);
            try {
                settlementApi.pay(settlementId);
                successCount.incrementAndGet();
            } catch (Exception e) {
                failures.add(e);
            }
        };

        Thread t1 = new Thread(payTask);
        Thread t2 = new Thread(payTask);
        t1.start();
        t2.start();
        ready.await();
        go.countDown();
        t1.join();
        t2.join();

        // 铁律:同一笔结算不能被并发支付两次(乐观锁 + 状态机检查兜底)
        assertThat(successCount.get()).isEqualTo(1);
        assertThat(failures).hasSize(1);
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }
}
