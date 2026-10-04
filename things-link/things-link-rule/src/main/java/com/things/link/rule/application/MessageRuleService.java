package com.things.link.rule.application;

import com.things.link.rule.domain.MessageRule;
import com.things.link.rule.domain.MessageRuleRepository;
import com.things.link.rule.domain.MessageRuleVersion;
import com.things.link.rule.domain.RuleErrorCode;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.id.Uuid7;
import com.things.link.support.audit.AuditLogEntry;
import com.things.link.support.audit.AuditLogService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 消息规则管理应用服务。
 *
 * <p>本服务只管理定义、不可变版本与发布指针，供管理HTTP调用，不被上行消费者调用。所有新源码先通过
 * S8-1A 的真实 Worker 解析，再与规则事实和审计日志处于同一事务提交。</p>
 */
@Service
public class MessageRuleService {

    /** 当前管理授权与持续写许可。 */
    private final RuleManagementAccess managementAccess;
    /** 节点用途与预算合同。 */
    private final RuleNodeCatalog nodeCatalog;
    /** 规则域持久化端口。 */
    private final MessageRuleRepository repository;
    /** 唯一允许执行或解析租户 JavaScript 的端口。 */
    private final ScriptSandbox scriptSandbox;
    /** 与规则事实同事务写入的审计服务。 */
    private final AuditLogService auditLogService;
    /** 动作规格序列化为版本事实 jsonb 的映射器。 */
    private final ObjectMapper objectMapper;

    /**
     * @param repository 规则仓储
     * @param scriptSandbox 脚本沙箱
     * @param auditLogService 审计服务
     * @param objectMapper JSON 映射器
     */
    public MessageRuleService(
            MessageRuleRepository repository,
            ScriptSandbox scriptSandbox,
            AuditLogService auditLogService,
            ObjectMapper objectMapper, RuleManagementAccess managementAccess, RuleNodeCatalog nodeCatalog) {
        this.managementAccess = managementAccess;
        this.nodeCatalog = nodeCatalog;
        this.repository = repository;
        this.scriptSandbox = scriptSandbox;
        this.auditLogService = auditLogService;
        this.objectMapper = objectMapper;
    }

    /** 创建 DRAFT 定义与版本号 1 的不可变脚本事实。 */
    @Transactional
    public MessageRuleView create(UUID projectId, CreateMessageRuleCommand command) {
        Access access = requireWriter(projectId);
        validateDefinition(command == null ? null : command.name(),
                command == null ? null : command.description());
        validateScript(command == null ? null : command.source());
        validateActions(command == null ? List.of() : command.actions());
        Instant now = Instant.now();
        UUID ruleId = Uuid7.generate();
        MessageRule rule = new MessageRule(
                ruleId, access.ownerTenantId(), projectId, normalize(command.name()),
                normalizeNullable(command.description()), MessageRule.Status.DRAFT, null, 1L,
                access.actorAccountId(), now, now, null);
        MessageRuleVersion version = version(
                access, projectId, ruleId, 1L, command.source(),
                actionsJson(command == null ? List.of() : command.actions()), now);
        try {
            if (!repository.create(rule, version)) {
                throw new BusinessException(RuleErrorCode.RULE_STATE_CONFLICT);
            }
        } catch (DuplicateKeyException exception) {
            throw new BusinessException(RuleErrorCode.RULE_NAME_CONFLICT);
        }
        audit(access, projectId, ruleId, "message_rule.created", Map.of(
                "versionId", version.id(),
                "versionNumber", version.versionNumber(),
                "sourceSha256", version.sourceSha256()));
        return view(rule);
    }

