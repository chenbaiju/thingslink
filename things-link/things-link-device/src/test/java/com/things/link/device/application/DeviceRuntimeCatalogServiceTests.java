package com.things.link.device.application;

import com.things.link.device.domain.DeviceRuntimeCatalogRepository;
import com.things.link.shared.error.BusinessException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 分享目录公开端口的参数上限与只读事实映射验收；真实RLS另由Bootstrap覆盖。 */
class DeviceRuntimeCatalogServiceTests {
    /** 非完整分页、重复或超20候选必须在访问持久层之前拒绝。 */
    @Test
    void rejectsUnboundedCandidatesAndIncompletePaginationBeforeRepository() {
        var repository = mock(DeviceRuntimeCatalogRepository.class);
        var service = new DeviceRuntimeCatalogService(repository);
        UUID project = UUID.randomUUID();
        UUID model = UUID.randomUUID();
        UUID device = UUID.randomUUID();
        List<UUID> nullItem = new ArrayList<>();
        nullItem.add(null);
        for (List<UUID> ids : List.of(List.<UUID>of(), List.of(device, device), nullItem,
                IntStream.range(0, 21).mapToObj(index -> UUID.randomUUID()).toList())) {
            assertThatThrownBy(() -> service.find(project, model, ids, null, null, 20))
                    .isInstanceOf(BusinessException.class);
        }
        assertThatThrownBy(() -> service.find(project, model, null, null, null, 20))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.find(project, model, List.of(device), Instant.now(), null, 20))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.find(project, model, List.of(device), null, device, 20))
                .isInstanceOf(BusinessException.class);
        for (int limit : List.of(0, 52)) {
            assertThatThrownBy(() -> service.find(project, model, List.of(device), null, null, limit))
                    .isInstanceOf(BusinessException.class);
        }
        verifyNoInteractions(repository);
    }

    /** 上游可查询最多51行探测下一页，端口保持SQL提供的顺序与全部精确字段。 */
    @Test
    void mapsBoundedRepositoryFactsWithoutReplacingKeysetIdentity() {
        var repository = mock(DeviceRuntimeCatalogRepository.class);
        var service = new DeviceRuntimeCatalogService(repository);
        UUID project = UUID.randomUUID();
        UUID model = UUID.randomUUID();
        UUID device = UUID.randomUUID();
        Instant at = Instant.parse("2026-09-07T01:02:03.123456Z");
        when(repository.find(project, model, List.of(device), at, device, 51)).thenReturn(List.of(
                new DeviceRuntimeCatalogRepository.Item(device, "温度计", "ONLINE", model, at)));
        assertThat(service.find(project, model, List.of(device), at, device, 51))
                .containsExactly(new RuntimeDeviceCatalogItem(device, "温度计", "ONLINE", model, at));
    }
}
