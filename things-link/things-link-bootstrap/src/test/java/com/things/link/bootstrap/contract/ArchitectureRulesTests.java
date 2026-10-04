package com.things.link.bootstrap.contract;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;

/**
 * 架构约束测试：把 {@code docs/BACKEND_ARCHITECTURE.md} 10.4 的依赖方向规则
 * 变成会让构建失败的检查。
 *
 * <p>为什么必须有这个测试：架构文档写了「禁止 Controller 直接调用 Repository」
 * 之类的规则，但**没有强制手段的禁止在三个月后一定会被违反**，尤其是单人项目，
 * 没有第二个人会在评审时发现。一个 ArchUnit 测试比写十遍规范有用。
 *
 * <p>为什么放在 bootstrap 模块：它依赖全部业务模块，是唯一能一次看到所有模块
 * 字节码、从而验证**跨模块**依赖方向的地方。放在任何业务模块里都只能看到自己
 * 和自己的依赖，看不到违规者。
 *
 * <p><b>新增模块时必须同步更新 {@link #BUSINESS_MODULES} 或 {@link #TOOL_MODULES}</b>，
 * 并让 bootstrap 在生产或测试作用域依赖它；否则新模块不受任何约束，而测试仍然全绿，
 * 这是最危险的失效方式。
 */
@DisplayName("架构约束（BACKEND_ARCHITECTURE.md 10.4）")
class ArchitectureRulesTests {

    /** 根包。所有模块的包都在其下。 */
    private static final String ROOT = "com.things.link";

    /**
     * 业务模块的 domain 段列表，与 artifact 名的 {@code things-link-<domain>}
     * 部分逐字对应（架构文档 10.1）。
     *
     * <p>不含 {@code shared} 与 {@code support}：它们是横切模块，不分四层，
     * 其约束由各自 pom 中的 banned-dependencies 规则在依赖解析期强制，
     * 比字节码检查更早失败。
     */
    private static final List<String> BUSINESS_MODULES = List.of(
            "iam",
            "project",
            "entitlement",
            "issuer",
            "device",
            "telemetry",
            "ingestion",
            "alarm",
            "task",
            "rule",
            "enduser",
            "export",
            "dashboard",
            "ota",
            "integration"
    );

    /**
     * 不进入生产依赖树、但仍须接受跨模块边界检查的工具模块。
     *
     * <p>模拟器不采用业务模块四层结构，因此不加入 {@link #BUSINESS_MODULES}；它通过 bootstrap
     * 的 test 依赖进入 ArchUnit classpath，仍会作为“业务模块之外的调用者”接受规则 2–4 检查。</p>
     */
    private static final List<String> TOOL_MODULES = List.of("simulator");

    /** 业务模块内部的分层包名（架构文档 10.3）。 */
    private static final List<String> LAYERS = List.of("api", "application", "domain", "infrastructure");

    /**
     * 全部生产字节码。
     *
     * <p>排除测试类：测试为了构造场景本来就会跨层访问，把它们纳入检查只会产生
     * 大量必须豁免的例外，最终导致规则被放宽到失去意义。
     */
    private static final JavaClasses PRODUCTION_CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages(ROOT);

    /**
     * 规则 1：Controller 不得直接调用 Repository。
     *
     * <p>绕过 application 层就等于绕过事务边界。表现出来的问题通常不是立刻报错，
     * 而是部分写入成功、部分失败后没有回滚 —— 事后极难定位。
     */
    @Test
    @DisplayName("规则 1：Controller 不得直接调用 Repository")
    void controllerMustNotAccessRepository() {
        ArchRule rule = noClasses()
                .that().haveSimpleNameEndingWith("Controller")
                .should().dependOnClassesThat().haveSimpleNameEndingWith("Repository")
                .because("Controller 必须经 application 层调用用例，直接访问 Repository 会绕过事务边界"
                        + "（BACKEND_ARCHITECTURE.md 10.4 规则 1）")
                .allowEmptyShould(true);

        rule.check(PRODUCTION_CLASSES);
    }

    /**
     * 规则 2 与 3：跨模块只能引用对方 {@code application} 包，
     * 不得引用 {@code domain} 与 {@code infrastructure}。
     *
     * <p>聚合与持久化实现是模块内部细节。一旦跨模块暴露，本模块的任何重构都会
     * 波及外部，模块边界名存实亡。
     */
    @Test
    @DisplayName("规则 2/3：跨模块不得引用对方的 domain 与 infrastructure")
    void crossModuleAccessMustNotReachInternalLayers() {
        for (String module : BUSINESS_MODULES) {
            for (String layer : List.of("domain", "infrastructure")) {
                ArchRule rule = noClasses()
                        .that().resideOutsideOfPackage(ROOT + "." + module + "..")
                        .should().dependOnClassesThat().resideInAPackage(ROOT + "." + module + "." + layer + "..")
                        .because("跨模块只能引用对方 application 包下的 public 类型；"
                                + module + "." + layer + " 是内部实现"
                                + "（BACKEND_ARCHITECTURE.md 10.4 规则 2/3）")
                        .allowEmptyShould(true);

                rule.check(PRODUCTION_CLASSES);
            }
        }
    }

