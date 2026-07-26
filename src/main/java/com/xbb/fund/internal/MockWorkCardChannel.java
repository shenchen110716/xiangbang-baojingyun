package com.xbb.fund.internal;

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