    /** 修改名称/说明并追加新源码版本，既有版本绝不覆盖。 */
    @Transactional
    public MessageRuleView revise(UUID projectId, UUID ruleId, ReviseMessageRuleCommand command) {
        Access access = requireWriter(projectId);
        validateDefinition(command == null ? null : command.name(),
                command == null ? null : command.description());
        if (command == null || command.expectedVersion() < 1) {
            throw new BusinessException(RuleErrorCode.RULE_INVALID);
        }
        validateScript(command.source());
        validateActions(command.actions());
        MessageRule current = repository.lock(projectId, ruleId)
                .orElseThrow(() -> new BusinessException(RuleErrorCode.RULE_NOT_FOUND));
        if (current.version() != command.expectedVersion()) {
            throw new BusinessException(RuleErrorCode.RULE_STATE_CONFLICT);
        }
        long nextVersion = repository.nextVersionNumber(projectId, ruleId);
        Instant now = Instant.now();
        MessageRule replacement = new MessageRule(
                current.id(), current.tenantId(), current.projectId(), normalize(command.name()),
                normalizeNullable(command.description()), current.status(), current.activeVersionId(),
                current.version(), current.createdBy(), current.createdAt(), now, null);
        MessageRuleVersion version = version(
                access, projectId, ruleId, nextVersion, command.source(), actionsJson(command.actions()), now);
        try {
            if (!repository.revise(replacement, command.expectedVersion(), version)) {
                throw new BusinessException(RuleErrorCode.RULE_STATE_CONFLICT);
            }
        } catch (DuplicateKeyException exception) {
            throw new BusinessException(RuleErrorCode.RULE_NAME_CONFLICT);
        }
        audit(access, projectId, ruleId, "message_rule.version.created", Map.of(
                "versionId", version.id(),
                "versionNumber", version.versionNumber(),
                "sourceSha256", version.sourceSha256()));
        return view(find(projectId, ruleId));
    }

    /** 发布指定历史版本；因此该操作同时支持显式回滚，但不会改写版本事实。 */
    @Transactional
    public MessageRuleView activate(
            UUID projectId, UUID ruleId, UUID versionId, long expectedVersion) {
        Access access = requireWriter(projectId);
        MessageRule current = repository.lock(projectId, ruleId)
                .orElseThrow(() -> new BusinessException(RuleErrorCode.RULE_NOT_FOUND));
        MessageRuleVersion candidate = repository.findVersion(projectId, ruleId, versionId)
                .orElseThrow(() -> new BusinessException(RuleErrorCode.RULE_NOT_FOUND));
        nodeCatalog.historical(candidate.actions(), false);
        if (expectedVersion < 1 || current.version() != expectedVersion
                || !repository.activate(projectId, ruleId, versionId, expectedVersion)) {
            throw new BusinessException(RuleErrorCode.RULE_STATE_CONFLICT);
        }
        audit(access, projectId, ruleId, "message_rule.version.activated", Map.of(
                "versionId", versionId,
                "versionNumber", candidate.versionNumber(),
                "previousVersionId", current.activeVersionId() == null ? "" : current.activeVersionId()));
        return view(find(projectId, ruleId));
    }

    /** 暂停活动规则；活动版本指针保留，便于审计与后续重新发布。 */
    @Transactional
    public MessageRuleView pause(UUID projectId, UUID ruleId, long expectedVersion) {
        Access access = requireWriter(projectId);
        MessageRule current = repository.lock(projectId, ruleId)
                .orElseThrow(() -> new BusinessException(RuleErrorCode.RULE_NOT_FOUND));
        if (expectedVersion < 1 || current.version() != expectedVersion
                || !repository.pause(projectId, ruleId, expectedVersion)) {
            throw new BusinessException(RuleErrorCode.RULE_STATE_CONFLICT);
        }
        audit(access, projectId, ruleId, "message_rule.paused", Map.of(
                "activeVersionId", current.activeVersionId()));
        return view(find(projectId, ruleId));
    }

