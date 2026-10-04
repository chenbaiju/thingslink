package com.things.link.testing;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.test.context.ContextCustomizerFactories;
import org.springframework.test.context.TestExecutionListeners;

/**
 * 声明仅供本测试类使用的静态容器；类结束后关闭上下文，再释放明确列出的资源。
 *
 * <p>不得登记基类共享资源或指向其他夹具资源的别名；不继承此所有权声明。
 * 每个类只能在当前JVM运行一次，不能在关闭后复用静态连接地址。
 * 异步依赖必须参加Spring生命周期，或由测试自行有界关闭；本设施不替后者证明物理结束。
 * 上下文必须登记调度器关闭监控；不支持类内替换或提前关闭上下文。
 * private字段也可能经方法被跨类借用，调用者必须人工确认所有权。
 *
 * <p>启动与「数据库容器真正可连接」的探活由 {@link OwnedTestContainersListener} 在触发 Spring 上下文创建之前完成，
 * 因此属性源里的 {@code start()} 只负责发布 URL 的时序，不再承担等待数据库就绪的职责。
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@ContextCustomizerFactories(OwnedTestContainersContextCustomizerFactory.class)
@TestExecutionListeners(listeners = OwnedTestContainersListener.class,
        mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS)
public @interface OwnedTestContainers {
    /** @return 本类直接声明的专属静态GenericContainer字段名称，不扫描其他字段 */
    String[] value();
}
