package com.xbb.baojing.common;

import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Ports backend/core/file_tokens.py — short-lived (5 min) HMAC-signed
 * download links for position videos / claim documents, replacing an
 * anonymous static mount. */
@Service
public class FileTokenService {
    private static final long DEFAULT_TTL_SECONDS = 300;
    private final byte[] secret;

    public FileTokenService(AppProperties props) {
        this.secret = props.getJwtSecret().getBytes(StandardCharsets.UTF_8);
    }

    private String sign(String resource, long expires) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            byte[] raw = mac.doFinal((resource + ":" + expires).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : raw) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public record Token(String token, long expires) {}

    public Token makeToken(String resource) {
        long expires = System.currentTimeMillis() / 1000 + DEFAULT_TTL_SECONDS;
        return new Token(sign(resource, expires), expires);
    }

    public boolean verify(String resource, long expires, String token) {
        if (System.currentTimeMillis() / 1000 > expires) return false;
        String expected = sign(resource, expires);
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8));
    }
}
