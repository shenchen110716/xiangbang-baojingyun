package com.xbb.mall.internal;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "mall_order", schema = "mall")
public class MallOrder {

    public enum Status { CREATED, PAID, REDEEMED, REFUNDED }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "product_id", nullable = false)
    private long productId;

    @Column(name = "buyer_user_id", nullable = false)
    private long buyerUserId;

    @Column(name = "amount_cents", nullable = false)
    private long amountCents;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.CREATED;

    @Column(name = "voucher_code", unique = true, length = 100)
    private String voucherCode;

    @Column(name = "redeemed_at")
    private Instant redeemedAt;

    @Column(name = "refunded_at")
    private Instant refundedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Version
    private long version;

    protected MallOrder() { }

    public MallOrder(long productId, long buyerUserId, long amountCents) {
        this.productId = productId;
        this.buyerUserId = buyerUserId;
        this.amountCents = amountCents;
    }

    public void markPaid(String voucherCode) {
        if (status != Status.CREATED) throw new IllegalStateException("只有新建订单可以支付");
        this.status = Status.PAID;
        this.voucherCode = voucherCode;
    }

    /** §6.3.6 R5:核销码一次性,核销后立即失效,同码二次出示直接拒绝。 */
    public void redeem() {
        if (status == Status.REDEEMED) throw new IllegalStateException("该核销码已被使用,不能重复核销");
        if (status == Status.REFUNDED) throw new IllegalStateException("该订单已退款,不能核销");
        if (status != Status.PAID) throw new IllegalStateException("只有已支付订单可以核销");
        this.status = Status.REDEEMED;
        this.redeemedAt = Instant.now();
    }

    public void refund() {
        if (status == Status.REDEEMED) throw new IllegalStateException("已核销的订单不能退款");
        if (status == Status.REFUNDED) throw new IllegalStateException("订单已退款");
        this.status = Status.REFUNDED;
        this.refundedAt = Instant.now();
    }

    public Long getId() { return id; }
    public long getProductId() { return productId; }
    public long getBuyerUserId() { return buyerUserId; }
    public long getAmountCents() { return amountCents; }
    public Status getStatus() { return status; }
    public String getVoucherCode() { return voucherCode; }
}
