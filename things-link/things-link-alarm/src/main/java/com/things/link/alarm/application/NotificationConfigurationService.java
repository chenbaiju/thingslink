package com.things.link.alarm.application;

import com.things.link.alarm.domain.AlarmErrorCode;
import com.things.link.alarm.domain.AlarmNotificationBinding;
import com.things.link.alarm.domain.AlarmNotificationGroup;
import com.things.link.alarm.domain.AlarmNotificationRecipient;
import com.things.link.alarm.domain.AlarmNotificationRepository;
import com.things.link.alarm.domain.AlarmNotificationTemplate;
import com.things.link.alarm.domain.AlarmRuleRepository;
import com.things.link.alarm.domain.NotificationChannel;
import com.things.link.project.application.ProjectService;
import com.things.link.project.application.ProjectLifecycleAccessService;
import com.things.link.shared.authz.ProjectRole;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.net.SafeOutboundHost;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.page.CursorPage;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** 通知组、收件人、模板和规则路由的配置用例；发送行为不在本服务内。 */
@Service
public class NotificationConfigurationService {
    /** 固定模板变量集合；避免模板成为任意表达式入口。 */
    private static final Set<String> TEMPLATE_VARIABLES =
            Set.of(
                    "${alarm.type}",
                    "${alarm.severity}",
                    "${alarm.value}",
                    "${alarm.activatedAt}",
                    "${alarm.instanceId}");

    /** 通知配置仓储。 */
    private final AlarmNotificationRepository repository;

    /** 项目成员与角色公开端口。 */
    private final ProjectService projectService;

    /** 原事务项目持续写许可。 */
    private final ProjectLifecycleAccessService lifecycle;

    /** 告警规则端口，仅查询本域事实以把缺失规则统一映射为 404。 */
    private final AlarmRuleRepository ruleRepository;

    /**
     * @param repository 通知端口 @param projectService 项目授权端口 @param lifecycle 项目持续写许可 @param ruleRepository 规则查询端口
     */
    public NotificationConfigurationService(
            AlarmNotificationRepository repository,
            ProjectService projectService,
            ProjectLifecycleAccessService lifecycle,
            AlarmRuleRepository ruleRepository) {
        this.repository = repository;
        this.projectService = projectService;
        this.lifecycle = lifecycle;
        this.ruleRepository = ruleRepository;
    }

    /**
     * @return 通知组页
     */
    @Transactional(readOnly = true)
    public CursorPage<AlarmNotificationGroup> pageGroups(UUID projectId, String cursor, int limit) {
        requireRead(projectId);
        return repository.pageGroups(projectId, cursor, pageSize(limit));
    }

    /**
     * @return 单个通知组
     */
    @Transactional(readOnly = true)
    public AlarmNotificationGroup getGroup(UUID projectId, UUID id) {
        requireRead(projectId);
        return group(projectId, id);
    }

    /**
     * @return 已创建通知组
     */
    @Transactional
    public AlarmNotificationGroup createGroup(UUID projectId, String name, boolean enabled) {
        UUID ownerTenant = requireWrite(projectId);
        Instant now = Instant.now();
        AlarmNotificationGroup value =
                new AlarmNotificationGroup(
                        Uuid7.generate(),
                        ownerTenant,
                        projectId,
                        required(name, 128),
                        enabled,
                        0,
                        now,
                        now,
                        null);
        create(() -> repository.createGroup(value));
        return value;
    }

    /**
     * @return 已更新通知组
     */
    @Transactional
    public AlarmNotificationGroup updateGroup(
            UUID projectId, UUID id, String name, boolean enabled, int version) {
        requireWrite(projectId);
        AlarmNotificationGroup old = group(projectId, id);
        if (old.version() != version) throw conflict();
        AlarmNotificationGroup next =
                new AlarmNotificationGroup(
                        old.id(),
                        old.tenantId(),
                        old.projectId(),
                        required(name, 128),
                        enabled,
                        version,
                        old.createdAt(),
                        Instant.now(),
                        null);
        if (!write(() -> repository.updateGroup(next))) throw conflict();
        return repository.findGroup(projectId, id).orElseThrow(this::notFound);
    }

    /** 删除通知组。 */
    @Transactional
    public void deleteGroup(UUID projectId, UUID id, int version) {
        requireWrite(projectId);
        group(projectId, id);
        if (!write(() -> repository.deleteGroup(projectId, id, version))) throw conflict();
    }

