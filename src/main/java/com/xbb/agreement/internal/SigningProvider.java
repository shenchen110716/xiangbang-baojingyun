package com.xbb.agreement.internal;

/**
 * 电子签服务商的防腐层(§6.2:"**预留电子签接口**(法大大/e签宝风格),不自建 CA")。
 *
 * <p>真实对接时只换这个接口的实现,协议域的业务规则(谁能签、必须带身份因子、
 * 存证哈希)一行不用动。同 §6.1.3 保险集成的 INTEGRATION_MODE=mock|real 模式。
 */
public interface SigningProvider {

    record Receipt(String providerRef) { }

    Receipt sign(String content, String contentHash, long signerUserId, Agreement.IdentityFactor factor);
}
