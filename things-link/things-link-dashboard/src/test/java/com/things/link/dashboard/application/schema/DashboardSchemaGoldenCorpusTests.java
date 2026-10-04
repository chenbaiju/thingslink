package com.things.link.dashboard.application.schema;

import com.things.link.dashboard.infrastructure.schema.JacksonDashboardSchemaParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.fail;

/** Java完整Schema管线消费版本化跨语言黄金语料的合同测试。 */
@DisplayName("看板Schema跨语言黄金语料")
class DashboardSchemaGoldenCorpusTests {
    /** 黄金语料清单版本，升级格式时必须显式登记新版本。 */
    private static final String CORPUS_FORMAT = "tc.dashboard-golden-corpus/v1";
    /** 清单声明的唯一哈希算法。 */
    private static final String HASH_ALGORITHM = "SHA-256";
    /** 重复字节配方允许的最大输出，防止受损清单在测试机无限分配。 */
    private static final int MAX_RECIPE_BYTES = 1_000_000;
    /** 清单根对象的封闭字段。 */
    private static final Set<String> MANIFEST_FIELDS =
            Set.of("formatVersion", "hashAlgorithm", "schemaVersion", "cases");
    /** 单个案例对象的封闭字段。 */
    private static final Set<String> CASE_FIELDS = Set.of("id", "input", "expected");
    /** 文件输入对象的封闭字段。 */
    private static final Set<String> FILE_INPUT_FIELDS = Set.of("kind", "path", "sha256");
    /** 重复字节输入配方的封闭字段。 */
    private static final Set<String> REPEAT_INPUT_FIELDS = Set.of("kind", "byte", "count", "sha256");
    /** 接受期望对象的封闭字段。 */
    private static final Set<String> ACCEPT_FIELDS = Set.of(
            "outcome", "scope", "normalizedUtf8", "normalizedBytes", "normalizedSha256",
            "unresolvedRequirements");
    /** 拒绝期望对象的封闭字段。 */
    private static final Set<String> REJECT_FIELDS = Set.of("outcome", "phase", "reason", "path");
    /** 三个必须长期保留的接受案例ID。 */
    private static final Set<String> REQUIRED_ACCEPT_CASE_IDS = Set.of(
            "accept_minimal_text", "accept_external_requirements", "accept_number_normalization");
    /** 完整内部语义可能产生且接受语料必须合计覆盖的八类未决需求。 */
    private static final Set<String> REQUIRED_REQUIREMENT_TYPES = Set.of(
            "HOST_COMPONENT", "BUILTIN_RESOURCE", "MODEL_REFERENCE", "DEFAULT_DEVICE",
            "MODEL_PROPERTY", "HISTORICAL_PROPERTY", "MODEL_GAUGE_RANGE", "DATA_ADAPTER");
    /** 可信测试清单和期望投影使用的JSON映射器。 */
    private static final ObjectMapper JSON = new ObjectMapper();
    /** 从任意Maven或IDE工作目录定位的黄金语料目录。 */
    private static final Path CORPUS_ROOT = locateRepositoryRoot()
            .resolve("things-link-client-contracts/contracts/dashboard-v1-golden");
    /** 使用生产严格解析器装配的完整内部Schema校验门面。 */
    private final DashboardSchemaValidator validator =
            DashboardSchemaValidator.using(new JacksonDashboardSchemaParser());

