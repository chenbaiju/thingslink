package com.things.link.support.notification.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.RejectedExecutionHandler;

/**
 * 发信用的异步执行器。
 *
 * <h2>为什么邮件要有自己的线程池，不能用默认的</h2>
 * 用 Spring 默认的 {@code applicationTaskExecutor} 意味着发信与其他所有 {@code @Async}
 * 任务共用一个池。SMTP 是慢的外部依赖 —— 服务商限流或网络抖动时，发信任务会把池
 * 占满，然后**与邮件毫不相干的异步任务一起卡住**。这类故障的排查方向几乎必然是错的。
 *
 * <h2>为什么队列有界、拒绝策略是丢弃并报错</h2>
 * 无界队列在 SMTP 卡住时会一直堆积到 OOM，而且堆积期间没有任何症状。
 *
 * <p>队列满时的三个选项里：
 * <ul>
 *   <li>{@code CallerRunsPolicy} 会让调用线程去发信。发信是在
 *       {@code @TransactionalEventListener(AFTER_COMMIT)} 里触发的，那时 HTTP 响应
 *       还没写出去 —— 等于把注册接口的响应时间挂在 SMTP 上，正是异步要避免的</li>
 *   <li>无声丢弃（{@code DiscardPolicy}）最糟：用户看到「请查收邮件」，信却从未发出</li>
 *   <li><b>丢弃并记 ERROR</b>：邮件确实丢了，但用户还有「重发」这条自助出路，
 *       而运维能从日志里看到发信管道已经堵了</li>
 * </ul>
 * 选第三个。它的前提是<b>验证邮件必须有重发入口</b>，否则丢弃就是死路。
 */
@Configuration
@EnableAsync
public class MailExecutorConfiguration {

    private static final Logger log = LoggerFactory.getLogger(MailExecutorConfiguration.class);

    /** 发信执行器的 bean 名。{@code @Async} 必须按名字引用它，否则会落到默认池。 */
    public static final String MAIL_EXECUTOR = "mailTaskExecutor";

    /**
     * 发信线程池。
     *
     * <p>池子刻意开得很小：邮件是低频操作（注册、重发验证、重置密码），
     * 而每条连接都占用服务商的并发配额。开大只会更快撞上对方的限流。
     *
     * @return 执行器
     */
    @Bean(name = MAIL_EXECUTOR)
    public ThreadPoolTaskExecutor mailTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("mail-");
        executor.setRejectedExecutionHandler(logAndDiscard());

        // 关闭时等待在途任务，最多 10 秒。不等的话，重启瞬间已经进池但还没发出的
        // 邮件会静默消失 —— 用户那边表现为「注册了但没收到信」，而日志里什么都没有
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        return executor;
    }

    /**
     * 队列满时丢弃并记 ERROR。
     *
     * @return 拒绝策略
     */
    private RejectedExecutionHandler logAndDiscard() {
        return (runnable, executor) -> log.error(
                "发信队列已满，本次邮件被丢弃。队列={} 活跃线程={} —— "
                        + "通常意味着 SMTP 服务商在限流或网络不通，用户需要自行点重发",
                executor.getQueue().size(), executor.getActiveCount());
    }

}