    /**
     * @return 收件人
     */
    @Transactional(readOnly = true)
    public List<AlarmNotificationRecipient> recipients(UUID projectId, UUID groupId) {
        requireRead(projectId);
        group(projectId, groupId);
        return repository.listRecipients(projectId, groupId);
    }

    /**
     * @return 已创建收件人
     */
    @Transactional
    public AlarmNotificationRecipient createRecipient(
            UUID projectId,
            UUID groupId,
            NotificationChannel channel,
            String target,
            boolean enabled) {
        UUID ownerTenant = requireWrite(projectId);
        group(projectId, groupId);
        Instant now = Instant.now();
        AlarmNotificationRecipient v =
                new AlarmNotificationRecipient(
                        Uuid7.generate(),
                        ownerTenant,
                        projectId,
                        groupId,
                        channel,
                        validatedTarget(channel, target),
                        enabled,
                        0,
                        now,
                        now,
                        null);
        create(() -> repository.createRecipient(v));
        return v;
    }

    /**
     * @return 已更新收件人
     */
    @Transactional
    public AlarmNotificationRecipient updateRecipient(
            UUID projectId,
            UUID id,
            NotificationChannel channel,
            String target,
            boolean enabled,
            int version) {
        requireWrite(projectId);
        AlarmNotificationRecipient old = recipient(projectId, id);
        if (old.version() != version) throw conflict();
        AlarmNotificationRecipient v =
                new AlarmNotificationRecipient(
                        old.id(),
                        old.tenantId(),
                        old.projectId(),
                        old.groupId(),
                        channel,
                        validatedTarget(channel, target),
                        enabled,
                        version,
                        old.createdAt(),
                        Instant.now(),
                        null);
        if (!write(() -> repository.updateRecipient(v))) throw conflict();
        return repository.findRecipient(projectId, id).orElseThrow(this::notFound);
    }

    /** 删除收件人。 */
    @Transactional
    public void deleteRecipient(UUID projectId, UUID id, int version) {
        requireWrite(projectId);
        recipient(projectId, id);
        if (!write(() -> repository.deleteRecipient(projectId, id, version))) throw conflict();
    }

    /**
     * @return 模板页
     */
    @Transactional(readOnly = true)
    public CursorPage<AlarmNotificationTemplate> pageTemplates(
            UUID projectId, String cursor, int limit) {
        requireRead(projectId);
        return repository.pageTemplates(projectId, cursor, pageSize(limit));
    }

    /**
     * @return 单个模板
     */
    @Transactional(readOnly = true)
    public AlarmNotificationTemplate getTemplate(UUID projectId, UUID id) {
        requireRead(projectId);
        return template(projectId, id);
    }

    /**
     * @return 已创建模板
     */
    @Transactional
    public AlarmNotificationTemplate createTemplate(
            UUID projectId,
            String name,
            NotificationChannel channel,
            String subject,
            String body,
            boolean enabled) {
        UUID ownerTenant = requireWrite(projectId);
        validateTemplate(channel, subject, body);
        Instant now = Instant.now();
        AlarmNotificationTemplate v =
                new AlarmNotificationTemplate(
                        Uuid7.generate(),
                        ownerTenant,
                        projectId,
                        required(name, 128),
                        channel,
                        subject,
                        body,
                        enabled,
                        0,
                        now,
                        now,
                        null);
        create(() -> repository.createTemplate(v));
        return v;
    }

    /**
     * @return 已更新模板
     */
    @Transactional
    public AlarmNotificationTemplate updateTemplate(
            UUID projectId,
            UUID id,
            String name,
            NotificationChannel channel,
            String subject,
            String body,
            boolean enabled,
            int version) {
        requireWrite(projectId);
        AlarmNotificationTemplate old = template(projectId, id);
        if (old.version() != version) throw conflict();
        validateTemplate(channel, subject, body);
        AlarmNotificationTemplate v =
                new AlarmNotificationTemplate(
                        old.id(),
                        old.tenantId(),
                        old.projectId(),
                        required(name, 128),
                        channel,
                        subject,
                        body,
                        enabled,
                        version,
                        old.createdAt(),
                        Instant.now(),
                        null);
        if (!write(() -> repository.updateTemplate(v))) throw conflict();
        return repository.findTemplate(projectId, id).orElseThrow(this::notFound);
    }