    /** 清单必须版本化、案例ID唯一、输入文件无游离项且每份实际字节命中冻结摘要。 */
    @Test
    @DisplayName("黄金清单和全部输入字节保持确定且无游离文件")
    void manifestAndInputBytesRemainDeterministic() throws IOException {
        JsonNode manifest = readManifest();
        assertClosed(manifest, MANIFEST_FIELDS, "manifest");
        assertThat(manifest.get("formatVersion").asString()).isEqualTo(CORPUS_FORMAT);
        assertThat(manifest.get("hashAlgorithm").asString()).isEqualTo(HASH_ALGORITHM);
        assertThat(manifest.get("schemaVersion").asString()).isEqualTo("tc.dashboard/v1");
        JsonNode cases = manifest.get("cases");
        assertThat(cases).isNotNull();
        assertThat(cases.isArray()).isTrue();
        assertThat(cases.size()).isGreaterThanOrEqualTo(20);

        Set<String> ids = new HashSet<>();
        Set<Path> referencedFiles = new HashSet<>();
        Set<String> parseReasons = new HashSet<>();
        Set<String> validationReasons = new HashSet<>();
        Set<String> acceptedComponentKinds = new HashSet<>();
        Set<String> acceptedCaseIds = new HashSet<>();
        Set<String> acceptedRequirementTypes = new HashSet<>();
        int acceptedCases = 0;
        int parseCases = 0;
        int validationCases = 0;
        for (JsonNode corpusCase : cases) {
            assertClosed(corpusCase, CASE_FIELDS, "case");
            String id = requiredText(corpusCase, "id", "case");
            assertThat(ids.add(id)).as("案例ID不得重复: %s", id).isTrue();
            JsonNode input = requiredObject(corpusCase, "input", id);
            byte[] bytes = loadInput(input, referencedFiles);
            assertThat(sha256(bytes)).as("输入摘要: %s", id)
                    .isEqualTo(requiredText(input, "sha256", id + ".input"));
            validateExpectedShape(corpusCase, id);
            JsonNode expected = corpusCase.get("expected");
            if ("ACCEPT".equals(expected.get("outcome").asString())) {
                acceptedCases++;
                acceptedCaseIds.add(id);
                collectAcceptedComponentKinds(bytes, acceptedComponentKinds, id);
                expected.get("unresolvedRequirements").forEach(requirement ->
                        acceptedRequirementTypes.add(requiredText(
                                requirement, "requirementType", id + ".expected.unresolvedRequirements")));
            } else if ("PARSE".equals(expected.get("phase").asString())) {
                parseCases++;
                parseReasons.add(expected.get("reason").asString());
            } else {
                validationCases++;
                validationReasons.add(expected.get("reason").asString());
            }
        }

        assertThat(acceptedCases).as("接受语料不得为空").isPositive();
        assertThat(parseCases).as("解析拒绝语料不得为空").isPositive();
        assertThat(validationCases).as("语义拒绝语料不得为空").isPositive();
        assertThat(parseReasons).as("解析拒绝原因必须精确覆盖Java闭集")
                .containsExactlyInAnyOrderElementsOf(enumNames(DashboardSchemaParseException.Reason.values()));
        assertThat(validationReasons).as("语义拒绝原因必须精确覆盖Java闭集")
                .containsExactlyInAnyOrderElementsOf(enumNames(DashboardSchemaValidationException.Reason.values()));
        assertThat(acceptedComponentKinds).as("接受输入必须精确覆盖Java十种组件kind")
                .containsExactlyInAnyOrderElementsOf(
                        enumNames(DashboardComponentDescriptorRegistry.ComponentKind.values()));
        assertThat(acceptedCaseIds).as("三个冻结接受案例不得删除、替换或静默新增")
                .containsExactlyInAnyOrderElementsOf(REQUIRED_ACCEPT_CASE_IDS);
        assertThat(acceptedRequirementTypes).as("接受期望必须合计精确覆盖八类未决需求")
                .containsExactlyInAnyOrderElementsOf(REQUIRED_REQUIREMENT_TYPES);

        Set<Path> actualFiles = new HashSet<>();
        try (Stream<Path> files = Files.walk(CORPUS_ROOT.resolve("inputs"))) {
            files.filter(Files::isRegularFile)
                    .map(Path::toAbsolutePath)
                    .map(Path::normalize)
                    .forEach(actualFiles::add);
        }
        assertThat(actualFiles).as("每个输入文件必须由清单引用，清单引用也必须真实存在")
                .containsExactlyInAnyOrderElementsOf(referencedFiles);
    }

    /** 为清单中的每个案例创建独立可定位的完整管线测试。 */
    @TestFactory
    @DisplayName("Java结果逐项匹配共享黄金期望")
    Stream<DynamicTest> javaResultsMatchGoldenCorpus() {
        List<JsonNode> cases = new ArrayList<>();
        readManifest().get("cases").forEach(cases::add);
        return cases.stream().map(corpusCase -> DynamicTest.dynamicTest(
                corpusCase.get("id").asString(), () -> executeCase(corpusCase)));
    }

    /** 执行单个接受或拒绝案例，并核对稳定结果而非异常中文文本。 */
    private void executeCase(JsonNode corpusCase) throws IOException {
        String id = corpusCase.get("id").asString();
        byte[] source = loadInput(corpusCase.get("input"), new HashSet<>());
        JsonNode expected = corpusCase.get("expected");
        if ("ACCEPT".equals(expected.get("outcome").asString())) {
            assertAccepted(id, source, expected);
        } else {
            assertRejected(id, source, expected);
        }
    }

