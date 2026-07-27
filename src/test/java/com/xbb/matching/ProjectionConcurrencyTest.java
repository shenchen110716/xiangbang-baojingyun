package com.xbb.matching;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 钉住"被多方写入的投影必须有乐观锁"。
 *
 * <p>`worker_projection` 有两个写入方:ProfileEventListener 写标签、
 * ReviewEventListener 写信用分。两者都是"读整行—改一个字段—整行写回",
 * 各自 REQUIRES_NEW。履约完成会**同时**触发这两条链路,没有版本号时
 * 后提交的一方把先提交的改动整个盖掉——工人刚验证过的技能标签被一次
 * 信用分更新悄悄打回自述状态,不报错、不留痕。
 *
 * <p>这个 bug 的表面症状是反哺链路"时灵时不灵"(约三次挂一次),
 * 排查时先后错怪过测试隔离和事件乱序,都不是。
 *
 * <p>这里测源码形状而不是并发行为:并发用例本身不稳定,而"有几个写入方"
 * 和"实体有没有 @Version"是静态可判定的。
 */
class ProjectionConcurrencyTest {

    private static final Path MATCHING = Path.of("src/main/java/com/xbb/matching/internal");

    @Test
    void 被多方写入的投影实体必须有乐观锁() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path entity : entities()) {
            String simpleName = entity.getFileName().toString().replace(".java", "");
            long writers = writerCount(simpleName);
            if (writers > 1 && !Files.readString(entity).contains("@Version")) {
                offenders.add(simpleName + "(" + writers + " 个写入方,无 @Version)");
            }
        }
        assertThat(offenders)
                .withFailMessage("""
                        这些实体被多处并发写入却没有乐观锁:%s
                        两个 REQUIRES_NEW 的监听器各自"读整行—改一个字段—写回"时,
                        后提交的会把先提交的改动整个盖掉,不报错也不留痕。
                        加 @Version:冲突方会失败,由 outbox 中继重投并基于新数据重放。""", offenders)
                .isEmpty();
    }

    /** 有多少个不同的文件调用了 <repo>.save(...)。 */
    private long writerCount(String entitySimpleName) throws IOException {
        String repoVar = switch (entitySimpleName) {
            case "WorkerProjection" -> "workers.save";
            case "JobProjection" -> "jobs.save";
            default -> null;
        };
        if (repoVar == null) {
            return 0;
        }
        try (Stream<Path> paths = Files.walk(MATCHING)) {
            return paths.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> {
                        try {
                            return Files.readString(p).contains(repoVar);
                        } catch (IOException e) {
                            throw new IllegalStateException(e);
                        }
                    })
                    .count();
        }
    }

    private List<Path> entities() throws IOException {
        try (Stream<Path> paths = Files.walk(MATCHING)) {
            return paths.filter(p -> p.getFileName().toString().endsWith("Projection.java")).toList();
        }
    }
}
