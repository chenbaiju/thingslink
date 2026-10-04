package com.things.link.device.application;

import com.things.link.device.domain.ConsoleDeviceRuntimeCatalogRepository;
import com.things.link.device.domain.DeviceRuntimeCatalogRepository.Item;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.CommonErrorCode;
import com.things.link.support.query.SignedQueryCursorCodec;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Console精确模型目录的身份绑定游标与有界分页；调用方必须先完成授权和同事务RLS。 */
@Service
public final class ConsoleDeviceCatalogService {
    /** 不与App或分享游标复用的固定域。 */
    private static final String PURPOSE = "CONSOLE_DEVICE_CATALOG";
    /** 普通RLS仓储。 */
    private final ConsoleDeviceRuntimeCatalogRepository repository;
    /** 既有签名游标组件，不引入浏览器自报权限。 */
    private final SignedQueryCursorCodec cursors;

    /** @param repository 普通项目目录仓储 @param cursors 绑定当前身份的签名游标 */
    public ConsoleDeviceCatalogService(ConsoleDeviceRuntimeCatalogRepository repository, SignedQueryCursorCodec cursors) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.cursors = Objects.requireNonNull(cursors, "cursors");
    }

    /** @param items 当前完整页 @param nextCursor 下一页签名游标或null @param hasMore 是否还有下一页 */
    public record Page(List<Item> items, String nextCursor, boolean hasMore) {
        /** 保留稳定页，禁止调用方增删事实。 */
        public Page { items = List.copyOf(items); }
    }

    /**
     * @param tenantId 项目真实租户
     * @param projectId 项目身份
     * @param actorId 已认证Console账号
     * @param modelVersionId 精确模型
     * @param cursor 可选签名游标
     * @param limit 1..50页大小
     * @return 授权及模型过滤后的单页；目录缺席不证明已选设备撤权
     */
    public Page query(UUID tenantId, UUID projectId, UUID actorId, UUID modelVersionId, String cursor, int limit) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(actorId, "actorId");
        if (modelVersionId == null || limit < 1 || limit > 50) throw new BusinessException(CommonErrorCode.INVALID_PARAMETER);
        String binding = String.join("|", "CONSOLE", tenantId.toString(), projectId.toString(), actorId.toString(),
                modelVersionId.toString(), Integer.toString(limit), "createdAt,id:DESC");
        var anchor = cursors.decode(cursor, PURPOSE, binding);
        List<Item> rows = repository.findConsole(projectId, modelVersionId,
                anchor.map(SignedQueryCursorCodec.Anchor::sortTime).orElse(null),
                anchor.map(SignedQueryCursorCodec.Anchor::sortId).orElse(null), limit + 1);
        if (rows.size() > limit + 1) throw new IllegalStateException("Console目录仓储超过有界预算");
        boolean more = rows.size() > limit;
        List<Item> visible = more ? List.copyOf(rows.subList(0, limit)) : List.copyOf(rows);
        String next = more ? cursors.encode(PURPOSE, binding, visible.getLast().createdAt(), visible.getLast().deviceId()) : null;
        return new Page(visible, next, more);
    }
}
