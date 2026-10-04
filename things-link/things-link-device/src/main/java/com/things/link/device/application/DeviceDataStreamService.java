package com.things.link.device.application;

import com.things.link.device.domain.DeviceDataStream;
import com.things.link.device.domain.DeviceDataStreamRepository;
import com.things.link.device.domain.DeviceErrorCode;
import com.things.link.device.domain.DeviceType;
import com.things.link.device.domain.DeviceTypeRepository;
import com.things.link.project.application.ProjectService;
import com.things.link.shared.authz.ProjectRole;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 自定义数据流应用服务，负责项目授权、发布冻结、Topic 规则和唯一约束错误翻译。
 *
 * <p>V1 控制面已下线（`PRODUCT_SCOPE_V1_R2.md` PS-039 = TECHNICAL_DEFERRED）：本类**不是 Spring Bean**（与
 * {@link DeviceDataStreamRepository} 实现一并摘除注解），在 V1 内无任何 HTTP 入口、无运行时消费者、也不可被误注入，
 * 仅作为普通代码保留供 S15 显式接线。S15 必须显式决定端点形态，禁止无意识恢复旧公开路径——ADR 0042 禁止的是已删除
 * 字段名静默复活，并不自动禁止 URL 复用；若复用旧路径，须同步更新范围、契约与测试并证明语义兼容。</p>
 */
public class DeviceDataStreamService {
    // TODO(S15, D-057): S15 接线时由 S15-1 显式决定端点形态并恢复 Bean 注册，禁止无意识恢复 V1 已删除的 /data-streams 路径。
    /** 数据流仓储。 */ private final DeviceDataStreamRepository repository;
    /** 设备类型仓储。 */ private final DeviceTypeRepository typeRepository;
    /** 项目成员服务。 */ private final ProjectService projectService;

    /** @param repository 数据流仓储 @param typeRepository 类型仓储 @param projectService 项目服务 */
    public DeviceDataStreamService(DeviceDataStreamRepository repository, DeviceTypeRepository typeRepository,
                                   ProjectService projectService) {
        this.repository = repository; this.typeRepository = typeRepository; this.projectService = projectService;
    }

    /** @param projectId 项目 ID @param deviceTypeId 类型 ID @return 数据流列表 */
    @Transactional(readOnly = true)
    public List<DeviceDataStream> list(UUID projectId, UUID deviceTypeId) {
        requireMember(projectId); requireType(projectId, deviceTypeId);
        return repository.findByDeviceType(projectId, deviceTypeId);
    }

    /** 创建草稿类型的数据流。 */
    @Transactional
    public DeviceDataStream create(UUID projectId, UUID deviceTypeId, String streamKey, String name,
                                   DeviceDataStream.Format format, boolean advanced, String publishTopic,
                                   String subscribeTopic) {
        requireManager(projectId); requireDraft(requireTypeForUpdate(projectId, deviceTypeId));
        Topics topics = normalizeTopics(advanced, publishTopic, subscribeTopic);
        TenantScope scope = TenantContext.current().orElseThrow(() -> new IllegalStateException("没有租户上下文"));
        DeviceDataStream stream = new DeviceDataStream(Uuid7.generate(), scope.tenantId(), projectId, deviceTypeId,
                streamKey.strip(), name.strip(), format, advanced, topics.publish(), topics.subscribe(),
                Instant.now());
        try { repository.create(stream); } catch (DuplicateKeyException exception) { throw conflict(exception); }
        return stream;
    }

    /** 修改草稿类型的数据流。 */
    @Transactional
    public DeviceDataStream update(UUID projectId, UUID deviceTypeId, UUID id, String streamKey, String name,
                                   DeviceDataStream.Format format, boolean advanced, String publishTopic,
                                   String subscribeTopic) {
        requireManager(projectId); requireDraft(requireTypeForUpdate(projectId, deviceTypeId));
        DeviceDataStream current = repository.findById(projectId, deviceTypeId, id)
                .orElseThrow(() -> new BusinessException(DeviceErrorCode.DATA_STREAM_NOT_FOUND));
        Topics topics = normalizeTopics(advanced, publishTopic, subscribeTopic);
        DeviceDataStream updated = new DeviceDataStream(current.id(), current.tenantId(), current.projectId(),
                current.deviceTypeId(), streamKey.strip(), name.strip(), format, advanced, topics.publish(),
                topics.subscribe(), current.createdAt());
        try {
            if (!repository.update(updated)) throw new BusinessException(DeviceErrorCode.DATA_STREAM_NOT_FOUND);
        } catch (DuplicateKeyException exception) { throw conflict(exception); }
        return updated;
    }

    /** 软删除草稿类型的数据流。 */
    @Transactional
    public void delete(UUID projectId, UUID deviceTypeId, UUID id) {
        requireManager(projectId); requireDraft(requireTypeForUpdate(projectId, deviceTypeId));
        if (!repository.softDelete(projectId, deviceTypeId, id)) {
            throw new BusinessException(DeviceErrorCode.DATA_STREAM_NOT_FOUND);
        }
    }

    /** @return 合法化后的 Topic；关闭高级模式时强制清空，避免幽灵配置 */
    private static Topics normalizeTopics(boolean advanced, String publishTopic, String subscribeTopic) {
        if (!advanced) return new Topics(null, null);
        if (publishTopic == null || publishTopic.isBlank() || subscribeTopic == null || subscribeTopic.isBlank()) {
            throw new BusinessException(DeviceErrorCode.DATA_STREAM_TOPIC_INVALID);
        }
        return new Topics(publishTopic.strip(), subscribeTopic.strip());
    }

    /** 将唯一索引冲突转换为稳定业务码；D-033 冻结 TCP 绑定并从应用层移除后只剩标识符唯一索引一种冲突。 */
    private static BusinessException conflict(DuplicateKeyException exception) {
        return new BusinessException(DeviceErrorCode.DATA_STREAM_KEY_CONFLICT);
    }

    /** @param projectId 项目 ID @param id 类型 ID @return 未删除类型 */
    private DeviceType requireType(UUID projectId, UUID id) {
        return typeRepository.findById(projectId, id)
                .orElseThrow(() -> new BusinessException(DeviceErrorCode.DEVICE_TYPE_NOT_FOUND));
    }
    /** @param projectId 项目 ID @param id 类型 ID @return 已锁定类型 */
    private DeviceType requireTypeForUpdate(UUID projectId, UUID id) { return typeRepository.findByIdForUpdate(projectId, id).orElseThrow(() -> new BusinessException(DeviceErrorCode.DEVICE_TYPE_NOT_FOUND)); }

    /** @param type 类型 */
    private static void requireDraft(DeviceType type) {
        if (type.status() != DeviceType.Status.DRAFT) {
            throw new BusinessException(DeviceErrorCode.DEVICE_TYPE_PUBLISHED_IMMUTABLE);
        }
    }

    /** @param projectId 项目 ID @return 成员角色 */
    private ProjectRole requireMember(UUID projectId) {
        return projectService.roleInProject(projectId)
                .orElseThrow(() -> new BusinessException(DeviceErrorCode.DEVICE_TYPE_NOT_FOUND));
    }

    /** @param projectId 项目 ID */
    private void requireManager(UUID projectId) {
        ProjectRole role = requireMember(projectId);
        if (role != ProjectRole.OWNER && role != ProjectRole.ADMIN) {
            throw new BusinessException(DeviceErrorCode.DEVICE_TYPE_WRITE_FORBIDDEN);
        }
    }

    /** 规范化后的上下行 Topic。 @param publish 上行 @param subscribe 下行 */
    private record Topics(String publish, String subscribe) { }
}
