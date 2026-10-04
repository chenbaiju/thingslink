package com.things.link.rule.application;

import com.things.link.rule.application.engine.RuleNode;
import com.things.link.rule.domain.RuleErrorCode;
import com.things.link.shared.error.BusinessException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 控制面用途白名单与参数校验；节点注册不自动授予动作或条件资格。 */
@Component
public class RuleNodeCatalog {
    /** 既有七种副作用节点的封闭集合。 */
    public static final Set<String> ACTIONS = Set.of("notification-action", "email-action", "webhook-action",
            "device-command-action", "device-property-set-action", "alarm-create-action", "alarm-clear-action");
    /** 手动场景仅允许只读payload比较。 */
    public static final Set<String> CONDITIONS = Set.of("payload-property-compare");
    /** 已装配的真实节点。 */
    private final Map<String, RuleNode> nodes;
    /** 按实际UTF-8序列化计量配置预算。 */
    private final ObjectMapper mapper;
    /** 装配真实节点，不建立第二份配置Schema。 */
    public RuleNodeCatalog(List<RuleNode> nodes, ObjectMapper mapper) {
        this.nodes = nodes.stream().collect(Collectors.toUnmodifiableMap(RuleNode::type, Function.identity()));
        this.mapper = mapper;
    }
    /** 返回被授权用途的完整Schema，前端不能以该目录代替服务端验证。 */
    public Catalog view() { return new Catalog(descriptors(CONDITIONS), descriptors(ACTIONS)); }
    /** 仅返回实际注册的允许节点，顺序固定以稳定契约。 */
    private List<Descriptor> descriptors(Set<String> allowed) {
        return allowed.stream().sorted().filter(nodes::containsKey)
                .map(type -> new Descriptor(type, nodes.get(type).configSchema())).toList();
    }
    /** 校验单节点用途及配置，null/未知类型统一业务拒绝。 */
    public void validate(String type, JsonNode config, boolean condition) {
        RuleErrorCode error = condition ? RuleErrorCode.SCENE_CONDITION_INVALID : RuleErrorCode.RULE_ACTION_INVALID;
        Set<String> allowed = condition ? CONDITIONS : ACTIONS;
        if (type == null || config == null || !allowed.contains(type) || !nodes.containsKey(type))
            throw new BusinessException(error);
        try {
            if (!nodes.get(type).validate(config).valid()) throw new BusinessException(error);
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new BusinessException(error);
        }
    }
    /** 新编辑预算不追溯截断历史事实；调用者选择领域参数错误。 */
    public void budget(List<?> conditions, List<?> actions, boolean scene) {
        if (conditions.size() > 32 || actions.size() > 32
                || mapper.writeValueAsString(List.of(conditions, actions)).getBytes(StandardCharsets.UTF_8).length > 65536)
            throw new BusinessException(scene ? RuleErrorCode.SCENE_INVALID : RuleErrorCode.RULE_INVALID);
    }
    /** 历史候选逐项重验用途；保留历史数量兼容，不应用新编辑预算。 */
    public void historical(JsonNode values, boolean condition) {
        RuleErrorCode error = condition ? RuleErrorCode.SCENE_CONDITION_INVALID : RuleErrorCode.RULE_ACTION_INVALID;
        if (values == null || !values.isArray()) throw new BusinessException(error);
        for (JsonNode value : values) {
            if (!value.isObject() || value.size() != 2 || !value.path("nodeType").isString() || !value.has("config"))
                throw new BusinessException(error);
            validate(value.get("nodeType").stringValue(), value.get("config"), condition);
        }
    }
    /** @param nodeType 稳定节点类型 @param configSchema 节点配置Schema */
    public record Descriptor(String nodeType, JsonNode configSchema) { }
    /** @param conditions 条件目录 @param actions 动作目录 */
    public record Catalog(List<Descriptor> conditions, List<Descriptor> actions) { }
}