    /** 核对接受案例的scope、完整规范字节及全部有序未决需求投影。 */
    private void assertAccepted(String id, byte[] source, JsonNode expected) {
        DashboardSchemaValidationResult result = validator.validateAndNormalize(source);
        byte[] normalized = result.normalizedUtf8();
        assertThat(result.scope().name()).as("scope: %s", id)
                .isEqualTo(expected.get("scope").asString());
        assertThat(new String(normalized, StandardCharsets.UTF_8)).as("规范JSON: %s", id)
                .isEqualTo(expected.get("normalizedUtf8").asString());
        assertThat(normalized.length).as("规范字节数: %s", id)
                .isEqualTo(expected.get("normalizedBytes").asInt());
        assertThat(sha256(normalized)).as("规范摘要: %s", id)
                .isEqualTo(expected.get("normalizedSha256").asString());
        assertThat(projectRequirements(result.unresolvedRequirements())).as("未决需求: %s", id)
                .isEqualTo(expected.get("unresolvedRequirements"));
    }

    /** 核对拒绝案例的阶段和原因；只有语义阶段比较结构化安全路径。 */
    private void assertRejected(String id, byte[] source, JsonNode expected) {
        String phase = expected.get("phase").asString();
        if ("PARSE".equals(phase)) {
            try {
                validator.validateAndNormalize(source);
                fail("案例 %s 应在解析阶段拒绝", id);
            } catch (DashboardSchemaParseException exception) {
                assertThat(exception.reason().name()).as("解析原因: %s", id)
                        .isEqualTo(expected.get("reason").asString());
                assertThat(exception.path()).as("解析路径: %s", id)
                        .isEqualTo(expected.get("path").asString());
            } catch (DashboardSchemaValidationException exception) {
                fail("案例 %s 错误进入语义阶段: %s @ %s", id, exception.reason(), exception.path());
            }
            return;
        }
        try {
            validator.validateAndNormalize(source);
            fail("案例 %s 应在语义阶段拒绝", id);
        } catch (DashboardSchemaValidationException exception) {
            assertThat(exception.reason().name()).as("语义原因: %s", id)
                    .isEqualTo(expected.get("reason").asString());
            assertThat(exception.path()).as("语义路径: %s", id)
                    .isEqualTo(expected.get("path").asString());
        } catch (DashboardSchemaParseException exception) {
            fail("案例 %s 过早在解析阶段拒绝: %s", id, exception.reason());
        }
    }

    /** 校验期望结果遵守解析无路径、语义必有路径和接受结果完整的清单闭集。 */
    private static void validateExpectedShape(JsonNode corpusCase, String id) {
        JsonNode expected = requiredObject(corpusCase, "expected", id);
        String outcome = requiredText(expected, "outcome", id + ".expected");
        if ("ACCEPT".equals(outcome)) {
            assertClosed(expected, ACCEPT_FIELDS, id + ".expected");
            assertThat(requiredText(expected, "scope", id + ".expected"))
                    .isEqualTo(DashboardSchemaValidationResult.Scope.COMPLETE_INTERNAL_SCHEMA_SEMANTICS.name());
            requiredText(expected, "normalizedUtf8", id + ".expected");
            requiredText(expected, "normalizedSha256", id + ".expected");
            assertThat(expected.get("normalizedBytes").canConvertToInt()).isTrue();
            assertThat(expected.get("unresolvedRequirements").isArray()).isTrue();
            return;
        }
        assertThat(outcome).as("outcome: %s", id).isEqualTo("REJECT");
        assertClosed(expected, REJECT_FIELDS, id + ".expected");
        String phase = requiredText(expected, "phase", id + ".expected");
        String reason = requiredText(expected, "reason", id + ".expected");
        String path = requiredText(expected, "path", id + ".expected");
        assertThat(path).startsWith("$").doesNotContain("\n", "\r").hasSizeLessThanOrEqualTo(512);
        if ("PARSE".equals(phase)) {
            assertThatCode(() -> DashboardSchemaParseException.Reason.valueOf(reason))
                    .as("解析原因必须来自Java闭集: %s", id).doesNotThrowAnyException();
        } else {
            assertThat(phase).as("phase: %s", id).isEqualTo("VALIDATION");
            assertThatCode(() -> DashboardSchemaValidationException.Reason.valueOf(reason))
                    .as("语义原因必须来自Java闭集: %s", id).doesNotThrowAnyException();
        }
    }

    /** 把八类Java未决需求转换为与共享TypeScript结果一致的稳定JSON形状。 */
    private static ArrayNode projectRequirements(List<DashboardSchemaExternalRequirement> requirements) {
        ArrayNode result = JSON.createArrayNode();
        requirements.forEach(requirement -> result.add(projectRequirement(requirement)));
        return result;
    }

