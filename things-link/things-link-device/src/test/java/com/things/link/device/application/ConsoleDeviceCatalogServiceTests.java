package com.things.link.device.application;

import com.things.link.device.domain.ConsoleDeviceRuntimeCatalogRepository;
import com.things.link.device.domain.DeviceRuntimeCatalogRepository.Item;
import com.things.link.shared.error.BusinessException;
import com.things.link.support.query.SignedQueryCursorCodec;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Console游标与完整当前页，不以模型目录缺席推断单设备权限。 */
class ConsoleDeviceCatalogServiceTests {
    /** 真实签名实现，不使用无签名游标替身。 */
    private final SignedQueryCursorCodec codec = new SignedQueryCursorCodec("console-catalog-test-secret-at-least-32-bytes");
    /** 每例独立仓储。 */
    private final ConsoleDeviceRuntimeCatalogRepository repository = mock(ConsoleDeviceRuntimeCatalogRepository.class);
    /** 被测目录核心。 */
    private final ConsoleDeviceCatalogService service = new ConsoleDeviceCatalogService(repository, codec);
    /** 请求真实租户。 */ private final UUID tenant = UUID.randomUUID();
    /** 请求项目。 */ private final UUID project = UUID.randomUUID();
    /** 已认证主体。 */ private final UUID actor = UUID.randomUUID();
    /** 精确模型。 */ private final UUID model = UUID.randomUUID();

    /** 游标签名锚点必须来自最后一条可见行，而不是探测行。 */
    @Test
    void paginatesWithExactLastVisibleAnchorAndScopeBoundCursor() {
        Item first = item(3), second = item(2), probe = item(1);
        when(repository.findConsole(eq(project), eq(model), any(), any(), eq(3))).thenReturn(List.of(first, second, probe));
        var page = service.query(tenant, project, actor, model, null, 2);
        assertThat(page.items()).containsExactly(first, second);
        assertThat(page.hasMore()).isTrue();
        service.query(tenant, project, actor, model, page.nextCursor(), 2);
        verify(repository).findConsole(project, model, second.createdAt(), second.deviceId(), 3);
        assertThatThrownBy(() -> service.query(tenant, project, UUID.randomUUID(), model, page.nextCursor(), 2))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.query(UUID.randomUUID(), project, actor, model, page.nextCursor(), 2))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.query(tenant, UUID.randomUUID(), actor, model, page.nextCursor(), 2))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.query(tenant, project, actor, UUID.randomUUID(), page.nextCursor(), 2))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.query(tenant, project, actor, model, page.nextCursor(), 3))
                .isInstanceOf(BusinessException.class);
    }

    /** 非法游标拒绝，空目录合法，不自动扫项目或放宽页预算。 */
    @Test
    void rejectsMalformedInputsButAcceptsEmptyAuthorizedPage() {
        for (String cursor : List.of("", "tampered", "a".repeat(2049))) {
            assertThatThrownBy(() -> service.query(tenant, project, actor, model, cursor, 20)).isInstanceOf(BusinessException.class);
        }
        for (int limit : List.of(0, 51)) {
            assertThatThrownBy(() -> service.query(tenant, project, actor, model, null, limit)).isInstanceOf(BusinessException.class);
        }
        when(repository.findConsole(project, model, null, null, 21)).thenReturn(List.of());
        var empty = service.query(tenant, project, actor, model, null, 20);
        assertThat(empty.items()).isEmpty();
        assertThat(empty.nextCursor()).isNull();
        assertThat(empty.hasMore()).isFalse();
    }

    /** 构造排序稳定的目录事实。 */
    private Item item(int second) {
        return new Item(UUID.randomUUID(), "device", "ONLINE", model, Instant.ofEpochSecond(second));
    }
}
