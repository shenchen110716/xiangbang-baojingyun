package com.xbb;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 钉住"调度路径上事务真的生效"。
 *
 * <p>之前 11 个中继都是 {@code relayScheduled()} 里直接 {@code publishPending();}——
 * **自调用绕过 Spring 代理,@Transactional 静默失效**。后果:`FOR UPDATE SKIP LOCKED`
 * 只在仓库自己的短事务里持锁,SELECT 一返回锁就释放,多实例会重复投递同一条事件;
 * 批内每次 save 各自提交,"整批一致"不存在。
 *
 * <p>这个 bug 存活了很久,因为**所有测试都直接调注入的代理 bean**
 * (`relay.publishPending()`),事务在那条路径上是生效的——测的路径和调度器跑的
 * 路径不是同一条。所以这里不测行为,直接测源码形状:调度入口不许自调用。
 */
class OutboxSchedulingContractTest {

    private static final Path MAIN = Path.of("src/main/java/com/xbb");

    /** 调度方法体里直接写 publishPending(); 就是自调用。 */
    private static final Pattern SELF_INVOCATION = Pattern.compile(
            "public void relayScheduled\\(\\)\\s*\\{[^}]*?(?<![.\\w])publishPending\\(\\)\\s*;",
            Pattern.DOTALL);

    @Test
    void 调度入口不得自调用事务方法() throws IOException {
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(MAIN)) {
            for (Path p : paths.filter(x -> x.getFileName().toString().endsWith("OutboxRelay.java")).toList()) {
                String src = Files.readString(p);
                if (SELF_INVOCATION.matcher(src).find()) {
                    offenders.add(MAIN.relativize(p).toString());
                }
            }
        }
        assertThat(offenders)
                .withFailMessage("""
                        这些中继的 relayScheduled() 直接调用了 publishPending():%s
                        自调用不经过 Spring 代理,@Transactional 会静默失效——
                        SKIP LOCKED 的行锁立刻释放、多实例重复投递、批内各自提交。
                        改成经代理调用(注入 ObjectProvider<自身> 再 getObject())。""", offenders)
                .isEmpty();
    }

    @Test
    void 每个中继都要有调度入口和事务方法() throws IOException {
        int relays = 0;
        try (Stream<Path> paths = Files.walk(MAIN)) {
            for (Path p : paths.filter(x -> x.getFileName().toString().endsWith("OutboxRelay.java")).toList()) {
                if (MAIN.relativize(p).getNameCount() == 1) {
                    continue;   // 根目录下的公共基类
                }
                String src = Files.readString(p);
                assertThat(src).as("%s 缺调度入口", p).contains("@Scheduled");
                assertThat(src).as("%s 的 publishPending 缺事务注解", p).contains("@Transactional(");
                relays++;
            }
        }
        assertThat(relays).isGreaterThanOrEqualTo(11);
    }
}
