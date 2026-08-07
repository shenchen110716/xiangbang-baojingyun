package com.xbb;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * api 包里不许出现 internal 的类型。
 *
 * <p><b>为什么单独守这一条。</b>api 包是给别的域看的契约。
 * 里面只要出现一个 internal 类型,任何读它的域就等于引用了别人的内部实现 ——
 * ModularityTests 会拦,但**它拦的是"有人真的去读了"那一刻**,
 * 而不是"契约里被写进去"那一刻。
 *
 * <p>这个项目里同一形状出现过两次:{@code OrgSummary.status} 修过一次,
 * 而 {@code OrgView.status} 上一直留着,直到 2026-08-07 有人去读才炸出来。
 * <b>修一处不等于修完</b> —— 所以要有一条按形状扫的守卫。
 */
class ApiPackagePurityTest {

    /** 形如 com.xbb.<域>.internal.Xxx。 */
    private static final Pattern INTERNAL_REF =
            Pattern.compile("com\\.xbb\\.(\\w+)\\.internal\\.(\\w+)");

    @Test
    void api包里不出现internal的类型() throws IOException {
        List<String> offenders = new ArrayList<>();
        Path root = Path.of("src/main/java/com/xbb");

        try (Stream<Path> files = Files.walk(root)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> p.toString().contains("/api/")).toList()) {
                String src = Files.readString(f);
                Matcher m = INTERNAL_REF.matcher(src);
                while (m.find()) {
                    offenders.add(root.relativize(f) + "  →  " + m.group());
                }
                // 不带包名的引用也要抓:import 了 internal 的类之后直接写类名
                for (String line : src.lines().toList()) {
                    if (line.trim().startsWith("import ") && line.contains(".internal.")) {
                        offenders.add(root.relativize(f) + "  →  " + line.trim());
                    }
                }
            }
        }

        assertThat(offenders)
                .as("""
                    api 包是给别的域看的契约,里面不该出现任何 internal 类型。
                    留着的话,读它的域就等于引用了别人的内部实现 ——
                    而那要等到"真的有人去读"才会被 ModularityTests 拦下。
                    改法:在 api 包里定义一个等价的类型(枚举名保持一致,JSON 就不变)。
                    """)
                .isEmpty();
    }
}
