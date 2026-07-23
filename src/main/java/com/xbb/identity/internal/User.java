package com.xbb.identity.internal;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "app_user", schema = "identity")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String phone;

    private String realName;

    @Column(unique = true)
    private String idNumber;

    @Column(nullable = false)
    private boolean verified = false;

    @Column(nullable = false)
    private boolean blocked = false;

    @Column(nullable = false, unique = true)
    private String inviteCode;

    @Column(name = "balance_cents", nullable = false)
    private long balanceCents = 0L;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    protected User() { }

    public User(String phone, String inviteCode) {
        this.phone = phone;
        this.inviteCode = inviteCode;
    }

    public void verify(String realName, String idNumber) {
        this.realName = realName;
        this.idNumber = idNumber;
        this.verified = true;
    }

    public Long getId() { return id; }
    public String getPhone() { return phone; }
    public String getRealName() { return realName; }
    public String getIdNumber() { return idNumber; }
    public boolean isVerified() { return verified; }
    public boolean isBlocked() { return blocked; }
    public String getInviteCode() { return inviteCode; }
    public long getBalanceCents() { return balanceCents; }
}
