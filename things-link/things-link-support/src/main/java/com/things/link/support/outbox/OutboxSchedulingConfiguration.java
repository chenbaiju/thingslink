package com.things.link.support.outbox;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 开启事务 Outbox 的后台轮询。
 *
 * <p>调度能力在 support 统一开启，因为 Outbox 是跨领域的可靠投递底座；业务模块只产生日志化事件，
 * 不得各自启动内存定时器，否则多实例下会产生无法统一治理的重复发布器。</p>
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class OutboxSchedulingConfiguration {
}
