package com.things.link.bootstrap.ota;

import com.things.link.support.storage.MinioVersionedPrivateObjectStorage;
import com.things.link.support.storage.VersionedPrivateObjectStorage;
import com.things.link.support.storage.VersionedPrivateObjectStorage.VersionRef;
import com.things.link.support.storage.VersionedPrivateObjectStorage.WriteIdentity;
import com.things.link.support.storage.VersionedPrivateObjectStorage.WriteRequest;
import com.things.link.support.storage.VersionedStorageControl;
import com.things.link.support.storage.VersionedStorageException;
import io.minio.GetObjectArgs;
import io.minio.ListObjectsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveBucketArgs;
import io.minio.RemoveObjectArgs;
import io.minio.SetBucketPolicyArgs;
import io.minio.SetBucketVersioningArgs;
import io.minio.messages.VersioningConfiguration;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 真实版本化MinIO、匿名HTTP与故障代理证明对象身份和物理取消，不认领业务发布授权。 */
@Testcontainers
class VersionedArtifactStorageIntegrationTests {
    /** 仅隔离容器内使用的测试账号，不能用于生产。 */
    private static final String ACCESS = "versioned-test";
    /** 仅隔离容器内使用的测试秘密，无生产用途。 */
    private static final String SECRET = "versioned-test-secret";
    /** 固定部署镜像、随机端口，测试结束由JUnit关闭。 */
    @Container
    static final GenericContainer<?> MINIO = new GenericContainer<>(DockerImageName.parse(
            "minio/minio:RELEASE.2025-04-22T22-12-26Z"))
            .withEnv("MINIO_ROOT_USER", ACCESS).withEnv("MINIO_ROOT_PASSWORD", SECRET)
            .withCommand("server", "/data").withExposedPorts(9000)
            .waitingFor(Wait.forHttp("/minio/health/ready").forPort(9000));
    /** JUnit专属本地文件，由框架最终回收。 */
    @TempDir
    Path temporary;
    /** 管理客户端仅用于建立和独立观察测试前提。 */
    private MinioClient admin;
    /** 每例专属桶，避免策略修改影响其他测试。 */
    private String bucket;
    /** 被测真实适配器拥有自身取消资源。 */
    private MinioVersionedPrivateObjectStorage storage;

    /** 每例创建私有且完整启用版本化的桶。 */
    @BeforeEach
    void prepare() throws Exception {
        admin = MinioClient.builder().endpoint(endpoint()).credentials(ACCESS, SECRET).build();
        bucket = "ota-" + UUID.randomUUID();
        admin.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
        setVersioning(VersioningConfiguration.Status.ENABLED);
        storage = new MinioVersionedPrivateObjectStorage(endpoint(), endpoint(), ACCESS, SECRET);
    }

