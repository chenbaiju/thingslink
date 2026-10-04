package com.things.link.telemetry.application;

import com.things.link.device.application.DeviceRuntimeDataService;
import com.things.link.device.application.RuntimeDeviceQuery;
import com.things.link.project.application.ProjectService;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import com.things.link.telemetry.domain.HistoryAggregation;
import com.things.link.telemetry.domain.HistoryGranularity;
import com.things.link.telemetry.domain.PropertyHistoryResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Console严格历史读取；真实项目成员、双轴RLS与精确模型复核不可由App身份代替。 */
@Service
public class ConsoleVersionedHistoryService {
    /** 项目成员与真实租户归属。 */
    private final ProjectService projects;
    /** 普通事务局部RLS范围。 */
    private final TransactionLocalRlsScope scope;
    /** 设备可见性及当前模型公开端口。 */
    private final DeviceRuntimeDataService devices;
    /** 最终粒度超限明确拒绝的历史核心。 */
    private final PropertyHistoryService history;

    /** @param projects 项目授权 @param scope 事务范围 @param devices 设备复核 @param history 历史核心 */
    public ConsoleVersionedHistoryService(ProjectService projects, TransactionLocalRlsScope scope,
            DeviceRuntimeDataService devices, PropertyHistoryService history) {
        this.projects = projects;
        this.scope = scope;
        this.devices = devices;
        this.history = history;
    }

    /** @param projectId 项目 @param deviceId 设备 @param propertyKey 顶层属性 @param expectedModelVersionId 精确模型
     * @param from 包含起点 @param to 不含终点 @param granularity 请求粒度 @param aggregation 聚合方式
     * @return 完整有界历史；既有double领域值不声称上报任意精度
     */
    @Transactional(readOnly = true)
    public PropertyHistoryResult query(UUID projectId, UUID deviceId, String propertyKey,
            UUID expectedModelVersionId, Instant from, Instant to,
            HistoryGranularity granularity, HistoryAggregation aggregation) {
        projects.requireRoleInProject(projectId);
        scope.establish(projects.requireProjectTenant(projectId), projectId);
        devices.requireAllAvailable(projectId,
                List.of(new RuntimeDeviceQuery(deviceId, expectedModelVersionId, List.of())));
        return history.queryVersionedTrusted(projectId, deviceId, propertyKey, from, to, granularity, aggregation);
    }
}
