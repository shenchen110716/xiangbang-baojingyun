package com.xbb.fund.internal;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 默认实现:不接真实微工卡。回执号与完税凭证号由幂等键确定性推导,
 * **不引入随机**,测试才可复现。
 *
 * <p>同时在内存里按幂等键去重,模拟真实通道的幂等语义——这样"重发"路径
 * 在测试里是真的走通了通道去重,而不是只测了本地状态机。
 */
/**
 * **只在 xbb.channel.mode=mock 时装配。**
 *
 * <p>它静默成功:不打款/不签署/不推送,却让上游认为一切正常。放到生产上,
 * 系统会扣减监管账户、把发放标成 SUCCESS、写入假的完税凭证号、通知工人"工资已发放",
 * 而钱一分没出去——错误被系统自己确认为成功并层层扩散,对账时才发现。
 * 所以它必须由配置显式打开,而不是靠"生产上记得换实现"。
 */
@ConditionalOnProperty(name = "xbb.channel.mode", havingValue = "mock")
@Component
class MockWorkCardChannel implements DisbursementChannel {

    private final Map<String, Receipt> issued = new ConcurrentHashMap<>();

    /** 测试用:让下一次指定幂等键的调用失败,以便验证失败与重发路径。 */
    private final Map<String, String> forcedFailures = new ConcurrentHashMap<>();

    void failNext(String idempotencyKey, String reason) {
        forcedFailures.put(idempotencyKey, reason);
    }

    @Override
    public Receipt disburse(String idempotencyKey, long payeeUserId, long amountCents, PayeeAccount account) {
        String failure = forcedFailures.remove(idempotencyKey);
        if (failure != null) throw new ChannelException(failure);

        return issued.computeIfAbsent(idempotencyKey, key -> new Receipt(
                "WC-" + key,
                "TAX-" + key));
    }
}
