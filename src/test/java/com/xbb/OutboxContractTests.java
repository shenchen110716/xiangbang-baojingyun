package com.xbb;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 钉住 outbox 赖以成立的两条约定。
 *
 * <p>这两条一旦被破坏,**所有 outbox 会同时失效,而且不会有任何报错**——
 * 事件照样被标成 PUBLISHED,只是再也不重试了。这种失效没有征兆,
 * 只能靠这里拦住。用扫源码而不是反射:约定本身就是"源码里不许出现某个注解"。
 */
class OutboxContractTests {

    private static final Path MAIN = Path.of("src/main/java/com/xbb");

    /**
     * 匹配注解的**短名与全限定名两种写法**。
     *
     * <p>最早只写了短名,于是 {@code @org.springframework.transaction.event.TransactionalEventListener}
     * 能大摇大摆绕过去——守卫存在但抓不到,和没有守卫一样,而且更危险(让人以为已经防住了)。
     * 这个漏洞是在"验证守卫真的会失败"那一步发现的,不是靠读代码看出来的。
     */
    private static final java.util.regex.Pattern ANNOTATION_USE = java.util.regex.Pattern.compile(
            "^\\s*@(?:[\\w.]+\\.)?TransactionalEventListener", java.util.regex.Pattern.MULTILINE);

    private static List<Path> javaSources() throws IOException {
        try (Stream<Path> paths = Files.walk(MAIN)) {
            return paths.filter(p -> p.toString().endsWith(".java")).toList();
        }
    }

    /**
     * 跨域事件由中继在**它自己的事务里**投递。消费方若用
     * {@code @TransactionalEventListener(AFTER_COMMIT)},就要等中继事务提交后才执行,
     * 而那时 outbox 行已经被标成 PUBLISHED——消费方再抛异常,事件就永久丢了,
     * outbox 等于没做。所以消费方必须是 {@code @EventListener},内联执行,
     * 异常才能回到中继的重试逻辑里。
     */
    @Test
    void 消费方不得使用AFTER_COMMIT监听器() throws IOException {
        List<String> offenders = javaSources().stream()
                .filter(path -> {
                    try {
                        // 只认**真的用作注解**的那种(行首),不认 javadoc 里提到它的文字——
                        // 那些注释恰恰是在解释为什么不能用它。
                        return ANNOTATION_USE.matcher(Files.readString(path)).find();
                    } catch (IOException e) {
                        throw new IllegalStateException(e);
                    }
                })
                .map(MAIN::relativize)
                .map(Path::toString)
                .toList();

        assertThat(offenders)
                .withFailMessage("""
                        这些类用了 @TransactionalEventListener:%s
                        跨域事件现在由 outbox 中继投递,AFTER_COMMIT 的监听器会在
                        outbox 行被标成 PUBLISHED **之后**才跑,它抛的异常谁也接不住,
                        事件就永久丢了。改用 @EventListener。""", offenders)
                .isEmpty();
    }

    /**
     * 业务数据与事件行必须落在同一个事务里,这要求 outbox 表在**发布方自己的 schema**。
     * 每个域有独立 DataSource,把 outbox 放共享 schema 就变成了跨事务,
     * 可能一个成功一个失败,outbox 的全部保证就此作废。
     *
     * <p>所以:有 outbox 中继的域,必须在自己的 migration 里建自己的 outbox 表。
     */
    @Test
    void 每个有中继的域都要有自己schema下的outbox表() throws IOException {
        List<String> relayDomains = javaSources().stream()
                .filter(p -> p.getFileName().toString().endsWith("OutboxRelay.java"))
                .map(MAIN::relativize)
                // 排除 com/xbb 根目录下的公共基类 AbstractOutboxRelay,它不属于任何域
                .filter(p -> p.getNameCount() > 1)
                .map(p -> p.getName(0).toString())
                .sorted().toList();

        assertThat(relayDomains).isNotEmpty();

        for (String domain : relayDomains) {
            Path migrations = Path.of("src/main/resources/db/migration", domain);
            boolean declaresOwnTable;
            try (Stream<Path> paths = Files.walk(migrations)) {
                declaresOwnTable = paths.filter(p -> p.toString().endsWith(".sql")).anyMatch(p -> {
                    try {
                        return Files.readString(p).contains("CREATE TABLE " + domain + ".outbox_event");
                    } catch (IOException e) {
                        throw new IllegalStateException(e);
                    }
                });
            }
            assertThat(declaresOwnTable)
                    .withFailMessage("%s 域有中继却没有 %s.outbox_event 表——"
                            + "事件行必须和业务数据落在同一个事务,也就必须在本域自己的 schema 里",
                            domain, domain)
                    .isTrue();
        }
    }
}
