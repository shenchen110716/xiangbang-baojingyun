package com.xbb.identity.internal;

import com.xbb.identity.api.Role;
import jakarta.persistence.*;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

@Entity
@Table(name = "user_role", schema = "identity")
@IdClass(UserRole.Key.class)
public class UserRole {

    @Id
    @Column(name = "user_id")
    private Long userId;

    @Id
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private Role role;

    @Column(name = "granted_at", nullable = false)
    private Instant grantedAt = Instant.now();

    protected UserRole() { }

    public UserRole(long userId, Role role) {
        this.userId = userId;
        this.role = role;
    }

    public Long getUserId() { return userId; }
    public Role getRole() { return role; }

    /** 复合主键 (user_id, role)。 */
    public static class Key implements Serializable {
        private Long userId;
        private Role role;

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Key key)) return false;
            return Objects.equals(userId, key.userId) && role == key.role;
        }

        @Override
        public int hashCode() { return Objects.hash(userId, role); }
    }
}
