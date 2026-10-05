package com.things.link.dashboard.application.schema;

import com.things.link.dashboard.application.draft.DashboardDraftContractValidator;
import com.things.link.dashboard.application.draft.DashboardDraftContractViolation;
import com.things.link.dashboard.application.draft.ValidatedDashboardDraft;
import com.things.link.dashboard.application.draft.ValidatedDashboardModelReference;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 复用既有严格Schema管线的公开看板草稿校验门面实现。
 *
 * <p>内部验证器继续保持包内边界；本门面只公开规范内容和models的有限核验需求，不暴露宿主、资源、
 * 数据适配等尚未由草稿管理用例裁决的内部需求类型。</p>
 */
@Component
public class DefaultDashboardDraftContractValidator implements DashboardDraftContractValidator {

    /** Revision只能是0或无前导零正十进制字符串，并由Long解析作最终上限裁决。 */
    private static final Pattern REVISION = Pattern.compile("0|[1-9][0-9]{0,18}");
    /** 既有完整Schema结构与语义验证器。 */
    private final DashboardSchemaValidator schemaValidator;

    /**
     * 创建固定执行原文解析、内部语义验证和模型投影的门面。
     *
     * @param parser 已装配的严格原文解析端口
     */
    public DefaultDashboardDraftContractValidator(DashboardSchemaParser parser) {
        this.schemaValidator = DashboardSchemaValidator.using(Objects.requireNonNull(parser, "parser"));
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public ValidatedDashboardDraft validate(String expectedRevision, byte[] source) {
        long revision = requireRevision(expectedRevision);
        DashboardSchemaValidationResult result;
        try {
            result = schemaValidator.validateAndNormalize(source);
        } catch (DashboardSchemaParseException exception) {
            throw new DashboardDraftContractViolation(
                    "PARSE_" + exception.reason().name(), exception.path(),
                    "看板草稿原文不符合合同", exception);
        } catch (DashboardSchemaValidationException exception) {
            throw new DashboardDraftContractViolation(
                    "SCHEMA_" + exception.reason().name(), exception.path(),
                    "看板草稿内部语义不符合合同", exception);
        }
        JsonNode normalized = result.normalizedRoot();
        List<ValidatedDashboardModelReference> models = projectModels(normalized.path("models"));
        return new ValidatedDashboardDraft(revision, normalized, models);
    }

    /** 将规范化后必为数组的models逐项投影为公开、有限的外部核验需求。 */
    private static List<ValidatedDashboardModelReference> projectModels(JsonNode models) {
        List<ValidatedDashboardModelReference> projected = new ArrayList<>(models.size());
        for (int position = 0; position < models.size(); position++) {
            JsonNode model = models.get(position);
            projected.add(new ValidatedDashboardModelReference(
                    position, model.path("key").asString(),
                    UUID.fromString(model.path("versionId").asString()),
                    model.path("digestAlgorithm").asString(), model.path("digest").asString(),
                    model.path("profile").asString()));
        }
        return List.copyOf(projected);
    }

    /** 解析规范revision并拒绝负数、前导零、符号、溢出和空值。 */
    private static long requireRevision(String expectedRevision) {
        if (expectedRevision == null || !REVISION.matcher(expectedRevision).matches()) {
            throw invalidRevision();
        }
        try {
            return Long.parseLong(expectedRevision);
        } catch (NumberFormatException exception) {
            throw new DashboardDraftContractViolation(
                    "INVALID_REVISION", "$.revision", "看板草稿revision超出Long范围", exception);
        }
    }

    /** @return 不回显非法revision原文的固定拒绝 */
    private static DashboardDraftContractViolation invalidRevision() {
        return new DashboardDraftContractViolation(
                "INVALID_REVISION", "$.revision", "看板草稿revision必须是规范非负十进制Long");
    }
}
