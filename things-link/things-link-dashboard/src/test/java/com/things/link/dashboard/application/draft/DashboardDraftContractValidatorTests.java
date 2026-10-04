package com.things.link.dashboard.application.draft;

import com.things.link.dashboard.infrastructure.schema.JacksonDashboardSchemaParser;
import com.things.link.dashboard.application.schema.DefaultDashboardDraftContractValidator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 看板草稿公开校验门面的revision信封、规范Schema和模型需求投影测试。 */
@DisplayName("看板草稿合同校验")
class DashboardDraftContractValidatorTests {

    /** 模型版本ID固定，便于同时核对规范内容与关系投影。 */
    private static final UUID MODEL_VERSION_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000101");

    /** 使用生产严格原文解析器装配公开草稿门面。 */
    private final DashboardDraftContractValidator validator =
            new DefaultDashboardDraftContractValidator(new JacksonDashboardSchemaParser());

    /** 合法Schema必须注入唯一默认值，并逐项保留外部模型核验与持久关系字段。 */
    @Test
    @DisplayName("合法草稿返回规范内容与精确模型需求")
    void validDraftReturnsNormalizedContentAndExactModelRequirements() {
        ValidatedDashboardDraft result = validator.validate("7", schema("a".repeat(64)));

        assertThat(result.expectedRevision()).isEqualTo(7);
        assertThat(result.content().path("variables").isArray()).isTrue();
        assertThat(result.content().path("variables").isEmpty()).isTrue();
        assertThat(result.models()).containsExactly(new ValidatedDashboardModelReference(
                0, "pump_model", MODEL_VERSION_ID,
                ValidatedDashboardModelReference.REQUIRED_DIGEST_ALGORITHM,
                "a".repeat(64), ValidatedDashboardModelReference.REQUIRED_PROFILE));
        assertThat(result.persistenceReferences()).singleElement().satisfies(reference -> {
            assertThat(reference.position()).isZero();
            assertThat(reference.modelKey()).isEqualTo("pump_model");
            assertThat(reference.thingModelVersionId()).isEqualTo(MODEL_VERSION_ID);
        });

        ((ObjectNode) result.content()).put("schemaVersion", "tc.dashboard/v2");
        assertThat(result.content().path("schemaVersion").asString()).isEqualTo("tc.dashboard/v1");
        assertThatThrownBy(() -> result.models().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    /** revision必须是无符号、无前导零且未溢出的规范十进制Long。 */
    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "-1", "+1", "01", "9223372036854775808"})
    @DisplayName("非法revision使用稳定安全路径拒绝")
    void invalidRevisionUsesStableSafeFailure(String revision) {
        assertThatThrownBy(() -> validator.validate(revision, schema("a".repeat(64))))
                .isInstanceOfSatisfying(DashboardDraftContractViolation.class, violation -> {
                    assertThat(violation.reasonCode()).isEqualTo("INVALID_REVISION");
                    assertThat(violation.path()).isEqualTo("$.revision");
                });
    }

    /** 原文与内部Schema失败必须保留结构化阶段，不向调用方暴露底层解析消息。 */
    @Test
    @DisplayName("原文和Schema失败映射为结构化安全拒绝")
    void parseAndSchemaFailuresUseStructuredSafeReasons() {
        byte[] duplicateKey = "{\"schemaVersion\":\"tc.dashboard/v1\",\"schemaVersion\":\"tc.dashboard/v1\"}"
                .getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> validator.validate("0", duplicateKey))
                .isInstanceOfSatisfying(DashboardDraftContractViolation.class, violation -> {
                    assertThat(violation.reasonCode()).startsWith("PARSE_");
                    assertThat(violation.path()).startsWith("$");
                });

        String unknownField = new String(schema("a".repeat(64)), StandardCharsets.UTF_8)
                .replace("\"pages\"", "\"privateToken\":\"secret\",\"pages\"");
        assertThatThrownBy(() -> validator.validate("0", unknownField.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOfSatisfying(DashboardDraftContractViolation.class, violation -> {
                    assertThat(violation.reasonCode()).startsWith("SCHEMA_");
                    assertThat(violation.path()).doesNotContain("privateToken", "secret");
                    assertThat(violation.getMessage()).doesNotContain("privateToken", "secret");
                });
    }

    /** 构造含一条完整模型引用的最小合法tc.dashboard/v1原文。 */
    private byte[] schema(String digest) {
        return ("""
                {"schemaVersion":"tc.dashboard/v1","presentation":{"mode":"RESPONSIVE_GRID"},
                 "models":[{"key":"pump_model","versionId":"%s",
                 "digestAlgorithm":"PG_JSONB_TEXT_V1_SHA256","digest":"%s",
                 "profile":"TC_PROPERTY_COMPOSITE_V1"}],
                 "pages":[{"id":"overview","title":"设备概览","components":[]}]}
                """).formatted(MODEL_VERSION_ID, digest).getBytes(StandardCharsets.UTF_8);
    }
}
