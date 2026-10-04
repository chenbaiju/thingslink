package com.things.link.task.application;

import com.things.link.device.application.DeviceIngestionService;
import com.things.link.device.application.DeviceSearchService;
import com.things.link.project.application.ProjectService;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.CommonErrorCode;
import com.things.link.shared.page.CursorPage;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.support.query.SignedQueryCursorCodec;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import com.things.link.task.domain.DeviceTaskJobItem;
import com.things.link.task.domain.DeviceTaskExecutionItem;
import com.things.link.task.domain.DeviceTaskRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.ArrayList;
import java.util.UUID;
import java.util.stream.Collectors;

/** 当前配置筛选和原执行目标分离；有界内存、事务超时拒绝不完整结果。 */
@Service
public class DeviceTaskQueryService {
    private final ProjectService projects;
    private final TransactionLocalRlsScope scope;
    private final DeviceIngestionService devices;
    private final DeviceSearchService groups;
    private final DeviceTaskRepository repository;
    private final SignedQueryCursorCodec cursors;
    public DeviceTaskQueryService(ProjectService projects, TransactionLocalRlsScope scope,
            DeviceIngestionService devices, DeviceSearchService groups, DeviceTaskRepository repository, SignedQueryCursorCodec cursors) {
        this.projects=projects; this.scope=scope; this.devices=devices; this.groups=groups; this.repository=repository; this.cursors=cursors;
    }

    /** 服务端过滤到足够匹配项后才分页；不向客户端暴露无关项目任务。 */
    @Transactional(readOnly=true, propagation=Propagation.REQUIRES_NEW, isolation=Isolation.REPEATABLE_READ, timeout=3)
    public CursorPage<DeviceTaskJobItem> jobs(UUID projectId, UUID deviceId, String cursor, int limit) {
        String binding = authorize(projectId, deviceId, limit);
        var anchor = cursors.decode(cursor, "CONSOLE_DEVICE_TASK_JOBS", binding);
        Instant beforeTime=anchor.map(SignedQueryCursorCodec.Anchor::sortTime).orElse(null);
        UUID beforeId=anchor.map(SignedQueryCursorCodec.Anchor::sortId).orElse(null);
        var matches = new ArrayList<DeviceTaskJobItem>();
        while (matches.size() <= limit) {
            var candidates=repository.candidates(projectId,beforeTime,beforeId,100);
            if (candidates.isEmpty()) return CursorPage.last(matches);
            var groupIds=candidates.stream().map(DeviceTaskJobItem::targetGroupId)
                    .filter(java.util.Objects::nonNull).collect(Collectors.toSet());
            var matching=groupIds.isEmpty() ? java.util.Set.<UUID>of() : groups.matchingTaskTargetGroups(projectId,deviceId,groupIds);
            for (var candidate : candidates) {
                if ("ALL_DEVICES".equals(candidate.targetType()) || matching.contains(candidate.targetGroupId())) {
                    matches.add(candidate);
                    if (matches.size() > limit) {
                        var last=matches.get(limit-1);
                        return CursorPage.of(matches.subList(0,limit),cursors.encode("CONSOLE_DEVICE_TASK_JOBS",binding,last.createdAt(),last.id()));
                    }
                }
            }
            if (candidates.size()<100) return CursorPage.last(matches);
            var last=candidates.getLast(); beforeTime=last.createdAt(); beforeId=last.id();
        }
        throw new IllegalStateException("任务查询分页边界异常");
    }

    /** 原目标快照分页；配置或设备组变化不能改写历史归属。 */
    @Transactional(readOnly=true, propagation=Propagation.REQUIRES_NEW, isolation=Isolation.REPEATABLE_READ, timeout=3)
    public CursorPage<DeviceTaskExecutionItem> history(UUID projectId, UUID deviceId, String cursor, int limit) {
        String binding=authorize(projectId,deviceId,limit);
        var anchor=cursors.decode(cursor,"CONSOLE_DEVICE_TASK_EXECUTIONS",binding);
        var rows=repository.history(projectId,deviceId,anchor.map(SignedQueryCursorCodec.Anchor::sortTime).orElse(null),
                anchor.map(SignedQueryCursorCodec.Anchor::sortId).orElse(null),limit+1);
        if (rows.size()<=limit) return CursorPage.last(rows);
        var last=rows.get(limit-1);
        return CursorPage.of(rows.subList(0,limit),cursors.encode("CONSOLE_DEVICE_TASK_EXECUTIONS",binding,last.startedAt(),last.id()));
    }

    private String authorize(UUID projectId, UUID deviceId, int limit) {
        projects.requireRoleInProject(projectId);
        if (deviceId==null || limit<1 || limit>50) throw new BusinessException(CommonErrorCode.INVALID_PARAMETER);
        var identity=TenantContext.current().orElseThrow();
        scope.establish(projects.requireProjectTenant(projectId),projectId);
        devices.requireDeviceOwner(projectId,deviceId);
        return identity.tenantId()+"|"+identity.accountId()+"|"+projectId+"|"+deviceId+"|"+limit;
    }
}