    /** 把一项未决需求投影为显式判别对象，不依赖Jackson对包内record的反射策略。 */
    private static ObjectNode projectRequirement(DashboardSchemaExternalRequirement requirement) {
        return switch (requirement) {
            case HostComponentRequirement value -> hostComponent(value);
            case BuiltinResourceRequirement value -> builtinResource(value);
            case ModelReferenceRequirement value -> modelReference(value);
            case DefaultDeviceRequirement value -> defaultDevice(value);
            case ModelPropertyRequirement value -> modelProperty(value);
            case HistoricalPropertyRequirement value -> historicalProperty(value);
            case ModelGaugeRangeRequirement value -> modelGaugeRange(value);
            case DataAdapterRequirement value -> dataAdapter(value);
        };
    }

    /** 投影宿主组件需求。 */
    private static ObjectNode hostComponent(HostComponentRequirement value) {
        ObjectNode node = typed("HOST_COMPONENT");
        node.put("kind", value.kind().name());
        node.put("componentVersion", value.componentVersion());
        ObjectNode range = JSON.createObjectNode();
        range.put("minInclusive", value.hostCompatibility().minInclusive());
        range.put("maxExclusive", value.hostCompatibility().maxExclusive());
        node.set("hostRange", range);
        return node;
    }

    /** 投影内置资源需求。 */
    private static ObjectNode builtinResource(BuiltinResourceRequirement value) {
        ObjectNode node = typed("BUILTIN_RESOURCE");
        node.put("resourceId", value.resourceId());
        node.put("digest", value.digest());
        return node;
    }

    /** 投影模型引用需求。 */
    private static ObjectNode modelReference(ModelReferenceRequirement value) {
        ObjectNode node = typed("MODEL_REFERENCE");
        node.put("modelKey", value.modelKey());
        node.put("versionId", value.versionId());
        node.put("digestAlgorithm", value.digestAlgorithm());
        node.put("digest", value.digest());
        node.put("profile", value.profile());
        return node;
    }

    /** 投影默认设备需求。 */
    private static ObjectNode defaultDevice(DefaultDeviceRequirement value) {
        ObjectNode node = typed("DEFAULT_DEVICE");
        node.put("variableKey", value.variableKey());
        node.put("variableType", value.variableType().name());
        node.put("modelKey", value.modelKey());
        node.put("deviceId", value.deviceId());
        return node;
    }

    /** 投影模型属性需求，并按名称排序集合语义的dataType。 */
    private static ObjectNode modelProperty(ModelPropertyRequirement value) {
        ObjectNode node = typed("MODEL_PROPERTY");
        node.put("modelKey", value.modelKey());
        node.put("propertyKey", value.propertyKey());
        ArrayNode types = JSON.createArrayNode();
        value.allowedDataTypes().stream().map(Enum::name).sorted().forEach(types::add);
        node.set("allowedDataTypes", types);
        return node;
    }

    /** 投影历史属性需求。 */
    private static ObjectNode historicalProperty(HistoricalPropertyRequirement value) {
        ObjectNode node = typed("HISTORICAL_PROPERTY");
        node.put("variableKey", value.variableKey());
        node.put("modelKey", value.modelKey());
        node.put("propertyKey", value.propertyKey());
        node.put("timeRangeVariableKey", value.timeRangeVariableKey());
        node.put("granularity", value.granularity());
        node.put("aggregation", value.aggregation());
        return node;
    }

    /** 投影仪表模型量程需求。 */
    private static ObjectNode modelGaugeRange(ModelGaugeRangeRequirement value) {
        ObjectNode node = typed("MODEL_GAUGE_RANGE");
        node.put("modelKey", value.modelKey());
        node.put("propertyKey", value.propertyKey());
        return node;
    }

    /** 投影有界数据适配需求。 */
    private static ObjectNode dataAdapter(DataAdapterRequirement value) {
        ObjectNode node = typed("DATA_ADAPTER");
        node.put("capability", value.capability().name());
        node.put("source", value.source().name());
        node.put("variableKey", value.variableKey());
        node.put("variableType", value.variableType().name());
        node.put("modelKey", value.modelKey());
        node.put("propertyKey", value.propertyKey());
        return node;
    }

    /** 创建带统一requirementType判别字段的需求对象。 */
    private static ObjectNode typed(String type) {
        ObjectNode node = JSON.createObjectNode();
        node.put("requirementType", type);
        return node;
    }

