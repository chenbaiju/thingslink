package com.things.link.export.application;

import com.things.link.export.domain.ProjectExportClaim;
import com.things.link.export.domain.ProjectExportJob;
import com.things.link.export.domain.ProjectExportJobRepository;
import com.things.link.export.domain.ProjectExportStatus;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import com.things.link.support.storage.ObjectStorageException;
import com.things.link.support.storage.ObjectUploadAbortedException;
import com.things.link.support.storage.ObjectUploadControl;
import com.things.link.support.storage.PrivateObjectStorage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** ADR0075 决策4：上传失败、CAS失权与临时文件都必须按当前attempt独立收束。 */
class ProjectExportWorkerTests {

    /** 构造可真实删除的尝试目录，避免只验证AutoCloseable mock调用。 */
    @TempDir
    private Path temporaryRoot;

    /** 每例恢复线程范围，证明worker不会覆盖调用线程原上下文。 */
    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    /** MinIO上传失败进入可重试状态，且不完成、不立即删未写入对象并清空本地尝试目录。 */
    @Test
    void uploadFailureSchedulesRetryAndRemovesLocalAttempt() throws Exception {
        Fixture fixture = fixture();
        GeneratedProjectExport generated = generated(fixture.claim());
        when(fixture.generator().generate(eq(fixture.claim()), any())).thenReturn(generated);
        when(fixture.jobs().registerUpload(eq(fixture.claim().jobId()), eq(fixture.claim().leaseToken()),
                any(), any(), anyString(), eq(generated.snapshotAt()))).thenReturn(true);
        doThrow(new ObjectStorageException("storage unavailable", new IllegalStateException("test")))
                .when(fixture.storage()).upload(eq("export"), anyString(), eq(generated.archive()),
                        eq("application/zip"), any(), any(ObjectUploadControl.class));
        TenantScope previous = new TenantScope(UUID.randomUUID(), null, UUID.randomUUID());
        TenantContext.set(previous);

        fixture.worker().process(fixture.claim());

        verify(fixture.jobs()).fail(fixture.claim().jobId(), fixture.claim().leaseToken(),
                "STORAGE_UPLOAD", false);
        verify(fixture.completion(), never()).complete(any(), any(), anyString(), anyLong(), anyString());
        verify(fixture.storage(), never()).delete(anyString(), anyString());
        assertThat(Files.exists(generated.workingDirectory())).isFalse();
        assertThat(TenantContext.current()).contains(previous);
        fixture.worker().destroy();
    }

    /** 完成CAS失权只能删除本attempt随机upload对象，不能覆盖任务或触碰其他对象。 */
    @Test
    void completionFenceDeletesOnlyCurrentUploadAndLeavesDurableCleanup() throws Exception {
        Fixture fixture = fixture();
        GeneratedProjectExport generated = generated(fixture.claim());
        when(fixture.generator().generate(eq(fixture.claim()), any())).thenReturn(generated);
        when(fixture.jobs().registerUpload(eq(fixture.claim().jobId()), eq(fixture.claim().leaseToken()),
                any(), any(), anyString(), eq(generated.snapshotAt()))).thenReturn(true);
        when(fixture.completion().complete(eq(fixture.claim()), any(), anyString(),
                eq(generated.archiveSize()), eq(generated.archiveSha256()))).thenReturn(false);
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);

        fixture.worker().process(fixture.claim());

