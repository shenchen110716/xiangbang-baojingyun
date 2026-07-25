package com.xbb.profile;

import com.xbb.TestcontainersConfig;
import com.xbb.identity.TestCodeAccessor;
import com.xbb.identity.api.IdentityApi;
import com.xbb.profile.api.ProfileApi;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Import({TestcontainersConfig.class, TestCodeAccessor.class})
class ProfileServiceTest {

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        TestcontainersConfig.registerProperties(registry);
    }

    @Autowired IdentityApi identityApi;
    @Autowired TestCodeAccessor codes;
    @Autowired ProfileApi profileApi;

    private long registeredUser(String phone) {
        return identityApi.loginByPhone(phone, codes.issue(phone)).userId();
    }

    @Test
    void 提交受控词表内的标签成功且不需要已实名() {
        long userId = registeredUser("15100000001");

        profileApi.submitTags(userId, List.of("普工", "叉车"));

        List<ProfileApi.ProfileTagView> profile = profileApi.getProfile(userId);
        assertThat(profile).hasSize(2);
        assertThat(profile).allSatisfy(tag -> {
            assertThat(tag.source()).isEqualTo("SELF_REPORTED");
            assertThat(tag.confidence()).isEqualTo(0.4);
        });
    }

    @Test
    void 提交词表外的标签报错() {
        long userId = registeredUser("15100000002");

        assertThatThrownBy(() -> profileApi.submitTags(userId, List.of("拧螺丝的")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("受控词表");
    }

    @Test
    void 重复提交同一标签不报重复键错误只更新时间戳() {
        long userId = registeredUser("15100000003");

        profileApi.submitTags(userId, List.of("质检"));
        profileApi.submitTags(userId, List.of("质检"));

        assertThat(profileApi.getProfile(userId)).hasSize(1);
    }
}
