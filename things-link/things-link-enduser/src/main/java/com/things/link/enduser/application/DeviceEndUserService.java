package com.things.link.enduser.application;

import com.things.link.device.application.DeviceIngestionService;
import com.things.link.project.application.ProjectService;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.CommonErrorCode;
import com.things.link.shared.page.CursorPage;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.support.query.SignedQueryCursorCodec;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import com.things.link.enduser.domain.DeviceEndUserItem;
import com.things.link.enduser.domain.DeviceEndUserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

/** 现行成员身份、有效设备与终端用户授权在独立只读快照内核验。 */
@Service
public class DeviceEndUserService {
    private static final String PURPOSE = "CONSOLE_DEVICE_END_USERS";
    private final ProjectService projects;
    private final TransactionLocalRlsScope scope;
    private final DeviceIngestionService devices;
    private final DeviceEndUserRepository repository;
    private final SignedQueryCursorCodec cursors;
    public DeviceEndUserService(ProjectService projects, TransactionLocalRlsScope scope,
            DeviceIngestionService devices, DeviceEndUserRepository repository, SignedQueryCursorCodec cursors) {
        this.projects = projects; this.scope = scope; this.devices = devices; this.repository = repository; this.cursors = cursors;
    }

    /** 每页重新授权，游标只携带排序位置，绝不充当资源许可。 */
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW, isolation = Isolation.REPEATABLE_READ)
    public CursorPage<DeviceEndUserItem> list(UUID projectId, UUID deviceId, String cursor, int limit) {
        projects.requireRoleInProject(projectId);
        if (deviceId == null || limit < 1 || limit > 50) throw new BusinessException(CommonErrorCode.INVALID_PARAMETER);
        var identity = TenantContext.current().orElseThrow();
        var tenant = projects.requireProjectTenant(projectId);
        scope.establish(tenant, projectId);
        devices.requireDeviceOwner(projectId, deviceId);
        String binding = identity.tenantId() + "|" + identity.accountId() + "|" + projectId + "|" + deviceId + "|" + limit;
        var anchor = cursors.decode(cursor, PURPOSE, binding);
        var rows = repository.find(tenant, projectId, deviceId,
                anchor.map(SignedQueryCursorCodec.Anchor::sortTime).orElse(null),
                anchor.map(SignedQueryCursorCodec.Anchor::sortId).orElse(null), limit + 1);
        if (rows.size() <= limit) return CursorPage.last(rows);
        var items = java.util.List.copyOf(rows.subList(0, limit));
        var last = items.getLast();
        return CursorPage.of(items, cursors.encode(PURPOSE, binding, last.createdAt(), last.id()));
    }
}
