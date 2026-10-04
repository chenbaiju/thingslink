package com.things.link.bootstrap.contract;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 源码级架构约束，补足 ArchUnit 无法看见 SQL 字符串与源码类型拼写的盲区。
 *
 * <p>架构文档 10.4 的表所有权不能只检查 Java 字节码：D-014 已证明直接把另一个领域表名写进
 * JdbcTemplate SQL 时，原有测试仍会全绿。本测试扫描生产 Java 源码，让同类绕过立即阻断构建。</p>
 */
@DisplayName("源码架构约束（BACKEND_ARCHITECTURE.md 10.4）")
class SourceArchitectureRulesTests {
    /** 业务表前缀到所有者模块的映射；新增前缀时须与架构文档 9.1 同步。 */
    private static final Map<String, String> TABLE_PREFIX_OWNERS = Map.ofEntries(
            Map.entry("dev_", "things-link-device"),
            Map.entry("ts_", "things-link-telemetry"),
            Map.entry("alarm_", "things-link-alarm"),
            Map.entry("task_", "things-link-task"),
            Map.entry("rule_", "things-link-rule"),
            Map.entry("app_", "things-link-enduser"),
            Map.entry("dash_", "things-link-dashboard"),
            Map.entry("ota_", "things-link-ota"),
            Map.entry("integ_", "things-link-integration"));
    /** ADR0096：应用发布表虽使用app_前缀，领域所有者仍是dashboard。 */
    private static final Map<String, String> TABLE_FAMILY_OWNERS = Map.of(
            "app_application", "things-link-dashboard");
    /** 提取生产SQL中直接访问的表名；会话变量及约束名不进入扫描。 */
    private static final Pattern SQL_TABLE = Pattern.compile(
            "(?i)\\b(?:FROM|JOIN|INSERT\\s+INTO|UPDATE|DELETE\\s+FROM|ALTER\\s+TABLE)"
                    + "\\s+(?:public\\.)?([a-z][a-z0-9_]+)\\b");
    /** LocalDateTime 会丢失项目时区语义，架构文档 6.3 禁止业务代码使用。 */
    private static final Pattern LOCAL_DATE_TIME = Pattern.compile("\\bLocalDateTime\\b");
    /** Kafka listener 注解到公开消费方法之间的源码片段，用于钉住显式工厂与并发配置。 */
    private static final Pattern KAFKA_LISTENER = Pattern.compile(
            "@KafkaListener\\s*\\((.*?)\\)\\s*public\\s+", Pattern.DOTALL);
    /** Scheduled 注解到公开任务方法之间的源码片段，用于禁止退回默认共享调度器。 */
    private static final Pattern SCHEDULED_METHOD = Pattern.compile(
            "@Scheduled\\s*\\((.*?)\\)\\s*public\\s+", Pattern.DOTALL);
    /** D-098：定位set_config调用起点；参数由平衡括号解析，避免嵌套表达式绕过。 */
    private static final Pattern SET_CONFIG_CALL = Pattern.compile("(?is)\\bset_config\\s*\\(");
    /** D-098：识别普通或双引号限定的SET LOCAL项目范围键。 */
    private static final Pattern TRANSACTION_LOCAL_SET = Pattern.compile(
            "(?is)\\bSET\\s+LOCAL\\s+(?:\"app\\.(?:tenant_id|project_id)\""
                    + "|app\\.(?:tenant_id|project_id))(?=\\s|=)");
    /** D-098：识别set_config第一个参数中的受控项目范围键及可选PostgreSQL类型转换。 */
    private static final Pattern TRANSACTION_LOCAL_KEY = Pattern.compile(
            "(?is)^\\s*'app\\.(?:tenant_id|project_id)'(?:\\s*::\\s*[a-z][a-z0-9_]*)?\\s*$");
    /** D-098：只有完整静态SQL字符串能证明动态键不会指向项目范围。 */
    private static final Pattern STATIC_SQL_STRING = Pattern.compile(
            "(?is)^\\s*'[a-z][a-z0-9_.:-]*'(?:\\s*::\\s*[a-z][a-z0-9_]*)?\\s*$");
    /** D-098：只有false字面量及其显式PostgreSQL布尔转换能证明不是事务局部设置。 */
    private static final Pattern FALSE_LITERAL = Pattern.compile(
            "(?is)^\\s*(?:false(?:\\s*::\\s*(?:bool|boolean))?"
                    + "|CAST\\s*\\(\\s*false\\s+AS\\s+(?:bool|boolean)\\s*\\))\\s*$");
    /** 不拥有业务表、也不采用领域分层的聚合、横切与工具模块。 */
    private static final Set<String> NON_BUSINESS_MODULES = Set.of(
            "things-link-bootstrap", "things-link-access", "things-link-runtime", "things-link-shared", "things-link-support",
            "things-link-testing", "things-link-simulator");
    /** 双物理池直连是 C4b-F2 的受控例外，不得被其他定时任务仿用来绕过业务路由。 */
    private static final Set<String> DUAL_POOL_PROBE_ALLOWLIST = Set.of(
            "things-link-support/src/main/java/com/things/link/support/tenant/DatabaseAvailabilityProbe.java");
    /** S12-2a1h：事务局部RLS设置只允许由两个集中范围组件持有。 */
    private static final Set<String> TRANSACTION_LOCAL_SCOPE_ALLOWLIST = Set.of(
            "things-link-support/src/main/java/com/things/link/support/tenant/TransactionLocalRlsScope.java",
            "things-link-support/src/main/java/com/things/link/support/tenant/TenantTransactionLocalRlsScope.java");

