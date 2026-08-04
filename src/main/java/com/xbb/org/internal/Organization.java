package com.xbb.org.internal;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "organization", schema = "org")
public class Organization {

    /** 类型定义已提到 {@link com.xbb.org.api.OrgType} —— 它出现在对外事件里,不能留在 internal。 */
    public enum Status { PENDING, APPROVED, REJECTED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private com.xbb.org.api.OrgType type;

    @Column(nullable = false)
    private String name;

    @Column(name = "credit_code", nullable = false, unique = true)
    private String creditCode;

    @Column(name = "legal_rep_user_id", nullable = false)
    private long legalRepUserId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.PENDING;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    // 乐观锁:并发 approve/reject 撞同一行时,后提交的会抛 OptimisticLockException
    // 而不是静默覆盖并各自发布矛盾的事件(见审计报告 TOCTOU 发现)。
    @Version
    private long version;

    protected Organization() { }

    public Organization(com.xbb.org.api.OrgType type, String name, String creditCode, long legalRepUserId) {
        this.type = type;
        this.name = name;
        this.creditCode = creditCode;
        this.legalRepUserId = legalRepUserId;
    }

    public void approve() {
        if (status != Status.PENDING) throw new IllegalStateException("只有待审核状态可以审批");
        this.status = Status.APPROVED;
    }

    public void reject() {
        if (status != Status.PENDING) throw new IllegalStateException("只有待审核状态可以审批");
        this.status = Status.REJECTED;
    }

    public Long getId() { return id; }
    public com.xbb.org.api.OrgType getType() { return type; }
    public String getName() { return name; }
    public String getCreditCode() { return creditCode; }
    public long getLegalRepUserId() { return legalRepUserId; }
    public Status getStatus() { return status; }
}
