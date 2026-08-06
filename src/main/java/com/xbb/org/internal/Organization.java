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

    /**
     * 统一社会信用代码。<b>个人主体为 null</b> —— 他没有这个东西。
     * 公司必填、个人必须为空,由数据库的 CHECK 保证(见 org V6):
     * 只写"个人可以为空"的话,个人主体上填一个也能过,
     * 于是同一个概念有两种表示,取数的地方迟早漏判一种。
     */
    @Column(name = "credit_code")
    private String creditCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "subject_type", nullable = false, length = 16)
    private com.xbb.org.api.SubjectType subjectType = com.xbb.org.api.SubjectType.COMPANY;

    /** 对外展示的地址。求职端岗位卡片要显示"在哪上班",缺它界面只能是空白。 */
    @Column(length = 200)
    private String address;

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
        this(type, name, creditCode, legalRepUserId, null);
    }

    public Organization(com.xbb.org.api.OrgType type, String name, String creditCode,
                        long legalRepUserId, String address) {
        this.type = type;
        this.name = name;
        this.creditCode = creditCode;
        this.legalRepUserId = legalRepUserId;
        this.address = address;
        this.subjectType = com.xbb.org.api.SubjectType.COMPANY;
    }

    /**
     * 个人主体。**建出来就是已审核** —— 和平台设立的服务站一样,
     * 这条路本来就只有平台运维能走,再走一遍审核是多余的。
     */
    static Organization individual(com.xbb.org.api.OrgType type, String name,
                                    long personUserId, String address) {
        Organization o = new Organization();
        o.type = type;
        o.name = name;
        o.creditCode = null;
        o.subjectType = com.xbb.org.api.SubjectType.INDIVIDUAL;
        o.legalRepUserId = personUserId;
        o.address = address;
        o.status = Status.APPROVED;
        return o;
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
    static Organization platformStation(String name, String creditCode, String address) {
        Organization o = new Organization();
        o.type = com.xbb.org.api.OrgType.SERVICE_STATION;
        o.name = name;
        o.creditCode = creditCode;
        o.address = address;
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
    public com.xbb.org.api.SubjectType getSubjectType() { return subjectType; }
    /** @return 可能为 null —— 老数据没有地址 */
    public String getAddress() { return address; }
}