    /**
     * 禁止业务模块在 Java SQL 字面量中写入其他领域拥有的表。
     *
     * @throws IOException 源文件不可读时直接让构建失败
     */
    @Test
    @DisplayName("业务模块不得在 SQL 字符串中访问其他领域表")
    void javaSourcesMustNotNameTablesOwnedByAnotherDomain() throws IOException {
        Path backend = locateBackendRoot();
        List<String> violations = new ArrayList<>();
        for (Path source : productionJavaSources(backend)) {
            String module = backend.relativize(source).getName(0).toString();
            var matcher = SQL_TABLE.matcher(Files.readString(source));
            while (matcher.find()) {
                String table = matcher.group(1).toLowerCase(java.util.Locale.ROOT);
                Optional<String> owner = ownerForTable(table);
                if (owner.isPresent() && !module.equals(owner.get())
                        && !isCatalogReadProjection(backend.relativize(source).toString().replace('\\', '/'),
                                table, matcher.group())) {
                    violations.add(backend.relativize(source) + " 引用了 " + table
                            + "，所有者应为 " + owner.get());
                }
            }
        }
        assertThat(violations).as("跨模块 SQL 表访问").isEmpty();
    }

    /** ADR0106只允许单一目录仓储读取公开视图，不能借此读私有表或执行更新。 */
    private static boolean isCatalogReadProjection(String source, String table, String sqlMatch) {
        return source.equals("things-link-enduser/src/main/java/com/things/link/enduser/"
                + "infrastructure/persistence/JdbcAppUserDeviceRepository.java")
                && table.equals("dev_device_runtime_catalog_v1")
                && sqlMatch.matches("(?is)^(?:FROM|JOIN)\\s+.*");
    }

    /** 白名单明确限制文件、对象和操作三项，不能演变成跨域私有表通行证。 */
    @Test
    void catalogProjectionExceptionDoesNotAllowPrivateTablesOrWrites() {
        String consumer = "things-link-enduser/src/main/java/com/things/link/enduser/"
                + "infrastructure/persistence/JdbcAppUserDeviceRepository.java";
        assertThat(isCatalogReadProjection(consumer, "dev_device_runtime_catalog_v1",
                "JOIN dev_device_runtime_catalog_v1")).isTrue();
        assertThat(isCatalogReadProjection(consumer, "dev_device_runtime_catalog_v1",
                "UPDATE dev_device_runtime_catalog_v1")).isFalse();
        assertThat(isCatalogReadProjection(consumer, "dev_device", "FROM dev_device")).isFalse();
        assertThat(isCatalogReadProjection("another/Repository.java", "dev_device_runtime_catalog_v1",
                "JOIN dev_device_runtime_catalog_v1")).isFalse();
    }

