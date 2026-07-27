package com.xbb;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.SimpleApplicationEventMulticaster;
import org.springframework.util.ErrorHandler;

import java.util.ArrayList;
import java.util.List;

/**
 * 让**一个消费方失败不再连累其他消费方**。
 *
 * <p>Spring 默认的广播器是同线程顺序调用,任一监听器抛出即中断,其后的监听器
 * 一次都不会执行。`EngagementCompleted` 有五个下游(结算/评价/人才库/画像/通知),
 * 顺序由 bean 注册顺序决定、不可控。于是评价域一个持续失败的 bug,
 * 就能让排在它后面的结算域**永远收不到事件——工资单永远不生成**,
 * 而告警只会说"投递失败",看不出结算根本没跑。
 *
 * <p>这里换成:每个监听器的异常都被接住、记下来、继续调下一个;
 * 广播结束后由 {@link AbstractOutboxRelay} 取走这批异常。
 * 有异常就把事件标成 FAILED 重试——所有消费方都会再收到一次,
 * 已经成功的那些靠自身幂等吸收(这也是消费方必须幂等的原因之一)。
 */
@Configuration
public class OutboxEventMulticasterConfig {

    /** 本次广播中各监听器抛出的异常。发布线程内有效。 */
    private static final ThreadLocal<List<Throwable>> FAILURES = ThreadLocal.withInitial(ArrayList::new);

    /** 取走并清空本次广播的失败列表。 */
    public static List<Throwable> drainFailures() {
        List<Throwable> collected = new ArrayList<>(FAILURES.get());
        FAILURES.get().clear();
        return collected;
    }

    @Bean(name = "applicationEventMulticaster")
    SimpleApplicationEventMulticaster applicationEventMulticaster() {
        SimpleApplicationEventMulticaster multicaster = new SimpleApplicationEventMulticaster();
        multicaster.setErrorHandler(new CollectingErrorHandler());
        return multicaster;
    }

    static class CollectingErrorHandler implements ErrorHandler {
        @Override
        public void handleError(Throwable t) {
            FAILURES.get().add(t);
        }
    }
}
