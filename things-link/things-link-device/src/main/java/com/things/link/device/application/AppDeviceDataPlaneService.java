package com.things.link.device.application;

import com.things.link.device.domain.DeviceRepository;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.CommonErrorCode;
import com.things.link.shared.page.CursorPage;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * App 设备数据面端口（S11-2b）。
 *
 * <p><b>信任边界</b>：本服务的全部方法<b>不做成员校验</b>，调用方（enduser 的
 * {@code AppDeviceAccessService}）必须先经 {@code app_user_device} 校验设备绑定，再进入本端口。
 * 这不是把鉴权降级成「靠自觉」——App 请求没有控制台 {@code accountId}，无法走
 * {@code ProjectService.requireRoleInProject}；隔离由 App 安全链写入的 {@code RlsScopeContext}
 * 驱动 RLS 兜底，且仓储 SQL 仍显式带 {@code project_id = ?}，形成两道独立防线。
 *
 * <p>只返回本模块 application 包的 DTO，不向跨模块调用方暴露 {@code Device} 等 domain 类型
 * （ArchUnit 规则 2/3）。
 */
@Service
public class AppDeviceDataPlaneService {

    /** 单页数量上限，与 {@code DeviceSearchQuery} 的 200 上限对齐。 */
    private static final int MAX_LIMIT = 200;

    /** 设备仓储。 */
    private final DeviceRepository deviceRepository;
    /** 当前值应用服务，复用其无成员校验的核心。 */
    private final DeviceCurrentValueService currentValueService;

    /** @param deviceRepository 设备仓储 @param currentValueService 当前值服务 */
    public AppDeviceDataPlaneService(DeviceRepository deviceRepository,
                                     DeviceCurrentValueService currentValueService) {
        this.deviceRepository = deviceRepository;
        this.currentValueService = currentValueService;
    }

    /**
     * 按设备 ID 白名单键集分页。
     *
     * @param projectId 项目 ID
     * @param deviceIds 绑定设备 ID 集合
     * @param cursor    上一页游标；null 表示首页
     * @param limit     单页数量，1～200
     * @return 一页设备投影
     */
    @Transactional(readOnly = true)
    public CursorPage<AppDevice> list(UUID projectId, Set<UUID> deviceIds, String cursor, int limit) {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new BusinessException(CommonErrorCode.INVALID_PARAMETER, "分页数量必须在 1～200 之间");
        }
        return deviceRepository.searchByIds(projectId, deviceIds, cursor, limit).map(AppDevice::from);
    }

    /**
     * 单设备详情。
     *
     * @param projectId 项目 ID
     * @param deviceId 设备 ID
     * @return 设备投影；不存在（含已软删、跨项目）时为空
     */
    @Transactional(readOnly = true)
    public Optional<AppDevice> detail(UUID projectId, UUID deviceId) {
        return deviceRepository.findById(projectId, deviceId).map(AppDevice::from);
    }

    /**
     * 单设备多属性当前值。
     *
     * @param projectId 项目 ID
     * @param deviceId 设备 ID
     * @param propertyKeys 属性键
     * @return 已有当前值；缺失组合不出现
     */
    @Transactional(readOnly = true)
    public List<AppCurrentValue> currentValues(UUID projectId, UUID deviceId, Collection<String> propertyKeys) {
        return currentValueService.findAllTrusted(projectId, List.of(deviceId), propertyKeys)
                .stream().map(AppCurrentValue::from).toList();
    }
}
