package com.things.link.device.application;

import com.things.link.device.domain.DeviceRuntimeCatalogRepository;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.CommonErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 分享合同§2/4指定候选目录公开端口；调用方先证明capability范围并在同事务建立普通RLS。
 * 端口不会替调用方授权，亦不扩大至全项目设备再由Java裁剪。
 */
@Service
public class DeviceRuntimeCatalogService {
    /** 专用只读仓储保留候选过滤先于分页的边界。 */
    private final DeviceRuntimeCatalogRepository repository;

    /** @param repository 普通RLS目录事实仓储 */
    public DeviceRuntimeCatalogService(DeviceRuntimeCatalogRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    /**
     * @param projectId 已确权项目
     * @param modelVersionId 变量声明精确模型
     * @param candidateIds 该变量完整且有界候选
     * @param beforeTime 可空创建时刻游标
     * @param beforeId 同时出现的设备身份游标
     * @param limit 1..51，包含至多一条探测行
     * @return 当前可见、未删且模型仍精确匹配的候选页
     */
    @Transactional(readOnly = true)
    public List<RuntimeDeviceCatalogItem> find(UUID projectId, UUID modelVersionId, List<UUID> candidateIds,
                                               Instant beforeTime, UUID beforeId, int limit) {
        if (projectId == null || modelVersionId == null || candidateIds == null || candidateIds.isEmpty()
                || candidateIds.size() > 20 || candidateIds.stream().anyMatch(Objects::isNull)
                || new HashSet<>(candidateIds).size() != candidateIds.size()
                || (beforeTime == null) != (beforeId == null) || limit < 1 || limit > 51) {
            throw new BusinessException(CommonErrorCode.INVALID_PARAMETER, "运行设备目录范围或分页参数不合法");
        }
        return repository.find(projectId, modelVersionId, List.copyOf(candidateIds), beforeTime, beforeId, limit)
                .stream().map(item -> new RuntimeDeviceCatalogItem(item.deviceId(), item.name(),
                        item.deviceStatus(), item.currentModelVersionId(), item.createdAt())).toList();
    }
}
