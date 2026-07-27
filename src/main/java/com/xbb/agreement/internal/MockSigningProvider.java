package com.xbb.agreement.internal;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 默认实现:不接真实服务商,回执号由内容哈希确定性推导——**不引入随机**,
 * 测试才可复现。真实服务商接入时新增一个实现并按配置切换即可。
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
class MockSigningProvider implements SigningProvider {

    @Override
    public Receipt sign(String content, String contentHash, long signerUserId, Agreement.IdentityFactor factor) {
        return new Receipt("MOCK-" + contentHash.substring(0, 8) + "-" + factor.name());
    }
}
