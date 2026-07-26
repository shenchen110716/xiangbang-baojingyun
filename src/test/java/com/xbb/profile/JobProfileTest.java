package com.xbb.profile;

import com.xbb.TestcontainersConfig;
import com.xbb.identity.TestCodeAccessor;
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
class JobProfileTest {

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        TestcontainersConfig.registerProperties(registry);
    }

    @Autowired ProfileApi profileApi;

    @Test
    void 设置岗位画像后能查回must与nice标签和坐标() {
        long jobId = 900001L;

        profileApi.setJobProfile(jobId, List.of("叉车"), List.of("质检", "打包"), 31.2304, 121.4737);

        ProfileApi.JobProfileView view = profileApi.findJobProfile(jobId).orElseThrow();
        assertThat(view.mustTags()).containsExactly("叉车");
        assertThat(view.niceTags()).containsExactly("质检", "打包");
        assertThat(view.lat()).isEqualTo(31.2304);
        assertThat(view.lon()).isEqualTo(121.4737);
    }

    @Test
    void must标签不在受控词表内报错() {
        assertThatThrownBy(() ->
                profileApi.setJobProfile(900002L, List.of("拧螺丝的"), List.of(), 31.0, 121.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("受控词表");
    }

    @Test
    void nice标签不在受控词表内同样报错() {
        assertThatThrownBy(() ->
                profileApi.setJobProfile(900003L, List.of("普工"), List.of("会飞"), 31.0, 121.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("受控词表");
    }

    @Test
    void 重复设置同一岗位画像是更新不是报重复键() {
        long jobId = 900004L;
        profileApi.setJobProfile(jobId, List.of("普工"), List.of(), 31.0, 121.0);

        profileApi.setJobProfile(jobId, List.of("电工"), List.of("焊工"), 32.0, 122.0);

        ProfileApi.JobProfileView view = profileApi.findJobProfile(jobId).orElseThrow();
        assertThat(view.mustTags()).containsExactly("电工");
        assertThat(view.niceTags()).containsExactly("焊工");
        assertThat(view.lat()).isEqualTo(32.0);
    }

    @Test
    void 没有画像的岗位查回空() {
        assertThat(profileApi.findJobProfile(999999L)).isEmpty();
    }
}
