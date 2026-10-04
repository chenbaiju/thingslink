package com.things.link.bootstrap.contract;

import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 全 reactor Flyway 迁移文件门禁。
 *
 * <p>Flyway 会把各业务模块的资源合并到同一数据库，源码目录彼此隔离并不代表版本空间隔离。
 * 本门禁依据《开发路线图》X-02d同时约束全局版本、文件名、目录归属和sidecar关联，
 * 避免错误直到既有数据库启动时才暴露。
 */
@DisplayName("Flyway 迁移文件合同")
class FlywayMigrationVersionUniquenessTests {

    /** 生产迁移文件名固定使用有效日期、四位排序槽和小写snake_case描述。 */
    private static final Pattern MIGRATION_NAME_PATTERN = Pattern.compile(
            "^V([0-9]{8})_([0-9]{4})__([a-z][a-z0-9]*(?:_[a-z0-9]+)*)\\.sql$");

    /** application配置中的显式生产迁移位置；目录清单就是允许贡献迁移的域闭集。 */
    private static final Pattern MIGRATION_LOCATION_PATTERN = Pattern.compile(
            "^\\s*-\\s+classpath:db/migration/([a-z][a-z0-9-]*)\\s*$");

    /** 每日排序槽的最小值；0100是序列下界，不表示凌晨一点。 */
    private static final int MINIMUM_DAILY_SLOT = 100;

    /** 每日排序槽的最大值；保留末位0才能继续按十递增插入新版本。 */
    private static final int MAXIMUM_DAILY_SLOT = 9990;

    /** 排序槽步长；它是同日全局序号，不能按HHmm时间合法性校验。 */
    private static final int DAILY_SLOT_STEP = 10;

    /** 已合入且可能已应用的两份历史发行方迁移；只能豁免原字节的槽位，不能改写校验和。 */
    private static final Map<String, String> PUBLISHED_UNALIGNED_SLOTS = Map.of(
            "things-link-issuer/src/main/resources/db/migration/issuer/"
                    + "V20260928_0101__self_hosted_enrollment_queue_index.sql",
            "53ea668dc89eb9655000c0bc61bd0b1aa6d33b28d380e38482cd89a65cc32ff8",
            "things-link-issuer/src/main/resources/db/migration/issuer/"
                    + "V20260928_0102__self_hosted_review_attestation.sql",
            "e5c8a14b5b0aab6e38e31be95bbbc08a8578ce5e1be71afeb5a7262dbc1dfce6");

    /** 迁移日期年份下界；与Python守卫保持一致，拒绝ISO扩展历法中的公元0年。 */
    private static final int MINIMUM_MIGRATION_YEAR = 1;

    /** 四位年份的上界；禁止未来放宽正则时意外接受扩展年份。 */
    private static final int MAXIMUM_MIGRATION_YEAR = 9999;

    /** 临时源码树用于证明非法命名、目录和sidecar不会被静默忽略。 */
    @TempDir
    Path temporaryBackendRoot;

    /**
     * 扫描真实源码树，验证所有生产迁移满足X-02d冻结合同。
     *
     * @throws IOException 源码树或配置不可读时失败，不能把未扫描冒充通过
     */
    @Test
    @DisplayName("所有生产迁移满足命名、目录与全局版本合同")
    void productionMigrationsSatisfyRepositoryContract() throws IOException {
        Path backendRoot = locateBackendRoot();
        Set<String> productionDomains = readMigrationDomains(
                backendRoot.resolve("things-link-bootstrap/src/main/resources/application.yml"));

        assertThat(auditMigrationTree(backendRoot, productionDomains))
                .as("Flyway生产迁移违反X-02d全局分配合同")
                .isEmpty();
    }

    /**
     * 测试profile必须覆盖生产配置中的全部迁移域，避免新模块迁移只在生产启动时出现。
     *
     * @throws IOException 配置不可读时失败
     */
    @Test
    @DisplayName("测试profile覆盖全部生产迁移位置")
    void testProfileCoversEveryProductionMigrationLocation() throws IOException {
        Path backendRoot = locateBackendRoot();
        Set<String> productionDomains = readMigrationDomains(
                backendRoot.resolve("things-link-bootstrap/src/main/resources/application.yml"));
        Set<String> testDomains = readMigrationDomains(
                backendRoot.resolve("things-link-bootstrap/src/test/resources/application-test.yml"));

        assertThat(testDomains)
                .as("application-test.yml必须覆盖生产Flyway域，测试专用migration-test不属于域清单")
                .containsExactlyInAnyOrderElementsOf(productionDomains);
    }

