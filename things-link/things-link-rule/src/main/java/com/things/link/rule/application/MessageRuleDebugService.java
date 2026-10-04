package com.things.link.rule.application;

import com.things.link.rule.domain.MessageRuleRepository;
import com.things.link.rule.domain.MessageRuleVersion;
import com.things.link.rule.domain.RuleDebugEvent;
import com.things.link.rule.domain.RuleErrorCode;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.id.Uuid7;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** S8-1C 无副作用样例执行服务：真实沙箱执行后只写有界、脱敏、短保留调试事实。 */
@Service
public class MessageRuleDebugService {

    /** 调试事件保留七天；这类排障事实不承担长期审计职责。 */
    static final Duration RETENTION = Duration.ofDays(7);
    /** 数据库摘要按 UTF-8 截断到 2048 字节，不能把 64 KiB 沙箱载荷复制进事实表。 */
    static final int MAX_SUMMARY_BYTES = 2_048;
    /** 账号凭据、设备凭据、令牌、密钥与 Cookie 的常见字段名统一遮蔽。 */
    private static final Set<String> SENSITIVE_KEYS = Set.of(
            "password", "passwd", "pwd", "token", "accesstoken", "refreshtoken",
            "authorization", "cookie", "secret", "clientsecret", "apikey", "devicecredential");
    /** 稳定遮蔽文本，不泄漏值长度。 */
    private static final String REDACTED = "[REDACTED]";

    /** 规则与不可变版本事实。 */
    private final MessageRuleRepository ruleRepository;
    /** 有界调试事件事实。 */
    private final RuleDebugFactWriter eventRepository;
    /** Worker之前的管理及项目快照检查。 */
    private final RuleManagementAccess managementAccess;
    /** 租户脚本唯一执行端口。 */
    private final ScriptSandbox scriptSandbox;
    /** JSON 结构验证、递归脱敏与确定性序列化。 */
    private final ObjectMapper objectMapper;

    /** @param ruleRepository 规则仓储 @param eventRepository 调试事件仓储
     * @param scriptSandbox 沙箱端口 @param objectMapper JSON 映射器 */
    public MessageRuleDebugService(
            MessageRuleRepository ruleRepository,
            RuleDebugFactWriter eventRepository,
            ScriptSandbox scriptSandbox,
            ObjectMapper objectMapper, RuleManagementAccess managementAccess) {
        this.managementAccess = managementAccess;
        this.ruleRepository = ruleRepository;
        this.eventRepository = eventRepository;
        this.scriptSandbox = scriptSandbox;
        this.objectMapper = objectMapper;
    }

    /**
     * 执行指定不可变版本并写调试事实；没有消息总线、设备写入、Outbox 或规则活动指针副作用。
     *
     * <p>沙箱进程不能参与数据库事务，因此先执行、后用短事务保存结果。保存失败会让调用失败，绝不把仅存在于
     * application 返回值的结果冒充可追溯事件。</p>
     */
    public MessageRuleDebugResult execute(UUID projectId, DebugMessageRuleCommand command) {
        Access access = requireManager(projectId);
        if (command == null || command.ruleId() == null || command.versionId() == null) {
            throw new BusinessException(RuleErrorCode.RULE_NOT_FOUND);
        }
        ruleRepository.find(projectId, command.ruleId())
                .orElseThrow(() -> new BusinessException(RuleErrorCode.RULE_NOT_FOUND));
        MessageRuleVersion version = ruleRepository.findVersion(
                        projectId, command.ruleId(), command.versionId())
                .orElseThrow(() -> new BusinessException(RuleErrorCode.RULE_NOT_FOUND));
        JsonNode input = parseInput(command.inputJson());

        String normalizedInput = input.toString();
        ScriptExecutionResult execution = scriptSandbox.execute(new ScriptExecutionRequest(
                ScriptKind.DEBUG, version.source(), normalizedInput));
        Instant occurredAt = Instant.now();
        UUID eventId = Uuid7.generate();
        String output = execution.outputJson();
        eventRepository.save(new RuleDebugEvent(
                eventId, access.ownerTenantId(), projectId, command.ruleId(), command.versionId(),
                execution.status().name(), execution.errorCode() == null ? "SUCCESS" : execution.errorCode(),
                execution.duration().toMillis(), utf8Length(command.inputJson()), utf8Length(output),
                summarize(input), output == null ? null : summarize(parseOutput(output)),
                access.actorAccountId(), occurredAt, occurredAt.plus(RETENTION)));
        return new MessageRuleDebugResult(
                eventId, execution.status(), output, execution.errorCode(), execution.duration(), occurredAt);
    }

