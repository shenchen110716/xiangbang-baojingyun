package com.xbb;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

/**
 * 外部通道模式守卫。
 *
 * <p>三个防腐层(代发/电子签/推送)的 mock 实现会**静默成功**:不打款、不签署、不推送,
 * 却让上游认为一切正常。真上了生产,系统会扣减监管账户、把发放标 SUCCESS、
 * 写入假的完税凭证号、通知工人"工资已发放",而钱一分没出去。
 *
 * <p>所以这里在启动时把话说死:mock 模式必须是**显式配置**的,而且要在日志里喊出来。
 * 没配 xbb.channel.mode 时直接启动失败——比"上线后才发现钱没打出去"便宜得多。
 */
@Component
public class ChannelModeGuard implements ApplicationListener<ApplicationReadyEvent> {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ChannelModeGuard.class);

    private final String mode;

    ChannelModeGuard(@Value("${xbb.channel.mode}") String mode) {
        this.mode = mode;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        if ("mock".equals(mode)) {
            log.warn("外部通道处于 mock 模式:代发不会真的打款、电子签没有法律效力、推送不会送达。"
                    + "生产环境必须设 xbb.channel.mode=real 并提供真实实现。");
            return;
        }
        if (!"real".equals(mode)) {
            throw new IllegalStateException("xbb.channel.mode 只能是 mock 或 real,当前是: " + mode);
        }
        // real 模式下不需要在这里逐个校验通道有没有实现:mock 实现已被
        // @ConditionalOnProperty 挡在容器外,若真实实现也不存在,Spring 会在注入
        // 依赖时直接报 NoSuchBeanDefinition 而起不来——那本身就是失败关闭。
        log.info("外部通道处于 real 模式。");
    }
}