    /**
     * 升到当前最新版本的旧库夹具必须覆盖完整生产迁移域；固定目标的局部迁移测试不受此规则扩域。
     *
     * @throws IOException 配置或夹具源码不可读时失败，不能将漏扫描当作通过
     */
    @Test
    @DisplayName("完整旧库升级夹具覆盖全部生产迁移位置")
    void fullUpgradeFixturesCoverEveryProductionMigrationLocation() throws IOException {
        Path backendRoot = locateBackendRoot();
        Set<String> productionDomains = readMigrationDomains(
                backendRoot.resolve("things-link-bootstrap/src/main/resources/application.yml"));
        Path deviceTests = backendRoot.resolve(
                "things-link-bootstrap/src/test/java/com/things/link/bootstrap/device");
        for (String fixture : List.of("model/DataStreamTcpBindingUpgradeTests.java",
                "model/DeviceModelBindingUpgradeTests.java", "topology/DeviceTopologyRoleUpgradeTests.java",
                "topology/DeviceTopologyProjectionUpgradeTests.java", "topology/DeviceTopologyRepairUpgradeTests.java")) {
            Matcher locations = Pattern.compile("String\\[\\] LOCATIONS\\s*=\\s*\\{([^}]+)}", Pattern.DOTALL)
                    .matcher(Files.readString(deviceTests.resolve(fixture)));
            assertThat(locations.find()).as("完整升级夹具必须具有可审计迁移清单：" + fixture).isTrue();
            Set<String> fixtureDomains = new LinkedHashSet<>();
            Matcher domain = Pattern.compile("\"classpath:db/migration/([a-z][a-z0-9-]*)\"")
                    .matcher(locations.group(1));
            while (domain.find()) {
                fixtureDomains.add(domain.group(1));
            }
            assertThat(fixtureDomains).as("完整升级夹具遗漏生产迁移域：" + fixture)
                    .containsExactlyInAnyOrderElementsOf(productionDomains);
        }
    }

    /**
     * 空源码树必须失败，防止IDE从不同工作目录运行时得到虚假的绿色结果。
     *
     * @throws IOException 临时目录不可读时失败
     */
    @Test
    @DisplayName("未扫描到生产迁移时失败")
    void emptyMigrationTreeIsRejected() throws IOException {
        assertThat(auditMigrationTree(temporaryBackendRoot, Set.of("support")))
                .containsExactly("未扫描到任何生产Flyway SQL迁移");
    }

    @Test
    @DisplayName("源码根的父目录含 target 时仍扫描生产迁移")
    void parentTargetDirectoryDoesNotHideProductionMigrations() throws IOException {
        Path backendRoot = temporaryBackendRoot.resolve("target/candidate/things-link");
        Path migration = backendRoot.resolve(
                "things-link-support/src/main/resources/db/migration/support/V20260927_0100__candidate_probe.sql");
        Files.createDirectories(migration.getParent());
        Files.writeString(migration, "-- candidate migration probe\n");

        assertThat(auditMigrationTree(backendRoot, Set.of("support"))).isEmpty();
    }

    /**
     * 根定位必须同时支持后端目录和外层monorepo目录，IDE不能因工作目录不同而跳过扫描。
     *
     * @throws IOException 临时POM不可写时失败
     */
    @Test
    @DisplayName("从monorepo工作目录也能定位后端根")
    void backendRootCanBeLocatedFromMonorepoWorkingDirectory() throws IOException {
        Path backendRoot = temporaryBackendRoot.resolve("things-link");
        Files.createDirectories(backendRoot.resolve("things-link-bootstrap"));
        Files.writeString(backendRoot.resolve("pom.xml"), "<project/>");
        Files.writeString(backendRoot.resolve("things-link-bootstrap/pom.xml"), "<project/>");

        assertThat(locateBackendRoot(temporaryBackendRoot)).isEqualTo(backendRoot);
    }

    /**
     * 日期、排序槽和描述任一非法时都必须给出文件级错误，而不是跳过该迁移。
     *
     * @throws IOException 临时迁移不可写时失败
     */
    @Test
    @DisplayName("非法日期、排序槽和描述不会被静默忽略")
    void invalidMigrationNamesAreRejected() throws IOException {
        writeMigration("support", "V00000101_0100__year_zero.sql");
        writeMigration("support", "V20260230_0100__valid_shape.sql");
        writeMigration("support", "V20260906_0050__slot_too_small.sql");
        writeMigration("support", "V20260906_0101__slot_not_aligned.sql");
        writeMigration("support", "V20260906_0290__accepted_non_clock_slot.sql");
        writeMigration("support", "V20260906_0300__Upper_case.sql");

        assertThat(auditMigrationTree(temporaryBackendRoot, Set.of("support")))
                .anyMatch(message -> message.contains("年份必须在0001..9999")
                        && message.contains("V00000101"))
                .anyMatch(message -> message.contains("不是有效日期") && message.contains("V20260230"))
                .anyMatch(message -> message.contains("必须在0100..9990") && message.contains("0050"))
                .anyMatch(message -> message.contains("必须按10递增") && message.contains("0101"))
                .anyMatch(message -> message.contains("文件名不符合") && message.contains("Upper_case"))
                .noneMatch(message -> message.contains("0290"));
    }

