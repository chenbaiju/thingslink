package com.things.link.rule.application;

import com.things.link.device.application.DeviceIngestionService;
import com.things.link.rule.application.engine.RuleMessage;
import com.things.link.rule.application.outbox.RuleActionDispatcher;
import com.things.link.rule.application.outbox.RuleActionProvenance;
import com.things.link.rule.domain.RuleErrorCode;
import com.things.link.rule.domain.RuleScene;
import com.things.link.rule.domain.RuleSceneExecution;
import com.things.link.rule.domain.RuleSceneRepository;
import com.things.link.rule.domain.RuleSceneVersion;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.id.Uuid7;
import com.things.link.support.audit.AuditLogEntry;
import com.things.link.support.audit.AuditLogService;
import com.things.link.support.trace.TraceContext;
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
import java.util.Optional;
import java.util.UUID;

/**
 * S9-4 手动场景应用服务：管理定义、不可变版本与活动版本指针，并提供一键执行的幂等闭环。
 *
 * <p>执行是同步的（条件 + 动作 + 执行事实 + 动作关联事实 + 全部 Outbox 同事务），不复用 Kafka 幂等回执、
 * 执行日志、重试或 DLQ。DISPATCHED 只表示副作用已可靠写入 Outbox，不代表已送达（见 ADR 0030）。</p>
 */
@Service
public class RuleSceneService {

    /** 场景输入载荷 JSON 字节数上限，防止超大 payload 进入规则消息。 */
    private static final int MAX_SCENE_PAYLOAD_BYTES = 16384;

    /** 手动场景规则消息类型；服务端写入，客户端不可伪造。 */
    private static final String MANUAL_SCENE_MESSAGE_TYPE = "MANUAL_SCENE";

    /** 场景域持久化端口。 */
    private final RuleSceneRepository repository;
    /** 管理读取与原事务持续写入许可。 */
    private final RuleManagementAccess managementAccess;
    /** 目标设备属主校验端口，返回可信归属但本服务只借其确权。 */
    private final DeviceIngestionService deviceIngestionService;
    /** 节点用途与新编辑预算的共享合同。 */
    private final RuleNodeCatalog nodeCatalog;
    /** 手动场景确定性执行处理器。 */
    private final RuleSceneExecutionProcessor processor;
    /** 中性动作意图派发层。 */
    private final RuleActionDispatcher dispatcher;
    /** 与执行事实同事务写入的审计服务。 */
    private final AuditLogService auditLogService;
    /** 条件/动作规格序列化为版本事实 jsonb 的映射器。 */
    private final ObjectMapper objectMapper;

    /** 装配本域仓储、跨域公开端口、用途目录与事务动作派发器。 */
    public RuleSceneService(RuleSceneRepository repository, RuleManagementAccess managementAccess,
            DeviceIngestionService deviceIngestionService, RuleNodeCatalog nodeCatalog,
            RuleSceneExecutionProcessor processor, RuleActionDispatcher dispatcher,
            AuditLogService auditLogService, ObjectMapper objectMapper) {
        this.repository = repository;
        this.managementAccess = managementAccess;
        this.deviceIngestionService = deviceIngestionService;
        this.nodeCatalog = nodeCatalog;
        this.processor = processor;
        this.dispatcher = dispatcher;
        this.auditLogService = auditLogService;
        this.objectMapper = objectMapper;
    }

    /** 创建 DRAFT 定义与版本号 1 的不可变条件/动作事实。 */
    @Transactional
    public RuleSceneView create(UUID projectId, CreateSceneCommand command) {
        Access access = requireWriter(projectId);
        validateDefinition(command == null ? null : command.name(),
                command == null ? null : command.description());
        validateConditions(command == null ? List.of() : command.conditions());
        validateActions(command == null ? List.of() : command.actions());
        nodeCatalog.budget(command.conditions() == null ? List.of() : command.conditions(), command.actions(), true);
        Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        UUID sceneId = Uuid7.generate();
        RuleScene scene = new RuleScene(
                sceneId, access.ownerTenantId(), projectId, normalize(command.name()),
                normalizeNullable(command.description()), RuleScene.Status.DRAFT, null, 1L,
                access.actorAccountId(), now, now, null);
        RuleSceneVersion version = version(access, projectId, sceneId, 1L,
                conditionsJson(command == null ? List.of() : command.conditions()),
                actionsJson(command == null ? List.of() : command.actions()), now);
        try {
            if (!repository.create(scene, version)) {
                throw new BusinessException(RuleErrorCode.SCENE_STATE_CONFLICT);
            }
        } catch (DuplicateKeyException exception) {
            throw new BusinessException(RuleErrorCode.SCENE_NAME_CONFLICT);
        }
        audit(access, projectId, sceneId, "rule_scene.created", Map.of(
                "versionId", version.id(), "versionNumber", version.versionNumber()));
        return view(scene);
    }