    /**
     * 规则 4：跨模块不得引用对方的 {@code api} 包。
     *
     * <p>这条最常被忽略，也是 DTO 泛滥的根源。{@code api} 下的 DTO 是 HTTP 契约，
     * 不是内部契约 —— 被别的模块复用后，「改一个 REST 字段」就会牵动另一个模块的
     * 内部逻辑，两个本应独立演进的东西被焊死在一起。
     */
    @Test
    @DisplayName("规则 4：跨模块不得引用对方的 api（HTTP DTO 不是内部契约）")
    void crossModuleAccessMustNotReachApiLayer() {
        for (String module : BUSINESS_MODULES) {
            ArchRule rule = noClasses()
                    .that().resideOutsideOfPackage(ROOT + "." + module + "..")
                    .should().dependOnClassesThat().resideInAPackage(ROOT + "." + module + ".api..")
                    .because("api 包下是 HTTP 契约 DTO，跨模块复用会让 REST 字段变更牵动其他模块"
                            + "（BACKEND_ARCHITECTURE.md 10.4 规则 4）")
                    .allowEmptyShould(true);

            rule.check(PRODUCTION_CLASSES);
        }
    }

    /**
     * 规则 5：包名必须落在已登记的模块包内。
     *
     * <p>守的是架构文档 10.1「artifact 与包名逐字对应」。它拦的是拼错的包名、
     * 没登记就新建的模块，以及散落在根包下的游离类 —— 这些都会悄悄绕过上面
     * 四条规则，因为规则是按包名匹配的。
     *
     * <p>{@code com.things.link} 根包本身允许存在类：启动类必须放在那里，
     * 组件扫描才能覆盖所有兄弟模块（架构文档 10.1 的唯一例外）。
     */
    @Test
    @DisplayName("规则 5：包名必须与已登记的模块对应")
    void packagesMustBelongToRegisteredModules() {
        String[] allowed = allowedPackages();

        ArchRule rule = classes()
                .that().resideInAPackage(ROOT + "..")
                .should().resideInAnyPackage(allowed)
                .because("包名必须与 artifact 的 <domain> 段逐字对应；未登记的包会绕过全部依赖方向检查"
                        + "（BACKEND_ARCHITECTURE.md 10.1 / 10.4 规则 5）")
                .allowEmptyShould(true);

        rule.check(PRODUCTION_CLASSES);
    }

    /**
     * 构造允许的包名清单：根包本身（放启动类）、两个横切模块，
     * 以及每个业务模块的根包与四个分层包。
     *
     * @return ArchUnit 包匹配表达式数组
     */
    private static String[] allowedPackages() {
        List<String> packages = new java.util.ArrayList<>();

        // 启动类所在的根包，不含子包
        packages.add(ROOT);
        // 横切模块不分层，允许其下任意子包
        packages.add(ROOT + ".shared..");
        packages.add(ROOT + ".support..");
        // 正式运行时是装配模块，不拥有业务表或领域分层。
        packages.add(ROOT + ".runtime..");
        // 工具模块。things-link-testing 的类在 src/main 下（只有 main 产物才能被
        // 其他模块依赖），因此会被 ArchUnit 扫到，需要在此登记。它不进生产依赖树，
        // 也不依赖任何业务模块，所以规则 1–4 对它没有约束意义。
        packages.add(ROOT + ".testing..");
        for (String module : TOOL_MODULES) {
            packages.add(ROOT + "." + module + "..");
        }
        for (String module : BUSINESS_MODULES) {
            // 模块根包本身（放 package-info 与模块级常量）
            packages.add(ROOT + "." + module);
            for (String layer : LAYERS) {
                packages.add(ROOT + "." + module + "." + layer + "..");
            }
        }

        return packages.toArray(String[]::new);
    }

    /** ADR0063：低层请求完成不能绕过完整交付事务；测试仍可独立验证状态机。 */
    @Test
    void modbusResponseCompletionRequiresDurableAcceptanceCaller() {
        methods().that().areDeclaredIn(com.things.link.device.application.ModbusPollService.class)
                .and().haveName("handleResponse")
                .should().onlyBeCalled().byClassesThat().haveFullyQualifiedName(
                        "com.things.link.device.application.ModbusResponseAcceptanceService")
                .because("ADR0063要求生产响应仅经可靠接管事务编排")
                .check(PRODUCTION_CLASSES);
    }

    /**
     * ADR0105：响应接管必须从无数据库外层事务的 Kafka 消费入口进入。
     *
     * <p>接管事务使用 {@code REQUIRES_NEW} 并独占一个 DATA 连接；若生产代码从已经占用
     * DATA 连接的外层事务调用，会额外占用连接并放大连接池耗尽风险。</p>
     */
    @Test
    void modbusResponseAcceptanceRequiresKafkaConsumerCaller() {
        methods().that().areDeclaredIn(com.things.link.device.application.ModbusResponseAcceptanceService.class)
                .and().haveName("accept")
                .should().onlyBeCalled().byClassesThat().haveFullyQualifiedName(
                        "com.things.link.ingestion.infrastructure.ModbusResponseKafkaConsumer")
                .because("ADR0105要求生产响应接管仅从无数据库外层事务的Kafka消费入口进入，"
                        + "避免嵌套调用额外占用DATA连接")
                .check(PRODUCTION_CLASSES);
    }
}