    /** 历史槽位豁免必须同时匹配精确路径和原字节，不能借旧文件名加入新迁移。 */
    @Test
    @DisplayName("已应用的非对齐历史槽位只允许原校验和")
    void publishedUnalignedSlotExceptionRejectsChangedBytes() throws IOException {
        writeMigration("issuer", "V20260928_0101__self_hosted_enrollment_queue_index.sql");
        assertThat(auditMigrationTree(temporaryBackendRoot, Set.of("issuer")))
                .anyMatch(message -> message.contains("必须按10递增")
                        && message.contains("V20260928_0101"));
    }

    /**
     * 同一Flyway语义版本即使描述不同也必须冲突，版本空间不能按模块拆分。
     *
     * @throws IOException 临时迁移不可写时失败
     */
    @Test
    @DisplayName("跨模块Flyway语义版本重复时失败")
    void semanticVersionDuplicatesAcrossModulesAreRejected() throws IOException {
        writeMigration("support", "V20260906_0100__first_change.sql");
        writeMigration("project", "V20260906_0100__second_change.sql");

        assertThat(auditMigrationTree(temporaryBackendRoot, Set.of("support", "project")))
                .anyMatch(message -> message.contains("Flyway语义版本重复")
                        && message.contains("things-link-support")
                        && message.contains("things-link-project"));
    }

    /**
     * 模块名、迁移末级目录与配置域必须三者一致，禁止未登记模块偷偷贡献迁移。
     *
     * @throws IOException 临时迁移不可写时失败
     */
    @Test
    @DisplayName("迁移只能位于已登记域的标准目录")
    void migrationOutsideRegisteredDomainDirectoryIsRejected() throws IOException {
        writeMigrationAt(Path.of(
                "things-link-device/src/main/resources/db/migration/support/V20260906_0100__wrong_owner.sql"));
        writeMigration("unknown", "V20260906_0200__unknown_domain.sql");

        assertThat(auditMigrationTree(temporaryBackendRoot, Set.of("support", "device")))
                .anyMatch(message -> message.contains("模块与迁移域不一致")
                        && message.contains("things-link-device"))
                .anyMatch(message -> message.contains("不属于生产locations闭集")
                        && message.contains("unknown"));
    }

    /**
     * Flyway脚本配置sidecar必须附着同目录同名SQL，孤立配置不能被构建静默带入。
     *
     * @throws IOException 临时sidecar不可写时失败
     */
    @Test
    @DisplayName("sql.conf必须关联同名生产迁移")
    void orphanSqlConfigurationIsRejected() throws IOException {
        writeMigration("support", "V20260906_0100__existing.sql");
        writeMigrationAt(Path.of(
                "things-link-support/src/main/resources/db/migration/support/"
                        + "V20260906_0200__missing.sql.conf"));

        assertThat(auditMigrationTree(temporaryBackendRoot, Set.of("support")))
                .anyMatch(message -> message.contains("sql.conf缺少同名SQL")
                        && message.contains("V20260906_0200__missing.sql.conf"));
    }

    /**
     * 从配置中读取显式迁移域，配置清单同时构成生产迁移贡献者闭集。
     *
     * @param configuration 配置文件
     * @return 保持声明顺序的迁移域集合
     * @throws IOException 配置不可读时失败
     */
    private static Set<String> readMigrationDomains(Path configuration) throws IOException {
        Set<String> domains = new LinkedHashSet<>();
        for (String line : Files.readAllLines(configuration)) {
            Matcher matcher = MIGRATION_LOCATION_PATTERN.matcher(line);
            if (matcher.matches()) {
                domains.add(matcher.group(1));
            }
        }
        assertThat(domains)
                .as("配置必须显式声明至少一个classpath:db/migration/<domain>位置: %s", configuration)
                .isNotEmpty();
        return domains;
    }