    /** 修改名称/说明并追加新版本，既有版本绝不覆盖。 */
    @Transactional
    public RuleSceneView revise(UUID projectId, UUID sceneId, ReviseSceneCommand command) {
        Access access = requireWriter(projectId);
        validateDefinition(command == null ? null : command.name(),
                command == null ? null : command.description());
        if (command == null || command.expectedVersion() < 1) {
            throw new BusinessException(RuleErrorCode.SCENE_INVALID);
        }
        validateConditions(command.conditions());
        validateActions(command.actions());
        nodeCatalog.budget(command.conditions() == null ? List.of() : command.conditions(), command.actions(), true);
        RuleScene current = lock(projectId, sceneId);
        if (current.version() != command.expectedVersion()) {
            throw new BusinessException(RuleErrorCode.SCENE_STATE_CONFLICT);
        }
        long nextVersion = repository.nextVersionNumber(projectId, sceneId);
        Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        RuleScene replacement = new RuleScene(
                current.id(), current.tenantId(), current.projectId(), normalize(command.name()),
                normalizeNullable(command.description()), current.status(), current.activeVersionId(),
                current.version(), current.createdBy(), current.createdAt(), now, null);
        RuleSceneVersion version = version(access, projectId, sceneId, nextVersion,
                conditionsJson(command.conditions()), actionsJson(command.actions()), now);
        try {
            if (!repository.revise(replacement, command.expectedVersion(), version)) {
                throw new BusinessException(RuleErrorCode.SCENE_STATE_CONFLICT);
            }
        } catch (DuplicateKeyException exception) {
            throw new BusinessException(RuleErrorCode.SCENE_NAME_CONFLICT);
        }
        audit(access, projectId, sceneId, "rule_scene.version.created", Map.of(
                "versionId", version.id(), "versionNumber", version.versionNumber()));
        return view(find(projectId, sceneId));
    }

    /** 发布指定历史版本为活动版本；因此该操作同时支持显式回滚，但不改写版本事实。 */
    @Transactional
    public RuleSceneView activate(UUID projectId, UUID sceneId, UUID versionId, long expectedVersion) {
        Access access = requireWriter(projectId);
        RuleScene current = lock(projectId, sceneId);
        RuleSceneVersion candidate = repository.findVersion(projectId, sceneId, versionId)
                .orElseThrow(() -> new BusinessException(RuleErrorCode.SCENE_NOT_FOUND));
        nodeCatalog.historical(candidate.conditions(), true);
        nodeCatalog.historical(candidate.actions(), false);
        if (candidate.actions().isEmpty()) throw new BusinessException(RuleErrorCode.SCENE_INVALID);
        if (expectedVersion < 1 || current.version() != expectedVersion
                || !repository.activate(projectId, sceneId, versionId, expectedVersion)) {
            throw new BusinessException(RuleErrorCode.SCENE_STATE_CONFLICT);
        }
        audit(access, projectId, sceneId, "rule_scene.version.activated", Map.of(
                "versionId", versionId,
                "versionNumber", candidate.versionNumber(),
                "previousVersionId", current.activeVersionId() == null ? "" : current.activeVersionId()));
        return view(find(projectId, sceneId));
    }

    /** 软删除场景定义；不可变版本与执行事实继续保留。 */
    @Transactional
    public void delete(UUID projectId, UUID sceneId, long expectedVersion) {
        Access access = requireWriter(projectId);
        RuleScene current = lock(projectId, sceneId);
        if (expectedVersion < 1 || current.version() != expectedVersion
                || !repository.softDelete(projectId, sceneId, expectedVersion)) {
            throw new BusinessException(RuleErrorCode.SCENE_STATE_CONFLICT);
        }
        audit(access, projectId, sceneId, "rule_scene.deleted", Map.of(
                "lastStatus", current.status().name(), "lastVersion", current.version()));
    }