    /** 软删除规则定义；不可变版本与审计事实继续保留。 */
    @Transactional
    public void delete(UUID projectId, UUID ruleId, long expectedVersion) {
        Access access = requireWriter(projectId);
        MessageRule current = repository.lock(projectId, ruleId)
                .orElseThrow(() -> new BusinessException(RuleErrorCode.RULE_NOT_FOUND));
        if (expectedVersion < 1 || current.version() != expectedVersion
                || !repository.softDelete(projectId, ruleId, expectedVersion)) {
            throw new BusinessException(RuleErrorCode.RULE_STATE_CONFLICT);
        }
        audit(access, projectId, ruleId, "message_rule.deleted", Map.of(
                "lastStatus", current.status().name(),
                "lastVersion", current.version()));
    }

    /** 读取规则定义；管理HTTP与内部调用均按管理权限收紧源码控制面。 */
    @Transactional(readOnly = true)
    public MessageRuleView get(UUID projectId, UUID ruleId) {
        requireManager(projectId);
        return view(find(projectId, ruleId));
    }

    /** 读取完整不可变版本历史；源码只对规则管理员开放。 */
    @Transactional(readOnly = true)
    public List<MessageRuleVersionView> versions(UUID projectId, UUID ruleId) {
        requireManager(projectId);
        find(projectId, ruleId);
        return repository.versions(projectId, ruleId).stream().map(MessageRuleService::versionView).toList();
    }

    /** 有界目录查询，游标只决定位置，不改变授权范围。 */
    @Transactional(readOnly = true)
    public RuleManagementPage<MessageRuleView> list(UUID projectId, String name, String status, String cursor, int limit) {
        requireManager(projectId);
        name = RuleManagementCursor.name(name, false); status = RuleManagementCursor.status(status, false);
        RuleManagementCursor.limit(limit, false);
        String scope = RuleManagementCursor.scope(projectId, "message", name, status);
        var position = RuleManagementCursor.decode(cursor, scope, false, false);
        var rows = repository.search(projectId, name, status, position.time(), position.id(), limit + 1);
        var items = rows.stream().limit(limit).map(MessageRuleService::view).toList();
        String next = rows.size() > limit ? RuleManagementCursor.encode(scope,
                List.of(items.getLast().createdAt().toString(), items.getLast().id().toString())) : null;
        return new RuleManagementPage<>(items, next);
    }
    /** 单版本读取完整动作及源码，归档仍可审阅。 */
    @Transactional(readOnly = true)
    public MessageRuleVersionView version(UUID projectId, UUID ruleId, UUID versionId) {
        requireManager(projectId); find(projectId, ruleId);
        return versionView(repository.findVersion(projectId, ruleId, versionId)
                .orElseThrow(() -> new BusinessException(RuleErrorCode.RULE_NOT_FOUND)));
    }
    /** 有界历史供管理UI使用，兼容旧全量数组入口。 */
    @Transactional(readOnly = true)
    public RuleManagementPage<MessageRuleVersionView> history(UUID projectId, UUID ruleId, String cursor, int limit) {
        requireManager(projectId); find(projectId, ruleId); RuleManagementCursor.limit(limit, false);
        String scope = RuleManagementCursor.scope(projectId, "message-history", ruleId.toString(), "");
        var position = RuleManagementCursor.decode(cursor, scope, true, false);
        var rows = repository.history(projectId, ruleId, position.version(), limit + 1);
        var items = rows.stream().limit(limit).map(MessageRuleService::versionView).toList();
        return new RuleManagementPage<>(items, rows.size() > limit
                ? RuleManagementCursor.encode(scope, items.getLast().versionNumber()) : null);
    }

    /** 新源码通过沙箱构造函数解析，但不调用租户函数正文。 */
    private void validateScript(String source) {
        if (source == null || source.isBlank()) {
            throw new BusinessException(RuleErrorCode.RULE_SCRIPT_INVALID);
        }
        ScriptValidationResult result;
        try {
            result = scriptSandbox.validate(ScriptKind.RULE, source);
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(RuleErrorCode.RULE_SCRIPT_INVALID);
        }
        if (!result.valid()) {
            throw new BusinessException(RuleErrorCode.RULE_SCRIPT_INVALID);
        }
    }