    /** 样例必须是合法 JSON；未进入沙箱的请求不能产生虚假调试事件。 */
    private JsonNode parseInput(String inputJson) {
        if (inputJson == null || inputJson.isBlank()) {
            throw new BusinessException(RuleErrorCode.RULE_DEBUG_INPUT_INVALID);
        }
        try {
            return objectMapper.readTree(inputJson);
        } catch (RuntimeException exception) {
            throw new BusinessException(RuleErrorCode.RULE_DEBUG_INPUT_INVALID);
        }
    }

    /** Worker 成功输出按协议必为 JSON；若该不变量破坏则拒绝保存误导事实。 */
    private JsonNode parseOutput(String outputJson) {
        try {
            return objectMapper.readTree(outputJson);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("沙箱成功输出不是 JSON", exception);
        }
    }

    /** 复制 JSON 树后递归遮蔽敏感字段，再按真实 UTF-8 字节边界截断。 */
    private String summarize(JsonNode source) {
        JsonNode sanitized = source.deepCopy();
        redact(sanitized);
        return truncateUtf8(sanitized.toString(), MAX_SUMMARY_BYTES);
    }

    /** 对对象与数组递归处理，字段名忽略大小写、连字符与下划线。 */
    private static void redact(JsonNode node) {
        if (node instanceof ObjectNode object) {
            object.properties().forEach(entry -> {
                if (isSensitive(entry.getKey())) {
                    object.put(entry.getKey(), REDACTED);
                } else {
                    redact(entry.getValue());
                }
            });
        } else if (node instanceof ArrayNode array) {
            array.forEach(MessageRuleDebugService::redact);
        }
    }

    /** @return 归一化字段是否属于凭据或隐私密钥 */
    private static boolean isSensitive(String key) {
        String normalized = key.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
        return SENSITIVE_KEYS.contains(normalized) || normalized.endsWith("password")
                || normalized.endsWith("token") || normalized.endsWith("secret");
    }

    /** 截断不拆分 Unicode 码点，最终 UTF-8 字节数严格不超过数据库上限。 */
    private static String truncateUtf8(String value, int maximumBytes) {
        if (utf8Length(value) <= maximumBytes) {
            return value;
        }
        StringBuilder result = new StringBuilder();
        int used = 0;
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            String character = new String(Character.toChars(codePoint));
            int bytes = utf8Length(character);
            if (used + bytes > maximumBytes) {
                break;
            }
            result.append(character);
            used += bytes;
            offset += Character.charCount(codePoint);
        }
        return result.toString();
    }

    /** @return null 视为零，否则返回真实 UTF-8 字节数 */
    private static int utf8Length(String value) {
        return value == null ? 0 : value.getBytes(StandardCharsets.UTF_8).length;
    }

    /** 二层授权先于源码读取与沙箱执行，跨租户协作者的归属来自项目 owner tenant。 */
    private Access requireManager(UUID projectId) {
        var identity = managementAccess.debug(projectId);
        return new Access(identity.tenantId(), identity.accountId());
    }

    /** @param ownerTenantId 项目 owner tenant @param actorAccountId 当前行为账号 */
    private record Access(UUID ownerTenantId, UUID actorAccountId) {
    }
}