    /** ADR0096的精确表族必须优先于app_通用前缀，且不能夺走enduser既有表。 */
    @Test
    @DisplayName("应用发布表族归dashboard，其余app表仍归enduser")
    void applicationPublicationTableFamilyHasSpecificOwner() {
        assertThat(ownerForTable("app_application")).contains("things-link-dashboard");
        assertThat(ownerForTable("app_application_version")).contains("things-link-dashboard");
        assertThat(ownerForTable("app_user")).contains("things-link-enduser");
    }

    /** 显式public schema不能让跨域SQL把schema名误当表名并绕过所有权检查。 */
    @Test
    @DisplayName("源码SQL扫描识别public限定的应用发布表")
    void sqlTablePatternRecognizesPublicQualifiedPublicationTable() {
        var matcher = SQL_TABLE.matcher("SELECT * FROM public.app_application_version");

        assertThat(matcher.find()).isTrue();
        assertThat(matcher.group(1)).isEqualTo("app_application_version");
        assertThat(ownerForTable(matcher.group(1))).contains("things-link-dashboard");
    }

    /**
     * 按精确表族优先、通用前缀其次解析领域所有者。
     *
     * @param table SQL中的小写表名。
     * @return 已登记所有者；平台表或未知表为空。
     */
    private static Optional<String> ownerForTable(String table) {
        for (Map.Entry<String, String> family : TABLE_FAMILY_OWNERS.entrySet()) {
            if (table.equals(family.getKey()) || table.startsWith(family.getKey() + "_")) {
                return Optional.of(family.getValue());
            }
        }
        return TABLE_PREFIX_OWNERS.entrySet().stream()
                .filter(entry -> table.startsWith(entry.getKey()))
                .map(Map.Entry::getValue)
                .findFirst();
    }

    /**
     * Java 业务源码不得使用没有时区语义的 LocalDateTime。
     *
     * @throws IOException 源文件不可读时直接让构建失败
     */
    @Test
    @DisplayName("业务源码不得使用 LocalDateTime")
    void productionSourcesMustUseInstantOrOffsetDateTime() throws IOException {
        Path backend = locateBackendRoot();
        List<String> violations = new ArrayList<>();
        for (Path source : productionJavaSources(backend)) {
            if (LOCAL_DATE_TIME.matcher(Files.readString(source)).find()) {
                violations.add(backend.relativize(source).toString());
            }
        }
        assertThat(violations).as("LocalDateTime 源码引用").isEmpty();
    }

    /**
     * G1-C1b：自定义数据流 V1 无运行时消费者（G-13）。{@code ScriptKind.CODEC} 只用于数据流编解码，
     * 该链路归 S15（PS-039 TECHNICAL_DEFERRED），因此生产源码中不得出现对 CODEC 的生产调用——
     * 一旦有人绕过范围决定提前接线，本测试立即失败。
     *
     * @throws IOException 源文件不可读时直接让构建失败
     */
    @Test
    @DisplayName("生产源码不得使用 ScriptKind.CODEC（编解码运行时归 S15）")
    void productionSourcesMustNotUseCodecScriptKind() throws IOException {
        Path backend = locateBackendRoot();
        List<String> violations = new ArrayList<>();
        for (Path source : productionJavaSources(backend)) {
            if (Files.readString(source).contains("ScriptKind.CODEC")) {
                violations.add(backend.relativize(source).toString());
            }
        }
        assertThat(violations).as("ScriptKind.CODEC 生产调用（编解码运行时尚未交付）").isEmpty();
    }

