package com.things.link.enduser.application;

import com.things.link.device.application.AppCurrentValue;
import com.things.link.enduser.domain.AppDeviceDetails;
import com.things.link.enduser.domain.AppDeviceReadRepository;
import com.things.link.device.application.AppDeviceDataPlaneService;
import com.things.link.enduser.domain.AppUserDevice;
import com.things.link.enduser.domain.AppUserDeviceRepository;
import com.things.link.enduser.domain.AppUserRole;
import com.things.link.enduser.domain.AppUserRoleRepository;
import com.things.link.enduser.domain.EndUserErrorCode;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.page.CursorPage;
import com.things.link.telemetry.application.AppCommandResult;
import com.things.link.telemetry.application.AppPropertyHistory;
import com.things.link.telemetry.application.AppTelemetryDataPlaneService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * App 设备数据面授权编排（S11-2b）。
 *
 * <p>这是「两层授权矩阵」唯一落地处：项目角色层（{@code app_user_role}）做进入项目门禁，
 * 设备关系层（{@code app_user_device.relation_role}）决定对单个设备能做什么（ADR 0035）。
 * 全部方法都先复验两层事实，不信任 15 分钟内的访问令牌；{@code projectId} 一律由调用方
 * 从令牌的 {@code pid} 声明取得，本服务不接受客户端传参。
 * 命令写入还须先在原事务持有可信{@code tid}/{@code pid}对应的项目写许可（ADR0064决策4）。
 *
 * <h2>授权矩阵</h2>
 * <ul>
 *   <li>读（列表/详情/当前值/历史）：角色 {@code ACTIVE}（任意项目角色）× 绑定 {@code ACTIVE}
 *       （任意关系角色）。</li>
 *   <li>控制（下发命令）：角色 {@code ACTIVE} × 绑定 {@code ACTIVE} 且
 *       {@code relation_role ∈ {PRIMARY, MEMBER}}；{@code READ_ONLY} → 60011。</li>
 * </ul>
 *
 * <p>未绑定 / 设备已软删 / 跨项目 / 命令不存在统一 404（60010），不泄露资源存在性。角色失效
 * 统一 401（60009），与访问令牌失效同码 —— 客户端收到即触发刷新，刷新回库复验角色并失败则登出，
 * 语义自洽。
 */
@Service
public class AppDeviceAccessService {

    private final AppDeviceReadRepository deviceReads;
    private final AppUserRoleRepository roleRepository;
    private final AppUserDeviceRepository deviceBindingRepository;
    private final AppDeviceDataPlaneService deviceDataPlane;
    private final AppTelemetryDataPlaneService telemetryDataPlane;
    /** ADR0064要求在命令副作用之前持有项目写许可，HTTP快照不能替代此锁。 */
    private final AppProjectWriteGuard projectWriteGuard;

    /** @param roleRepository 项目角色仓储 @param deviceBindingRepository 设备绑定仓储
     *  @param deviceDataPlane 设备数据面端口 @param telemetryDataPlane 遥测数据面端口
     *  @param projectWriteGuard 原业务事务中的项目生命周期写许可
     *  @param deviceReads 授权设备的有界公开投影查询 */
    public AppDeviceAccessService(AppUserRoleRepository roleRepository,
                                  AppUserDeviceRepository deviceBindingRepository,
                                  AppDeviceDataPlaneService deviceDataPlane,
                                  AppTelemetryDataPlaneService telemetryDataPlane,
                                  AppProjectWriteGuard projectWriteGuard, AppDeviceReadRepository deviceReads) {
        this.deviceReads = deviceReads;
        this.roleRepository = roleRepository;
        this.deviceBindingRepository = deviceBindingRepository;
        this.deviceDataPlane = deviceDataPlane;
        this.telemetryDataPlane = telemetryDataPlane;
        this.projectWriteGuard = projectWriteGuard;
    }

    /**
     * 列当前终端用户可访问的设备（游标分页）。
     *
     * @param projectId 项目 ID（取自令牌）
     * @param appUserId 终端用户 ID
     * @param tenantId 可信令牌租户
     * @param query 名称或设备键字面子串
     * @param status 连接状态，可空
     * @param cursor    上一页游标；null 表示首页
     * @param limit     单页数量
     * @return 一页设备投影（只含有效绑定设备）
     */
    @Transactional(readOnly = true)
    public CursorPage<AppDeviceDetails> list(UUID tenantId, UUID projectId, UUID appUserId, String cursor, int limit, String query, String status) {
        requireActiveRole(projectId, appUserId);
        return deviceReads.list(tenantId, projectId, appUserId, cursor, limit, query, status);
    }

    /**
     * 单设备详情。
     *
     * @param tenantId 可信令牌租户
     * @param projectId 项目 ID
     * @param appUserId 终端用户 ID
     * @param deviceId 设备 ID
     * @return 设备投影
     */
    @Transactional(readOnly = true)
    public AppDeviceDetails detail(UUID tenantId, UUID projectId, UUID appUserId, UUID deviceId) {
        requireActiveRole(projectId, appUserId);
        return deviceReads.detail(tenantId, projectId, appUserId, deviceId).orElseThrow(AppDeviceAccessService::notFound);
    }