    /**
     * 聚合迁移树中的全部违规；一次报告完整集合，避免逐个修复再重跑。
     *
     * @param backendRoot 后端聚合源码根目录
     * @param productionDomains 生产配置允许贡献迁移的域闭集
     * @return 按扫描顺序排列的违规说明
     * @throws IOException 源码树不可读时失败
     */
    private static List<String> auditMigrationTree(Path backendRoot, Set<String> productionDomains)
            throws IOException {
        List<String> violations = new ArrayList<>();
        List<Path> sqlFiles = new ArrayList<>();
        List<Path> sqlConfigurationFiles = new ArrayList<>();

        try (var paths = Files.walk(backendRoot)) {
            paths.filter(Files::isRegularFile)
                    .filter(path -> !containsPathSegment(backendRoot.relativize(path), "target"))
                    .filter(FlywayMigrationVersionUniquenessTests::isInsideMigrationTree)
                    .forEach(path -> {
                        String fileName = path.getFileName().toString();
                        if (fileName.endsWith(".sql")) {
                            sqlFiles.add(path);
                        } else if (fileName.endsWith(".sql.conf")) {
                            sqlConfigurationFiles.add(path);
                        }
                    });
        }

        if (sqlFiles.isEmpty()) {
            violations.add("未扫描到任何生产Flyway SQL迁移");
        }

        Map<MigrationVersion, List<String>> pathsByVersion = new LinkedHashMap<>();
        for (Path sqlFile : sqlFiles) {
            String relativePath = relativePath(backendRoot, sqlFile);
            validateMigrationPath(backendRoot, sqlFile, productionDomains, violations);
            registerVersion(sqlFile, relativePath, pathsByVersion, violations);
        }
        pathsByVersion.forEach((version, paths) -> {
            if (paths.size() > 1) {
                violations.add("Flyway语义版本重复 " + version + ": " + paths);
            }
        });

        for (Path configurationFile : sqlConfigurationFiles) {
            validateMigrationPath(backendRoot, configurationFile, productionDomains, violations);
            Path sqlFile = configurationFile.resolveSibling(
                    configurationFile.getFileName().toString().substring(
                            0, configurationFile.getFileName().toString().length() - ".conf".length()));
            if (!Files.isRegularFile(sqlFile)) {
                violations.add("sql.conf缺少同名SQL: " + relativePath(backendRoot, configurationFile));
            }
        }
        return violations;
    }

    /**
     * 判断文件是否位于任一生产资源的db/migration树内；末级目录是否合法由后续规则诊断。
     *
     * @param path 候选文件
     * @return 位于生产迁移树时返回true
     */
    private static boolean isInsideMigrationTree(Path path) {
        String normalized = path.toString().replace('\\', '/');
        return normalized.contains("/src/main/resources/db/migration/");
    }

    /**
     * 验证模块、资源路径、末级域目录与生产locations闭集一致。
     *
     * @param backendRoot 后端聚合根目录
     * @param file 迁移或sidecar文件
     * @param productionDomains 允许的生产域
     * @param violations 违规收集器
     */
    private static void validateMigrationPath(
            Path backendRoot,
            Path file,
            Set<String> productionDomains,
            List<String> violations) {
        Path relative = backendRoot.relativize(file);
        String displayPath = relativePath(backendRoot, file);
        if (relative.getNameCount() != 8
                || !"src".equals(relative.getName(1).toString())
                || !"main".equals(relative.getName(2).toString())
                || !"resources".equals(relative.getName(3).toString())
                || !"db".equals(relative.getName(4).toString())
                || !"migration".equals(relative.getName(5).toString())) {
            violations.add("生产迁移目录必须是things-link-<domain>/src/main/resources/db/migration/<domain>: "
                    + displayPath);
            return;
        }

        String module = relative.getName(0).toString();
        String domain = relative.getName(6).toString();
        if (!module.equals("things-link-" + domain)) {
            violations.add("模块与迁移域不一致: " + displayPath);
        }
        if (!productionDomains.contains(domain)) {
            violations.add("迁移域不属于生产locations闭集 " + productionDomains + ": " + displayPath);
        }
    }

