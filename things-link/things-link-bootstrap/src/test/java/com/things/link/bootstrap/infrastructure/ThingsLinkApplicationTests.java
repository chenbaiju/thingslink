package com.things.link.bootstrap.infrastructure;

import com.things.link.testing.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 应用上下文装配的冒烟测试。
 *
 * <p>它验证的是「所有 Bean 能被创建、依赖能被注入、配置能被解析、Flyway 迁移能
 * 执行」。随着后续模块引入更多组件，这里会成为最先发现装配错误的地方 ——
 * 上下文起不来时，业务测试的报错往往指向别处，很难定位。
 *
 * <p>S0-4 起继承 {@link AbstractIntegrationTest}：引入 JPA 之后上下文需要一个真实
 * 的 DataSource 才能启动，不能再用空上下文。容器由基类管理，全 JVM 只启一次。
 */
class ThingsLinkApplicationTests extends AbstractIntegrationTest {

    /**
     * 上下文能正常加载。
     *
     * <p>断言 Java 运行版本符合基线（{@code docs/DEVELOPMENT_ROADMAP.md} 决策 #1，
     * JDK 21）。pom 里的 maven-enforcer-plugin 守的是<b>构建期</b>的 JDK，这里守的是
     * <b>运行期</b>的 JVM —— 两者可以不同，例如用 21 编译却在 17 的 JVM 上运行时，
     * 失败信息会是难以定位的 UnsupportedClassVersionError。
     */
    @Test
    void contextLoads() {
        assertThat(Runtime.version().feature()).isGreaterThanOrEqualTo(21);
    }

}
