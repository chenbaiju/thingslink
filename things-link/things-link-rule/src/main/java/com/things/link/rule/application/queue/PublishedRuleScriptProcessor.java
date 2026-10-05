package com.things.link.rule.application.queue;

import com.things.link.rule.application.ScriptExecutionRequest;
import com.things.link.rule.application.ScriptExecutionResult;
import com.things.link.rule.application.ScriptExecutionStatus;
import com.things.link.rule.application.ScriptKind;
import com.things.link.rule.application.ScriptSandbox;
import com.things.link.rule.application.engine.DeterministicRuleEngine;
import com.things.link.rule.application.engine.RuleExecutionContext;
import com.things.link.rule.application.engine.RuleMessage;
import com.things.link.rule.application.engine.RuleNodeResult;
import com.things.link.rule.application.engine.RuleSideEffectIntent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 在公平队列 Worker 上按冻结顺序串行执行全部活动脚本，再执行每个版本的确定性动作节点产出副作用意图。
 *
 * <p>脚本只派生 payload；动作节点只在主机侧产出 {@link RuleSideEffectIntent}，真正的副作用交由 S9 Outbox
 * 边界投递，处理器不触碰数据库、网络或 Kafka。</p>
 */
@Component
public final class PublishedRuleScriptProcessor implements RuleExecutionProcessor {

    /** 只记录封闭分类，避免租户源码、输入和 guest 异常进入日志。 */
    private static final Logger LOGGER = LoggerFactory.getLogger(PublishedRuleScriptProcessor.class);

    /** 所有租户代码只能经过 S8-1A 沙箱端口。 */
    private final ScriptSandbox sandbox;
    /** JSON 只承载 payload，不向脚本暴露可修改的可信身份。 */
    private final ObjectMapper objectMapper;
    /** 动作节点确定性执行入口，控制面已在写入版本事实前完成 fail-closed 校验。 */
    private final DeterministicRuleEngine engine;
    /** 可测试的 UTC 时钟，固定本次执行开始时刻。 */
    private final Clock clock;

    /** @param sandbox 不可信脚本沙箱 @param objectMapper 共享 JSON 映射器 @param engine 节点引擎 @param clock UTC 时钟 */
    public PublishedRuleScriptProcessor(ScriptSandbox sandbox, ObjectMapper objectMapper,
                                        DeterministicRuleEngine engine, Clock clock) {
        this.sandbox = sandbox;
        this.objectMapper = objectMapper;
        this.engine = engine;
        this.clock = clock;
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public RuleExecutionResult process(RuleExecutionEnvelope envelope) {
        RuleMessage current = envelope.message();
        Instant startedAt = clock.instant();
        List<RuleSideEffectIntent> intents = new ArrayList<>();
        int position = 0;
        for (PublishedRuleStep step : envelope.plan().steps()) {
            ScriptExecutionResult result = sandbox.execute(new ScriptExecutionRequest(
                    ScriptKind.RULE, step.source(), current.payload().toString()));
            if (result.status() != ScriptExecutionStatus.SUCCESS) {
                // 执行日志的 result_code 是恢复决策分类 SCRIPT_FAILURE；此处保留沙箱固定码以诊断具体边界。
                String sandboxCode = result.errorCode().matches("SANDBOX_[A-Z_]{1,56}")
                        ? result.errorCode() : "SANDBOX_UNCLASSIFIED";
                LOGGER.warn("生产规则沙箱失败 messageId={} status={} sandboxCode={} durationMs={}",
                        current.messageId(), result.status(), sandboxCode, result.duration().toMillis());
                throw failure(result);
            }
            try {
                JsonNode output = objectMapper.readTree(result.outputJson());
                if (output == null || !output.isObject()) {
                    throw new RuleExecutionException(RuleExecutionFailure.CONTRACT_INVALID,
                            "生产规则输出必须是 JSON 对象");
                }
                current = current.withPayload(output);
            } catch (RuleExecutionException exception) {
                throw exception;
            } catch (RuntimeException exception) {
                throw new RuleExecutionException(RuleExecutionFailure.CONTRACT_INVALID,
                        "生产规则输出不是合法 JSON", exception);
            }
            for (PublishedRuleAction action : step.actions()) {
                if (!com.things.link.rule.application.RuleNodeCatalog.ACTIONS.contains(action.nodeType()))
                    throw new RuleExecutionException(RuleExecutionFailure.CONTRACT_INVALID, "冻结动作不属于允许用途");
                position++;
                RuleNodeResult nodeResult = engine.execute(action.nodeType(), action.config(), current,
                        new RuleExecutionContext(step.ruleId(), step.ruleVersionId(),
                                actionNodeId(step.ruleVersionId(), position), envelope.attempt(), startedAt));
                current = nodeResult.message();
                intents.addAll(nodeResult.sideEffectIntents());
            }
        }
        return new RuleExecutionResult(current, intents);
    }

    /** 动作没有持久化节点 ID，用版本与位置派生出稳定、非空的受限上下文节点标识。 */
    private static UUID actionNodeId(UUID ruleVersionId, int position) {
        return UUID.nameUUIDFromBytes((ruleVersionId + "#" + position).getBytes(StandardCharsets.UTF_8));
    }

    /** 沙箱饱和可恢复，其余租户脚本失败按 ADR 0017 永久进入 DLQ。 */
    private static RuleExecutionException failure(ScriptExecutionResult result) {
        RuleExecutionFailure failure = result.status() == ScriptExecutionStatus.REJECTED
                ? RuleExecutionFailure.SANDBOX_UNAVAILABLE : RuleExecutionFailure.SCRIPT_FAILURE;
        return new RuleExecutionException(failure, "生产规则沙箱返回" + result.status());
    }
}