    /** 删除模板。 */
    @Transactional
    public void deleteTemplate(UUID projectId, UUID id, int version) {
        requireWrite(projectId);
        template(projectId, id);
        if (!write(() -> repository.deleteTemplate(projectId, id, version))) throw conflict();
    }

    /**
     * @return 规则的通知绑定
     */
    @Transactional(readOnly = true)
    public List<AlarmNotificationBinding> bindings(UUID projectId, UUID ruleId) {
        requireRead(projectId);
        return repository.listBindings(projectId, ruleId);
    }

    /**
     * @return 已创建绑定
     */
    @Transactional
    public AlarmNotificationBinding createBinding(
            UUID projectId,
            UUID ruleId,
            UUID groupId,
            UUID templateId,
            NotificationChannel channel,
            boolean enabled) {
        UUID ownerTenant = requireWrite(projectId);
        rule(projectId, ruleId);
        group(projectId, groupId);
        AlarmNotificationTemplate t = template(projectId, templateId);
        if (t.channel() != channel) throw invalid();
        Instant now = Instant.now();
        AlarmNotificationBinding v =
                new AlarmNotificationBinding(
                        Uuid7.generate(),
                        ownerTenant,
                        projectId,
                        ruleId,
                        groupId,
                        templateId,
                        channel,
                        enabled,
                        0,
                        now,
                        now,
                        null);
        create(() -> repository.createBinding(v));
        return v;
    }

    /**
     * @return 已更新绑定
     */
    @Transactional
    public AlarmNotificationBinding updateBinding(
            UUID projectId,
            UUID id,
            UUID groupId,
            UUID templateId,
            NotificationChannel channel,
            boolean enabled,
            int version) {
        requireWrite(projectId);
        AlarmNotificationBinding old = binding(projectId, id);
        if (old.version() != version) throw conflict();
        rule(projectId, old.ruleId());
        group(projectId, groupId);
        AlarmNotificationTemplate t = template(projectId, templateId);
        if (t.channel() != channel) throw invalid();
        AlarmNotificationBinding v =
                new AlarmNotificationBinding(
                        old.id(),
                        old.tenantId(),
                        old.projectId(),
                        old.ruleId(),
                        groupId,
                        templateId,
                        channel,
                        enabled,
                        version,
                        old.createdAt(),
                        Instant.now(),
                        null);
        if (!write(() -> repository.updateBinding(v))) throw conflict();
        return repository.findBinding(projectId, id).orElseThrow(this::notFound);
    }

    /** 删除绑定。 */
    @Transactional
    public void deleteBinding(UUID projectId, UUID id, int version) {
        requireWrite(projectId);
        binding(projectId, id);
        if (!write(() -> repository.deleteBinding(projectId, id, version))) throw conflict();
    }

    /**
     * @return 项目投递意图页
     */
    @Transactional(readOnly = true)
    public CursorPage<com.things.link.alarm.domain.AlarmNotificationDelivery> pageDeliveries(
            UUID projectId, UUID instanceId, String cursor, int limit) {
        requireRead(projectId);
        return repository.pageDeliveries(projectId, instanceId, cursor, pageSize(limit));
    }

    /** 校验 EMAIL/WEBHOOK 目标，不允许 PUSH 伪收件人或任何 secret、header、认证字段。 */
    private static String validatedTarget(NotificationChannel channel, String value) {
        String target = required(value, 2048);
        try {
            if (channel == NotificationChannel.EMAIL) {
                if (!target.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) throw invalid();
            } else if (channel == NotificationChannel.WEBHOOK) {
                URI uri = URI.create(target);
                if (!"https".equalsIgnoreCase(uri.getScheme())
                        || uri.getHost() == null
                        || uri.getUserInfo() != null
                        || uri.getQuery() != null
                        || uri.getFragment() != null) throw invalid();
                SafeOutboundHost.requireNoForbiddenLiteral(uri);
            } else {
                // ADR 0038：PUSH 受众来自有效设备关系和安装实例，不能把内部 ID/token 塞进 recipient.target。
                throw invalid();
            }
        } catch (IllegalArgumentException e) {
            throw invalid();
        }
        return target;
    }