        verify(fixture.storage()).upload(eq("export"), key.capture(), eq(generated.archive()),
                eq("application/zip"), eq(Map.of(
                        "sha256", generated.archiveSha256(),
                        "export-id", fixture.claim().jobId().toString(),
                        "project-generation", Long.toString(fixture.claim().projectGeneration()))),
                any(ObjectUploadControl.class));
        verify(fixture.storage()).delete("export", key.getValue());
        assertThat(key.getValue()).startsWith("projects/" + fixture.claim().tenantId() + "/"
                + fixture.claim().projectId() + "/generations/" + fixture.claim().projectGeneration()
                + "/exports/" + fixture.claim().jobId() + "/");
        assertThat(key.getValue()).endsWith("/project-export-v1.zip");
        verify(fixture.jobs(), never()).fail(any(), any(), anyString(), anyBoolean());
        assertThat(Files.exists(generated.workingDirectory())).isFalse();
        fixture.worker().destroy();
    }

    /** 登记孤儿清理事实CAS失败时禁止上传，并立即删除本地制品。 */
    @Test
    void lostLeaseBeforeUploadNeverWritesObject() throws Exception {
        Fixture fixture = fixture();
        GeneratedProjectExport generated = generated(fixture.claim());
        when(fixture.generator().generate(eq(fixture.claim()), any())).thenReturn(generated);
        when(fixture.jobs().registerUpload(eq(fixture.claim().jobId()), eq(fixture.claim().leaseToken()),
                any(), any(), anyString(), eq(generated.snapshotAt()))).thenReturn(false);

        fixture.worker().process(fixture.claim());

        verify(fixture.storage(), never()).upload(
                anyString(), anyString(), any(), anyString(), any(), any(ObjectUploadControl.class));
        verify(fixture.completion(), never()).complete(any(), any(), anyString(), anyLong(), anyString());
        assertThat(Files.exists(generated.workingDirectory())).isFalse();
        fixture.worker().destroy();
    }

    /** 对象已被成功任务采用后，即使本地close失败也绝不能删除正式对象。 */
    @Test
    void localCleanupFailureAfterAdoptionNeverDeletesSucceededObject() {
        Fixture fixture = fixture();
        GeneratedProjectExport generated = mock(GeneratedProjectExport.class);
        Path archive = temporaryRoot.resolve("adopted.zip");
        Instant snapshotAt = Instant.parse("2026-09-04T12:34:56Z");
        when(generated.archive()).thenReturn(archive);
        when(generated.snapshotAt()).thenReturn(snapshotAt);
        when(generated.archiveSize()).thenReturn(9L);
        when(generated.archiveSha256()).thenReturn("d".repeat(64));
        doThrow(new IllegalStateException("local cleanup failed")).when(generated).close();
        when(fixture.generator().generate(eq(fixture.claim()), any())).thenReturn(generated);
        when(fixture.jobs().registerUpload(eq(fixture.claim().jobId()), eq(fixture.claim().leaseToken()),
                any(), any(), anyString(), eq(snapshotAt))).thenReturn(true);
        when(fixture.completion().complete(eq(fixture.claim()), any(), anyString(), eq(9L),
                eq("d".repeat(64)))).thenReturn(true);

        fixture.worker().process(fixture.claim());

        verify(fixture.storage(), never()).delete(anyString(), anyString());
        fixture.worker().destroy();
    }

    /** 完成事务服务端已提交但响应丢失时，权威成功事实必须阻止删除正式对象和反向失败。 */
    @Test
    void completionOutcomeUnknownKeepsAuthoritativelySucceededObject() throws Exception {
        Fixture fixture = fixture();
        GeneratedProjectExport generated = generated(fixture.claim());
        AtomicReference<String> uploadedKey = new AtomicReference<>();
        when(fixture.generator().generate(eq(fixture.claim()), any())).thenReturn(generated);
        when(fixture.jobs().registerUpload(eq(fixture.claim().jobId()), eq(fixture.claim().leaseToken()),
                any(), any(), anyString(), eq(generated.snapshotAt()))).thenReturn(true);
        doAnswer(invocation -> {
            uploadedKey.set(invocation.getArgument(1));
            return null;
        }).when(fixture.storage()).upload(eq("export"), anyString(), eq(generated.archive()),
                eq("application/zip"), any(), any(ObjectUploadControl.class));
        doThrow(new IllegalStateException("commit response lost")).when(fixture.completion())
                .complete(eq(fixture.claim()), any(), anyString(), eq(generated.archiveSize()),
                        eq(generated.archiveSha256()));
        when(fixture.jobs().findByIdentity(
                fixture.claim().tenantId(), fixture.claim().projectId(), fixture.claim().jobId()))
                .thenAnswer(invocation -> Optional.of(succeededJob(
                        fixture.claim(), uploadedKey.get(), generated.archiveSize(), generated.archiveSha256())));

        fixture.worker().process(fixture.claim());

        verify(fixture.storage(), never()).delete(anyString(), anyString());
        verify(fixture.jobs(), never()).fail(any(), any(), anyString(), anyBoolean());
        assertThat(Files.exists(generated.workingDirectory())).isFalse();
        fixture.worker().destroy();
    }

    /** 上传适配器按共享attempt控制报告总预算到期时，worker必须永久失败且不进入完成事务。 */
    @Test
    void uploadTimeoutTerminatesWholeAttemptAndNeverCompletes() throws Exception {
        Fixture fixture = fixture();
        GeneratedProjectExport generated = generated(fixture.claim());
        AtomicReference<ObjectUploadControl> sharedControl = new AtomicReference<>();
        doAnswer(invocation -> {
            sharedControl.set(invocation.getArgument(1));
            return generated;
        }).when(fixture.generator()).generate(eq(fixture.claim()), any());
        when(fixture.jobs().registerUpload(eq(fixture.claim().jobId()), eq(fixture.claim().leaseToken()),
                any(), any(), anyString(), eq(generated.snapshotAt()))).thenReturn(true);
        doAnswer(invocation -> {
            ObjectUploadControl control = invocation.getArgument(5);
            assertThat(control).isSameAs(sharedControl.get());
            assertThat(control.remainingNanos()).isPositive();
            throw new ObjectUploadAbortedException("attempt deadline", true);
        }).when(fixture.storage()).upload(eq("export"), anyString(), eq(generated.archive()),
                eq("application/zip"), any(), any(ObjectUploadControl.class));

        fixture.worker().process(fixture.claim());

        verify(fixture.jobs()).fail(
                fixture.claim().jobId(), fixture.claim().leaseToken(), "EXPORT_LIMIT", true);
        verify(fixture.completion(), never()).complete(any(), any(), anyString(), anyLong(), anyString());
        verify(fixture.storage(), never()).delete(anyString(), anyString());
        assertThat(Files.exists(generated.workingDirectory())).isFalse();
        fixture.worker().destroy();
    }

    /** 上传期间发现租约失权时不再完成或改写失败状态，预登记cleanup交由后继领取。 */
    @Test
    void uploadLeaseCancellationStopsWithoutOverwritingNewOwner() throws Exception {
        Fixture fixture = fixture();
        GeneratedProjectExport generated = generated(fixture.claim());
        when(fixture.generator().generate(eq(fixture.claim()), any())).thenReturn(generated);
        when(fixture.jobs().registerUpload(eq(fixture.claim().jobId()), eq(fixture.claim().leaseToken()),
                any(), any(), anyString(), eq(generated.snapshotAt()))).thenReturn(true);
        doAnswer(invocation -> {
            ObjectUploadControl control = invocation.getArgument(5);
            throw new ObjectUploadAbortedException("lease lost", control.timedOut());
        }).when(fixture.storage()).upload(eq("export"), anyString(), eq(generated.archive()),
                eq("application/zip"), any(), any(ObjectUploadControl.class));

        fixture.worker().process(fixture.claim());

        verify(fixture.completion(), never()).complete(any(), any(), anyString(), anyLong(), anyString());
        verify(fixture.jobs(), never()).fail(any(), any(), anyString(), anyBoolean());
        verify(fixture.storage(), never()).delete(anyString(), anyString());
        assertThat(Files.exists(generated.workingDirectory())).isFalse();
        fixture.worker().destroy();
    }

    /** 完成结果无法权威确认时不得猜测对象未采用；预登记的持久清理事实负责最终判定。 */
    @Test
    void completionOutcomeUnknownWithoutReadableJobLeavesObjectForDurableCleanup() throws Exception {
        Fixture fixture = fixture();
        GeneratedProjectExport generated = generated(fixture.claim());
        when(fixture.generator().generate(eq(fixture.claim()), any())).thenReturn(generated);
        when(fixture.jobs().registerUpload(eq(fixture.claim().jobId()), eq(fixture.claim().leaseToken()),
                any(), any(), anyString(), eq(generated.snapshotAt()))).thenReturn(true);
        doThrow(new IllegalStateException("commit result unavailable")).when(fixture.completion())
                .complete(eq(fixture.claim()), any(), anyString(), eq(generated.archiveSize()),
                        eq(generated.archiveSha256()));
        when(fixture.jobs().findByIdentity(
                fixture.claim().tenantId(), fixture.claim().projectId(), fixture.claim().jobId()))
                .thenReturn(Optional.empty());

        fixture.worker().process(fixture.claim());

        verify(fixture.storage(), never()).delete(anyString(), anyString());
        assertThat(Files.exists(generated.workingDirectory())).isFalse();
        fixture.worker().destroy();
    }

    /** 建立单次worker测试依赖；heartbeat首次运行在30秒后，不参与短单测时序。 */
    private Fixture fixture() {
        ProjectExportJobRepository jobs = mock(ProjectExportJobRepository.class);
        ProjectExportArchiveGenerator generator = mock(ProjectExportArchiveGenerator.class);
        PrivateObjectStorage storage = mock(PrivateObjectStorage.class);
        ProjectExportCompletionService completion = mock(ProjectExportCompletionService.class);
        ProjectExportClaim claim = new ProjectExportClaim(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 2,
                UUID.randomUUID(), 1, UUID.randomUUID());
        ProjectExportWorker worker = new ProjectExportWorker(jobs, generator, storage, completion, "unit-worker");
        return new Fixture(jobs, generator, storage, completion, worker, claim);
    }

    /** 在真实临时目录创建最小ZIP替身，close必须递归删除目录。 */
    private GeneratedProjectExport generated(ProjectExportClaim claim) throws Exception {
        Path directory = Files.createDirectory(temporaryRoot.resolve(claim.jobId().toString()));
        Path archive = directory.resolve("project-export-v1.zip");
        Files.writeString(archive, "zip-bytes");
        return new GeneratedProjectExport(
                directory, archive, Instant.parse("2026-09-04T12:34:56Z"),
                Files.size(archive), "c".repeat(64));
    }

    /** 建立与本attempt对象身份、大小和摘要全部一致的权威成功事实。 */
    private static ProjectExportJob succeededJob(ProjectExportClaim claim, String objectKey,
                                                  long size, String sha256) {
        Instant now = Instant.parse("2026-09-04T12:35:00Z");
        return new ProjectExportJob(
                claim.jobId(), claim.tenantId(), claim.projectId(), claim.projectGeneration(),
                claim.requesterAccountId(), ProjectExportStatus.SUCCEEDED, claim.attemptCount(),
                null, now.minusSeconds(4), objectKey, size, sha256, null,
                now.minusSeconds(60), now.minusSeconds(30), now, now.plusSeconds(86_400));
    }

    /** 单次worker所需依赖和领取身份。 */
    private record Fixture(ProjectExportJobRepository jobs,
                           ProjectExportArchiveGenerator generator,
                           PrivateObjectStorage storage,
                           ProjectExportCompletionService completion,
                           ProjectExportWorker worker,
                           ProjectExportClaim claim) {
    }
}