    /**
     * 校验迁移文件名并按Flyway自身的版本语义登记，避免自定义字符串比较产生分叉。
     *
     * @param sqlFile SQL迁移文件
     * @param relativePath 用于错误输出的相对路径
     * @param pathsByVersion 版本到文件路径的登记表
     * @param violations 违规收集器
     */
    private static void registerVersion(
            Path sqlFile,
            String relativePath,
            Map<MigrationVersion, List<String>> pathsByVersion,
            List<String> violations) throws IOException {
        Matcher matcher = MIGRATION_NAME_PATTERN.matcher(sqlFile.getFileName().toString());
        if (!matcher.matches()) {
            violations.add("迁移文件名不符合VyyyyMMdd_NNNN__lower_snake.sql: " + relativePath);
            return;
        }

        String date = matcher.group(1);
        int year = Integer.parseInt(date.substring(0, 4));
        if (year < MINIMUM_MIGRATION_YEAR || year > MAXIMUM_MIGRATION_YEAR) {
            violations.add("迁移日期年份必须在0001..9999: " + relativePath);
            return;
        }
        try {
            LocalDate.parse(date, DateTimeFormatter.BASIC_ISO_DATE);
        } catch (DateTimeException exception) {
            violations.add("迁移版本不是有效日期 " + date + ": " + relativePath);
            return;
        }

        String slotText = matcher.group(2);
        int slot = Integer.parseInt(slotText);
        if (slot < MINIMUM_DAILY_SLOT || slot > MAXIMUM_DAILY_SLOT) {
            violations.add("迁移排序槽必须在0100..9990: " + relativePath);
            return;
        }
        if (slot % DAILY_SLOT_STEP != 0 && !matchesPublishedUnalignedSlot(sqlFile, relativePath)) {
            violations.add("迁移排序槽必须按10递增: " + relativePath);
            return;
        }

        MigrationVersion version = MigrationVersion.fromVersion(date + "." + slotText);
        pathsByVersion.computeIfAbsent(version, ignored -> new ArrayList<>()).add(relativePath);
    }

    private static boolean matchesPublishedUnalignedSlot(Path sqlFile, String relativePath)
            throws IOException {
        String expected = PUBLISHED_UNALIGNED_SLOTS.get(relativePath);
        if (expected == null) return false;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(sqlFile));
            return expected.equals(HexFormat.of().formatHex(digest));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JDK 缺少 SHA-256", impossible);
        }
    }

    /**
     * 判断路径是否含指定目录段；只排除构建产物，不依赖平台路径分隔符。
     *
     * @param path 候选路径
     * @param segment 目录段
     * @return 任一段完全匹配时返回true
     */
    private static boolean containsPathSegment(Path path, String segment) {
        for (Path part : path) {
            if (segment.equals(part.toString())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 生成稳定的Unix风格相对路径，保证Windows与Unix诊断一致。
     *
     * @param backendRoot 后端聚合根目录
     * @param path 文件路径
     * @return 统一使用斜杠的相对路径
     */
    private static String relativePath(Path backendRoot, Path path) {
        return backendRoot.relativize(path).toString().replace('\\', '/');
    }

    /**
     * 在临时源码树的标准域目录写入迁移探针。
     *
     * @param domain 迁移域
     * @param fileName 文件名
     * @throws IOException 探针不可写时失败
     */
    private void writeMigration(String domain, String fileName) throws IOException {
        writeMigrationAt(Path.of("things-link-" + domain
                + "/src/main/resources/db/migration/" + domain + "/" + fileName));
    }

    /**
     * 按相对路径写入空探针文件，内容无关且不会执行。
     *
     * @param relativePath 临时源码树内路径
     * @throws IOException 探针不可写时失败
     */
    private void writeMigrationAt(Path relativePath) throws IOException {
        Path file = temporaryBackendRoot.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "-- X-02d migration guard probe\n");
    }

    /**
     * 从Maven或IDE工作目录向上定位后端聚合目录，消除固定cwd导致的空扫描。
     *
     * @return 同时包含根POM和Bootstrap模块的后端目录
     */
    private static Path locateBackendRoot() {
        return locateBackendRoot(Path.of("").toAbsolutePath().normalize());
    }

    /**
     * 从给定目录逐级向上查找后端聚合目录，同时识别外层monorepo中的things-link子目录。
     *
     * @param startingDirectory Maven或IDE的起始工作目录
     * @return 同时包含根POM和Bootstrap模块的后端目录
     */
    private static Path locateBackendRoot(Path startingDirectory) {
        Path current = startingDirectory.toAbsolutePath().normalize();
        while (current != null) {
            if (isBackendRoot(current)) {
                return current;
            }
            Path backendChild = current.resolve("things-link");
            if (isBackendRoot(backendChild)) {
                return backendChild;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("无法定位things-link后端聚合目录");
    }

    /**
     * 判定目录是否为后端Maven聚合根，双文件条件避免误认普通父POM。
     *
     * @param candidate 候选目录
     * @return 根POM与Bootstrap POM同时存在时返回true
     */
    private static boolean isBackendRoot(Path candidate) {
        return Files.isRegularFile(candidate.resolve("pom.xml"))
                && Files.isRegularFile(candidate.resolve("things-link-bootstrap/pom.xml"));
    }
}
