package com.xbb.broker;

import com.xbb.TestcontainersConfig;
import com.xbb.broker.api.BrokerApi;
import com.xbb.identity.TestCodeAccessor;
import com.xbb.identity.api.IdentityApi;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

@SpringBootTest
@Import({TestcontainersConfig.class, TestCodeAccessor.class})
class BrokerServiceTest {

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        TestcontainersConfig.registerProperties(registry);
    }

    @Autowired IdentityApi identityApi;
    @Autowired TestCodeAccessor codes;
    @Autowired BrokerApi brokerApi;
    @Autowired com.xbb.broker.internal.BrokerVerifiedUserRepository verifiedUsers;

    private long verifiedUser(String phone, String realName, String idNumber) {
        long userId = identityApi.loginByPhone(phone, codes.issue(phone)).userId();
        identityApi.verifyRealName(userId, realName, idNumber);
        await().atMost(Duration.ofSeconds(5)).until(() -> verifiedUsers.findById(userId).isPresent());
        return userId;
    }

    @Test
    void 已实名用户可以注册经纪人() {
        long userId = verifiedUser("13000000010", "孙经纪一", "110101199001011010");

        brokerApi.registerBroker(userId);

        assertThat(brokerApi.findBroker(userId).orElseThrow().registered()).isTrue();
    }

    @Test
    void 未实名用户不能注册经纪人() {
        long userId = identityApi.loginByPhone("13000000011", codes.issue("13000000011")).userId();

        assertThatThrownBy(() -> brokerApi.registerBroker(userId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("实名认证");
    }

    @Test
    void 经纪人可以绑定已实名工人() {
        long brokerUserId = verifiedUser("13000000012", "孙经纪二", "110101199001011012");
        brokerApi.registerBroker(brokerUserId);
        long workerUserId = verifiedUser("13000000013", "工人一", "110101199001011013");

        long invitationId = brokerApi.bindWorker(brokerUserId, workerUserId);

        assertThat(invitationId).isPositive();
    }

    @Test
    void 工人已被绑定不可重复绑定() {
        long brokerA = verifiedUser("13000000014", "孙经纪三", "110101199001011014");
        brokerApi.registerBroker(brokerA);
        long brokerB = verifiedUser("13000000015", "孙经纪四", "110101199001011015");
        brokerApi.registerBroker(brokerB);
        long workerUserId = verifiedUser("13000000016", "工人二", "110101199001011016");
        brokerApi.bindWorker(brokerA, workerUserId);

        assertThatThrownBy(() -> brokerApi.bindWorker(brokerB, workerUserId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("已经绑定");
    }

    @Test
    void 非经纪人不能绑定工人() {
        long notBroker = verifiedUser("13000000017", "路人三", "110101199001011017");
        long workerUserId = verifiedUser("13000000018", "工人三", "110101199001011018");

        assertThatThrownBy(() -> brokerApi.bindWorker(notBroker, workerUserId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不是经纪人");
    }
}
