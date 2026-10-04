package com.things.link.dashboard.application.schema;

import com.things.link.dashboard.application.DashboardSchemaContractVersion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Java看板规则与版本化共享客户端机器合同的一致性测试。 */
@DisplayName("看板共享客户端机器合同")
class DashboardClientContractParityTests {
    /** 读取仓库内唯一版本化机器合同。 */
    private final JsonNode contract = readContract();

    /** 机器合同必须保留独立格式版本、Schema版本和两阶段稳定拒绝原因。 */
    @Test
    @DisplayName("协议版本和拒绝原因与Java保持一致")
    void protocolVersionsAndReasonsMatchJava() {
        assertThat(contract.get("formatVersion").asString()).isEqualTo("tc.dashboard-client-contract/v1");
        assertThat(contract.get("schemaVersion").asString())
                .isEqualTo(DashboardSchemaContractVersion.V1.value());
        assertThat(strings(contract.get("rejectionReasons").get("parse")))
                .containsExactly(java.util.Arrays.stream(DashboardSchemaParseException.Reason.values())
                        .map(Enum::name).toArray(String[]::new));
        assertThat(strings(contract.get("rejectionReasons").get("validation")))
                .containsExactly(java.util.Arrays.stream(DashboardSchemaValidationException.Reason.values())
                        .map(Enum::name).toArray(String[]::new));
    }

    /** 十组件的版本、字段、slot、dataType、画布和HostRange必须逐项一致。 */
    @Test
    @DisplayName("十组件描述符完整矩阵与Java保持一致")
    void componentDescriptorMatrixMatchesJava() {
        Map<String, JsonNode> contractDescriptors = new HashMap<>();
        for (JsonNode component : contract.get("components")) {
            assertThat(contractDescriptors.put(component.get("kind").asString() + "/" + component.get("componentVersion").asString(), component))
                    .as("客户端组件kind不得重复")
                    .isNull();
        }

        assertThat(contractDescriptors).hasSize(20);
        for (DashboardComponentDescriptorRegistry.ComponentDescriptor descriptor
                : DashboardComponentDescriptorRegistry.descriptors()) {
            JsonNode component = contractDescriptors.get(descriptor.kind().name() + "/" + descriptor.componentVersion());
            assertThat(component).as("客户端必须登记组件 " + descriptor.kind()).isNotNull();
            assertThat(component.get("componentVersion").asString()).isEqualTo(descriptor.componentVersion());
            assertFields(component.get("props"), descriptor.props());
            assertSlots(component.get("slots"), descriptor.slots());
            assertThat(component.get("supportedDataTypes").get("applicability").asString())
                    .isEqualTo(descriptor.supportedDataTypes().applicability().name());
            assertThat(strings(component.get("supportedDataTypes").get("values")))
                    .containsExactlyInAnyOrderElementsOf(enumNames(descriptor.supportedDataTypes().allowedTypes()));
            assertThat(strings(component.get("supportedCanvasModes")))
                    .containsExactlyInAnyOrderElementsOf(enumNames(descriptor.supportedCanvasModes()));
            assertThat(component.get("hostRange").get("minInclusive").asString())
                    .isEqualTo(descriptor.hostCompatibility().minInclusive());
            assertThat(component.get("hostRange").get("maxExclusive").asString())
                    .isEqualTo(descriptor.hostCompatibility().maxExclusive());
        }
    }

    /** 比较props字段闭集、机器类型、必填和默认字面值。 */
    private static void assertFields(
            JsonNode contractFields,
            Map<String, DashboardComponentDescriptorRegistry.FieldDescriptor> javaFields) {
        assertThat(fieldNames(contractFields)).containsExactlyInAnyOrderElementsOf(javaFields.keySet());
        javaFields.forEach((name, field) -> {
            JsonNode contractField = contractFields.get(name);
            assertThat(contractField.get("type").asString()).isEqualTo(field.type().name());
            assertThat(contractField.get("required").asBoolean()).isEqualTo(field.required());
            Optional<String> defaultLiteral = Optional.ofNullable(contractField.get("default"))
                    .map(DashboardClientContractParityTests::literal);
            assertThat(defaultLiteral).isEqualTo(field.defaultLiteral());
        });
    }

    /** 比较binding slot闭集、数组性、必填和允许source。 */
    private static void assertSlots(
            JsonNode contractSlots,
            Map<String, DashboardComponentDescriptorRegistry.SlotDescriptor> javaSlots) {
        assertThat(fieldNames(contractSlots)).containsExactlyInAnyOrderElementsOf(javaSlots.keySet());
        javaSlots.forEach((name, slot) -> {
            JsonNode contractSlot = contractSlots.get(name);
            assertThat(contractSlot.get("valueType").asString()).isEqualTo(slot.type().name());
            assertThat(contractSlot.get("required").asBoolean()).isEqualTo(slot.required());
            assertThat(strings(contractSlot.get("sources")))
                    .containsExactlyInAnyOrderElementsOf(enumNames(slot.acceptedSources()));
            String expectedCardinality = slot.type() == DashboardComponentDescriptorRegistry.ValueType.BINDING
                    ? "SINGLE" : "MULTIPLE";
            assertThat(contractSlot.get("cardinality").asString()).isEqualTo(expectedCardinality);
        });
    }

    /** 把JSON数组转换为保持原顺序的字符串列表。 */
    private static List<String> strings(JsonNode array) {
        java.util.ArrayList<String> values = new java.util.ArrayList<>();
        array.forEach(value -> values.add(value.asString()));
        return List.copyOf(values);
    }

    /** 把枚举集合转换为名称集合。 */
    private static Set<String> enumNames(Set<? extends Enum<?>> values) {
        Set<String> names = new HashSet<>();
        values.forEach(value -> names.add(value.name()));
        return Set.copyOf(names);
    }

    /** 取得JSON对象字段名闭集。 */
    private static Set<String> fieldNames(JsonNode object) {
        Set<String> names = new HashSet<>();
        object.propertyNames().forEach(names::add);
        return Set.copyOf(names);
    }

    /** 把JSON默认值转成与Java描述符一致的稳定文本。 */
    private static String literal(JsonNode value) {
        return value.isString() ? value.asString() : value.toString();
    }

    /** 从任意Maven或IDE工作目录定位并读取共享机器合同。 */
    private static JsonNode readContract() {
        try {
            Path repository = locateRepositoryRoot();
            return new ObjectMapper().readTree(Files.readAllBytes(
                    repository.resolve("things-link-client-contracts/contracts/dashboard-v1.json")));
        } catch (IOException exception) {
            throw new IllegalStateException("无法读取看板共享客户端机器合同", exception);
        }
    }

    /** 向上查找同时包含后端和共享客户端合同的仓库根目录。 */
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
