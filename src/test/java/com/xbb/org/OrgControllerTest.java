package com.xbb.org;

import com.xbb.TestcontainersConfig;
import com.xbb.identity.TestCodeAccessor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfig.class, TestCodeAccessor.class})
class OrgControllerTest {

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        TestcontainersConfig.registerProperties(registry);
    }

    @Autowired MockMvc mvc;

    @Test
    void 未实名用户提交入驻返回409() throws Exception {
        mvc.perform(post("/api/org")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"ENTERPRISE\",\"name\":\"测试企业\",\"creditCode\":\"91110000000000009X\",\"legalRepUserId\":888888}"))
                .andExpect(status().isConflict());
    }
}
