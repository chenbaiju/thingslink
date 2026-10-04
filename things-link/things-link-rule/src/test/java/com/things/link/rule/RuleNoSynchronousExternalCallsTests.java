package com.things.link.rule;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import com.things.link.rule.application.engine.RuleNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * S9 核心约束的强制手段：规则事务内禁止同步调用外部系统，副作用只能经 Outbox 异步投递。
 *
 * <p>规则模块只被允许产出「副作用意图」（{@code RuleSideEffectIntent}）；真正发 HTTP/SMTP/短信的客户端
 * 与事务 Outbox 写入器都属于宿主侧边界，动作节点一旦直接依赖它们，本测试即让构建失败。</p>
 */
@DisplayName("S9 规则副作用架构约束")
class RuleNoSynchronousExternalCallsTests {

    /** 只导入规则模块生产字节码；禁止包的目标通过 classpath 解析。 */
    private static final JavaClasses RULE_CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.things.link.rule");

    /** 规则模块任何代码都不得同步依赖 HTTP 客户端或邮件发送器，否则可绕过 Outbox 同步调用外部系统。 */
    @Test
    @DisplayName("规则模块不得同步依赖 HTTP/SMTP 客户端")
    void ruleModuleMustNotDependOnSynchronousExternalClients() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("com.things.link.rule..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework.mail..",
                        "org.springframework.web.client..",
                        "okhttp3..",
                        "java.net.http..")
                .because("S9 规则事务内禁止同步调用外部系统，副作用只能经 Outbox 异步投递");
        rule.check(RULE_CLASSES);
    }

    /** 动作节点只能产出意图，不得直接写 Outbox 或发 Kafka，否则会绕过统一的副作用投递边界。 */
    @Test
    @DisplayName("RuleNode 实现不得直接写 Outbox 或发 Kafka")
    void ruleNodesMustNotAccessOutboxOrKafka() {
        ArchRule rule = noClasses()
                .that().implement(RuleNode.class)
                .should().dependOnClassesThat().resideInAnyPackage(
                        "com.things.link.support.outbox..",
                        "org.springframework.kafka.core..")
                .because("动作节点只能产出 RuleSideEffectIntent，真正副作用由 S9 Outbox 边界执行");
        rule.check(RULE_CLASSES);
    }
}
