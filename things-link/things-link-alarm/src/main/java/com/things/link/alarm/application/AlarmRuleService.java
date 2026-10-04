package com.things.link.alarm.application;

import com.things.link.alarm.domain.AlarmErrorCode;
import com.things.link.alarm.domain.AlarmRule;
import com.things.link.alarm.domain.AlarmRuleRepository;
import com.things.link.device.application.DeviceIngestionService;
import com.things.link.project.application.ProjectService;
import com.things.link.project.application.ProjectLifecycleAccessService;
import com.things.link.shared.authz.ProjectRole;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.page.CursorPage;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/** 规则配置用例；评估通路不经过本服务，避免把 HTTP 授权混进 Kafka 数据面。 */
@Service
public class AlarmRuleService {
    /** 规则域持久化端口。 */ private final AlarmRuleRepository repository;
    /** 项目成员与角色公开端口。 */ private final ProjectService projectService;
    /** 原事务项目持续写许可。 */ private final ProjectLifecycleAccessService lifecycle;
    /** 设备域公开端口，拒绝跨项目设备和非数值/不可上报属性。 */ private final DeviceIngestionService deviceIngestionService;
    /** @param repository 规则仓储 @param projectService 项目授权端口 @param lifecycle 项目持续写许可 @param deviceIngestionService 设备物模型端口 */
    public AlarmRuleService(AlarmRuleRepository repository, ProjectService projectService,
                            ProjectLifecycleAccessService lifecycle, DeviceIngestionService deviceIngestionService) {
        this.repository = repository; this.projectService = projectService; this.lifecycle = lifecycle;
        this.deviceIngestionService = deviceIngestionService;
    }
    /** 创建固定白名单告警规则。 */
    @Transactional public AlarmRule create(UUID projectId, AlarmRuleCommand command) {
        UUID ownerTenant = requireWrite(projectId); validate(command, false);
        validateDeviceProperty(projectId, command);
        Instant now = Instant.now();
        AlarmRule rule = new AlarmRule(Uuid7.generate(), ownerTenant, projectId, normalize(command.name()),
                normalize(command.alarmType()), AlarmRule.OriginatorType.DEVICE, command.deviceId(),
                normalize(command.propertyKey()), command.triggerOperator(), command.triggerThreshold(),
                command.triggerDurationSeconds(), command.clearOperator(), command.clearThreshold(),
                command.clearDurationSeconds(), command.severity(), command.enabled(), 0, now, now, null);
        try {
            if (!repository.create(rule)) throw new BusinessException(AlarmErrorCode.RULE_NAME_CONFLICT);
        } catch (DuplicateKeyException exception) { throw new BusinessException(AlarmErrorCode.RULE_NAME_CONFLICT); }
        return rule;
    }
    /** 获取一条规则。 */
    @Transactional(readOnly = true) public AlarmRule get(UUID projectId, UUID ruleId) {
        requireMember(projectId); return find(projectId, ruleId);
    }
    /** 稳定键集分页列出规则。 */
    @Transactional(readOnly = true) public CursorPage<AlarmRule> page(UUID projectId, String cursor, int limit) {
        requireMember(projectId); return repository.page(projectId, cursor, pageSize(limit));
    }
    /** 使用 version CAS 修改规则，防止两位管理员静默覆盖互相设置。 */
    @Transactional public AlarmRule update(UUID projectId, UUID ruleId, AlarmRuleCommand command) {
        requireWrite(projectId); validate(command, true); validateDeviceProperty(projectId, command); AlarmRule old = find(projectId, ruleId); Instant now = Instant.now();
        if (old.version() != command.version()) throw new BusinessException(AlarmErrorCode.INSTANCE_STATE_CONFLICT);
        if (!repository.update(new AlarmRule(old.id(), old.tenantId(), old.projectId(), normalize(command.name()),
                normalize(command.alarmType()), old.originatorType(), command.deviceId(), normalize(command.propertyKey()),
                command.triggerOperator(), command.triggerThreshold(), command.triggerDurationSeconds(), command.clearOperator(),
                command.clearThreshold(), command.clearDurationSeconds(), command.severity(), command.enabled(), old.version(),
                old.createdAt(), now, null))) throw new BusinessException(AlarmErrorCode.INSTANCE_STATE_CONFLICT);
        return find(projectId, ruleId);
    }
    /** 软删除规则；既有实例保留审计，不再让新上行评估该规则。 */
    @Transactional public void delete(UUID projectId, UUID ruleId, int version) {
        requireWrite(projectId); find(projectId, ruleId);
        if (!repository.softDelete(projectId, ruleId, version)) throw new BusinessException(AlarmErrorCode.INSTANCE_STATE_CONFLICT);
    }
    /** @return 当前有效规则或统一 404 */
    private AlarmRule find(UUID projectId, UUID ruleId) { return repository.findById(projectId, ruleId).orElseThrow(() -> new BusinessException(AlarmErrorCode.RULE_NOT_FOUND)); }
    /** 规则配置是 OWNER/ADMIN 能力，OPERATOR 只能维护运行期实例。 */
    private void requireManager(UUID projectId) { ProjectRole role = requireMember(projectId); if (role != ProjectRole.OWNER && role != ProjectRole.ADMIN) throw new BusinessException(AlarmErrorCode.MAINTAIN_FORBIDDEN); }
    /** 非成员由 ProjectService 统一返回项目 404，不能泄露存在性。 */
    private ProjectRole requireMember(UUID projectId) { return projectService.requireRoleInProject(projectId); }
    /** 保留锁前角色拒绝，持许可后重验角色；返回项目真实owner tenant供新事实使用。 */
    private UUID requireWrite(UUID projectId) {
        requireManager(projectId);
        UUID ownerTenant = projectService.requireProjectTenant(projectId);
        lifecycle.requireActiveForWrite(ownerTenant, projectId);
        requireManager(projectId);
        return ownerTenant;
    }
    /** 对规则输入做应用层快速失败；数据库 CHECK 仍是绕过 API 时的最终防线。 */
    private static void validate(AlarmRuleCommand value, boolean requireVersion) {
        if (value == null || blank(value.name(), 128) || blank(value.alarmType(), 64) || value.deviceId() == null
                || blank(value.propertyKey(), 64) || !value.propertyKey().matches("[A-Za-z][A-Za-z0-9_-]{0,63}")
                || value.triggerOperator() == null || value.clearOperator() == null || value.severity() == null
                || !Double.isFinite(value.triggerThreshold()) || !Double.isFinite(value.clearThreshold())
                || value.triggerDurationSeconds() < 0 || value.triggerDurationSeconds() > 604800
                || value.clearDurationSeconds() < 0 || value.clearDurationSeconds() > 604800
                || (requireVersion && (value.version() == null || value.version() < 0))) throw new BusinessException(AlarmErrorCode.RULE_INVALID);
    }
    /**
     * 规则配置也必须经设备域公开端口验证。
     *
     * <p>以两个阈值作为示例值调用既有物模型校验：这样属性不存在、非数值、只读或跨项目设备都在写规则前
     * 拒绝，alarm 模块无需读取 dev_* 表。阈值本身已经先经过 finite 校验。</p>
     */
    private void validateDeviceProperty(UUID projectId, AlarmRuleCommand value) {
        try {
            deviceIngestionService.validateReportedProperties(projectId, value.deviceId(),
                    java.util.Map.of(value.propertyKey(), value.triggerThreshold()));
            deviceIngestionService.validateReportedProperties(projectId, value.deviceId(),
                    java.util.Map.of(value.propertyKey(), value.clearThreshold()));
        } catch (BusinessException exception) {
            throw new BusinessException(AlarmErrorCode.RULE_INVALID, "告警规则设备或数值属性不合法");
        }
    }
    /** @return 去空白后字符串 */ private static String normalize(String value) { return value.strip(); }
    /** @return 是否缺失或超过长度 */ private static boolean blank(String value, int maximum) { return value == null || value.isBlank() || value.strip().length() > maximum; }
    /** 统一限制避免任意客户端把查询 page 撑大。 */ private static int pageSize(int value) { if (value < 1 || value > 100) throw new BusinessException(AlarmErrorCode.RULE_INVALID, "分页大小必须在 1 到 100 之间"); return value; }
}