    /**
     * G1-C3b：所有生产 Kafka 边界必须显式选择 poll 工厂与有上限的并发配置，禁止依赖全局默认值。
     *
     * @throws IOException 源文件不可读时直接让构建失败
     */
    @Test
    @DisplayName("生产 Kafka listener 必须显式声明工厂与并发")
    void productionKafkaListenersMustDeclareFactoryAndConcurrency() throws IOException {
        Path backend = locateBackendRoot();
        List<String> violations = new ArrayList<>();
        for (Path source : allProductionJavaSources(backend)) {
            var matcher = KAFKA_LISTENER.matcher(Files.readString(source));
            int index = 0;
            while (matcher.find()) {
                index++;
                String annotation = matcher.group(1);
                if (!annotation.contains("containerFactory") || !annotation.contains("concurrency")) {
                    violations.add(backend.relativize(source) + "#listener-" + index);
                }
            }
        }
        assertThat(violations).as("缺少显式 containerFactory/concurrency 的 Kafka listener").isEmpty();
    }

    /**
     * G1-C3b：每个后台任务必须声明隔离调度器，防止慢 Outbox、通知或维护任务重新共享默认单线程池。
     *
     * @throws IOException 源文件不可读时直接让构建失败
     */
    @Test
    @DisplayName("生产 Scheduled 方法必须显式声明隔离调度器")
    void productionScheduledMethodsMustDeclareScheduler() throws IOException {
        Path backend = locateBackendRoot();
        List<String> violations = new ArrayList<>();
        for (Path source : allProductionJavaSources(backend)) {
            var matcher = SCHEDULED_METHOD.matcher(Files.readString(source));
            int index = 0;
            while (matcher.find()) {
                index++;
                if (!matcher.group(1).contains("scheduler")) {
                    violations.add(backend.relativize(source) + "#scheduled-" + index);
                }
            }
        }
        assertThat(violations).as("仍依赖默认调度器的生产 Scheduled 方法").isEmpty();
    }

    /**
     * G1-C3c/C4b-F2：后台定时扫描必须进入 DATA；双物理池可用性探针必须显式声明受控边界。
     *
     * @throws IOException 源文件不可读时直接让构建失败
     */
    @Test
    @DisplayName("生产 Scheduled 类必须显式声明 DATA 或受控双池边界")
    void productionScheduledClassesMustDeclareDatabaseBoundary() throws IOException {
        Path backend = locateBackendRoot();
        List<String> violations = new ArrayList<>();
        for (Path source : allProductionJavaSources(backend)) {
            String text = Files.readString(source);
            if (text.contains("@Scheduled")
                    && !text.contains("@DataPlaneDatabase")
                    && !text.contains("@DualPoolDatabaseProbe")) {
                violations.add(backend.relativize(source).toString());
            }
        }
        assertThat(violations).as("未声明数据库故障域的后台定时任务").isEmpty();
    }

    /**
     * G1-C4b-F2：双池标记只允许既有可用性探针使用，防止普通任务借标记绕过 DATA 路由约束。
     *
     * @throws IOException 源文件不可读时直接让构建失败
     */
    @Test
    @DisplayName("双物理池探针标记不得扩散到其他生产类")
    void dualPoolProbeMarkerMustStayOnAllowlist() throws IOException {
        Path backend = locateBackendRoot();
        List<String> violations = new ArrayList<>();
        for (Path source : allProductionJavaSources(backend)) {
            String relative = backend.relativize(source).toString().replace('\\', '/');
            if (Files.readString(source).contains("@DualPoolDatabaseProbe")
                    && !DUAL_POOL_PROBE_ALLOWLIST.contains(relative)) {
                violations.add(relative);
            }
        }
        assertThat(violations).as("越权使用双物理池探针标记的生产类").isEmpty();
    }

    /**
     * D-098：生产代码不得绕过集中组件直接建立事务局部数据库范围。
     *
     * <p>扫描全部生产Java而非只扫业务模块，避免未来把SQL下沉到bootstrap或横切模块绕过守卫。
     * EMQX与路由数据源使用的是会话级{@code local=false}，不属于本债务且不会命中。</p>
     *
     * @throws IOException 源文件不可读时直接让构建失败
     */
    @Test
    @DisplayName("事务局部 set_config 只能由集中范围组件执行")
    void transactionLocalSetConfigMustStayInsideCentralScopes() throws IOException {
        Path backend = locateBackendRoot();
        List<String> violations = new ArrayList<>();
        for (Path source : allProductionJavaSources(backend)) {
            String relative = backend.relativize(source).toString().replace('\\', '/');
            if (containsTransactionLocalRlsWrite(Files.readString(source))
                    && !TRANSACTION_LOCAL_SCOPE_ALLOWLIST.contains(relative)) {
                violations.add(relative);
            }
        }
        assertThat(violations).as("D-098：集中范围组件外的事务局部set_config").isEmpty();
    }

