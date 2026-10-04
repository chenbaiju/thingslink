package com.things.link.bootstrap.export;

import com.things.link.bootstrap.fixture.ProjectExportMinioFixture;

import com.things.link.support.storage.MinioPrivateObjectStorage;
import com.things.link.support.storage.ObjectStorageException;
import com.things.link.support.storage.ObjectUploadAbortedException;
import com.things.link.support.storage.ObjectUploadControl;
import com.things.link.support.storage.PrivateObjectStorage;
import io.minio.MinioAsyncClient;
import io.minio.PutObjectArgs;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** ADR0075 决策4：项目导出必须通过真实固定版本 MinIO 上传和删除私有对象。 */
class ProjectExportObjectStorageTests {

    /** 每例独占临时目录，验证适配器只读取调用方给出的完整制品。 */
    @TempDir
    private Path temporaryDirectory;

    /** 真实上传字节和随后删除必须能由独立SDK客户端观察，不能只验证mock调用。 */
    @Test
    void uploadsExactArtifactAndDeletesOnlyTheNamedObject() throws Exception {
        PrivateObjectStorage storage = storage();
        String prefix = "projects/test/" + UUID.randomUUID() + "/";
        String adoptedKey = prefix + "adopted/project-export-v1.zip";
        String neighbourKey = prefix + "neighbour/project-export-v1.zip";
        byte[] adopted = "exact-export-zip".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] neighbour = "other-attempt".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Path adoptedFile = write("adopted.zip", adopted);
        Path neighbourFile = write("neighbour.zip", neighbour);

        try {
            storage.upload(ProjectExportMinioFixture.BUCKET, adoptedKey, adoptedFile,
                    "application/zip", Map.of("sha256", "fixture-digest"));
            storage.upload(ProjectExportMinioFixture.BUCKET, neighbourKey, neighbourFile,
                    "application/zip", Map.of());

            assertThat(ProjectExportMinioFixture.readObject(adoptedKey)).isEqualTo(adopted);
            assertThat(ProjectExportMinioFixture.readObject(neighbourKey)).isEqualTo(neighbour);

            storage.delete(ProjectExportMinioFixture.BUCKET, adoptedKey);
            assertThat(ProjectExportMinioFixture.listObjectKeys(prefix)).containsExactly(neighbourKey);
            // MinIO删除不存在对象按幂等成功处理，清理worker重放不得制造新失败。
            storage.delete(ProjectExportMinioFixture.BUCKET, adoptedKey);
        } finally {
            ProjectExportMinioFixture.deletePrefix(prefix);
        }
    }

    /** 服务端拒绝上传时必须转换成稳定存储异常，供worker进入持久重试而非误标成功。 */
    @Test
    void reportsRealServerRejectionAsStorageFailure() throws Exception {
        PrivateObjectStorage storage = storage();
        Path artifact = write("rejected.zip", new byte[]{1, 2, 3});
        String missingBucket = "missing-" + UUID.randomUUID();

        assertThatThrownBy(() -> storage.upload(missingBucket, "export.zip", artifact,
                "application/zip", Map.of()))
                .isInstanceOf(ObjectStorageException.class)
                .hasMessage("私有对象上传失败");
    }

    /** 异步上传未返回时必须按单调截止主动取消底层请求，不能让heartbeat无限续租。 */
    @Test
    void cancelsBlockedUploadAtAttemptDeadline() throws Exception {
        MinioAsyncClient async = mock(MinioAsyncClient.class);
        CompletableFuture<Object> pending = new CompletableFuture<>();
        doReturn(pending).when(async).putObject(any(PutObjectArgs.class));
        PrivateObjectStorage storage = new MinioPrivateObjectStorage(
                ProjectExportMinioFixture.client(), ProjectExportMinioFixture.client(), async);
        Path artifact = write("timeout.zip", new byte[]{1, 2, 3});
        ObjectUploadControl control = ObjectUploadControl.start(Duration.ofMillis(25), () -> false);

        assertThatThrownBy(() -> storage.upload(ProjectExportMinioFixture.BUCKET, "timeout/export.zip",
                artifact, "application/zip", Map.of(), control))
                .isInstanceOfSatisfying(ObjectUploadAbortedException.class,
                        failure -> assertThat(failure.timedOut()).isTrue());
        assertThat(pending.isCancelled()).isTrue();
    }

    /** 租约取消与时间到期必须可区分，且同样取消仍在网络中的MinIO future。 */
    @Test
    void cancelsBlockedUploadWhenLeaseIsLost() throws Exception {
        MinioAsyncClient async = mock(MinioAsyncClient.class);
        CompletableFuture<Object> pending = new CompletableFuture<>();
        doReturn(pending).when(async).putObject(any(PutObjectArgs.class));
        PrivateObjectStorage storage = new MinioPrivateObjectStorage(
                ProjectExportMinioFixture.client(), ProjectExportMinioFixture.client(), async);
        Path artifact = write("cancelled.zip", new byte[]{4, 5, 6});
        AtomicBoolean cancelled = new AtomicBoolean(true);
        ObjectUploadControl control = ObjectUploadControl.start(Duration.ofMinutes(15), cancelled::get);

        assertThatThrownBy(() -> storage.upload(ProjectExportMinioFixture.BUCKET, "cancelled/export.zip",
                artifact, "application/zip", Map.of(), control))
                .isInstanceOfSatisfying(ObjectUploadAbortedException.class,
                        failure -> assertThat(failure.timedOut()).isFalse());
        // 上传开始前即失权时不建立future，底层无需取消；关键是绝不能调用网络。
        assertThat(pending.isDone()).isFalse();
        verify(async, never()).putObject(any(PutObjectArgs.class));
    }

    /** 构造使用同一真实服务端的internal/external客户端；5g1不在此签发下载地址。 */
    private static PrivateObjectStorage storage() {
        return new MinioPrivateObjectStorage(
                ProjectExportMinioFixture.client(), ProjectExportMinioFixture.client());
    }

    /**
     * 在Junit管理的临时目录写入制品。
     * @param filename 文件名
     * @param content 文件字节
     * @return 可上传路径
     */
    private Path write(String filename, byte[] content) throws Exception {
        Path path = temporaryDirectory.resolve(filename);
        Files.write(path, content);
        return path;
    }
}
