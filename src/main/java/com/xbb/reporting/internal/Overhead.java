package com.xbb.reporting.internal;

import com.xbb.reporting.api.ReportingApi;
import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "overhead", schema = "reporting")
public class Overhead {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String label;

    @Column(name = "amount_cents", nullable = false)
    private long amountCents;

    @Enumerated(EnumType.STRING)
    @Column(name = "allocation_basis", nullable = false, length = 20)
    private ReportingApi.AllocationBasis allocationBasis;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected Overhead() { }

    public Overhead(String label, long amountCents, ReportingApi.AllocationBasis allocationBasis) {
        this.label = label;
        this.amountCents = amountCents;
        this.allocationBasis = allocationBasis;
    }

    public Long getId() { return id; }
    public long getAmountCents() { return amountCents; }
    public ReportingApi.AllocationBasis getAllocationBasis() { return allocationBasis; }
}