    /** 每个动作在写入版本事实前必须通过确定性引擎校验，非法类型或配置立即 fail-closed。 */
    private void validateActions(List<ActionSpec> actions) {
        List<ActionSpec> values = actions == null ? List.of() : actions;
        nodeCatalog.budget(List.of(), values, false);
        for (ActionSpec action : values) {
            if (action == null) throw new BusinessException(RuleErrorCode.RULE_ACTION_INVALID);
            nodeCatalog.validate(action.nodeType(), action.config(), false);
        }
    }

    /** @return 动作规格序列化为版本事实 jsonb 数组 */
    private JsonNode actionsJson(List<ActionSpec> actions) {
        return objectMapper.valueToTree(actions);
    }

    /** @return 当前有效规则或项目内统一 404 */
    private MessageRule find(UUID projectId, UUID ruleId) {
        if (ruleId == null) {
            throw new BusinessException(RuleErrorCode.RULE_NOT_FOUND);
        }
        return repository.find(projectId, ruleId)
                .orElseThrow(() -> new BusinessException(RuleErrorCode.RULE_NOT_FOUND));
    }

    /**
     * 二层授权并解析项目 owner tenant。
     *
     * <p>跨租户协作者的 JWT tenant 是自己的租户，规则归属必须来自 project 公开端口，不能直接使用调用者 tenant。</p>
     */
    private Access requireManager(UUID projectId) {
        var identity = managementAccess.read(projectId, false);
        return new Access(identity.tenantId(), identity.accountId());
    }
    /** 原事务取得项目许可后才进入定义行锁。 */
    private Access requireWriter(UUID projectId) {
        var identity = managementAccess.write(projectId, false);
        return new Access(identity.tenantId(), identity.accountId());
    }

    /** 名称与说明先做应用层快速失败，数据库长度约束仍是最终防线。 */
    private static void validateDefinition(String name, String description) {
        if (name == null || name.isBlank() || name.strip().length() > 128
                || (description != null && description.strip().length() > 512)) {
            throw new BusinessException(RuleErrorCode.RULE_INVALID);
        }
    }

    /** @return 只追加的版本事实 */
    private static MessageRuleVersion version(
            Access access, UUID projectId, UUID ruleId, long number, String source,
            JsonNode actions, Instant now) {
        return new MessageRuleVersion(
                Uuid7.generate(), access.ownerTenantId(), projectId, ruleId, number,
                source, sha256(source), actions, access.actorAccountId(), now);
    }

    /** 源码摘要只用于版本比对和审计，不替代不可变源码事实。 */
    private static String sha256(String source) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(source.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JDK 缺少强制提供的 SHA-256", exception);
        }
    }

    /** @return 去除名称两端空白 */
    private static String normalize(String value) {
        return value.strip();
    }

    /** @return 空说明归一为 null，避免唯一语义出现空串分支 */
    private static String normalizeNullable(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    /** 规则源码变更只记录摘要和版本 ID，禁止把源码正文写进 sys_audit_log。 */
    private void audit(
            Access access, UUID projectId, UUID ruleId, String action, Map<String, ?> details) {
        auditLogService.record(new AuditLogEntry(
                access.ownerTenantId(), projectId, access.actorAccountId(),
                "message_rule", ruleId, action, details));
    }

    /** @return application 层规则投影 */
    private static MessageRuleView view(MessageRule rule) {
        return new MessageRuleView(
                rule.id(), rule.name(), rule.description(), rule.status().name(),
                rule.activeVersionId(), rule.version(), rule.createdAt(), rule.updatedAt());
    }

    /** @return application 层版本投影 */
    private static MessageRuleVersionView versionView(MessageRuleVersion version) {
        return new MessageRuleVersionView(
                version.id(), version.versionNumber(), version.source(), version.sourceSha256(),
                version.createdBy(), version.createdAt(), version.actions());
    }

    /** @param ownerTenantId 项目 owner tenant @param actorAccountId 当前行为账号 */
    private record Access(UUID ownerTenantId, UUID actorAccountId) {
    }
}