    /** 校验模板所含全部占位符均在白名单内。 */
    private static void validateTemplate(NotificationChannel channel, String subject, String body) {
        if (channel == null
                || body == null
                || body.isBlank()
                || (channel == NotificationChannel.EMAIL && (subject == null || subject.isBlank())))
            throw invalid();
        validateVariables(body);
        if (subject != null) validateVariables(subject);
    }

    /** 拒绝未知 ${...}，避免配置数据被日后误解释为脚本。 */
    private static void validateVariables(String value) {
        int from = 0;
        while ((from = value.indexOf("${", from)) >= 0) {
            int end = value.indexOf('}', from);
            if (end < 0 || !TEMPLATE_VARIABLES.contains(value.substring(from, end + 1)))
                throw invalid();
            from = end + 1;
        }
    }

    /**
     * @return 受限文本
     */
    private static String required(String value, int max) {
        if (value == null || value.isBlank() || value.strip().length() > max) throw invalid();
        return value.strip();
    }

    /**
     * @return 当前项目通知组
     */
    private AlarmNotificationGroup group(UUID p, UUID id) {
        return repository.findGroup(p, id).orElseThrow(this::notFound);
    }

    /**
     * @return 当前项目收件人
     */
    private AlarmNotificationRecipient recipient(UUID p, UUID id) {
        return repository.findRecipient(p, id).orElseThrow(this::notFound);
    }

    /**
     * @return 当前项目模板
     */
    private AlarmNotificationTemplate template(UUID p, UUID id) {
        return repository.findTemplate(p, id).orElseThrow(this::notFound);
    }

    /**
     * @return 当前项目绑定
     */
    private AlarmNotificationBinding binding(UUID p, UUID id) {
        return repository.findBinding(p, id).orElseThrow(this::notFound);
    }

    /**
     * @return 当前项目有效规则；不存在或软删除统一为既有告警规则 404。
     */
    private void rule(UUID p, UUID id) {
        ruleRepository
                .findById(p, id)
                .orElseThrow(() -> new BusinessException(AlarmErrorCode.RULE_NOT_FOUND));
    }

    /** 统一并发名称、唯一键和外键冲突，避免 JDBC 约束穿透为 500。 */
    private static void create(java.util.function.BooleanSupplier action) {
        try {
            if (!action.getAsBoolean()) throw conflict();
        } catch (DataIntegrityViolationException e) {
            throw conflict();
        }
    }

    /** 统一将 JDBC 约束错误压缩为业务冲突，避免把索引和外键名字暴露成 500。 */
    private static boolean write(java.util.function.BooleanSupplier action) {
        try {
            return action.getAsBoolean();
        } catch (DataIntegrityViolationException exception) {
            throw conflict();
        }
    }

    /** 保留锁前角色拒绝，持许可后重验角色；返回项目真实owner tenant供新事实使用。 */
    private UUID requireWrite(UUID projectId) {
        requireManage(projectId);
        UUID ownerTenant = projectService.requireProjectTenant(projectId);
        lifecycle.requireActiveForWrite(ownerTenant, projectId);
        requireManage(projectId);
        return ownerTenant;
    }

    /** 二次授权：HTTP 之外的内部调用也不能越权。 */
    private void requireManage(UUID p) {
        ProjectRole r = projectService.requireRoleInProject(p);
        if (r != ProjectRole.OWNER && r != ProjectRole.ADMIN)
            throw new BusinessException(AlarmErrorCode.MAINTAIN_FORBIDDEN);
    }

    /** 二次读取授权。 */
    private void requireRead(UUID p) {
        projectService.requireRoleInProject(p);
    }

    /**
     * @return 配置不存在
     */
    private BusinessException notFound() {
        return new BusinessException(AlarmErrorCode.NOTIFICATION_CONFIGURATION_NOT_FOUND);
    }

    /**
     * @return 配置冲突
     */
    private static BusinessException conflict() {
        return new BusinessException(AlarmErrorCode.NOTIFICATION_CONFIGURATION_CONFLICT);
    }

    /**
     * @return 配置非法
     */
    private static BusinessException invalid() {
        return new BusinessException(AlarmErrorCode.NOTIFICATION_CONFIGURATION_INVALID);
    }

    /**
     * @return 有界页大小
     */
    private static int pageSize(int v) {
        if (v < 1 || v > 100) throw invalid();
        return v;
    }
}
