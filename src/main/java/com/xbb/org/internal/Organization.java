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

    /**
     * 法人代表 / 站长。
     *
     * <p><b>服务站可以为空</b> —— 平台先建点位、再派人管。
     * 工厂与企业仍然必须有,由数据库的 CHECK 保证(见 org V5)。
     */
    @Column(name = "legal_rep_user_id")
    private Long legalRepUserId;

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
    /** @return 可能为 null(服务站尚未指派站长) */
    public Long getLegalRepUserId() { return legalRepUserId; }

    /** 平台设立的服务站:建出来就是已审核,且暂无站长。 */
    static Organization platformStation(String name, String creditCode) {
        Organization o = new Organization();
        o.type = com.xbb.org.api.OrgType.SERVICE_STATION;
        o.name = name;
        o.creditCode = creditCode;
        o.legalRepUserId = null;
        o.status = Status.APPROVED;
        return o;
    }

    /** 换站长。只对服务站开放 —— 工厂的法人代表不是平台能改的。 */
    void assignMaster(Long newMasterUserId) {
        if (type != com.xbb.org.api.OrgType.SERVICE_STATION) {
            throw new IllegalStateException("只有服务站可以更换负责人");
        }
        this.legalRepUserId = newMasterUserId;
    }
    public Status getStatus() { return status; }
}
