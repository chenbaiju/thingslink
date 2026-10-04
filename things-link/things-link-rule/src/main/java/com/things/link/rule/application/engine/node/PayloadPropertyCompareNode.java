package com.things.link.rule.application.engine.node;

import com.things.link.rule.application.engine.RuleExecutionContext;
import com.things.link.rule.application.engine.RuleMessage;
import com.things.link.rule.application.engine.RuleNode;
import com.things.link.rule.application.engine.RuleNodeResult;
import com.things.link.rule.application.engine.RuleNodeValidation;
import com.things.link.rule.application.engine.RuleRelation;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.Set;

/** 使用 RFC 6901 JSON Pointer 读取 payload 属性并执行有限类型比较。 */
@Component
public final class PayloadPropertyCompareNode implements RuleNode {

    /** 节点注册表中的稳定类型。 */
    public static final String TYPE = "payload-property-compare";
    /** 首批运算符是封闭集合，禁止引入脚本或任意表达式。 */
    private static final Set<String> OPERATORS = Set.of("EQ", "NE", "GT", "GTE", "LT", "LTE", "EXISTS");
    /** JSON Schema 描述控制面的配置表面，运行时仍由 validate 执行等价约束。 */
    private static final JsonNode CONFIG_SCHEMA = new ObjectMapper().readTree("""
            {"type":"object","additionalProperties":false,"required":["pointer","operator"],
             "properties":{"pointer":{"type":"string","pattern":"^(/.*)?$","maxLength":256},
             "operator":{"enum":["EQ","NE","GT","GTE","LT","LTE","EXISTS"]},"value":{}}}
            """);

    /** {@inheritDoc} */
    @Override
    public String type() {
        return TYPE;
    }

    /** {@inheritDoc} */
    @Override
    public JsonNode configSchema() {
        return CONFIG_SCHEMA.deepCopy();
    }

    /** {@inheritDoc} */
    @Override
    public RuleNodeValidation validate(JsonNode config) {
        if (config == null || !config.isObject() || config.size() < 2 || config.size() > 3
                || !config.has("pointer") || !config.get("pointer").isString()
                || !config.has("operator") || !config.get("operator").isString()
                || config.properties().stream().anyMatch(entry -> !Set.of("pointer", "operator", "value")
                        .contains(entry.getKey()))) {
            return RuleNodeValidation.invalid("config 只允许 pointer、operator 和可选 value");
        }
        String pointer = config.get("pointer").stringValue();
        String operator = config.get("operator").stringValue();
        if (pointer.length() > 256 || (!pointer.isEmpty() && !pointer.startsWith("/"))) {
            return RuleNodeValidation.invalid("pointer 必须是最长 256 字符的 JSON Pointer");
        }
        if (!OPERATORS.contains(operator)) {
            return RuleNodeValidation.invalid("operator 不受支持");
        }
        if (!"EXISTS".equals(operator) && !config.has("value")) {
            return RuleNodeValidation.invalid("比较运算符必须提供 value");
        }
        if (Set.of("GT", "GTE", "LT", "LTE").contains(operator) && !config.get("value").isNumber()) {
            return RuleNodeValidation.invalid("顺序比较的 value 必须是数字");
        }
        return RuleNodeValidation.success();
    }

    /** {@inheritDoc} */
    @Override
    public RuleNodeResult execute(RuleMessage message, JsonNode config, RuleExecutionContext context) {
        JsonNode actual = message.payload().at(config.get("pointer").stringValue());
        String operator = config.get("operator").stringValue();
        boolean matches = switch (operator) {
            case "EXISTS" -> !actual.isMissingNode();
            case "EQ" -> !actual.isMissingNode() && actual.equals(config.get("value"));
            case "NE" -> actual.isMissingNode() || !actual.equals(config.get("value"));
            case "GT" -> compareNumber(actual, config.get("value"), comparison -> comparison > 0);
            case "GTE" -> compareNumber(actual, config.get("value"), comparison -> comparison >= 0);
            case "LT" -> compareNumber(actual, config.get("value"), comparison -> comparison < 0);
            case "LTE" -> compareNumber(actual, config.get("value"), comparison -> comparison <= 0);
            default -> throw new IllegalStateException("validate 已拒绝未知运算符");
        };
        return RuleNodeResult.withoutSideEffect(matches ? RuleRelation.TRUE : RuleRelation.FALSE, message);
    }

    /** 非数字实际值按不匹配处理，避免隐式字符串转数字造成跨版本差异。 */
    private static boolean compareNumber(
            JsonNode actual,
            JsonNode expected,
            java.util.function.IntPredicate predicate) {
        if (!actual.isNumber()) {
            return false;
        }
        BigDecimal left = actual.decimalValue();
        BigDecimal right = expected.decimalValue();
        return predicate.test(left.compareTo(right));
    }
}
