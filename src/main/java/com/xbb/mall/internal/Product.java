package com.xbb.mall.internal;

import com.xbb.mall.api.ProductSettlementMode;
import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "product", schema = "mall")
public class Product {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "merchant_id", nullable = false)
    private long merchantId;

    @Column(nullable = false, length = 200)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(name = "settlement_mode", nullable = false, length = 20)
    private ProductSettlementMode settlementMode;

    @Column(name = "price_cents", nullable = false)
    private long priceCents;

    @Column(name = "session_label", nullable = false, length = 100)
    private String sessionLabel;

    @Column(nullable = false)
    private int stock;

    @Column(name = "refund_deadline")
    private Instant refundDeadline;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    /** 乐观锁:§6.3.6 R1 防超卖的最后一道保险。 */
    @Version
    private long version;

    protected Product() { }

    public Product(long merchantId, String title, ProductSettlementMode settlementMode,
                    long priceCents, String sessionLabel, int stock, Instant refundDeadline) {
        this.merchantId = merchantId;
        this.title = title;
        this.settlementMode = settlementMode;
        this.priceCents = priceCents;
        this.sessionLabel = sessionLabel;
        this.stock = stock;
        this.refundDeadline = refundDeadline;
    }

    /** §6.3.6 R1:锁内复检剩余量再扣减。库存为 0 直接拒绝,绝不允许扣成负数。 */
    public void reserveOne() {
        if (stock <= 0) throw new IllegalStateException("已售罄");
        stock--;
    }

    public void releaseOne() {
        stock++;
    }

    public Long getId() { return id; }
    public long getMerchantId() { return merchantId; }
    public String getTitle() { return title; }
    public ProductSettlementMode getSettlementMode() { return settlementMode; }
    public long getPriceCents() { return priceCents; }
    public String getSessionLabel() { return sessionLabel; }
    public int getStock() { return stock; }
    public Instant getRefundDeadline() { return refundDeadline; }
}
