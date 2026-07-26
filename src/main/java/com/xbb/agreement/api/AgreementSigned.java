package com.xbb.agreement.api;

import java.time.Instant;

/** 履约域订阅它放行"确认完成"(§6.2:协议签署是到岗的前置门禁)。 */
public record AgreementSigned(long agreementId, long applicationId, long workerUserId,
                               long orgId, String contentHash, Instant occurredAt) { }
