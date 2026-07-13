package com.xbb.baojing.common;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Date;

@Component
public class JwtService {
    private final SecretKey key;

    public JwtService(AppProperties props) {
        String secret = props.getJwtSecret();
        // jjwt's HS256 key builder requires >= 256 bits; pad short dev secrets
        // deterministically rather than failing at boot for local/dev use.
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            byte[] padded = new byte[32];
            for (int i = 0; i < 32; i++) padded[i] = bytes[i % bytes.length];
            bytes = padded;
        }
        this.key = Keys.hmacShaKeyFor(bytes);
    }

    public String issueToken(int userId, int sessionVersion) {
        Date now = new Date();
        Date exp = new Date(now.getTime() + Duration.ofHours(12).toMillis());
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("sv", sessionVersion)
                .issuedAt(now)
                .expiration(exp)
                .signWith(key)
                .compact();
    }

    /** Returns [userId, sessionVersion], or null if the token is invalid/expired. */
    public int[] verify(String token) {
        try {
            Claims claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
            int uid = Integer.parseInt(claims.getSubject());
            int sv = claims.get("sv", Integer.class);
            return new int[]{uid, sv};
        } catch (JwtException | NumberFormatException | NullPointerException e) {
            return null;
        }
    }
}
