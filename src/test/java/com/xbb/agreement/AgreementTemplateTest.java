package com.xbb.agreement;

import com.xbb.agreement.internal.AgreementTemplate;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 模板渲染与存证哈希是纯函数,纯 JUnit 单测,不起 Spring。 */
class AgreementTemplateTest {

    @Test
    void 渲染结果包含双方主体岗位与薪酬() {
        String content = AgreementTemplate.render(1001L, 2002L, 3003L, 4004L, 32050);

        assertThat(content).contains("4004");    // 甲方组织
        assertThat(content).contains("3003");    // 乙方工人
        assertThat(content).contains("2002");    // 岗位
        assertThat(content).contains("320.50");  // 薪酬,分转元
        assertThat(content).contains("1001");    // 关联履约单
    }

    @Test
    void 同样的变量渲染出同样的哈希() {
        String a = AgreementTemplate.render(1L, 2L, 3L, 4L, 30000);
        String b = AgreementTemplate.render(1L, 2L, 3L, 4L, 30000);

        // 存证要可复现:同样的输入必须得到同样的正文和哈希,否则举证时无法自证
        assertThat(a).isEqualTo(b);
        assertThat(AgreementTemplate.hash(a)).isEqualTo(AgreementTemplate.hash(b));
    }

    @Test
    void 变量不同则哈希不同() {
        String a = AgreementTemplate.render(1L, 2L, 3L, 4L, 30000);
        String b = AgreementTemplate.render(1L, 2L, 3L, 4L, 40000);

        assertThat(AgreementTemplate.hash(a)).isNotEqualTo(AgreementTemplate.hash(b));
    }

    @Test
    void 哈希是64位十六进制的SHA256() {
        String hash = AgreementTemplate.hash("任意内容");

        assertThat(hash).hasSize(64).matches("[0-9a-f]{64}");
    }
}
