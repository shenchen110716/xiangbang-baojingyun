package com.xbb.org;

import com.xbb.TestcontainersConfig;
import com.xbb.identity.TestPlatformOps;
import com.xbb.identity.TestCodeAccessor;
import com.xbb.identity.api.IdentityApi;
import com.xbb.org.api.OrgApi;
import com.xbb.org.internal.Organization;
import com.xbb.org.internal.VerifiedUserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

@SpringBootTest
@Import({TestcontainersConfig.class, TestCodeAccessor.class, TestPlatformOps.class})
class OrgServiceTest {

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        TestcontainersConfig.registerProperties(registry);
    }

    @Autowired IdentityApi identityApi;
    @Autowired TestPlatformOps.Accessor ops;
    @Autowired TestCodeAccessor codes;
    @Autowired OrgApi orgApi;
    @Autowired VerifiedUserRepository verifiedUsers;

    private long verifiedUser(String phone, String realName, String idNumber) {
        long userId = identityApi.loginByPhone(phone, codes.issue(phone)).userId();
        identityApi.verifyRealName(userId, realName, idNumber);
        await().atMost(Duration.ofSeconds(15)).until(() -> verifiedUsers.findById(userId).isPresent());
        return userId;
    }

    @Test
    void 已验证用户可以提交组织入驻() {
        long userId = verifiedUser("13500000001", "赵六", "110101199001019001");

        long orgId = orgApi.submit(Organization.Type.FACTORY, "六号工厂", "91110000000000001X", userId);

        var view = orgApi.findById(orgId).orElseThrow();
        assertThat(view.status()).isEqualTo(Organization.Status.PENDING);
    }

    @Test
    void 未验证用户不能提交组织入驻() {
        assertThatThrownBy(() -> orgApi.submit(Organization.Type.ENTERPRISE, "黑户企业", "91110000000000002X", 999_999L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("未实名");
    }

    @Test
    void 审核通过后状态变更() {
        long userId = verifiedUser("13500000002", "孙七", "110101199001019002");
        long orgId = orgApi.submit(Organization.Type.SERVICE_STATION, "七号服务站", "91110000000000003X", userId);

        orgApi.approve(orgId, ops.userId());

        assertThat(orgApi.findById(orgId).orElseThrow().status()).isEqualTo(Organization.Status.APPROVED);
    }

    @Test
    void 已审核的组织不能重复审核() {
        long userId = verifiedUser("13500000003", "周八", "110101199001019003");
        long orgId = orgApi.submit(Organization.Type.ENTERPRISE, "八号企业", "91110000000000004X", userId);
        orgApi.approve(orgId, ops.userId());

        assertThatThrownBy(() -> orgApi.reject(orgId, ops.userId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("待审核");
    }

    @Test
    void 并发审核只有一个能成功() throws InterruptedException {
        long userId = verifiedUser("13500000010", "赵九", "110101199001019010");
        long orgId = orgApi.submit(Organization.Type.ENTERPRISE, "九号企业", "91110000000000010X", userId);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        List<Exception> failures = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger successCount = new AtomicInteger();

        Runnable approveTask = () -> {
            ready.countDown();
            awaitLatch(go);
            try {
                orgApi.approve(orgId, ops.userId());
                successCount.incrementAndGet();
            } catch (Exception e) {
                failures.add(e);
            }
        };
        Runnable rejectTask = () -> {
            ready.countDown();
            awaitLatch(go);
            try {
                orgApi.reject(orgId, ops.userId());
                successCount.incrementAndGet();
            } catch (Exception e) {
                failures.add(e);
            }
        };

        Thread t1 = new Thread(approveTask);
        Thread t2 = new Thread(rejectTask);
        t1.start();
        t2.start();
        ready.await();
        go.countDown();
        t1.join();
        t2.join();

        // 铁律:两个并发的 approve/reject 不能都成功——乐观锁(或至少状态机检查)必须挡掉一个,
        // 不能出现"两个矛盾的事件都发布出去了"的情况(审计报告 TOCTOU 发现)。
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
