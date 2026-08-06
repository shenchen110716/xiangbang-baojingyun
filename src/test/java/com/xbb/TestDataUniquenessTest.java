package com.xbb;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 测试数据不重复的守卫。**不起 Spring,只扫源码。**
 *
 * <p>测试之间没有隔离,全靠手工分配手机号/身份证号段避免撞车 ——
 * 这是记录在案的已知弱点,而它**真的会咬人**:
 * 加计薪方案测试时我挑了 1001 段,和 broker 的两个既有测试撞了;
 * 换到 2001 段又和结算域自己的测试撞了。两次都是跑了整套才发现,
 * 报错还是含糊的"该身份证已被绑定",看不出是测试数据问题。
 *
 * <p>约定靠人守就会这样。这条把它交给机器:**新加测试时立刻红,不用等跑完整套。**
 *
 * <p>做不到的是"自动分配号段" —— 那要改所有测试。这条只保证冲突被立刻发现。
 */
class TestDataUniquenessTest {

    /** 18 位身份证。测试里的都是 110101 + 生日 + 4 位序号这个形态。 */
    private static final Pattern ID_CARD = Pattern.compile("\"(\\d{6}\\d{8}\\d{4})\"");

    /**
     * 统一社会信用代码。**不要求两侧有引号** —— 走真实 HTTP 的测试把它写在
     * JSON 字面量里({@code \\"creditCode\\":\\"9111...\\"}),收尾是反斜杠不是引号。
     *
     * <p>我就是这么栽的:用带引号的正则扫了一遍,断定某个字母位空闲,
     * 结果 StationHttpTest 早就在用,建站直接 409。
     * **核对手段本身出错时,会给出一个自信的错误答案,比不查更危险。**
     */
    private static final Pattern CREDIT_CODE = Pattern.compile("(91\\d{11}[a-zA-Z]\\d{2}[A-Z])");

    // 这里原先有一份 KNOWN_DUPLICATES 名单,写着「不是豁免,是待办」,
    // 里面挂着 110101199001017777。**清的时候发现那条重复根本不存在** ——
    // 那个号只在 IdentityControllerTest 里真用过一次,第二处"出现"是名单自己。
    //
    // 扫描把本文件也扫了进来,于是**凡是被加进名单的号都因此变成"重复"**,
    // 名单自我印证、永远清不掉,而且会掩盖那个号后来真的被别人用了。
    // 改成跳过本文件之后名单空了,一并删掉。

    @Test
    void 测试里的身份证号不能重复() throws IOException {
        Map<String, List<String>> byId = new TreeMap<>();
        Path root = Path.of("src/test/java");
        try (Stream<Path> files = Files.walk(root)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java"))
                    // **跳过本文件。**不跳的话,写在这里的号码字面量会被当成一次"使用",
                    // 于是任何想在这里说明的号都自动变成跨文件重复
                    .filter(p -> !p.getFileName().toString().equals("TestDataUniquenessTest.java"))
                    .toList()) {
                String src = Files.readString(f);
                Matcher m = ID_CARD.matcher(src);
                while (m.find()) {
                    byId.computeIfAbsent(m.group(1), k -> new ArrayList<>())
                            .add(root.relativize(f).toString());
                }
                Matcher c = CREDIT_CODE.matcher(src);
                while (c.find()) {
                    byId.computeIfAbsent(c.group(1), k -> new ArrayList<>())
                            .add(root.relativize(f).toString());
                }
            }
        }
        // 同一个文件里重复用没关系(同一个人),跨文件才是冲突
        Map<String, List<String>> conflicts = new TreeMap<>();
        byId.forEach((id, files) -> {
            Set<String> distinct = new TreeSet<>(files);
            if (distinct.size() > 1) {
                conflicts.put(id, new ArrayList<>(distinct));
            }
        });

        assertThat(conflicts)
                .as("身份证号或统一社会信用代码跨测试类重复。测试共用一个数据库容器,第二个用它的测试会拿到"
                    + "「该身份证已被绑定」—— 报错看不出是测试数据问题,查起来很费时间。"
                    + "换一个没人用过的号段即可")
                .isEmpty();
    }
}