    /** 扫描器必须识别嵌套表达式、转换和参数化写法，同时排除注释、会话级设置与容错读取。 */
    @Test
    @DisplayName("事务局部 set_config 源码模式保持精确")
    void transactionLocalSetConfigPatternMustKeepItsBoundary() {
        assertThat(containsTransactionLocalRlsWrite(
                "SELECT set_config('app.tenant_id', ?, true)")).isTrue();
        assertThat(containsTransactionLocalRlsWrite("""
                SELECT set_config(
                    'app.project_id', ?, true
                )
                """)).isTrue();
        assertThat(containsTransactionLocalRlsWrite(
                "SELECT set_config(?, ?, true)")).isTrue();
        assertThat(containsTransactionLocalRlsWrite(
                "SELECT set_config('app.tenant_id', COALESCE(?, ''), true)")).isTrue();
        assertThat(containsTransactionLocalRlsWrite(
                "SELECT set_config('app.tenant_id'::text, ?, CAST(true AS boolean))")).isTrue();
        assertThat(containsTransactionLocalRlsWrite(
                "\"SELECT set_config('\" + TENANT_SETTING + \"', ?, true)\"")).isTrue();
        assertThat(containsTransactionLocalRlsWrite(
                "\"SELECT set_config('app.tenant_id', ?, \" + localFlag + \")\"")).isTrue();
        assertThat(containsTransactionLocalRlsWrite(
                "SET LOCAL app.tenant_id = '00000000-0000-0000-0000-000000000001'")).isTrue();
        assertThat(containsTransactionLocalRlsWrite(
                "SET LOCAL \"app.project_id\" = '00000000-0000-0000-0000-000000000001'")).isTrue();
        assertThat(containsTransactionLocalRlsWrite(
                "jdbcTemplate.execute(\"SET LOCAL \\\"app.project_id\\\" = ?\")")).isTrue();
        assertThat(containsTransactionLocalRlsWrite(
                "set_config(..., true)")).isFalse();
        assertThat(containsTransactionLocalRlsWrite(
                "SELECT current_setting('app.tenant_id', true)")).isFalse();
        assertThat(containsTransactionLocalRlsWrite(
                "SELECT set_config('app.tenant_id', ?, false)")).isFalse();
        assertThat(containsTransactionLocalRlsWrite(
                "SELECT set_config('app.tenant_id', ?, CAST(false AS boolean))")).isFalse();
        assertThat(containsTransactionLocalRlsWrite(
                "SELECT set_config('cleanup_history_generation', ?, true)")).isFalse();
        assertThat(containsTransactionLocalRlsWrite(
                "// SELECT set_config('app.tenant_id', ?, true)")).isFalse();
        assertThat(containsTransactionLocalRlsWrite(
                "/** SELECT set_config('app.project_id', ?, true) */")).isFalse();
    }

