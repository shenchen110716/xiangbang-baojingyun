package com.xbb.settlement.api;

import java.time.Instant;

/**
 * 结算已算出金额,可以准备发放了——不代表钱已经动。
 * 真正发钱的操作在 fund 域(资金域是唯一动钱者)。
 */
public record SettlementCalculated(long settlementId, long applicationId, long workerUserId,
                                    long amountCents, Instant occurredAt) { }
