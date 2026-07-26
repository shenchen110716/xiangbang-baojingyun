package com.xbb;

import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

/**
 * 运维入口:把各域中继的"卡死事件"汇总到一处,并支持人工重放。
 *
 * <p>为什么要有它:每个域的 outbox 表在各自的 schema 里,谁也不能跨 schema 查
 * (三条铁律之一)。所以汇总不能靠一条 SQL,只能让每个中继报自己那份——
 * 这里注入全部中继,各查各的库,再合起来。
 *
 * <p><b>目前不对外暴露 HTTP 接口。</b>重放一条资金事件是高权限操作,而本项目
 * 还没有 RBAC(见 OpsApi 的说明:通用权限模型是独立的一块)。把它挂在
 * "任何登录用户都能访问"的 /api 下面是不可接受的,而临时发明一套 token 鉴权
 * 又会留下一个绕过统一权限模型的后门。所以能力先做出来并测好,
 * HTTP 出口等 RBAC 落地后再接。
 */
@Component
public class OutboxAdmin {

    private final List<AbstractOutboxRelay<?>> relays;

    OutboxAdmin(List<AbstractOutboxRelay<?>> relays) {
        this.relays = relays;
    }

    /** 全平台卡死的事件,按重试次数从多到少——最该先看的排前面。 */
    public List<AbstractOutboxRelay.StuckEvent> stuckEvents() {
        return relays.stream()
                .flatMap(relay -> relay.stuck().stream())
                .sorted(Comparator.comparingInt(AbstractOutboxRelay.StuckEvent::attemptCount).reversed())
                .toList();
    }

    /**
     * 重放某个域里的一条事件。
     *
     * @return 是否找到了这条事件
     * @throws IllegalArgumentException 域名不存在——写错域名时要明确报错,
     *                                  而不是返回 false 让人以为"事件不存在"
     */
    public boolean replay(String domain, String eventId) {
        AbstractOutboxRelay<?> relay = relays.stream()
                .filter(r -> r.domain().equals(domain))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("没有这个域的 outbox: " + domain));
        return relay.replay(eventId);
    }

    /** 有中继的域名单,给运维界面出下拉用。 */
    public List<String> domains() {
        return relays.stream().map(AbstractOutboxRelay::domain).sorted().toList();
    }
}
