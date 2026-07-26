package com.xbb.ops;

import com.xbb.TestcontainersConfig;
import com.xbb.identity.TestCodeAccessor;
import com.xbb.ops.api.OpsApi;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import({TestcontainersConfig.class, TestCodeAccessor.class})
class OpsServiceTest {

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        TestcontainersConfig.registerProperties(registry);
    }

    @Autowired OpsApi opsApi;

    @Test
    void 字典项按类型分组且按权重排序() {
        // 用独立类型:SKILL_TAG 有 Flyway 种子数据(画像域的受控词表),
        // 拿它做排序断言会被种子数据干扰
        opsApi.addItem("SORT_TEST", "forklift", "叉车", 2);
        opsApi.addItem("SORT_TEST", "qc", "质检", 1);
        opsApi.addItem("OTHER_TYPE", "x", "无关项", 0);

        var items = opsApi.itemsOf("SORT_TEST");

        assertThat(items).extracting(OpsApi.DictItemView::key).containsExactly("qc", "forklift");
        assertThat(items).extracting(OpsApi.DictItemView::key).doesNotContain("x");
    }

    @Test
    void 停用后不再出现在列表() {
        long id = opsApi.addItem("STATUS_TEST", "temp", "临时项", 0);
        assertThat(opsApi.itemsOf("STATUS_TEST")).hasSize(1);

        opsApi.disableItem(id);

        assertThat(opsApi.itemsOf("STATUS_TEST")).isEmpty();
    }

    @Test
    void 停用后可以重新启用() {
        long id = opsApi.addItem("REENABLE_TEST", "k", "v", 0);
        opsApi.disableItem(id);

        opsApi.enableItem(id);

        assertThat(opsApi.itemsOf("REENABLE_TEST")).hasSize(1);
    }

    @Test
    void 按类型与键精确查找() {
        opsApi.addItem("LOOKUP_TEST", "the-key", "值", 0);

        assertThat(opsApi.findItem("LOOKUP_TEST", "the-key")).isPresent();
        assertThat(opsApi.findItem("LOOKUP_TEST", "missing")).isEmpty();
    }
}