    /**
     * 单设备多属性当前值。
     *
     * @param projectId 项目 ID
     * @param appUserId 终端用户 ID
     * @param deviceId 设备 ID
     * @param propertyKeys 属性键
     * @return 已有当前值；缺失组合不出现
     */
    @Transactional(readOnly = true)
    public List<AppCurrentValue> currentValues(UUID projectId, UUID appUserId, UUID deviceId,
                                               Collection<String> propertyKeys) {
        requireActiveRole(projectId, appUserId);
        requireBoundDevice(projectId, appUserId, deviceId);
        return deviceDataPlane.currentValues(projectId, deviceId, propertyKeys);
    }

    /**
     * 查询属性历史聚合曲线。
     *
     * @param projectId 项目 ID
     * @param appUserId 终端用户 ID
     * @param deviceId 设备 ID
     * @param propertyKey 属性键
     * @param from 起始时刻（含）
     * @param to 结束时刻（不含）
     * @param granularity 请求粒度
     * @param aggregation 聚合函数
     * @return 历史聚合投影
     */
    @Transactional(readOnly = true)
    public AppPropertyHistory history(UUID projectId, UUID appUserId, UUID deviceId, String propertyKey,
                                      Instant from, Instant to, String granularity, String aggregation) {
        requireActiveRole(projectId, appUserId);
        requireBoundDevice(projectId, appUserId, deviceId);
        return telemetryDataPlane.history(projectId, deviceId, propertyKey, from, to, granularity, aggregation);
    }

    /**
     * 受理设备命令。
     *
     * @param tenantId 项目归属租户 ID（取自已验证令牌）
     * @param projectId 项目 ID
     * @param appUserId 终端用户 ID
     * @param deviceId 设备 ID
     * @param idempotencyKey 业务幂等键
     * @param commandKey 物模型命令键
     * @param input 命令输入对象
     * @return 命令结果投影
     */
    @Transactional
    public AppCommandResult submit(UUID tenantId, UUID projectId, UUID appUserId, UUID deviceId, String idempotencyKey,
                                   String commandKey, JsonNode input) {
        projectWriteGuard.requireWritable(tenantId, projectId);
        requireActiveRole(projectId, appUserId);
        AppUserDevice binding = requireBoundDevice(projectId, appUserId, deviceId);
        requireControl(binding);
        return telemetryDataPlane.submit(projectId, deviceId, idempotencyKey, commandKey, input, appUserId);
    }

    /**
     * 回读命令状态。
     *
     * @param projectId 项目 ID
     * @param appUserId 终端用户 ID
     * @param deviceId 设备 ID
     * @param commandId 命令 ID
     * @return 命令结果投影
     */
    @Transactional(readOnly = true)
    public AppCommandResult status(UUID projectId, UUID appUserId, UUID deviceId, UUID commandId) {
        requireActiveRole(projectId, appUserId);
        requireBoundDevice(projectId, appUserId, deviceId);
        return telemetryDataPlane.status(projectId, deviceId, commandId)
                .orElseThrow(AppDeviceAccessService::notFound);
    }

    /** ADR0110目录读取复用真实控制授权；只读不冒充项目写许可，提交仍执行原项目门禁。
     * @param projectId 项目 @param appUserId App用户 @param deviceId 设备
     */
    @Transactional(readOnly = true)
    public void requireCommandCatalogAccess(UUID projectId, UUID appUserId, UUID deviceId) {
        requireActiveRole(projectId, appUserId);
        requireControl(requireBoundDevice(projectId, appUserId, deviceId));
    }

    /**
     * 项目角色门禁：角色赋值必须 {@code ACTIVE}，否则 60009。
     *
     * <p>任何角色（APP_ADMIN/MAINTAINER/OPERATOR/OBSERVER）只要 ACTIVE 即可进入项目；
     * 角色不参与控制判定（ADR 0035）。
     */
    private void requireActiveRole(UUID projectId, UUID appUserId) {
        AppUserRole role = roleRepository.findByProjectAndUser(projectId, appUserId)
                .orElseThrow(() -> new BusinessException(EndUserErrorCode.END_USER_ACCESS_INVALID));
        if (role.status() != AppUserRole.Status.ACTIVE) {
            throw new BusinessException(EndUserErrorCode.END_USER_ACCESS_INVALID);
        }
    }

    /**
     * 设备关系门禁：须存在 {@code ACTIVE} 绑定，否则 60010（未绑定/已关闭统一 404）。
     *
     * @return 有效绑定（含关系角色，供控制判定）
     */
    private AppUserDevice requireBoundDevice(UUID projectId, UUID appUserId, UUID deviceId) {
        return deviceBindingRepository.findByProjectAndUser(projectId, appUserId).stream()
                .filter(binding -> binding.status() == AppUserDevice.Status.ACTIVE)
                .filter(binding -> binding.deviceId().equals(deviceId))
                .findFirst()
                .orElseThrow(AppDeviceAccessService::notFound);
    }

    /** 控制判定：只有 {@code PRIMARY}/{@code MEMBER} 可下发命令，{@code READ_ONLY} → 60011。 */
    private void requireControl(AppUserDevice binding) {
        if (binding.relationRole() == AppUserDevice.RelationRole.READ_ONLY) {
            throw new BusinessException(EndUserErrorCode.END_USER_DEVICE_CONTROL_FORBIDDEN);
        }
    }

    /** 统一「资源不可见」404，不区分未绑定 / 已软删 / 跨项目 / 命令不存在。 */
    private static BusinessException notFound() {
        return new BusinessException(EndUserErrorCode.END_USER_DEVICE_NOT_FOUND);
    }
}
