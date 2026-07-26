package com.xbb.reimbursement;

import com.xbb.TestcontainersConfig;
import com.xbb.fund.api.AccountType;
import com.xbb.fund.api.FundApi;
import com.xbb.identity.TestCodeAccessor;
import com.xbb.reimbursement.api.ReimbursementApi;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** §6.5.2:报销域只管流程与审批,**打款走资金域**。 */
@SpringBootTest
@Import({TestcontainersConfig.class, TestCodeAccessor.class})
class ReimbursementServiceTest {

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        TestcontainersConfig.registerProperties(registry);
    }

    @Autowired ReimbursementApi reimbursementApi;
    @Autowired FundApi fundApi;

    @Test
    void 审批通过会经资金域真的打款() {
        fundApi.topUp(AccountType.PLATFORM_REVENUE, 500_000, "平台备资");
        long before = fundApi.balanceOf(AccountType.PLATFORM_REVENUE);
        long id = reimbursementApi.submit(301L, 20_000, "打车费", "INV-001");

        reimbursementApi.approve(id, 302L);

        // 报销域自己不碰钱,钱从监管账户出——余额确实少了
        assertThat(fundApi.balanceOf(AccountType.PLATFORM_REVENUE)).isEqualTo(before - 20_000);
        assertThat(reimbursementApi.find(id).orElseThrow().status())
                .isEqualTo(ReimbursementApi.Status.PAID);
    }

    @Test
    void 驳回不打款() {
        fundApi.topUp(AccountType.PLATFORM_REVENUE, 500_000, "平台备资");
        long before = fundApi.balanceOf(AccountType.PLATFORM_REVENUE);
        long id = reimbursementApi.submit(303L, 30_000, "住宿费", "INV-002");

        reimbursementApi.reject(id, 304L, "发票不清晰");

        assertThat(fundApi.balanceOf(AccountType.PLATFORM_REVENUE)).isEqualTo(before);
        assertThat(reimbursementApi.find(id).orElseThrow().status())
                .isEqualTo(ReimbursementApi.Status.REJECTED);
    }

    @Test
    void 不能审批自己提交的报销() {
        long id = reimbursementApi.submit(305L, 10_000, "餐费", null);

        // 内控红线,金额再小也不行
        assertThatThrownBy(() -> reimbursementApi.approve(id, 305L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不能审批自己");
    }

    @Test
    void 驳回后可以改金额重新提交() {
        long id = reimbursementApi.submit(306L, 99_000, "多报了", null);
        reimbursementApi.reject(id, 307L, "金额不符");

        reimbursementApi.resubmit(id, 306L, 50_000, "改正后的金额");

        ReimbursementApi.ReimbursementView view = reimbursementApi.find(id).orElseThrow();
        assertThat(view.status()).isEqualTo(ReimbursementApi.Status.SUBMITTED);
        assertThat(view.amountCents()).isEqualTo(50_000);
        assertThat(view.rejectReason()).isNull();
    }

    @Test
    void 不能重新提交别人的报销() {
        long id = reimbursementApi.submit(308L, 10_000, "事由", null);
        reimbursementApi.reject(id, 309L, "驳回");

        assertThatThrownBy(() -> reimbursementApi.resubmit(id, 999L, 5_000, "改"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("自己的报销");
    }

    @Test
    void 已打款的不能重复审批() {
        fundApi.topUp(AccountType.PLATFORM_REVENUE, 500_000, "平台备资");
        long id = reimbursementApi.submit(310L, 10_000, "事由", null);
        reimbursementApi.approve(id, 311L);

        assertThatThrownBy(() -> reimbursementApi.approve(id, 311L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("待审批");
    }

    @Test
    void 报销金额必须为正() {
        assertThatThrownBy(() -> reimbursementApi.submit(312L, 0, "零元报销", null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
