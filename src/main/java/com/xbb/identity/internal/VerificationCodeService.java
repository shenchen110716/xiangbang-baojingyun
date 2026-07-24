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
        // compute() 对同一个 key 是原子的(ConcurrentHashMap 内部按桶加锁)——
        // 用一次原子操作把"读取+判断+消费"合成一步,避免两个并发请求都读到
        // 同一个未消费的验证码、都通过校验(审计报告发现的竞态)。
        boolean[] matched = { false };
        store.compute(phone, (key, e) -> {
            if (e == null || Instant.now().isAfter(e.expiresAt())) {
                return e;
            }
            if (e.code().equals(code)) {
                matched[0] = true;
                return null; // 消费掉,后来者(哪怕验证码字符串相同)拿到的都是 null
            }
            return e;
        });
        return matched[0];
    }
}
