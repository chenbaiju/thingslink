package com.things.link.bootstrap.ota;

import com.things.link.support.storage.MinioVersionedPrivateObjectStorage;
import com.things.link.support.storage.VersionedPrivateObjectStorage.InventoryPage;
import com.things.link.support.storage.VersionedPrivateObjectStorage.MultipartRef;
import com.things.link.support.storage.VersionedPrivateObjectStorage.VersionItem;
import com.things.link.support.storage.VersionedPrivateObjectStorage.WriteIdentity;
import com.things.link.support.storage.VersionedPrivateObjectStorage.WriteRequest;
import com.things.link.support.storage.VersionedStorageControl;
import com.things.link.support.storage.VersionedStorageException;
import io.minio.AbortMultipartUploadArgs;
import io.minio.CreateMultipartUploadArgs;
import io.minio.ListObjectsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioAsyncClient;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveBucketArgs;
import io.minio.RemoveObjectArgs;
import io.minio.SetBucketVersioningArgs;
import io.minio.UploadPartArgs;
import io.minio.messages.VersioningConfiguration;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
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

/** ADR0116：真实MinIO证明精确版本/分片盘点与回收，不把一次空页称为持久上传收束。 */
@Testcontainers
class VersionedArtifactRecoveryIntegrationTests {
    /** 仅限本测试独占容器。 */
    private static final String ACCESS = "recovery-test";
    /** 测试秘密不用于任何真实环境。 */
    private static final String SECRET = "recovery-test-secret";
    /** 随机映射端口，不读取或修改开发桶。 */
    @Container
    static final GenericContainer<?> MINIO = new GenericContainer<>(DockerImageName.parse(
            "minio/minio:RELEASE.2025-04-22T22-12-26Z"))
            .withEnv("MINIO_ROOT_USER", ACCESS).withEnv("MINIO_ROOT_PASSWORD", SECRET)
            .withCommand("server", "/data").withExposedPorts(9000)
            .waitingFor(Wait.forHttp("/minio/health/ready").forPort(9000)
                    .withStartupTimeout(Duration.ofSeconds(120)));
    /** JUnit负责删除上传临时文件。 */
    @TempDir
    Path temporary;
    /** 管理身份只负责建立和独立观察异常前提。 */
    private MinioClient admin;
    /** 独立SDK低层接口制造真实未完成multipart。 */
    private MinioAsyncClient multipartAdmin;
    /** 每例独占桶。 */
    private String bucket;
    /** 被测受限端口拥有自己的物理调用资源。 */
    private MinioVersionedPrivateObjectStorage storage;
    /** 即使中途断言失败也精确清理每个已创建multipart。 */
    private final List<MultipartRef> fixtureUploads = new ArrayList<>();

    /** 私桶启用版本化，SDK夹具与生产适配器相互独立。 */
    @BeforeEach
    void prepare() throws Exception {
        admin = MinioClient.builder().endpoint(endpoint()).credentials(ACCESS, SECRET).build();
        multipartAdmin = MinioAsyncClient.builder().endpoint(endpoint()).credentials(ACCESS, SECRET).build();
        bucket = "ota-recovery-" + UUID.randomUUID();
        admin.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
        versioning(VersioningConfiguration.Status.ENABLED);
        storage = new MinioVersionedPrivateObjectStorage(endpoint(), endpoint(), ACCESS, SECRET);
    }