    /** 按FILE或REPEAT_BYTE配方构造输入，并记录真实文件供游离检查。 */
    private static byte[] loadInput(JsonNode input, Set<Path> referencedFiles) throws IOException {
        String kind = requiredText(input, "kind", "input");
        if ("FILE".equals(kind)) {
            assertClosed(input, FILE_INPUT_FIELDS, "input");
            Path relative = Path.of(requiredText(input, "path", "input")).normalize();
            assertThat(relative.isAbsolute()).isFalse();
            assertThat(relative.startsWith("inputs")).isTrue();
            Path resolved = CORPUS_ROOT.resolve(relative).toAbsolutePath().normalize();
            assertThat(resolved).startsWith(CORPUS_ROOT.resolve("inputs").toAbsolutePath().normalize());
            referencedFiles.add(resolved);
            return Files.readAllBytes(resolved);
        }
        assertThat(kind).isEqualTo("REPEAT_BYTE");
        assertClosed(input, REPEAT_INPUT_FIELDS, "input");
        int repeatedByte = input.get("byte").asInt(-1);
        int count = input.get("count").asInt(-1);
        assertThat(repeatedByte).isBetween(0, 255);
        assertThat(count).isBetween(0, MAX_RECIPE_BYTES);
        byte[] bytes = new byte[count];
        Arrays.fill(bytes, (byte) repeatedByte);
        return bytes;
    }

    /** 读取可信版本化黄金清单。 */
    private static JsonNode readManifest() {
        try {
            return JSON.readTree(Files.readAllBytes(CORPUS_ROOT.resolve("manifest.json")));
        } catch (IOException exception) {
            throw new IllegalStateException("无法读取看板跨语言黄金清单", exception);
        }
    }

    /** 要求父对象含指定JSON对象字段。 */
    private static JsonNode requiredObject(JsonNode parent, String name, String path) {
        JsonNode value = parent.get(name);
        assertThat(value).as("%s.%s", path, name).isNotNull();
        assertThat(value.isObject()).as("%s.%s必须是对象", path, name).isTrue();
        return value;
    }

    /** 要求父对象含指定非空文本字段。 */
    private static String requiredText(JsonNode parent, String name, String path) {
        JsonNode value = parent.get(name);
        assertThat(value).as("%s.%s", path, name).isNotNull();
        assertThat(value.isString()).as("%s.%s必须是字符串", path, name).isTrue();
        assertThat(value.asString()).as("%s.%s不能为空", path, name).isNotEmpty();
        return value.asString();
    }

    /** 要求对象字段集合与版本化清单声明完全一致，防止拼写错误被读取器忽略。 */
    private static void assertClosed(JsonNode object, Set<String> expectedFields, String path) {
        Set<String> actualFields = new HashSet<>();
        object.propertyNames().forEach(actualFields::add);
        assertThat(actualFields).as("%s字段闭集", path)
                .containsExactlyInAnyOrderElementsOf(expectedFields);
    }

    /** 把枚举数组转换为名称集合，供拒绝原因覆盖检查使用。 */
    private static Set<String> enumNames(Enum<?>[] values) {
        Set<String> names = new HashSet<>();
        Arrays.stream(values).map(Enum::name).forEach(names::add);
        return Set.copyOf(names);
    }

    /** 从实际接受输入读取组件kind，避免expected自行宣称覆盖而输入没有对应组件。 */
    private static void collectAcceptedComponentKinds(
            byte[] source, Set<String> componentKinds, String caseId) throws IOException {
        JsonNode root = JSON.readTree(source);
        JsonNode pages = root.get("pages");
        assertThat(pages).as("接受输入必须包含pages: %s", caseId).isNotNull();
        assertThat(pages.isArray()).as("接受输入pages必须是数组: %s", caseId).isTrue();
        for (JsonNode page : pages) {
            JsonNode components = page.get("components");
            assertThat(components).as("接受输入页面必须包含components: %s", caseId).isNotNull();
            assertThat(components.isArray()).as("接受输入components必须是数组: %s", caseId).isTrue();
            for (JsonNode component : components) {
                componentKinds.add(requiredText(component, "kind", caseId + ".component"));
            }
        }
    }

    /** 计算固定小写十六进制SHA-256，避免清单与实际输入悄然分叉。 */
    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance(HASH_ALGORITHM).digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JDK缺少强制SHA-256算法", exception);
        }
    }

    /** 从任意Maven或IDE工作目录向上定位仓库根目录。 */
    private static Path locateRepositoryRoot() {
        Path candidate = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (candidate != null) {
            if (Files.isRegularFile(candidate.resolve("things-link/pom.xml"))
                    && Files.isDirectory(candidate.resolve("things-link-client-contracts"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException("无法定位ThingsLink仓库根目录");
    }

}
