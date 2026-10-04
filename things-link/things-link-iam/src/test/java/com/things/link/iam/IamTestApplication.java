package com.things.link.iam;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * iam 模块集成测试用的最小 Spring Boot 应用。
 *
 * <p>iam 是库模块，本身没有启动类，而 {@code @SpringBootTest} 需要向上找到一个
 * {@code @SpringBootConfiguration} 才能引导上下文。仅测试期存在。
 *
 * <h2>为什么扫描根包而不是只扫 com.things.link.iam</h2>
 * 只扫本模块的话，support 模块的 {@code GlobalExceptionHandler} 与各过滤器都不会
 * 被注册，于是业务异常不会被转成统一错误响应，而是以 500 冒泡 ——
 * 测试看到的行为与真实应用完全不同。
 *
 * <p>扫描根包让这里的测试同时覆盖<b>跨模块装配</b>：异常处理器确实生效、
 * traceId 确实写进了响应。这补上了 ErrorResponseContractTests 用
 * standaloneSetup 留下的缺口 —— 那层只验证组件本身的行为，不验证它被注册了。
 */
@SpringBootApplication(scanBasePackages = "com.things.link")
public class IamTestApplication {
}