    /** 清理全部已知multipart以及版本/删除标记，不依赖只删除当前键。 */
    @AfterEach
    void cleanup() throws Exception {
        if (storage != null) storage.close();
        try {
            for (MultipartRef upload : fixtureUploads) {
                try {
                    multipartAdmin.abortMultipartUpload(AbortMultipartUploadArgs.builder().bucket(upload.bucket())
                            .object(upload.objectKey()).uploadId(upload.uploadId()).build()).get(10, TimeUnit.SECONDS);
                } catch (java.util.concurrent.ExecutionException failure) {
                    if (!(failure.getCause() instanceof io.minio.errors.ErrorResponseException response)
                            || !"NoSuchUpload".equals(response.errorResponse().code())) throw failure;
                }
            }
            if (admin != null && bucket != null) {
                for (var result : admin.listObjects(ListObjectsArgs.builder().bucket(bucket).recursive(true)
                        .includeVersions(true).build())) {
                    var item = result.get();
                    admin.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(item.objectName())
                            .versionId(item.versionId()).build());
                }
                admin.removeBucket(RemoveBucketArgs.builder().bucket(bucket).build());
            }
        } finally {
            if (multipartAdmin != null) multipartAdmin.close();
            if (admin != null) admin.close();
        }
    }

    /** 删除标记也占真实页预算；过滤邻键后空页仍应推进游标且不能误删邻居。 */
    @Test
    void inventoriesAllVersionsAndDeleteMarkersWithoutTouchingPrefixNeighbors() throws Exception {
        String key = "attempt/固件 α +";
        put(key, new byte[] {1});
        put(key, new byte[] {2});
        admin.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(key).build());
        for (int i = 0; i < 3; i++) put(key + "-neighbor-" + i, new byte[] {(byte) i});
        List<String> before = adminVersions();
        List<VersionItem> versions = collect(cursor -> storage.listVersions(bucket, key, cursor, 1, control()));
        assertThat(versions).hasSize(3);
        assertThat(versions.stream().filter(VersionItem::deleteMarker).count()).isEqualTo(1);
        assertThat(versions).allSatisfy(item -> assertThat(item.version().objectKey()).isEqualTo(key));
        for (VersionItem item : versions) {
            storage.delete(item.version(), control());
            storage.delete(item.version(), control());
        }
        assertThat(collect(cursor -> storage.listVersions(bucket, key, cursor, 1, control()))).isEmpty();
        assertThat(adminVersions()).containsExactlyElementsOf(before.stream()
                .filter(item -> !item.startsWith(key + "|")).toList());
    }

    /** 真实分片已写入，但无完成对象；按精确上传ID分页取消且重复取消安全。 */
    @Test
    void inventoriesAndAbortsRealMultipartUploadsWithoutAbortingNeighbor() throws Exception {
        String key = "attempt/multipart 固件+";
        MultipartRef first = multipart(key);
        MultipartRef second = multipart(key);
        MultipartRef neighbor = multipart(key + "-neighbor");
        List<MultipartRef> uploads = collect(cursor -> storage.listMultipartUploads(bucket, key, cursor, 1, control()));
        assertThat(uploads).containsExactlyInAnyOrder(first, second);
        // 仅持有邻居uploadId不能跨key取消真实上传；错误精确身份按NoSuchUpload幂等处理。
        storage.abortMultipart(new MultipartRef(bucket, key, neighbor.uploadId()), control());
        for (MultipartRef upload : uploads) {
            storage.abortMultipart(upload, control());
            storage.abortMultipart(upload, control());
        }
        assertThat(collect(cursor -> storage.listMultipartUploads(bucket, key, cursor, 1, control()))).isEmpty();
        assertThat(collect(cursor -> storage.listMultipartUploads(bucket, neighbor.objectKey(), cursor, 1, control())))
                .containsExactly(neighbor);
        assertThat(collect(cursor -> storage.listVersions(bucket, key, cursor, 1, control()))).isEmpty();
    }

    /** 丢失成功响应的真实写入仍可盘点；先前空盘点不能阻止稍后版本出现。 */
    @Test
    void rediscoversLateAndUnknownWritesInsteadOfTreatingEarlierEmptyAsFinal() throws Exception {
        String key = "late-write";
        assertThat(storage.listVersions(bucket, key, null, 1, control()).items()).isEmpty();
        Path source = temporary.resolve("late.bin");
        Files.write(source, new byte[] {3, 1, 4});
        try (var proxy = new ArtifactStorageFaultProxyFixture(MINIO.getHost(), MINIO.getMappedPort(9000),
                "/" + bucket + "/" + key, ArtifactStorageFaultProxyFixture.Mode.DROP_RESPONSE);
             var proxied = new MinioVersionedPrivateObjectStorage(proxy.endpoint(), endpoint(), ACCESS, SECRET)) {
            assertThatThrownBy(() -> proxied.upload(new WriteRequest(new WriteIdentity(bucket, key, UUID.randomUUID()), source), control()))
                    .isInstanceOfSatisfying(VersionedStorageException.class, failure -> assertThat(failure.mayHaveWritten()).isTrue());
            assertThat(proxy.targetRequests()).isEqualTo(1);
        }
        List<VersionItem> recovered = collect(cursor -> storage.listVersions(bucket, key, cursor, 1, control()));
        assertThat(recovered).hasSize(1);
        storage.delete(recovered.getFirst().version(), control());
        assertThat(storage.listVersions(bucket, key, null, 1, control()).items()).isEmpty();
        put(key, new byte[] {9});
        assertThat(storage.listVersions(bucket, key, null, 1, control()).items()).hasSize(1);
    }

    /** 游标不可跨键/桶/盘点种类复用；返回集合不可由调用方改写。 */
    @Test
    void bindsCursorToInventoryScopeAndReturnsImmutablePages() throws Exception {
        put("cursor", new byte[] {1});
        put("cursor", new byte[] {2});
        var page = storage.listVersions(bucket, "cursor", null, 1, control());
        assertThat(page.hasMore()).isTrue();
        assertThat(page.nextCursor()).isNotBlank();
        assertThatThrownBy(() -> page.items().clear()).isInstanceOf(UnsupportedOperationException.class);
        fails(() -> storage.listVersions(bucket, "another", page.nextCursor(), 1, control()));
        fails(() -> storage.listVersions("another-bucket", "cursor", page.nextCursor(), 1, control()));
        fails(() -> storage.listMultipartUploads(bucket, "cursor", page.nextCursor(), 1, control()));
        fails(() -> storage.listVersions(bucket, "cursor", "malformed!", 1, control()));
        fails(() -> storage.listVersions(bucket, "cursor", null, 0, control()));
        fails(() -> storage.listMultipartUploads(bucket, "cursor", null, 501, control()));
    }

    /** 配置/权限和取消都是失败，不能返回伪造空页以放行项目删除。 */
    @Test
    void refusesCancellationAndUnreadableConfigurationInsteadOfReportingEmpty() {
        var cancelled = new VersionedStorageControl(Duration.ofSeconds(10), () -> true);
        fails(() -> storage.listVersions(bucket, "unknown", null, 1, cancelled));
        fails(() -> storage.listMultipartUploads(bucket, "unknown", null, 1, cancelled));
        try (var unauthorized = new MinioVersionedPrivateObjectStorage(endpoint(), endpoint(), "unknown-user", "unknown-secret")) {
            fails(() -> unauthorized.listVersions(bucket, "unknown", null, 1, control()));
            fails(() -> unauthorized.listMultipartUploads(bucket, "unknown", null, 1, control()));
        }
    }

    /** 历史null版本不能被伪造为可固定版本的清理身份。 */
    @Test
    void rejectsHistoricalNullVersionsEvenAfterVersioningIsReenabled() throws Exception {
        versioning(VersioningConfiguration.Status.SUSPENDED);
        put("legacy", new byte[] {0});
        versioning(VersioningConfiguration.Status.ENABLED);
        assertThatThrownBy(() -> storage.listVersions(bucket, "legacy", null, 1, control()))
                .isInstanceOfSatisfying(VersionedStorageException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(VersionedStorageException.Reason.INTEGRITY));
    }

    /** 测试夹具直接写多版本，不把此操作误认为生产允许复用尝试键。 */
    private void put(String key, byte[] value) throws Exception {
        admin.putObject(PutObjectArgs.builder().bucket(bucket).object(key)
                .stream(new ByteArrayInputStream(value), (long) value.length, -1L).build());
    }

    /** 创建真实未完成上传并写入非空part；不通过假列表替代对象存储证据。 */
    private MultipartRef multipart(String key) throws Exception {
        String id = multipartAdmin.createMultipartUpload(CreateMultipartUploadArgs.builder().bucket(bucket)
                .object(key).build()).get(10, TimeUnit.SECONDS).result().uploadId();
        MultipartRef reference = new MultipartRef(bucket, key, id);
        fixtureUploads.add(reference);
        assertThat(multipartAdmin.uploadPart(UploadPartArgs.builder().bucket(bucket).object(key).uploadId(id)
                .partNumber(1).data(new byte[] {1, 2, 3}, 3).build()).get(10, TimeUnit.SECONDS).part().etag()).isNotBlank();
        return reference;
    }

    /** 页数硬上限仅用于隔离测试，发现重复游标立即失败，不隐藏无限分页。 */
    private static <T> List<T> collect(Function<String, InventoryPage<T>> fetch) {
        List<T> all = new ArrayList<>();
        var seen = new HashSet<String>();
        String cursor = null;
        for (int i = 0; i < 32; i++) {
            InventoryPage<T> page = fetch.apply(cursor);
            assertThat(page.items().size()).isLessThanOrEqualTo(1);
            all.addAll(page.items());
            if (!page.hasMore()) {
                assertThat(page.nextCursor()).isNull();
                return all;
            }
            assertThat(page.nextCursor()).isNotBlank();
            assertThat(seen.add(page.nextCursor())).as("游标必须推进").isTrue();
            cursor = page.nextCursor();
        }
        throw new AssertionError("小规模固定夹具不应超过32个存储页");
    }

    /** 管理端全桶独立清单，用于发现错误前缀删除。 */
    private List<String> adminVersions() throws Exception {
        List<String> values = new ArrayList<>();
        for (var result : admin.listObjects(ListObjectsArgs.builder().bucket(bucket).recursive(true).includeVersions(true).build())) {
            var item = result.get();
            values.add(item.objectName() + "|" + item.versionId() + "|" + item.isDeleteMarker());
        }
        return values;
    }

    /** 固定操作预算，不能让测试无限阻塞。 */
    private static VersionedStorageControl control() {
        return new VersionedStorageControl(Duration.ofSeconds(10), () -> false);
    }

    /** 所有非法输入/权限/取消都必须传播稳定技术异常。 */
    private static void fails(Runnable operation) {
        assertThatThrownBy(operation::run).isInstanceOf(VersionedStorageException.class);
    }

    /** 版本化配置只用于模拟真实历史状态。 */
    private void versioning(VersioningConfiguration.Status status) throws Exception {
        admin.setBucketVersioning(SetBucketVersioningArgs.builder().bucket(bucket)
                .config(new VersioningConfiguration(status, null, null, null)).build());
    }

    /** 本例容器随机端口。 */
    private String endpoint() { return "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000); }
}
