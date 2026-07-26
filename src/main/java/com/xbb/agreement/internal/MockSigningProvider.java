package com.xbb.agreement.internal;

import org.springframework.stereotype.Component;

/**
 * 默认实现:不接真实服务商,回执号由内容哈希确定性推导——**不引入随机**,
 * 测试才可复现。真实服务商接入时新增一个实现并按配置切换即可。
 */
@Component
class MockSigningProvider implements SigningProvider {

    @Override
    public Receipt sign(String content, String contentHash, long signerUserId, Agreement.IdentityFactor factor) {
        return new Receipt("MOCK-" + contentHash.substring(0, 8) + "-" + factor.name());
    }
}
