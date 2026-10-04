package com.things.link.telemetry.application;

import com.things.link.device.application.DeviceIngestionService;
import com.things.link.project.application.ProjectService;
import com.things.link.shared.authz.ProjectRole;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.CommonErrorCode;
import com.things.link.shared.page.CursorPage;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.support.query.SignedQueryCursorCodec;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import com.things.link.telemetry.domain.DeviceCommandErrorCode;
import com.things.link.telemetry.domain.DeviceCommandHistoryItem;
import com.things.link.telemetry.domain.DeviceCommandHistoryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

/** 现行控制权限、有效目标设备与命令摘要在独立只读快照内核验。 */
@Service
public class DeviceCommandHistoryService {
    private static final String PURPOSE = "CONSOLE_DEVICE_COMMAND_HISTORY";
    private final ProjectService projects;
    private final TransactionLocalRlsScope scope;
    private final DeviceIngestionService devices;
    private final DeviceCommandHistoryRepository repository;
    private final SignedQueryCursorCodec cursors;
    public DeviceCommandHistoryService(ProjectService projects, TransactionLocalRlsScope scope,
            DeviceIngestionService devices, DeviceCommandHistoryRepository repository, SignedQueryCursorCodec cursors) {
        this.projects = projects; this.scope = scope; this.devices = devices; this.repository = repository; this.cursors = cursors;
    }

    /** 每页重新授权，游标只携带排序位置，绝不充当资源许可。 */
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW, isolation = Isolation.REPEATABLE_READ)
    public CursorPage<DeviceCommandHistoryItem> list(UUID projectId, UUID deviceId, String cursor, int limit) {
        if (projects.requireRoleInProject(projectId) == ProjectRole.VIEWER)
            throw new BusinessException(DeviceCommandErrorCode.COMMAND_CONTROL_FORBIDDEN);
        if (deviceId == null || limit < 1 || limit > 50) throw new BusinessException(CommonErrorCode.INVALID_PARAMETER);
        var identity = TenantContext.current().orElseThrow();
        var tenant = projects.requireProjectTenant(projectId);
        scope.establish(tenant, projectId);
        devices.requireDeviceOwner(projectId, deviceId);
        String binding = identity.tenantId() + "|" + identity.accountId() + "|" + projectId + "|" + deviceId + "|" + limit;
        var anchor = cursors.decode(cursor, PURPOSE, binding);
        var rows = repository.find(projectId, deviceId,
                anchor.map(SignedQueryCursorCodec.Anchor::sortTime).orElse(null),
                anchor.map(SignedQueryCursorCodec.Anchor::sortId).orElse(null), limit + 1);
        if (rows.size() <= limit) return CursorPage.last(rows);
        var items = java.util.List.copyOf(rows.subList(0, limit));
        var last = items.getLast();
        return CursorPage.of(items, cursors.encode(PURPOSE, binding, last.acceptedAt(), last.id()));
    }
}
