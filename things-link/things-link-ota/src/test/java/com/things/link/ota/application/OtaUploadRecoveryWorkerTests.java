package com.things.link.ota.application;

import com.things.link.ota.domain.OtaUploadSession;
import com.things.link.support.storage.VersionedPrivateObjectStorage;
import com.things.link.support.storage.VersionedPrivateObjectStorage.InventoryPage;
import com.things.link.support.storage.VersionedPrivateObjectStorage.VersionItem;
import com.things.link.support.storage.VersionedPrivateObjectStorage.VersionRef;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 恢复反例验证租约能力不可刷新窃取，空盘点不能证明未知写入收束。 */
class OtaUploadRecoveryWorkerTests {
    /** 短事务状态替身。 */
    private final OtaUploadService service = mock(OtaUploadService.class);
    /** 外部对象端口替身，不冒充真实S3验收。 */
    private final VersionedPrivateObjectStorage storage = mock(VersionedPrivateObjectStorage.class);
    /** 使用真实非阻塞租约句柄，测试结束显式关闭线程。 */
    private OtaUploadProcessor processor;
    /** 本轮恢复编排。 */
    private OtaUploadRecoveryWorker worker;

    /** 启动生命周期使控制信号具有真实工作含义。 */
    @BeforeEach
    void start() {
        when(service.renew(any())).thenReturn(true);
        processor = new OtaUploadProcessor(service);
        worker = new OtaUploadRecoveryWorker(service, processor, true);
        worker.start();
    }

    /** 先取消worker，再收束自有心跳。 */
    @AfterEach
    void stop() { worker.stop(); processor.close(); }

    /** 回读发现新token时，旧领取不能用新能力进行任何物理对象操作。 */
    @Test
    void staleClaimCannotStealRefreshedLeaseToken() {
        OtaUploadSession claimed = session(UUID.randomUUID(), "UNKNOWN", false, false);
        OtaUploadSession newOwner = copy(claimed, UUID.randomUUID(), "UNKNOWN", false, false);
        when(service.reread(claimed)).thenReturn(newOwner);
        worker.recover(claimed);
        verify(service).postpone(claimed, "CANCELLED");
        verify(service, never()).requireStorage();
        verify(service, never()).recordVersion(any(), any());
        verify(service, never()).markCleanup(any(), any());
        verify(service, never()).finishCleanup(any());
        verifyNoInteractions(storage);
    }

    /** 相同token也可能已过期，首次真实续租失败时不能等五秒才停止外部操作。 */
    @Test
    void expiredUnchangedTokenCannotBeginStorageBeforeFirstHeartbeat() {
        OtaUploadSession claimed = session(UUID.randomUUID(), "UNKNOWN", false, false);
        when(service.renew(claimed)).thenReturn(false);
        worker.recover(claimed);
        verify(service).renew(claimed);
        verify(service, never()).reread(any());
        verify(service, never()).requireStorage();
        verify(service, never()).recordVersion(any(), any());
        verify(service, never()).finishCleanup(any());
        verifyNoInteractions(storage);
    }

    /** 已取消且写入已终结的UNKNOWN必须先转回收，再清精确版本，重盘点后结束。 */
    @Test
    void cancelledSettledUnknownMovesToCleanupAndRechecksEmptyInventory() {
        OtaUploadSession claimed = session(UUID.randomUUID(), "UNKNOWN", true, true);
        OtaUploadSession cleanup = copy(claimed, claimed.leaseToken(), "CLEANUP_PENDING", true, true);
        when(service.reread(claimed)).thenReturn(claimed, cleanup);
        when(service.requireStorage()).thenReturn(storage);
        VersionRef version = new VersionRef(claimed.bucket(), claimed.objectKey(), claimed.versionId());
        when(storage.listVersions(eq(claimed.bucket()), eq(claimed.objectKey()), eq(null), eq(32), any()))
                .thenReturn(new InventoryPage<>(List.of(new VersionItem(version, false)), null, false));
        when(storage.listVersions(eq(claimed.bucket()), eq(claimed.objectKey()), eq(null), eq(1), any()))
                .thenReturn(new InventoryPage<>(List.of(), null, false));
        when(storage.listMultipartUploads(eq(claimed.bucket()), eq(claimed.objectKey()), eq(null), eq(32), any()))
                .thenReturn(new InventoryPage<>(List.of(), null, false));
        when(storage.listMultipartUploads(eq(claimed.bucket()), eq(claimed.objectKey()), eq(null), eq(1), any()))
                .thenReturn(new InventoryPage<>(List.of(), null, false));
        worker.recover(claimed);
        InOrder order = inOrder(service, storage);
        order.verify(service).markCleanup(claimed, "CANCELLED");
        order.verify(storage).delete(eq(version), any());
        order.verify(storage).listVersions(eq(claimed.bucket()), eq(claimed.objectKey()), eq(null), eq(1), any());
        order.verify(storage).listMultipartUploads(eq(claimed.bucket()), eq(claimed.objectKey()), eq(null), eq(1), any());
        order.verify(service).finishCleanup(cleanup);
        verify(service, never()).adopt(any());
    }

    /** 丢回执后暂时空盘点只退避，不能设置writeSettled或完成清理。 */
    @Test
    void emptyUnknownInventoryNeverSettlesOrCleansPotentialLateWrite() {
        OtaUploadSession claimed = session(UUID.randomUUID(), "UNKNOWN", false, false);
        when(service.reread(claimed)).thenReturn(claimed);
        when(service.requireStorage()).thenReturn(storage);
        when(storage.listVersions(eq(claimed.bucket()), eq(claimed.objectKey()), eq(null), eq(2), any()))
                .thenReturn(new InventoryPage<>(List.of(), null, false));
        worker.recover(claimed);
        verify(service).postpone(claimed, "UNKNOWN_WRITE");
        verify(service, never()).recordVersion(any(), any());
        verify(service, never()).finishCleanup(any());
        verify(service, never()).markCleanup(any(), any());
        verify(storage, never()).delete(any(), any());
        verify(storage, never()).abortMultipart(any(), any());
    }

    /** 构造当前租约下的可能写入身份，时间只作为固定记录内容。 */
    private static OtaUploadSession session(UUID token, String status, boolean settled, boolean cancelled) {
        Instant time = Instant.parse("2026-09-12T00:00:00Z");
        return new OtaUploadSession(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), 1, 1, 1, "0".repeat(64), "ota-test", "exact/key",
                status, settled ? "version-1" : null, null, "1".repeat(64), "2".repeat(64), time,
                time.plusSeconds(3600), time.plusSeconds(120), time, settled ? time : null,
                cancelled ? time : null, null, time, token);
    }

    /** 保留完整会话身份，只调整明确受测的租约或生命周期字段。 */
    private static OtaUploadSession copy(OtaUploadSession original, UUID token, String status,
                                        boolean settled, boolean cancelled) {
        return new OtaUploadSession(original.id(), original.tenantId(), original.projectId(), original.firmwareId(),
                original.createdBy(), original.requestId(), original.projectGeneration(), original.expectedLength(),
                original.revision(), original.expectedSha256(), original.bucket(), original.objectKey(), status,
                settled ? "version-1" : null, original.failureCode(), original.keyDigest(), original.requestDigest(),
                original.createdAt(), original.expiresAt(), original.leaseUntil(), original.writeStartedAt(),
                settled ? original.createdAt() : null, cancelled ? original.createdAt() : null, null,
                original.nextAttemptAt(), token);
    }
}
