package com.things.link.support;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * support 模块集成测试用的最小 Spring Boot 应用。
 *
 * <p>support 是库模块，本身没有 {@code @SpringBootApplication}，而
 * {@code @SpringBootTest} 需要向上找到一个 {@code @SpringBootConfiguration} 才能
 * 引导上下文。这个类只在测试期存在，不进生产产物。
 *
 * <p>放在 {@code com.things.link.support} 包根下，组件扫描才能覆盖本模块的全部
 * 组件（过滤器、幂等存储、异常处理器）。
 *
 * <p>注意它<b>不是</b>全仓库唯一启动类的例外 —— 那条规则约束的是生产代码
 * （架构文档 10.2），测试脚手架不在其列。
 */
@SpringBootApplication
public class SupportTestApplication {
}
