package com.xbb.identity.internal;

import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class VerificationCodeService {

    private static final Duration TTL = Duration.ofMinutes(5);
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Entry> store = new ConcurrentHashMap<>();

    private record Entry(String code, Instant expiresAt) { }

    public String issue(String phone) {
        String code = String.format("%06d", random.nextInt(1_000_000));
        store.put(phone, new Entry(code, Instant.now().plus(TTL)));
        return code;
    }

    public boolean verify(String phone, String code) {
        Entry e = store.get(phone);
        if (e == null || Instant.now().isAfter(e.expiresAt())) {
            return false;
        }
        boolean ok = e.code().equals(code);
        if (ok) {
            store.remove(phone);
        }
        return ok;
    }
}
