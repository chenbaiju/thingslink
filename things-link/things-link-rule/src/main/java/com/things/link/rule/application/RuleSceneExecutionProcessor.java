package com.things.link.rule.application;

import com.things.link.rule.application.engine.DeterministicRuleEngine;
import com.things.link.rule.application.engine.RuleExecutionContext;
import com.things.link.rule.application.engine.RuleMessage;
import com.things.link.rule.application.engine.RuleNodeResult;
import com.things.link.rule.application.engine.RuleRelation;
import com.things.link.rule.application.engine.RuleSideEffectIntent;
import com.things.link.rule.domain.RuleSceneExecution;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 手动场景的确定性执行处理器：ALL_OF 有序短路求值 + 有序动作意图收集，不调用脚本沙箱、不读库、不碰网络。
 *
 * <p>条件只允许白名单纯计算节点且必须返回 TRUE/FALSE；动作只允许七个冻结动作节点。非法关系、未知节点与
 * 节点异常一律 fail-closed 为 {@code FAILED}，绝不产生副作用意图（见 ADR 0030 D）。</p>
 */
@Component
public class RuleSceneExecutionProcessor {

    /** 手动场景条件白名单：只读 payload 的纯计算节点。 */
    private static final Set<String> CONDITION_NODES = Set.of("payload-property-compare");

    /** 手动场景动作白名单：与 S9-1～S9-3 冻结副作用一致。 */
    private static final Set<String> ACTION_NODES = Set.of(
            "notification-action", "email-action", "webhook-action",
            "device-command-action", "device-property-set-action",
            "alarm-create-action", "alarm-clear-action");

    /** 节点执行入口，负责校验配置并分派到真实节点实现。 */
    private final DeterministicRuleEngine engine;

    /** @param engine 节点执行入口 */
    public RuleSceneExecutionProcessor(DeterministicRuleEngine engine) {
        this.engine = engine;
    }

    /**
     * 求值冻结版本的条件与动作，返回封闭终态与动作意图。
     *
     * @param message 服务端构造的不可变规则消息
     * @param sceneId 场景定义 ID
     * @param sceneVersionId 冻结场景版本 ID
     * @param startedAt 本次执行固定开始时刻
     * @param conditions 有序条件节点数组
     * @param actions 有序动作节点数组
     * @return 封闭终态；SKIPPED/FAILED 时无意图
     */
    public SceneExecutionOutcome process(
            RuleMessage message,
            UUID sceneId,
            UUID sceneVersionId,
            Instant startedAt,
            JsonNode conditions,
            JsonNode actions) {
        int position = 0;
        for (JsonNode condition : conditions) {
            if (!condition.isObject() || !condition.hasNonNull("nodeType")
                    || !CONDITION_NODES.contains(condition.get("nodeType").asText())) {
                return SceneExecutionOutcome.failed();
            }
            RuleNodeResult result = execute(
                    condition, message, sceneId, sceneVersionId, position++, startedAt);
            if (result == null) {
                return SceneExecutionOutcome.failed();
            }
            if (result.relation() == RuleRelation.FALSE) {
                return SceneExecutionOutcome.skipped();
            }
            if (result.relation() != RuleRelation.TRUE) {
                return SceneExecutionOutcome.failed();
            }
        }
        List<RuleSideEffectIntent> intents = new ArrayList<>();
        for (JsonNode action : actions) {
            if (!action.isObject() || !action.hasNonNull("nodeType")
                    || !ACTION_NODES.contains(action.get("nodeType").asText())) {
                return SceneExecutionOutcome.failed();
            }
            RuleNodeResult result = execute(
                    action, message, sceneId, sceneVersionId, position++, startedAt);
            if (result == null || result.relation() != RuleRelation.SUCCESS) {
                return SceneExecutionOutcome.failed();
            }
            intents.addAll(result.sideEffectIntents());
        }
        return SceneExecutionOutcome.dispatched(intents);
    }

    /** 单节点执行；未知节点、非法配置或节点异常统一 fail-closed。 */
    private RuleNodeResult execute(
            JsonNode node,
            RuleMessage message,
            UUID sceneId,
            UUID sceneVersionId,
            int position,
            Instant startedAt) {
        try {
            RuleExecutionContext context = new RuleExecutionContext(
                    sceneId, sceneVersionId, nodeId(sceneVersionId, position), 1, startedAt);
            return engine.execute(
                    node.get("nodeType").asText(), node.get("config"), message, context);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    /** 场景没有持久化节点图，用版本 ID 与位置合成稳定 nodeId，供节点契约标识。 */
    private static UUID nodeId(UUID sceneVersionId, int position) {
        return UUID.nameUUIDFromBytes(
                (sceneVersionId + ":node:" + position).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * @param status 封闭终态
     * @param intents 仅 DISPATCHED 时非空的副作用意图
     */
    public record SceneExecutionOutcome(
            RuleSceneExecution.Status status,
            List<RuleSideEffectIntent> intents) {

        /** @return 全部条件成立且动作意图已收集 */
        public static SceneExecutionOutcome dispatched(List<RuleSideEffectIntent> intents) {
            return new SceneExecutionOutcome(
                    RuleSceneExecution.Status.DISPATCHED, List.copyOf(intents));
        }

        /** @return 首个条件不成立，短路结束 */
        public static SceneExecutionOutcome skipped() {
            return new SceneExecutionOutcome(RuleSceneExecution.Status.SKIPPED, List.of());
        }

        /** @return 求值 fail-closed */
        public static SceneExecutionOutcome failed() {
            return new SceneExecutionOutcome(RuleSceneExecution.Status.FAILED, List.of());
        }
    }
}