    /** 读取场景定义；场景是控制面资源，按管理权限收紧。 */
    @Transactional(readOnly = true)
    public RuleSceneView get(UUID projectId, UUID sceneId) {
        requireManager(projectId);
        return view(find(projectId, sceneId));
    }

    /** 读取完整不可变版本历史。 */
    @Transactional(readOnly = true)
    public List<RuleSceneVersionView> versions(UUID projectId, UUID sceneId) {
        requireManager(projectId);
        find(projectId, sceneId);
        return repository.versions(projectId, sceneId).stream().map(RuleSceneService::versionView).toList();
    }

    /** 有界目录查询，游标只决定位置，不改变授权范围。 */
    @Transactional(readOnly = true)
    public RuleManagementPage<RuleSceneView> list(UUID projectId, String name, String status, String cursor, int limit) {
        requireManager(projectId);
        name = RuleManagementCursor.name(name, true); status = RuleManagementCursor.status(status, true);
        RuleManagementCursor.limit(limit, true);
        String scope = RuleManagementCursor.scope(projectId, "scene", name, status);
        var position = RuleManagementCursor.decode(cursor, scope, false, true);
        var rows = repository.search(projectId, name, status, position.time(), position.id(), limit + 1);
        var items = rows.stream().limit(limit).map(RuleSceneService::view).toList();
        String next = rows.size() > limit ? RuleManagementCursor.encode(scope,
                List.of(items.getLast().createdAt().toString(), items.getLast().id().toString())) : null;
        return new RuleManagementPage<>(items, next);
    }
    /** 单版本读取完整条件及动作，归档仍可审阅。 */
    @Transactional(readOnly = true)
    public RuleSceneVersionView version(UUID projectId, UUID sceneId, UUID versionId) {
        requireManager(projectId); find(projectId, sceneId);
        return versionView(repository.findVersion(projectId, sceneId, versionId)
                .orElseThrow(() -> new BusinessException(RuleErrorCode.SCENE_NOT_FOUND)));
    }
    /** 有界历史供管理UI使用，兼容旧全量数组入口。 */
    @Transactional(readOnly = true)
    public RuleManagementPage<RuleSceneVersionView> history(UUID projectId, UUID sceneId, String cursor, int limit) {
        requireManager(projectId); find(projectId, sceneId); RuleManagementCursor.limit(limit, true);
        String scope = RuleManagementCursor.scope(projectId, "scene-history", sceneId.toString(), "");
        var position = RuleManagementCursor.decode(cursor, scope, true, true);
        var rows = repository.history(projectId, sceneId, position.version(), limit + 1);
        var items = rows.stream().limit(limit).map(RuleSceneService::versionView).toList();
        return new RuleManagementPage<>(items, rows.size() > limit
                ? RuleManagementCursor.encode(scope, items.getLast().versionNumber()) : null);
    }

    /** 暂停仅影响之后的新执行；旧幂等键仍返回原事实。 */
    @Transactional
    public RuleSceneView pause(UUID projectId, UUID sceneId, long expectedVersion) {
        Access access = requireWriter(projectId);
        RuleScene current = lock(projectId, sceneId);
        if (expectedVersion < 1 || current.version() != expectedVersion
                || !repository.pause(projectId, sceneId, expectedVersion))
            throw new BusinessException(RuleErrorCode.SCENE_STATE_CONFLICT);
        audit(access, projectId, sceneId, "rule_scene.paused", Map.of("version", expectedVersion));
        return view(find(projectId, sceneId));
    }

