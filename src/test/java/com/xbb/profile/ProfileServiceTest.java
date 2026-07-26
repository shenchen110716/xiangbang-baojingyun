package com.xbb.profile;

import com.xbb.TestcontainersConfig;
import com.xbb.identity.TestCodeAccessor;
import com.xbb.identity.api.IdentityApi;
import com.xbb.ops.api.OpsApi;
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
    @Autowired OpsApi opsApi;

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

    @Test
    void 设置人才期望薪资与坐标后能查回() {
        long userId = registeredUser("15100000004");

        profileApi.setWorkerPreference(userId, 30000, 31.2304, 121.4737);

        ProfileApi.WorkerPreferenceView view = profileApi.findWorkerPreference(userId).orElseThrow();
        assertThat(view.expectedWageCents()).isEqualTo(30000);
        assertThat(view.lat()).isEqualTo(31.2304);
        assertThat(view.lon()).isEqualTo(121.4737);
    }

    @Test
    void 重复设置人才偏好是更新不是报重复键() {
        long userId = registeredUser("15100000005");
        profileApi.setWorkerPreference(userId, 30000, 31.0, 121.0);

        profileApi.setWorkerPreference(userId, 35000, 32.0, 122.0);

        ProfileApi.WorkerPreferenceView view = profileApi.findWorkerPreference(userId).orElseThrow();
        assertThat(view.expectedWageCents()).isEqualTo(35000);
        assertThat(view.lat()).isEqualTo(32.0);
    }

    @Test
    void 没有设置偏好的用户查回空() {
        long userId = registeredUser("15100000006");

        assertThat(profileApi.findWorkerPreference(userId)).isEmpty();
    }

    // ---------- 受控词表来自运营字典(不再硬编码) ----------

    @Test
    void 运营新增词条后画像域立刻可用不需要改代码发版() {
        long userId = registeredUser("15100000007");
        // 新词条入库前提交会被拒
        assertThatThrownBy(() -> profileApi.submitTags(userId, List.of("电焊机操作")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("受控词表");

        opsApi.addItem(OpsApi.SKILL_TAG, "电焊机操作", "电焊机操作", 99);

        // 这才是把词表迁进运营字典的真正价值:运营自己就能扩词表
        profileApi.submitTags(userId, List.of("电焊机操作"));
        assertThat(profileApi.getProfile(userId))
                .extracting(ProfileApi.ProfileTagView::tagName).contains("电焊机操作");
    }

    @Test
    void 运营停用词条后不再能提交该标签() {
        long userId = registeredUser("15100000008");
        long itemId = opsApi.addItem(OpsApi.SKILL_TAG, "已废弃工种", "已废弃工种", 98);
        profileApi.submitTags(userId, List.of("已废弃工种"));

        opsApi.disableItem(itemId);

        // 停用就该真的禁止新提交,否则"停用"这个动作等于没生效
        assertThatThrownBy(() -> profileApi.submitTags(registeredUser("15100000009"),
                List.of("已废弃工种")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("受控词表");
        // 已经提交过的历史标签不受影响,不会被追溯删除
        assertThat(profileApi.getProfile(userId))
                .extracting(ProfileApi.ProfileTagView::tagName).contains("已废弃工种");
    }
}