    /** 明确枚举删除所有版本及删除标记，不能只删除当前键宣称清空。 */
    @AfterEach
    void cleanVersionsAndOwnedClients() throws Exception {
        if (storage != null) { storage.close(); }
        if (admin != null && bucket != null) {
            for (var result : admin.listObjects(ListObjectsArgs.builder().bucket(bucket).recursive(true).includeVersions(true).build())) {
                var item = result.get();
                admin.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(item.objectName()).versionId(item.versionId()).build());
            }
            admin.removeBucket(RemoveBucketArgs.builder().bucket(bucket).build());
            admin.close();
        }
    }

    /** 固定A版本不会因同键B写入漂移；签址带版本并真实支持Range，同时匿名访问拒绝。 */
    @Test
    void pinsVersionsVerifiesContentAndPresignsExactRange() throws Exception {
        byte[] first = "firmware-alpha".getBytes(StandardCharsets.UTF_8);
        WriteIdentity identity = identity("firmware");
        VersionRef version = storage.upload(request(identity, first), control());
        assertThat(version.versionId()).isNotBlank().isNotEqualTo("null");
        assertThat(storage.verify(version, first.length, sha(first), control()).sha256()).isEqualTo(sha(first));
        byte[] second = "firmware-bravo".getBytes(StandardCharsets.UTF_8);
        String secondVersion = admin.putObject(PutObjectArgs.builder().bucket(bucket).object(identity.objectKey())
                .stream(new ByteArrayInputStream(second), (long) second.length, -1L).build()).versionId();
        assertThat(storage.verify(version, first.length, sha(first), control()).version()).isEqualTo(version);
        URI signed = storage.presignGet(version, Duration.ofSeconds(60), control());
        assertThat(signed.getRawQuery()).contains("versionId=");
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpResponse<byte[]> full = client.send(HttpRequest.newBuilder(signed).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
            assertThat(full.statusCode()).isEqualTo(200);
            assertThat(full.body()).isEqualTo(first);
            HttpResponse<byte[]> range = client.send(HttpRequest.newBuilder(signed).header("Range", "bytes=1-4").GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            assertThat(range.statusCode()).isEqualTo(206);
            assertThat(range.body()).isEqualTo(java.util.Arrays.copyOfRange(first, 1, 5));
            for (String path : new String[] {"/" + bucket + "/" + identity.objectKey(), "/" + bucket + "?list-type=2"}) {
                assertThat(client.send(HttpRequest.newBuilder(URI.create(endpoint() + path)).GET().build(),
                        HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(403);
            }
            assertThat(client.send(HttpRequest.newBuilder(URI.create(endpoint() + "/" + bucket + "/" + identity.objectKey()))
                    .method("HEAD", HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.discarding())
                    .statusCode()).isEqualTo(403);
        }
        storage.delete(version, control());
        storage.delete(version, control());
        try (var stream = admin.getObject(GetObjectArgs.builder().bucket(bucket).object(identity.objectKey()).versionId(secondVersion).build())) {
            assertThat(stream.readAllBytes()).isEqualTo(second);
        }
    }

    /** 只有真实正文精确匹配才通过，长度、摘要和版本错误均不得返回成功对象。 */
    @Test
    void rejectsDigestLengthAndVersionMismatch() throws Exception {
        byte[] value = "firmware".getBytes(StandardCharsets.UTF_8);
        VersionRef version = storage.upload(request(identity("check"), value), control());
        rejects(() -> storage.verify(version, value.length + 1, sha(value), control()), VersionedStorageException.Reason.INTEGRITY);
        rejects(() -> storage.verify(version, value.length - 1, sha(value), control()), VersionedStorageException.Reason.INTEGRITY);
        rejects(() -> storage.verify(version, value.length, "0".repeat(64), control()), VersionedStorageException.Reason.INTEGRITY);
        rejects(() -> storage.verify(new VersionRef(bucket, "missing", UUID.randomUUID().toString()), value.length,
                sha(value), control()), VersionedStorageException.Reason.NOT_FOUND);
        assertThatThrownBy(() -> new VersionRef(bucket, "check", "null")).isInstanceOf(IllegalArgumentException.class);
        rejects(() -> storage.presignGet(version, Duration.ofSeconds(301), control()), VersionedStorageException.Reason.CONFIGURATION);
    }

    /** 版本暂停或公开策略均拒绝；配置读取权限失败不能当作私有桶。 */
    @Test
    void refusesUnqualifiedOrUnreadableBucket() throws Exception {
        setVersioning(VersioningConfiguration.Status.SUSPENDED);
        rejects(() -> storage.requireQualifiedBucket(bucket, control()), VersionedStorageException.Reason.CONFIGURATION);
        setVersioning(VersioningConfiguration.Status.ENABLED);
        admin.setBucketPolicy(SetBucketPolicyArgs.builder().bucket(bucket).config("""
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":{"AWS":["*"]},
                "Action":["s3:GetObject"],"Resource":["arn:aws:s3:::%s/*"]}]}
                """.formatted(bucket)).build());
        rejects(() -> storage.requireQualifiedBucket(bucket, control()), VersionedStorageException.Reason.CONFIGURATION);
        try (var wrongCredentials = new MinioVersionedPrivateObjectStorage(endpoint(), endpoint(), "no-access", "no-secret")) {
            assertThatThrownBy(() -> wrongCredentials.requireQualifiedBucket(bucket, control()))
                    .isInstanceOf(VersionedStorageException.class);
        }
    }

    /** 探测严格区分不存在、正确请求身份和冲突，不隐式认领其他调用的对象。 */
    @Test
    void probesOnlyTheExclusiveRequestIdentity() throws Exception {
        WriteIdentity identity = identity("probe");
        assertThat(storage.probe(identity, control()).status()).isEqualTo(VersionedPrivateObjectStorage.ProbeStatus.ABSENT);
        VersionRef version = storage.upload(request(identity, new byte[] {1, 2, 3}), control());
        assertThat(storage.probe(identity, control()).version()).isEqualTo(version);
        assertThat(storage.probe(new WriteIdentity(bucket, identity.objectKey(), UUID.randomUUID()), control()).status())
                .isEqualTo(VersionedPrivateObjectStorage.ProbeStatus.CONFLICT);
    }

    /** 真实资格GET首响应丢失仅恢复一次，随后只执行一次对象PUT。 */
    @Test
    void retriesLostQualificationResponseOnceBeforeSingleWrite() throws Exception {
        try (ArtifactStorageFaultProxyFixture proxy = new ArtifactStorageFaultProxyFixture(MINIO.getHost(),
                MINIO.getMappedPort(9000), "/" + bucket, ArtifactStorageFaultProxyFixture.Mode.DROP_FIRST_VERSIONING_RESPONSE);
             var proxied = new MinioVersionedPrivateObjectStorage(proxy.endpoint(), endpoint(), ACCESS, SECRET)) {
            byte[] bytes = "one-write-after-read-recovery".getBytes(StandardCharsets.UTF_8);
            var version = proxied.upload(request(identity("qualification-recovery"), bytes), control());
            assertThat(proxy.targetRequests()).isEqualTo(2);
            assertThat(proxy.putRequests()).isEqualTo(1);
            assertThat(storage.verify(version, bytes.length, sha(bytes), control()).size()).isEqualTo(bytes.length);
        }
    }

    /** 持续丢资格GET响应严格停止在两次，未进入PUT也不制造未知对象。 */
    @Test
    void stopsAfterTwoLostQualificationResponsesWithoutWriting() throws Exception {
        try (ArtifactStorageFaultProxyFixture proxy = new ArtifactStorageFaultProxyFixture(MINIO.getHost(),
                MINIO.getMappedPort(9000), "/" + bucket, ArtifactStorageFaultProxyFixture.Mode.DROP_ALL_VERSIONING_RESPONSES);
             var proxied = new MinioVersionedPrivateObjectStorage(proxy.endpoint(), endpoint(), ACCESS, SECRET)) {
            var target = identity("qualification-exhausted");
            assertThatThrownBy(() -> proxied.upload(request(target, new byte[] {1}), control()))
                    .isInstanceOfSatisfying(VersionedStorageException.class, failure -> {
                        assertThat(failure.reason()).isEqualTo(VersionedStorageException.Reason.UNAVAILABLE);
                        assertThat(failure.mayHaveWritten()).isFalse();
                        assertThat(failure.getCause()).isNull();
                    });
            assertThat(proxy.targetRequests()).isEqualTo(2);
            assertThat(proxy.putRequests()).isZero();
            assertThat(storage.probe(target, control()).status()).isEqualTo(VersionedPrivateObjectStorage.ProbeStatus.ABSENT);
        }
    }

    /** 服务端已写入但客户端未收到响应时，不盲目重试；按稳定请求身份恢复真实版本。 */
    @Test
    void recoversCommittedWriteAfterResponseLoss() throws Exception {
        try (ArtifactStorageFaultProxyFixture proxy = new ArtifactStorageFaultProxyFixture(MINIO.getHost(), MINIO.getMappedPort(9000),
                "/" + bucket + "/lost", ArtifactStorageFaultProxyFixture.Mode.DROP_RESPONSE);
             var proxied = new MinioVersionedPrivateObjectStorage(proxy.endpoint(), endpoint(), ACCESS, SECRET)) {
            WriteIdentity identity = identity("lost");
            byte[] value = "committed-upload".getBytes(StandardCharsets.UTF_8);
            assertThatThrownBy(() -> proxied.upload(request(identity, value), control()))
                    .isInstanceOfSatisfying(VersionedStorageException.class, failure -> assertThat(failure.mayHaveWritten()).isTrue());
            assertThat(proxy.targetRequests()).isEqualTo(1);
            var restored = storage.probe(identity, control());
            assertThat(restored.status()).isEqualTo(VersionedPrivateObjectStorage.ProbeStatus.MATCHED);
            assertThat(storage.verify(restored.version(), value.length, sha(value), control()).size()).isEqualTo(value.length);
        }
    }

    /** 已写成功的500响应不能被SDK自动重试而制造多个版本。 */
    @Test
    void refusesStatusRetryAfterCommittedWrite() throws Exception {
        try (ArtifactStorageFaultProxyFixture proxy = new ArtifactStorageFaultProxyFixture(MINIO.getHost(),
                MINIO.getMappedPort(9000), "/" + bucket + "/retry", ArtifactStorageFaultProxyFixture.Mode.ERROR_RESPONSE);
             var proxied = new MinioVersionedPrivateObjectStorage(proxy.endpoint(), endpoint(), ACCESS, SECRET)) {
            WriteIdentity identity = identity("retry");
            byte[] value = "one-write-only".getBytes(StandardCharsets.UTF_8);
            assertThatThrownBy(() -> proxied.upload(request(identity, value), control()))
                    .isInstanceOfSatisfying(VersionedStorageException.class,
                            failure -> assertThat(failure.mayHaveWritten()).isTrue());
            assertThat(proxy.targetRequests()).isEqualTo(1);
            var restored = storage.probe(identity, control());
            assertThat(restored.status()).isEqualTo(VersionedPrivateObjectStorage.ProbeStatus.MATCHED);
            assertThat(storage.verify(restored.version(), value.length, sha(value), control()).size()).isEqualTo(value.length);
            int versions = 0;
            for (var entry : admin.listObjects(ListObjectsArgs.builder().bucket(bucket).prefix("retry")
                    .recursive(true).includeVersions(true).build())) {
                assertThat(entry.get().objectName()).isEqualTo("retry");
                versions++;
            }
            assertThat(versions).isEqualTo(1);
        }
    }

    /** 完成分片已提交但500响应丢失时不得重发Complete，保留对象供恢复。 */
    @Test
    void refusesMultipartCompletionRetryAfterCommittedWrite() throws Exception {
        try (ArtifactStorageFaultProxyFixture proxy = new ArtifactStorageFaultProxyFixture(MINIO.getHost(),
                MINIO.getMappedPort(9000), "/" + bucket + "/complete", ArtifactStorageFaultProxyFixture.Mode.ERROR_COMPLETE);
             var proxied = new MinioVersionedPrivateObjectStorage(proxy.endpoint(), endpoint(), ACCESS, SECRET)) {
            byte[] value = new byte[6 * 1024 * 1024];
            WriteIdentity identity = identity("complete");
            assertThatThrownBy(() -> proxied.upload(request(identity, value), control()))
                    .isInstanceOfSatisfying(VersionedStorageException.class,
                            failure -> assertThat(failure.mayHaveWritten()).isTrue());
            assertThat(proxy.targetRequests()).isEqualTo(1);
            var restored = storage.probe(identity, control());
            assertThat(restored.status()).isEqualTo(VersionedPrivateObjectStorage.ProbeStatus.MATCHED);
            assertThat(storage.verify(restored.version(), value.length, sha(value), control()).size()).isEqualTo(value.length);
        }
    }

    /** 阻塞正文取消必须实际关闭本次连接，同时让同适配器的并行正常读取完成。 */
    @Test
    void cancellationClosesOnlyTheStalledCall() throws Exception {
        byte[] value = new byte[8192];
        VersionRef slow = storage.upload(request(identity("slow"), value), control());
        VersionRef fast = storage.upload(request(identity("fast"), value), control());
        try (ArtifactStorageFaultProxyFixture proxy = new ArtifactStorageFaultProxyFixture(MINIO.getHost(), MINIO.getMappedPort(9000),
                "/" + bucket + "/slow", ArtifactStorageFaultProxyFixture.Mode.STALL_BODY);
             var proxied = new MinioVersionedPrivateObjectStorage(proxy.endpoint(), endpoint(), ACCESS, SECRET);
             var executor = Executors.newFixedThreadPool(2)) {
            AtomicBoolean cancel = new AtomicBoolean();
            var stalled = executor.submit(() -> {
                rejects(() -> proxied.verify(slow, value.length, sha(value),
                        new VersionedStorageControl(Duration.ofSeconds(20), cancel::get)), VersionedStorageException.Reason.CANCELLED);
                return true;
            });
            assertThat(proxy.awaitTarget(5, TimeUnit.SECONDS)).isTrue();
            proxy.holdCompanion("/" + bucket + "/fast");
            var healthy = executor.submit(() -> proxied.verify(fast, value.length, sha(value), control()));
            assertThat(proxy.awaitCompanion(5, TimeUnit.SECONDS)).isTrue();
            assertThat(healthy.isDone()).isFalse();
            cancel.set(true);
            assertThat(stalled.get(5, TimeUnit.SECONDS)).isTrue();
            assertThat(proxy.awaitClientClosed(3, TimeUnit.SECONDS)).isTrue();
            proxy.releaseCompanion();
            assertThat(healthy.get(5, TimeUnit.SECONDS).size()).isEqualTo(value.length);
        }
    }

    /** 截止必须中断已进入响应正文的物理读取，不能仅在下一次read前检查。 */
    @Test
    void deadlineClosesStalledBody() throws Exception {
        byte[] value = new byte[8192];
        VersionRef slow = storage.upload(request(identity("deadline"), value), control());
        try (ArtifactStorageFaultProxyFixture proxy = new ArtifactStorageFaultProxyFixture(MINIO.getHost(), MINIO.getMappedPort(9000),
                "/" + bucket + "/deadline", ArtifactStorageFaultProxyFixture.Mode.STALL_BODY);
             var proxied = new MinioVersionedPrivateObjectStorage(proxy.endpoint(), endpoint(), ACCESS, SECRET)) {
            long started = System.nanoTime();
            rejects(() -> proxied.verify(slow, value.length, sha(value),
                    new VersionedStorageControl(Duration.ofSeconds(5), () -> false)), VersionedStorageException.Reason.TIMEOUT);
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(8));
            assertThat(proxy.awaitTarget(1, TimeUnit.SECONDS)).isTrue();
            assertThat(proxy.awaitClientClosed(3, TimeUnit.SECONDS)).isTrue();
        }
    }

    /** 从未启用版本的独立桶不能通过资格检查，也不能触发上传。 */
    @Test
    void refusesNeverVersionedBucket() throws Exception {
        String plain = "plain-" + UUID.randomUUID();
        admin.makeBucket(MakeBucketArgs.builder().bucket(plain).build());
        try {
            rejects(() -> storage.requireQualifiedBucket(plain, control()), VersionedStorageException.Reason.CONFIGURATION);
            WriteIdentity identity = new WriteIdentity(plain, "rejected", UUID.randomUUID());
            assertThatThrownBy(() -> storage.upload(request(identity, new byte[] {1}), control()))
                    .isInstanceOfSatisfying(VersionedStorageException.class, error -> {
                        assertThat(error.reason()).isEqualTo(VersionedStorageException.Reason.CONFIGURATION);
                        assertThat(error.mayHaveWritten()).isFalse();
                    });
            assertThat(admin.listObjects(ListObjectsArgs.builder().bucket(plain).build()).iterator().hasNext()).isFalse();
        } finally {
            admin.removeBucket(RemoveBucketArgs.builder().bucket(plain).build());
        }
    }

    /** 组件关闭中断已进入正文的活跃Call，并拒绝关闭后的新操作。 */
    @Test
    void closingComponentCancelsActiveBodyRead() throws Exception {
        byte[] value = new byte[8192];
        VersionRef version = storage.upload(request(identity("closing"), value), control());
        try (ArtifactStorageFaultProxyFixture proxy = new ArtifactStorageFaultProxyFixture(MINIO.getHost(),
                MINIO.getMappedPort(9000), "/" + bucket + "/closing", ArtifactStorageFaultProxyFixture.Mode.STALL_BODY);
             var proxied = new MinioVersionedPrivateObjectStorage(proxy.endpoint(), endpoint(), ACCESS, SECRET);
             var executor = Executors.newSingleThreadExecutor()) {
            var active = executor.submit(() -> {
                rejects(() -> proxied.verify(version, value.length, sha(value), control()),
                        VersionedStorageException.Reason.CANCELLED);
                return true;
            });
            assertThat(proxy.awaitTarget(5, TimeUnit.SECONDS)).isTrue();
            assertThat(active.isDone()).isFalse();
            long started = System.nanoTime();
            proxied.close();
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));
            assertThat(active.get(3, TimeUnit.SECONDS)).isTrue();
            assertThat(proxy.awaitClientClosed(3, TimeUnit.SECONDS)).isTrue();
            rejects(() -> proxied.requireQualifiedBucket(bucket, control()), VersionedStorageException.Reason.CANCELLED);
        }
    }

    /** 每例独占键和请求UUID，域级互斥由后续上传会话实现。 */
    private WriteIdentity identity(String key) { return new WriteIdentity(bucket, key, UUID.randomUUID()); }
    /** 本地完整文件仅在测试临时目录，写入完成后不再修改。 */
    private WriteRequest request(WriteIdentity identity, byte[] bytes) throws Exception {
        Path path = temporary.resolve(UUID.randomUUID().toString());
        Files.write(path, bytes);
        return new WriteRequest(identity, path);
    }
    /** 真实容器地址不与本机部署数据混用。 */
    private static String endpoint() { return "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000); }
    /** 单个测试操作预算，不复用过期控制器。 */
    private static VersionedStorageControl control() { return new VersionedStorageControl(Duration.ofSeconds(20), () -> false); }
    /** 正文独立SHA256，不从对象元数据取得期望摘要。 */
    private static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    /** 版本策略只改本例桶。 */
    private void setVersioning(VersioningConfiguration.Status status) throws Exception {
        admin.setBucketVersioning(SetBucketVersioningArgs.builder().bucket(bucket)
                .config(new VersioningConfiguration(status, null, null, null)).build());
    }
    /** 固定原因码与无泄露异常链。 */
    private static void rejects(CheckedAction action, VersionedStorageException.Reason reason) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(VersionedStorageException.class, failure -> {
            assertThat(failure.reason()).isEqualTo(reason);
            assertThat(failure.getCause()).isNull();
        });
    }
    /** 允许测试行为携带受检I/O异常，失败仍由断言定位。 */
    @FunctionalInterface
    private interface CheckedAction { void run() throws Exception; }
}