    /**
     * 一键执行活动版本：同幂等键同内容重试返回既有事实；同键不同内容拒绝；重新点击使用新键得到新执行。
     *
     * @param projectId 项目隔离轴
     * @param sceneId 场景 ID
     * @param idempotencyKey 客户端必填的 Idempotency-Key 头原值
     * @param command 目标设备与输入载荷
     * @return 封闭终态执行事实
     */
    @Transactional
    public RuleSceneExecutionView execute(
            UUID projectId, UUID sceneId, String idempotencyKey, ExecuteSceneCommand command) {
        Access access = requireWriter(projectId);
        RuleScene scene = lock(projectId, sceneId);
        if (command == null || command.deviceId() == null
                || idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 128) {
            throw new BusinessException(RuleErrorCode.SCENE_EXECUTION_INPUT_INVALID);
        }
        deviceIngestionService.requireDeviceOwner(projectId, command.deviceId());
        JsonNode payload = normalizePayload(command.payload());

        String digest = digest(command.deviceId(), payload);
        Optional<RuleSceneExecution> existing =
                repository.findExecutionByKey(projectId, sceneId, idempotencyKey);
        if (existing.isPresent()) {
            RuleSceneExecution replay = existing.get();
            if (!replay.requestDigest().equals(digest)) {
                throw new BusinessException(RuleErrorCode.SCENE_STATE_CONFLICT);
            }
            return executionView(replay);
        }

        requireExecutable(scene);
        RuleSceneVersion version = repository.findVersion(projectId, sceneId, scene.activeVersionId())
                .orElseThrow(() -> new BusinessException(RuleErrorCode.SCENE_STATE_CONFLICT));
        Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        String traceId = TraceContext.resolve(TraceContext.current());
        UUID executionId = Uuid7.generate();
        RuleMessage message = new RuleMessage(executionId, access.ownerTenantId(), projectId,
                command.deviceId(), traceId, now, MANUAL_SCENE_MESSAGE_TYPE, payload, Map.of());
        RuleSceneExecutionProcessor.SceneExecutionOutcome outcome = processor.process(
                message, scene.id(), version.id(), now, version.conditions(), version.actions());

        RuleSceneExecution execution = new RuleSceneExecution(
                executionId, access.ownerTenantId(), projectId, scene.id(), version.id(),
                idempotencyKey, digest, command.deviceId(), outcome.status(),
                RuleSceneExecution.Trigger.MANUAL, access.actorAccountId(), traceId, now, now, now);
        if (!repository.insertExecution(execution)) {
            // 并发同键已被抢先写入；回滚本事务，客户端重试命中上面 existing 分支返回赢家事实。
            throw new BusinessException(RuleErrorCode.SCENE_STATE_CONFLICT);
        }
        if (outcome.status() == RuleSceneExecution.Status.DISPATCHED) {
            RuleActionProvenance provenance = RuleActionProvenance.scene(
                    access.ownerTenantId(), projectId, command.deviceId(), traceId, now,
                    access.actorAccountId(), scene.id(), version.id(), executionId);
            dispatcher.dispatch(provenance, outcome.intents());
        }
        audit(access, projectId, sceneId, "rule_scene.executed", Map.of(
                "executionId", executionId, "status", outcome.status().name(),
                "sceneVersionId", version.id()));
        return executionView(execution);
    }

    /** 场景必须是 ACTIVE 且已发布活动版本才能一键执行。 */
    private static void requireExecutable(RuleScene scene) {
        if (scene.status() != RuleScene.Status.ACTIVE || scene.activeVersionId() == null) {
            throw new BusinessException(RuleErrorCode.SCENE_NOT_EXECUTABLE);
        }
    }

    /** 每个条件在写入版本事实前必须通过确定性引擎校验，非法类型或配置立即 fail-closed。 */
    private void validateConditions(List<ConditionSpec> conditions) {
        if (conditions == null || conditions.isEmpty()) {
            return;
        }
        for (ConditionSpec condition : conditions) {
            if (condition == null) throw new BusinessException(RuleErrorCode.SCENE_CONDITION_INVALID);
            nodeCatalog.validate(condition.nodeType(), condition.config(), true);
        }
    }

    /** 手动场景必须至少一个动作，否则一键执行没有任何副作用；每个动作复用规则动作节点校验。 */
    private void validateActions(List<ActionSpec> actions) {
        if (actions == null || actions.isEmpty()) {
            throw new BusinessException(RuleErrorCode.SCENE_INVALID);
        }
        for (ActionSpec action : actions) {
            if (action == null) throw new BusinessException(RuleErrorCode.RULE_ACTION_INVALID);
            nodeCatalog.validate(action.nodeType(), action.config(), false);
        }
    }

    /** @return 条件规格序列化为版本事实 jsonb 数组 */
    private JsonNode conditionsJson(List<ConditionSpec> conditions) {
        return objectMapper.valueToTree(conditions == null ? List.of() : conditions);
    }

    /** @return 动作规格序列化为版本事实 jsonb 数组 */
    private JsonNode actionsJson(List<ActionSpec> actions) {
        return objectMapper.valueToTree(actions == null ? List.of() : actions);
    }

