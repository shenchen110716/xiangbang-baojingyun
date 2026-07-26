package com.xbb.voice.internal;

import com.xbb.voice.internal.IntentRegistry.Intent;
import com.xbb.voice.internal.IntentRegistry.Role;

import java.util.Optional;

/**
 * 语音意图路由(主文档 §7.3),纯函数。
 *
 * <p>**权限必须前置**,这是本类存在的核心理由:
 * "图形界面里,工人**根本看不到**'发薪'按钮;语音界面里,他**可以说出任何话**。
 * 语音优先架构天然放大了越权尝试的入口——随口一句'给我发薪'就是一次尝试。
 * 因此**意图路由层必须是第一道权限闸**,而不是把请求放进域里再拒。"
 */
public class IntentRouter {

    /** 置信度低于这个值就反问澄清,不猜(§7.3:"猜错的代价远大于多问一句")。 */
    static final double MIN_CONFIDENCE = 0.7;

    public sealed interface Routing {
        /** 放行到对应域执行 */
        record Dispatch(Intent intent) implements Routing { }
        /** 权限不足,在路由层就拒绝,请求不进入任何域 */
        record Denied(String reason) implements Routing { }
        /** 意图不在注册表内,或置信度不足 → 反问澄清 */
        record Clarify(String question) implements Routing { }
    }

    public Routing route(String intentName, double confidence, Role role) {
        Optional<Intent> found = IntentRegistry.find(intentName);
        if (found.isEmpty()) {
            // 注册表外的意图一律不放行——LLM 不能自由发明意图
            return new Routing.Clarify("没太听懂,您是想做什么?");
        }
        if (confidence < MIN_CONFIDENCE) {
            return new Routing.Clarify("没太听清,您是想" + found.get().samples().get(0) + "吗?");
        }
        // 权限闸:在进入任何域之前
        if (!found.get().allowedRoles().contains(role)) {
            return new Routing.Denied("您没有执行这个操作的权限");
        }
        return new Routing.Dispatch(found.get());
    }
}
