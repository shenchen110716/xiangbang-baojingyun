package com.xbb.collab;

import com.xbb.TestcontainersConfig;
import com.xbb.collab.api.CollabApi;
import com.xbb.identity.TestCodeAccessor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Import({TestcontainersConfig.class, TestCodeAccessor.class})
class CollabServiceTest {

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        TestcontainersConfig.registerProperties(registry);
    }

    @Autowired CollabApi collabApi;

    @Test
    void 负责人可以更新进度() {
        long taskId = collabApi.createTask("某厂招50人", "本周内完成", 100L, 200L, 1L, 2L);

        collabApi.updateProgress(taskId, 200L, 60);

        assertThat(collabApi.findTask(taskId).orElseThrow().progress()).isEqualTo(60);
    }

    @Test
    void 创建人也可以更新进度() {
        long taskId = collabApi.createTask("盯考勤", null, 101L, 201L, null, null);

        collabApi.updateProgress(taskId, 101L, 30);

        assertThat(collabApi.findTask(taskId).orElseThrow().progress()).isEqualTo(30);
    }

    @Test
    void 无关的人不能更新别人的任务() {
        long taskId = collabApi.createTask("对接工厂", null, 102L, 202L, null, null);

        assertThatThrownBy(() -> collabApi.updateProgress(taskId, 999L, 50))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("创建人或负责人");
    }

    @Test
    void 已关闭的任务不能再更新() {
        long taskId = collabApi.createTask("已完成的事", null, 103L, 203L, null, null);
        collabApi.closeTask(taskId, 103L);

        assertThatThrownBy(() -> collabApi.updateProgress(taskId, 203L, 80))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("已关闭");
    }

    @Test
    void 进度必须在0到100之间() {
        long taskId = collabApi.createTask("边界", null, 104L, 204L, null, null);

        assertThatThrownBy(() -> collabApi.updateProgress(taskId, 204L, 101))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> collabApi.updateProgress(taskId, 204L, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 按负责人查待办() {
        long assignee = 205L;
        collabApi.createTask("任务甲", null, 105L, assignee, null, null);
        collabApi.createTask("任务乙", null, 105L, assignee, null, null);

        assertThat(collabApi.myTasks(assignee)).hasSizeGreaterThanOrEqualTo(2);
    }

    @Test
    void 任务可关联岗位与用工单位() {
        long taskId = collabApi.createTask("某厂招工", null, 106L, 206L, 777L, 888L);

        CollabApi.TaskView view = collabApi.findTask(taskId).orElseThrow();
        assertThat(view.relatedJobId()).isEqualTo(777L);
        assertThat(view.relatedOrgId()).isEqualTo(888L);
    }
}