    /** @return 当前有效场景或项目内统一 404 */
    private RuleScene find(UUID projectId, UUID sceneId) {
        if (sceneId == null) {
            throw new BusinessException(RuleErrorCode.SCENE_NOT_FOUND);
        }
        return repository.find(projectId, sceneId)
                .orElseThrow(() -> new BusinessException(RuleErrorCode.SCENE_NOT_FOUND));
    }

    /** 执行载荷必须为 JSON 对象且大小受限；缺省为空对象。 */
    private JsonNode normalizePayload(JsonNode payload) {
        JsonNode normalized = payload == null ? objectMapper.createObjectNode() : payload;
        if (!normalized.isObject()) {
            throw new BusinessException(RuleErrorCode.SCENE_EXECUTION_INPUT_INVALID);
        }
        if (normalized.toString().getBytes(StandardCharsets.UTF_8).length > MAX_SCENE_PAYLOAD_BYTES) {
            throw new BusinessException(RuleErrorCode.SCENE_EXECUTION_INPUT_INVALID);
        }
        return normalized;
    }

    /** 持久项目归属及管理角色校验，允许归档读取。 */
    private Access requireManager(UUID projectId) {
        var identity = managementAccess.read(projectId, true);
        return new Access(identity.tenantId(), identity.accountId());
    }
    /** 持续项目许可先于场景行锁。 */
    private Access requireWriter(UUID projectId) {
        var identity = managementAccess.write(projectId, true);
        return new Access(identity.tenantId(), identity.accountId());
    }
    /** 场景锁串行化执行与暂停/发布/删除，不锁不可变历史。 */
    private RuleScene lock(UUID projectId, UUID sceneId) {
        return repository.lock(projectId, sceneId)
                .orElseThrow(() -> new BusinessException(RuleErrorCode.SCENE_NOT_FOUND));
    }

    /** 名称与说明先做应用层快速失败，数据库长度约束仍是最终防线。 */
    private static void validateDefinition(String name, String description) {
        if (name == null || name.isBlank() || name.strip().length() > 128
                || (description != null && description.strip().length() > 512)) {
            throw new BusinessException(RuleErrorCode.SCENE_INVALID);
        }
    }

    /** @return 只追加的版本事实 */
    private static RuleSceneVersion version(
            Access access, UUID projectId, UUID sceneId, long number,
            JsonNode conditions, JsonNode actions, Instant now) {
        return new RuleSceneVersion(
                Uuid7.generate(), access.ownerTenantId(), projectId, sceneId, number,
                conditions, actions, access.actorAccountId(), now);
    }

    /** 请求摘要只用于幂等键内容比对，不保存 payload 本体。 */
    private static String digest(UUID deviceId, JsonNode payload) {
        return sha256Hex(deviceId + "\n" + payload);
    }

    /** @return 小写十六进制 SHA-256，满足执行事实 64 位十六进制摘要约束 */
    private static String sha256Hex(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
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

    /** 场景配置变更只记录摘要和版本 ID，禁止把条件/动作正文写进 sys_audit_log。 */
    private void audit(
            Access access, UUID projectId, UUID sceneId, String action, Map<String, ?> details) {
        auditLogService.record(new AuditLogEntry(
                access.ownerTenantId(), projectId, access.actorAccountId(),
                "rule_scene", sceneId, action, details));
    }

    /** @return application 层场景投影 */
    private static RuleSceneView view(RuleScene scene) {
        return new RuleSceneView(
                scene.id(), scene.name(), scene.description(), scene.status().name(),
                scene.activeVersionId(), scene.version(), scene.createdAt(), scene.updatedAt());
    }

    /** @return application 层版本投影 */
    private static RuleSceneVersionView versionView(RuleSceneVersion version) {
        return new RuleSceneVersionView(
                version.id(), version.versionNumber(), version.conditions(), version.actions(),
                version.createdBy(), version.createdAt());
    }

    /** @return application 层执行事实投影 */
    private static RuleSceneExecutionView executionView(RuleSceneExecution execution) {
        return new RuleSceneExecutionView(
                execution.id(), execution.sceneId(), execution.sceneVersionId(),
                execution.idempotencyKey(), execution.deviceId(), execution.status().name(),
                execution.trigger().name(), execution.operatorAccountId(), execution.traceId(),
                execution.occurredAt(), execution.createdAt());
    }

    /** @param ownerTenantId 项目 owner tenant @param actorAccountId 当前行为账号 */
    private record Access(UUID ownerTenantId, UUID actorAccountId) {
    }
}
