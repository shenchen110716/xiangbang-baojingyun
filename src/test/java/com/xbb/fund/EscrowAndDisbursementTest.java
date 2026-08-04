package com.xbb.fund;

import com.xbb.TestcontainersConfig;
import com.xbb.identity.TestPlatformOps;
import com.xbb.fund.api.AccountType;
import com.xbb.fund.api.DisbursementStatus;
import com.xbb.fund.api.FundApi;
import com.xbb.fund.internal.MockWorkCardChannelAccessor;
import com.xbb.fund.internal.Payout;
import com.xbb.fund.internal.PayoutRepository;
import com.xbb.identity.TestCodeAccessor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 监管账户资金隔离(§6.4.2)与微工卡代发(§6.4)。
 * 主文档把税务合规定性为"灵活用工能否规模化的生死线,不是可选项"。
 */
@SpringBootTest
@Import({TestcontainersConfig.class, TestCodeAccessor.class, MockWorkCardChannelAccessor.class, TestPlatformOps.class})
class EscrowAndDisbursementTest {

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        TestcontainersConfig.registerProperties(registry);
    }

    @Autowired FundApi fundApi;
    @Autowired TestPlatformOps.Accessor ops;
    @Autowired PayoutRepository payouts;
    @Autowired MockWorkCardChannelAccessor channel;

    private static final AtomicLong SETTLEMENT_SEQ = new AtomicLong(700_000);

    private long pendingPayout(long amountCents) {
        return payouts.save(new Payout(SETTLEMENT_SEQ.incrementAndGet(), 4242L, amountCents)).getId();
    }

    // ---------- 资金隔离(§6.4.2) ----------

    @Test
    void 入金记三段式流水且余额递增() {
        long before = fundApi.balanceOf(AccountType.USER_FUNDS);

        fundApi.topUp(AccountType.USER_FUNDS, 100_000, "某厂服务费入金");

        assertThat(fundApi.balanceOf(AccountType.USER_FUNDS)).isEqualTo(before + 100_000);
    }

    @Test
    void 平台收入与用户资金分账不混同() {
        long userBefore = fundApi.balanceOf(AccountType.USER_FUNDS);
        long platformBefore = fundApi.balanceOf(AccountType.PLATFORM_REVENUE);

        fundApi.topUp(AccountType.PLATFORM_REVENUE, 50_000, "平台服务费");

        // §6.4.2:"平台自有收入与用户资金分账,不混同"——动一个不该影响另一个
        assertThat(fundApi.balanceOf(AccountType.PLATFORM_REVENUE)).isEqualTo(platformBefore + 50_000);
        assertThat(fundApi.balanceOf(AccountType.USER_FUNDS)).isEqualTo(userBefore);
    }

    @Test
    void 余额不足不能代发() {
        // 先把用户资金账户余额记下来,构造一笔远超余额的代发
        long balance = fundApi.balanceOf(AccountType.USER_FUNDS);
        long payoutId = pendingPayout(balance + 1_000_000);

        assertThatThrownBy(() -> fundApi.disburse(payoutId, ops.userId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("余额不足");
    }

    // ---------- 微工卡代发(§6.4.2) ----------

    @Test
    void 代发成功后有完税凭证号() {
        fundApi.topUp(AccountType.USER_FUNDS, 200_000, "备资");
        long payoutId = pendingPayout(30_000);

        fundApi.disburse(payoutId, ops.userId());

        FundApi.DisbursementView view = fundApi.findDisbursement(payoutId, ops.userId()).orElseThrow();
        assertThat(view.status()).isEqualTo(DisbursementStatus.SUCCESS);
        // 税务合规是规模化的生死线,凭证号不是可选字段
        assertThat(view.taxCertificateNo()).isNotBlank();
        assertThat(view.externalRef()).isNotBlank();
    }

    @Test
    void 代发会从监管账户扣款() {
        fundApi.topUp(AccountType.USER_FUNDS, 200_000, "备资");
        long before = fundApi.balanceOf(AccountType.USER_FUNDS);
        long payoutId = pendingPayout(30_000);

        fundApi.disburse(payoutId, ops.userId());

        assertThat(fundApi.balanceOf(AccountType.USER_FUNDS)).isEqualTo(before - 30_000);
    }

    @Test
    void 重复代发同一笔不会重复打钱() {
        fundApi.topUp(AccountType.USER_FUNDS, 200_000, "备资");
        long payoutId = pendingPayout(30_000);
        fundApi.disburse(payoutId, ops.userId());
        long afterFirst = fundApi.balanceOf(AccountType.USER_FUNDS);

        fundApi.disburse(payoutId, ops.userId());   // 再调一次

        assertThat(fundApi.balanceOf(AccountType.USER_FUNDS)).isEqualTo(afterFirst);
    }

    // ---------- 失败与重发(§6.4.2"失败可重发") ----------

    @Test
    void 通道失败记录原因且预扣款原路退回() {
        fundApi.topUp(AccountType.USER_FUNDS, 200_000, "备资");
        long before = fundApi.balanceOf(AccountType.USER_FUNDS);
        long payoutId = pendingPayout(30_000);
        channel.failNext("payout-" + payoutId, "收款账户异常");

        fundApi.disburse(payoutId, ops.userId());

        FundApi.DisbursementView view = fundApi.findDisbursement(payoutId, ops.userId()).orElseThrow();
        assertThat(view.status()).isEqualTo(DisbursementStatus.FAILED);
        assertThat(view.failReason()).contains("收款账户异常");
        // 钱没发出去就不能扣:预扣要冲正回来
        assertThat(fundApi.balanceOf(AccountType.USER_FUNDS)).isEqualTo(before);
        // 发放本身仍是待发放,没有被误标成已发放
        assertThat(fundApi.findById(payoutId, ops.userId()).orElseThrow().status()).isEqualTo(Payout.Status.PENDING);
    }

    @Test
    void 失败后可以重发并转为成功() {
        fundApi.topUp(AccountType.USER_FUNDS, 200_000, "备资");
        long payoutId = pendingPayout(30_000);
        channel.failNext("payout-" + payoutId, "网络超时");
        fundApi.disburse(payoutId, ops.userId());
        assertThat(fundApi.findDisbursement(payoutId, ops.userId()).orElseThrow().status())
                .isEqualTo(DisbursementStatus.FAILED);

        fundApi.retryDisbursement(payoutId, ops.userId());

        FundApi.DisbursementView view = fundApi.findDisbursement(payoutId, ops.userId()).orElseThrow();
        assertThat(view.status()).isEqualTo(DisbursementStatus.SUCCESS);
        assertThat(view.retryCount()).isEqualTo(1);
        assertThat(view.taxCertificateNo()).isNotBlank();
    }

    @Test
    void 已成功的代发不能重发() {
        fundApi.topUp(AccountType.USER_FUNDS, 200_000, "备资");
        long payoutId = pendingPayout(30_000);
        fundApi.disburse(payoutId, ops.userId());

        assertThatThrownBy(() -> fundApi.retryDisbursement(payoutId, ops.userId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("无需重发");
    }
}
