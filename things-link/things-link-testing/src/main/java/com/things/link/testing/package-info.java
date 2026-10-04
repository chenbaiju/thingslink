/**
 * 集成测试基础设施。
 *
 * <p>工具模块，不进生产依赖树 —— 其他模块一律以 {@code <scope>test</scope>} 依赖。
 * 与 {@code things-link-simulator} 同类，不属于架构文档 10.2 的业务模块序列，
 * 因此不适用 10.3 的四层分包，也不参与 10.4 的跨模块依赖检查。
 *
 * <p>核心是 {@link com.things.link.testing.AbstractIntegrationTest}：
 * 用 Testcontainers 起真实中间件，容器在整个 JVM 内只启一次（singleton 模式）。
 */
package com.things.link.testing;