    /**
     * 判断生产源码是否直接建立事务局部项目范围。
     *
     * <p>先移除Java注释，防止高密度约束说明被当成可执行SQL；随后用平衡括号拆解set_config，
     * 使COALESCE、CAST等嵌套表达式不能截断扫描。参数化键或local标志无法静态证明安全，按失败关闭处理。</p>
     *
     * @param source Java源码。
     * @return 存在受控范围写入时为true。
     */
    private static boolean containsTransactionLocalRlsWrite(String source) {
        String executableSource = removeJavaComments(source);
        // Java普通字符串会转义SQL标识符的双引号；归一化后再匹配，文本块与普通字符串得到同一语义。
        if (TRANSACTION_LOCAL_SET.matcher(executableSource.replace("\\\"", "\"")).find()) {
            return true;
        }
        var matcher = SET_CONFIG_CALL.matcher(executableSource);
        while (matcher.find()) {
            int openingParenthesis = matcher.end() - 1;
            int closingParenthesis = findClosingParenthesis(executableSource, openingParenthesis);
            if (closingParenthesis < 0) {
                continue;
            }
            List<String> arguments = splitSqlArguments(
                    executableSource.substring(openingParenthesis + 1, closingParenthesis));
            if (arguments.size() == 3
                    && isTransactionLocalKey(arguments.get(0))
                    && isTransactionLocalFlag(arguments.get(2))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 识别受控键；问号代表运行时键，因无法证明不会写入项目范围而失败关闭。
     *
     * @param argument set_config第一个参数。
     * @return 参数可能指向受控键时为true。
     */
    private static boolean isTransactionLocalKey(String argument) {
        String normalized = argument.trim();
        if (TRANSACTION_LOCAL_KEY.matcher(normalized).matches()) {
            return true;
        }
        // 只有完整的非目标SQL字符串字面量可判安全；参数、拼接与函数表达式一律失败关闭。
        return !STATIC_SQL_STRING.matcher(normalized).matches();
    }

    /**
     * 识别事务局部标志；只有可证明为false的静态表达式排除，其余动态表达式均按可能启用处理。
     *
     * @param argument set_config第三个参数。
     * @return 参数可能启用local语义时为true。
     */
    private static boolean isTransactionLocalFlag(String argument) {
        return !FALSE_LITERAL.matcher(argument).matches();
    }

    /**
     * 在忽略SQL单引号内容的前提下查找配对右括号。
     *
     * @param source 待扫描源码。
     * @param openingParenthesis 左括号位置。
     * @return 配对右括号位置；源码不完整时返回-1。
     */
    private static int findClosingParenthesis(String source, int openingParenthesis) {
        int depth = 0;
        boolean inSqlString = false;
        for (int index = openingParenthesis; index < source.length(); index++) {
            char current = source.charAt(index);
            if (current == '\'' && inSqlString && index + 1 < source.length()
                    && source.charAt(index + 1) == '\'') {
                index++;
                continue;
            }
            if (current == '\'') {
                inSqlString = !inSqlString;
                continue;
            }
            if (inSqlString) {
                continue;
            }
            if (current == '(') {
                depth++;
            } else if (current == ')' && --depth == 0) {
                return index;
            }
        }
        return -1;
    }

    /**
     * 按顶层逗号拆分SQL函数参数，并保留嵌套函数表达式。
     *
     * @param arguments set_config括号内文本。
     * @return 按调用顺序排列的参数。
     */
    private static List<String> splitSqlArguments(String arguments) {
        List<String> result = new ArrayList<>();
        int start = 0;
        int depth = 0;
        boolean inSqlString = false;
        for (int index = 0; index < arguments.length(); index++) {
            char current = arguments.charAt(index);
            if (current == '\'' && inSqlString && index + 1 < arguments.length()
                    && arguments.charAt(index + 1) == '\'') {
                index++;
                continue;
            }
            if (current == '\'') {
                inSqlString = !inSqlString;
            } else if (!inSqlString && current == '(') {
                depth++;
            } else if (!inSqlString && current == ')') {
                depth--;
            } else if (!inSqlString && current == ',' && depth == 0) {
                result.add(arguments.substring(start, index));
                start = index + 1;
            }
        }
        result.add(arguments.substring(start));
        return result;
    }

    /**
     * 移除Java行注释与块注释，同时保留普通字符串、字符字面量和文本块中的注释符号。
     *
     * @param source Java源码。
     * @return 用空白替代注释且保留换行的源码。
     */
    private static String removeJavaComments(String source) {
        StringBuilder result = new StringBuilder(source.length());
        boolean lineComment = false;
        boolean blockComment = false;
        boolean stringLiteral = false;
        boolean characterLiteral = false;
        boolean textBlock = false;
        boolean escaped = false;
        for (int index = 0; index < source.length(); index++) {
            char current = source.charAt(index);
            char next = index + 1 < source.length() ? source.charAt(index + 1) : '\0';
            if (lineComment) {
                if (current == '\n' || current == '\r') {
                    lineComment = false;
                    result.append(current);
                } else {
                    result.append(' ');
                }
                continue;
            }
            if (blockComment) {
                if (current == '*' && next == '/') {
                    result.append("  ");
                    index++;
                    blockComment = false;
                } else {
                    result.append(current == '\n' || current == '\r' ? current : ' ');
                }
                continue;
            }
            if (textBlock) {
                result.append(current);
                if (current == '"' && next == '"' && index + 2 < source.length()
                        && source.charAt(index + 2) == '"') {
                    result.append("\"\"");
                    index += 2;
                    textBlock = false;
                }
                continue;
            }
            if (stringLiteral || characterLiteral) {
                result.append(current);
                if (escaped) {
                    escaped = false;
                } else if (current == '\\') {
                    escaped = true;
                } else if (stringLiteral && current == '"') {
                    stringLiteral = false;
                } else if (characterLiteral && current == '\'') {
                    characterLiteral = false;
                }
                continue;
            }
            if (current == '/' && next == '/') {
                result.append("  ");
                index++;
                lineComment = true;
            } else if (current == '/' && next == '*') {
                result.append("  ");
                index++;
                blockComment = true;
            } else if (current == '"' && next == '"' && index + 2 < source.length()
                    && source.charAt(index + 2) == '"') {
                result.append("\"\"\"");
                index += 2;
                textBlock = true;
            } else {
                result.append(current);
                stringLiteral = current == '"';
                characterLiteral = current == '\'';
            }
        }
        return result.toString();
    }

    /**
     * G1-C3c：设备、类型与组成员不得恢复无界全量仓储方法。
     *
     * @throws IOException 源文件不可读时直接让构建失败
     */
    @Test
    @DisplayName("设备高基数仓储不得恢复全量方法")
    void deviceRepositoriesMustNotRestoreUnboundedLists() throws IOException {
        Path deviceSources = locateBackendRoot().resolve("things-link-device/src/main/java");
        List<String> violations = new ArrayList<>();
        try (var files = Files.walk(deviceSources)) {
            for (Path source : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String text = Files.readString(source);
                if (text.contains("findByProject(") || text.contains("findDevices(")) {
                    violations.add(deviceSources.relativize(source).toString());
                }
            }
        }
        assertThat(violations).as("无界设备/类型/组成员仓储方法").isEmpty();
    }

    /** 返回所有 Maven 子模块的生产 Java 源文件。 */
    private static List<Path> productionJavaSources(Path backend) throws IOException {
        try (var modules = Files.list(backend)) {
            List<Path> sources = new ArrayList<>();
            for (Path module : modules.filter(path -> path.getFileName().toString().startsWith("things-link-"))
                    .filter(path -> !NON_BUSINESS_MODULES.contains(path.getFileName().toString()))
                    .toList()) {
                Path sourceRoot = module.resolve("src/main/java");
                if (Files.isDirectory(sourceRoot)) {
                    try (var files = Files.walk(sourceRoot)) {
                        sources.addAll(files.filter(path -> path.toString().endsWith(".java")).toList());
                    }
                }
            }
            return sources;
        }
    }

    /** @return 包含 support 横切模块在内的全部生产 Java 源文件 */
    private static List<Path> allProductionJavaSources(Path backend) throws IOException {
        try (var files = Files.walk(backend)) {
            return files.filter(path -> path.toString().contains("/src/main/java/"))
                    .filter(path -> path.toString().endsWith(".java"))
                    .toList();
        }
    }

    /**
     * 同时兼容从根 reactor、bootstrap 模块和 IDE 启动测试的工作目录。
     *
     * @return 后端聚合模块绝对路径
     */
    private static Path locateBackendRoot() {
        Path candidate = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (candidate != null) {
            if (Files.isRegularFile(candidate.resolve("pom.xml"))
                    && Files.isDirectory(candidate.resolve("things-link-device"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException("无法定位 things-link 后端聚合模块");
    }
}
