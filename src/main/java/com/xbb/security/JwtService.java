package com.xbb.security;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

/**
 * 跨域共享的基础设施(签发/校验都是纯粹的技术关注点,不属于任何业务域),
 * 因此放在 com.xbb.security 而不是某个域的 internal 包下——
 * identity 用它签发登录 token,JwtAuthenticationFilter 用它校验每个请求。
 */
@Service
public class JwtService {

    private final SecretKey key;
    private final long ttlMinutes;

    public JwtService(@Value("${xbb.jwt.secret}") String secret,
                       @Value("${xbb.jwt.ttl-minutes}") long ttlMinutes) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.ttlMinutes = ttlMinutes;
    }

    public String issue(long userId) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(ttlMinutes * 60)))
                .signWith(key)
                .compact();
    }

    public long parseUserId(String token) {
        return Long.parseLong(
                Jwts.parser().verifyWith(key).build()
                        .parseSignedClaims(token).getPayload().getSubject());
    }
}
