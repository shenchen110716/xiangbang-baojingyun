package com.xbb.reporting.internal;

import com.xbb.reporting.api.ReportingApi;
import jakarta.persistence.*;
import java.time.Instant;

/** 宽表事实行。每条都来自某个域的事件,不是 join 出来的。 */
@Entity
@Table(name = "ledger_fact", schema = "reporting")
public class LedgerFact {

    public enum EntryType { REVENUE, DIRECT_COST }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReportingApi.Dimension dimension;

    @Column(name = "dimension_id", nullable = false)
    private long dimensionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "entry_type", nullable = false, length = 20)
    private EntryType entryType;

    @Column(name = "amount_cents", nullable = false)
    private long amountCents;

    @Column(nullable = false, length = 40)
    private String source;

    @Column(name = "reference_id")
    private Long referenceId;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    protected LedgerFact() { }

    public LedgerFact(ReportingApi.Dimension dimension, long dimensionId, EntryType entryType,
                       long amountCents, String source, Long referenceId, Instant occurredAt) {
        this.dimension = dimension;
        this.dimensionId = dimensionId;
        this.entryType = entryType;
        this.amountCents = amountCents;
        this.source = source;
        this.referenceId = referenceId;
        this.occurredAt = occurredAt;
    }

    public ReportingApi.Dimension getDimension() { return dimension; }
    public long getDimensionId() { return dimensionId; }
    public EntryType getEntryType() { return entryType; }
    public long getAmountCents() { return amountCents; }
    public String getSource() { return source; }
}
