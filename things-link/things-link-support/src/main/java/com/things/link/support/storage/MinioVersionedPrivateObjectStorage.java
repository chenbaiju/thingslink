package com.things.link.support.storage;

import io.minio.AbortMultipartUploadArgs;
import io.minio.ListObjectVersionsArgs;
import io.minio.ListMultipartUploadsArgs;
import io.minio.MinioAsyncClient;
import io.minio.messages.Item;
import io.minio.GetBucketPolicyArgs;
import io.minio.GetBucketVersioningArgs;
import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.Http;
import io.minio.MinioClient;
import io.minio.ObjectWriteResponse;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import io.minio.errors.ErrorResponseException;
import io.minio.messages.VersioningConfiguration;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import okhttp3.Call;
import okhttp3.ConnectionPool;
import okhttp3.EventListener;
import okhttp3.OkHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import com.things.link.support.storage.VersionedStorageException.Reason;

/** 每次操作隔离实际HTTP调用及截止探针，固定版本与私桶资格不会借用导出接口语义。 */
@Component
@ConditionalOnProperty(prefix = "things-link.storage", name = {
        "internal-endpoint", "external-endpoint", "access-key", "secret-key"})
public class MinioVersionedPrivateObjectStorage implements VersionedPrivateObjectStorage, AutoCloseable {
    /** 只记录异常类型和固定SDK分类，禁止输出正文、异常链或对象地址。 */
    private static final Logger LOG = LoggerFactory.getLogger(MinioVersionedPrivateObjectStorage.class);
    /** 服务端网络地址，只来自受控配置。 */
    private final String internalEndpoint;
    /** 签名输出网络地址，签发后禁止改写host。 */
    private final String externalEndpoint;
    /** 服务端访问身份，禁止写入日志。 */
    private final String accessKey;
    /** 服务端秘密，禁止附带在异常中。 */
    private final String secretKey;
    /** 本组件自有连接池与调度器，操作共享资源但不共享取消作用域。 */
    private final OkHttpClient baseHttp = new OkHttpClient.Builder().retryOnConnectionFailure(false)
            .followRedirects(false).followSslRedirects(false)
            .addInterceptor(new Http.StatusRetryInterceptor(Set.of(), 0, 1)).build();
    /** 只运行非阻塞截止探测的自有后台线程。 */
    private final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "versioned-storage-cancellation");
        thread.setDaemon(true);
        return thread;
    });
    /** 当前组件拥有的操作范围。 */
    private final Set<Scope> scopes = ConcurrentHashMap.newKeySet();
    /** 关闭后拒绝启动新调用。 */
    private final AtomicBoolean closed = new AtomicBoolean();

    /** 四项配置显式装配，不提供测试替身或成功默认值。 */
    public MinioVersionedPrivateObjectStorage(
            @Value("${things-link.storage.internal-endpoint}") String internalEndpoint,
            @Value("${things-link.storage.external-endpoint}") String externalEndpoint,
            @Value("${things-link.storage.access-key}") String accessKey,
            @Value("${things-link.storage.secret-key}") String secretKey) {
        this.internalEndpoint = internalEndpoint;
        this.externalEndpoint = externalEndpoint;
        this.accessKey = accessKey;
        this.secretKey = secretKey;
    }

    /** 验证每个键都版本化且没有桶策略，不推定外部管理员后续不会修改配置。 */
    @Override
    public void requireQualifiedBucket(String bucket, VersionedStorageControl control) {
        execute(control, scope -> { qualified(scope, bucket); return null; });
    }

    /** 上传进入put之前核对资格，可能已发送的任何失败均保留写入未知事实。 */
    @Override
    public VersionRef upload(WriteRequest request, VersionedStorageControl control) {
        return execute(control, scope -> {
            if (request == null || request.source() == null) { throw failure(Reason.CONFIGURATION, false); }
            identity(request.identity());
            WriteIdentity id = request.identity();
            qualified(scope, id.bucket());
            try (InputStream source = Files.newInputStream(request.source())) {
                long size = Files.size(request.source());
                scope.check();
                scope.mayHaveWritten = true;
                ObjectWriteResponse response = scope.internal.putObject(PutObjectArgs.builder()
                        .bucket(id.bucket()).object(id.objectKey()).contentType("application/octet-stream")
                        .userMetadata(Map.of("tc-request-id", id.requestId().toString()))
                        .stream(source, size, -1L).maxRetries(0).delayMs(0).build());
                scope.check();
                if (!validVersion(response.versionId())) { throw failure(Reason.RESULT_UNKNOWN, true); }
                return new VersionRef(id.bucket(), id.objectKey(), response.versionId());
            }
        });
    }

    /** 只有NoSuchKey证明不存在；匹配元数据后仍须固定版本验正文。 */
    @Override
    public ProbeResult probe(WriteIdentity identity, VersionedStorageControl control) {
        return execute(control, scope -> {
            identity(identity);
            qualified(scope, identity.bucket());
            StatObjectResponse response;
            try {
                response = scope.internal.statObject(StatObjectArgs.builder().bucket(identity.bucket())
                        .object(identity.objectKey()).build());
            } catch (ErrorResponseException error) {
                if ("NoSuchKey".equals(error.errorResponse().code())) {
                    return new ProbeResult(ProbeStatus.ABSENT, null);
                }
                throw error;
            }
            if (!identity.requestId().toString().equals(response.userMetadata().getFirst("tc-request-id"))) {
                return new ProbeResult(ProbeStatus.CONFLICT, null);
            }
            if (!validVersion(response.versionId())) { throw failure(Reason.INTEGRITY, false); }
            return new ProbeResult(ProbeStatus.MATCHED,
                    new VersionRef(identity.bucket(), identity.objectKey(), response.versionId()));
        });
    }

    /** 固定64KiB缓冲且读到EOF；超长立即中止，ETag不能替代SHA256。 */
    @Override
    public VerifiedObject verify(VersionRef version, long expectedLength, String expectedSha256,
                                 VersionedStorageControl control) {
        return execute(control, scope -> {
            version(version);
            if (expectedLength < 0 || expectedSha256 == null || !expectedSha256.matches("[0-9a-f]{64}")) {
                throw failure(Reason.CONFIGURATION, false);
            }
            qualified(scope, version.bucket());
            StatObjectResponse stat = scope.internal.statObject(StatObjectArgs.builder().bucket(version.bucket())
                    .object(version.objectKey()).versionId(version.versionId()).build());
            if (!version.versionId().equals(stat.versionId()) || stat.size() != expectedLength) {
                throw failure(Reason.INTEGRITY, false);
            }
            try (GetObjectResponse response = scope.internal.getObject(GetObjectArgs.builder().bucket(version.bucket())
                    .object(version.objectKey()).versionId(version.versionId()).build())) {
                if (!version.versionId().equals(response.headers().get("x-amz-version-id"))) {
                    throw failure(Reason.INTEGRITY, false);
                }
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                byte[] buffer = new byte[65_536];
                long size = 0;
                while (true) {
                    scope.check();
                    int read = response.read(buffer);
                    if (read < 0) { break; }
                    if (read > expectedLength - size) { throw failure(Reason.INTEGRITY, false); }
                    size += read;
                    digest.update(buffer, 0, read);
                }
                scope.check();
                String sha256 = HexFormat.of().formatHex(digest.digest());
                if (size != expectedLength || !sha256.equals(expectedSha256)) {
                    throw failure(Reason.INTEGRITY, false);
                }
                return new VerifiedObject(version, size, sha256);
            }
        });
    }

    /** 固定版本短地址仅证明签名构造，不证明接收设备有权下载。 */
    @Override
    public URI presignGet(VersionRef version, Duration ttl, VersionedStorageControl control) {
        return execute(control, scope -> {
            version(version);
            if (ttl == null || ttl.compareTo(Duration.ofSeconds(1)) < 0
                    || ttl.compareTo(Duration.ofSeconds(300)) > 0 || ttl.getNano() != 0) {
                throw failure(Reason.CONFIGURATION, false);
            }
            qualified(scope, version.bucket());
            return URI.create(scope.external.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .bucket(version.bucket()).object(version.objectKey()).versionId(version.versionId())
                    .method(Http.Method.GET).expiry((int) ttl.toSeconds()).build()));
        });
    }

    /** 只删除精确版本，服务端不存在结果不能误删其他版本。 */
    @Override
    public void delete(VersionRef version, VersionedStorageControl control) {
        execute(control, scope -> {
            version(version);
            qualified(scope, version.bucket());
            try {
                scope.internal.removeObject(RemoveObjectArgs.builder().bucket(version.bucket())
                        .object(version.objectKey()).versionId(version.versionId()).build());
            } catch (ErrorResponseException error) {
                String code = error.errorResponse().code();
                if (!"NoSuchKey".equals(code) && !"NoSuchVersion".equals(code)) { throw error; }
            }
            return null;
        });
    }

    /** 单个provider页面覆盖版本和删除标记，精确过滤相邻键但保持provider游标。 */
    @Override
    public InventoryPage<VersionItem> listVersions(String bucket, String objectKey, String cursor, int limit,
                                                  VersionedStorageControl control) {
        return execute(control, scope -> {
            inventoryInput(bucket, objectKey, limit);
            var position = VersionedInventoryCursor.decode(cursor, "versions", bucket, objectKey);
            qualified(scope, bucket);
            var result = await(scope, scope.async.listObjectVersions(ListObjectVersionsArgs.builder().bucket(bucket)
                    .prefix(objectKey).maxKeys(limit).encodingType("url").keyMarker(position.keyMarker())
                    .versionIdMarker(position.idMarker()).build())).result();
            if (!bucket.equals(result.name()) || result.contents().size() + result.deleteMarkers().size() > limit
                    || !result.commonPrefixes().isEmpty()) { throw failure(Reason.INTEGRITY, false); }
            List<VersionItem> items = new ArrayList<>();
            for (Item item : result.contents()) {
                addVersion(items, item, false, result.encodingType(), bucket, objectKey);
            }
            for (Item item : result.deleteMarkers()) {
                addVersion(items, item, true, result.encodingType(), bucket, objectKey);
            }
            String next = result.isTruncated() ? VersionedInventoryCursor.next("versions", bucket, objectKey,
                    position, result.nextKeyMarker(), result.nextVersionIdMarker()) : null;
            return new InventoryPage<>(items, next, result.isTruncated());
        });
    }

    /** 一次只读取一个multipart页面，不隐藏过滤后空页或根据HEAD推定已完全回收。 */
    @Override
    public InventoryPage<MultipartRef> listMultipartUploads(String bucket, String objectKey, String cursor, int limit,
                                                           VersionedStorageControl control) {
        return execute(control, scope -> {
            inventoryInput(bucket, objectKey, limit);
            var position = VersionedInventoryCursor.decode(cursor, "multipart", bucket, objectKey);
            qualified(scope, bucket);
            var result = await(scope, scope.multipart.listInventory(ListMultipartUploadsArgs.builder().bucket(bucket)
                    .prefix(objectKey).maxUploads(limit).keyMarker(position.keyMarker())
                    .uploadIdMarker(position.idMarker()).build()));
            if (!bucket.equals(result.bucketName()) || result.uploads().size() > limit) {
                throw failure(Reason.INTEGRITY, false);
            }
            List<MultipartRef> items = new ArrayList<>();
            for (var upload : result.uploads()) {
                if (objectKey.equals(upload.objectName())) {
                    if (upload.uploadId() == null || upload.uploadId().isBlank()) {
                        throw failure(Reason.INTEGRITY, false);
                    }
                    items.add(new MultipartRef(bucket, objectKey, upload.uploadId()));
                }
            }
            String next = result.isTruncated() ? VersionedInventoryCursor.next("multipart", bucket, objectKey,
                    position, result.nextKeyMarker(), result.nextUploadIdMarker()) : null;
            return new InventoryPage<>(items, next, result.isTruncated());
        });
    }

    /** 精确上传ID中止不影响相邻键；NoSuchUpload是幂等终态而不是整个键已清空。 */
    @Override
    public void abortMultipart(MultipartRef upload, VersionedStorageControl control) {
        execute(control, scope -> {
            if (upload == null || upload.uploadId() == null || upload.uploadId().isBlank()) {
                throw failure(Reason.CONFIGURATION, false);
            }
            inventoryInput(upload.bucket(), upload.objectKey(), 1);
            qualified(scope, upload.bucket());
            try {
                await(scope, scope.async.abortMultipartUpload(AbortMultipartUploadArgs.builder().bucket(upload.bucket())
                        .object(upload.objectKey()).uploadId(upload.uploadId()).build()));
            } catch (ErrorResponseException error) {
                if (!"NoSuchUpload".equals(error.errorResponse().code())) { throw error; }
            }
            return null;
        });
    }

    /** 明确拒绝不可安全引用的历史null版本，不能静默跳过并宣称回收完成。 */
    private static void addVersion(List<VersionItem> items, Item item, boolean marker, String encoding,
                                   String bucket, String objectKey) {
        item.setEncodingType(encoding);
        if (objectKey.equals(item.objectName())) {
            if (!validVersion(item.versionId())) { throw failure(Reason.INTEGRITY, false); }
            items.add(new VersionItem(new VersionRef(bucket, objectKey, item.versionId()), marker));
        }
    }

    /** 有界单页只接受非空精确键，禁止误盘点整个桶。 */
    private static void inventoryInput(String bucket, String objectKey, int limit) {
        if (bucket == null || bucket.isBlank() || objectKey == null || objectKey.isBlank()
                || limit < 1 || limit > 500) { throw failure(Reason.CONFIGURATION, false); }
    }

    /** 解包SDK异步异常以保留固定分类；物理取消仍由同一Scope追踪实际Call完成。 */
    private static <T> T await(Scope scope, CompletableFuture<T> future) throws Exception {
        try {
            scope.check();
            return future.get(Math.max(1, scope.control.remainingNanos()), TimeUnit.NANOSECONDS);
        } catch (TimeoutException exception) {
            scope.cancel(Reason.TIMEOUT);
            throw failure(Reason.TIMEOUT, false);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            while (cause instanceof CompletionException && cause.getCause() != null) { cause = cause.getCause(); }
            if (cause instanceof Error error) { throw error; }
            if (cause instanceof Exception error) { throw error; }
            throw failure(Reason.UNAVAILABLE, false);
        }
    }

    /** 资格只读查询遇到断连时最多换新连接恢复一次，写入和其他失败均不重试。 */
    private void qualified(Scope scope, String bucket) throws Exception {
        if (bucket == null || bucket.isBlank()) { throw failure(Reason.CONFIGURATION, false); }
        try {
            qualifiedOnce(scope, scope.internal, bucket);
        } catch (Exception error) {
            scope.check();
            if (!readConnectionEnded(error)) throw error;
            // 不清除共享池或重跑整个上传；私有池仅服务这一次只读资格恢复。
            ConnectionPool recoveryPool = new ConnectionPool();
            try {
                long remaining = Math.max(1, TimeUnit.NANOSECONDS.toMillis(scope.control.remainingNanos()));
                OkHttpClient http = scope.http.newBuilder().connectionPool(recoveryPool)
                        .callTimeout(remaining, TimeUnit.MILLISECONDS)
                        .readTimeout(remaining, TimeUnit.MILLISECONDS).build();
                MinioClient reader = MinioClient.builder().endpoint(internalEndpoint).credentials(accessKey, secretKey)
                        .httpClient(http).build();
                scope.check();
                qualifiedOnce(scope, reader, bucket);
            } finally {
                recoveryPool.evictAll();
            }
        }
    }

    /** 精确白名单识别SDK包装的EOF或连接断开，超时、中断、TLS及响应错误不恢复。 */
    private static boolean readConnectionEnded(Throwable error) {
        boolean disconnected = false;
        for (int depth = 0; depth < 8 && error != null; depth++) {
            if (error instanceof java.io.InterruptedIOException || error instanceof InterruptedException
                    || error instanceof javax.net.ssl.SSLException || error instanceof ErrorResponseException
                    || error instanceof VersionedStorageException) return false;
            disconnected |= error instanceof java.io.EOFException || error instanceof java.net.SocketException;
            if (error.getCause() == error) break;
            error = error.getCause();
        }
        return disconnected;
    }

    /** 两项只读资格使用同一截止，存在任何policy都失败，不解释IAM策略语言。 */
    private void qualifiedOnce(Scope scope, MinioClient reader, String bucket) throws Exception {
        scope.check();
        VersioningConfiguration config = reader.getBucketVersioning(
                GetBucketVersioningArgs.builder().bucket(bucket).build());
        if (config.status() != VersioningConfiguration.Status.ENABLED
                || config.excludedPrefixes() != null && !config.excludedPrefixes().isEmpty()
                || Boolean.TRUE.equals(config.excludeFolders())) { throw failure(Reason.CONFIGURATION, false); }
        scope.check();
        if (!reader.getBucketPolicy(GetBucketPolicyArgs.builder().bucket(bucket).build()).isEmpty()) {
            throw failure(Reason.CONFIGURATION, false);
        }
        scope.check();
    }

    /** 一个操作共享全部前置预算，退出时取消本范围未完成Call并注销探针。 */
    private <T> T execute(VersionedStorageControl control, Operation<T> operation) {
        if (control == null) { throw failure(Reason.CONFIGURATION, false); }
        Scope scope = null;
        try {
            scope = new Scope(control);
            T result = operation.run(scope);
            scope.check();
            return result;
        } catch (Exception error) {
            if (!(error instanceof VersionedStorageException)) {
                String sdkCode = error instanceof ErrorResponseException response
                        && response.errorResponse() != null && response.errorResponse().code() != null
                        && Set.of("NoSuchKey", "NoSuchVersion", "NoSuchBucket", "AccessDenied",
                                "RequestTimeTooSkewed", "SignatureDoesNotMatch", "InternalError", "SlowDown")
                                .contains(response.errorResponse().code())
                        ? response.errorResponse().code() : "UNCLASSIFIED";
                Throwable origin = error;
                for (int depth = 0; depth < 8 && origin.getCause() != null && origin.getCause() != origin; depth++) {
                    origin = origin.getCause();
                }
                String location = java.util.Arrays.stream(origin.getStackTrace()).limit(4)
                        .map(frame -> frame.getClassName() + "." + frame.getMethodName() + ":" + frame.getLineNumber())
                        .collect(java.util.stream.Collectors.joining(","));
                LOG.warn("版本化存储依赖失败：exceptionType={}, rootType={}, sdkCode={}, writeStarted={}, location={}",
                        error.getClass().getName(), origin.getClass().getName(), sdkCode, scope != null && scope.mayHaveWritten, location);
            }
            if (error instanceof InterruptedException) { Thread.currentThread().interrupt(); }
            if (scope != null) {
                scope.check();
                if (error instanceof VersionedStorageException failure) { throw failure; }
                if (scope.mayHaveWritten) { throw failure(Reason.RESULT_UNKNOWN, true); }
            }
            if (error instanceof VersionedStorageException failure) { throw failure; }
            if (error instanceof ErrorResponseException response
                    && ("NoSuchKey".equals(response.errorResponse().code())
                    || "NoSuchVersion".equals(response.errorResponse().code()))) {
                throw failure(Reason.NOT_FOUND, false);
            }
            throw failure(Reason.UNAVAILABLE, false);
        } finally {
            if (scope != null) { scope.close(); }
        }
    }

    /** 校验固定版本身份，不允许退回null版本。 */
    private static void version(VersionRef ref) {
        if (ref == null || ref.bucket() == null || ref.bucket().isBlank() || ref.objectKey() == null
                || ref.objectKey().isBlank() || !validVersion(ref.versionId())) {
            throw failure(Reason.CONFIGURATION, false);
        }
    }
    /** 只接受服务端非空且非null版本值。 */
    private static boolean validVersion(String value) {
        return value != null && !value.isBlank() && !"null".equals(value);
    }
    /** 写身份缺失不能变为匿名或可复用键上传。 */
    private static void identity(WriteIdentity id) {
        if (id == null || id.bucket() == null || id.bucket().isBlank() || id.objectKey() == null
                || id.objectKey().isBlank() || id.requestId() == null) { throw failure(Reason.CONFIGURATION, false); }
    }
    /** 构造不携带供应商异常的安全错误。 */
    private static VersionedStorageException failure(Reason reason, boolean written) {
        return new VersionedStorageException(reason, written);
    }
    /** 组件关闭仅取消自身操作并释放自身线程池及连接池。 */
    @PreDestroy
    @Override
    public synchronized void close() {
        if (closed.compareAndSet(false, true)) {
            scopes.forEach(scope -> scope.cancel(Reason.CANCELLED));
            watchdog.shutdownNow();
            baseHttp.dispatcher().executorService().shutdownNow();
            baseHttp.connectionPool().evictAll();
            try {
                if (!watchdog.awaitTermination(5, TimeUnit.SECONDS)
                        || !baseHttp.dispatcher().executorService().awaitTermination(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("版本化对象存储自有线程未关闭");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("版本化对象存储关闭被中断", exception);
            }
        }
    }
    /** 可以抛出SDK检查异常的单次内部操作。 */
    @FunctionalInterface
    private interface Operation<T> {
        /** 执行同一范围内的前置和正文动作。 */
        T run(Scope scope) throws Exception;
    }

    /** 每操作独立追踪实际HTTP Call，禁止取消共享dispatcher中的其他工作。 */
    private final class Scope extends EventListener implements AutoCloseable {
        /** 调用方单调预算与外部取消信号。 */
        private final VersionedStorageControl control;
        /** 本操作仍未完成的物理网络调用。 */
        private final Set<Call> calls = ConcurrentHashMap.newKeySet();
        /** 截止触发后保留固定分类，后续Call开始也会立即取消。 */
        private volatile Reason aborted;
        /** 操作已退出时拒绝SDK迟到的新网络调用。 */
        private volatile boolean finished;
        /** 上传进入SDK之后必须保守记录可能写入。 */
        private volatile boolean mayHaveWritten;
        /** 同一绝对预算和事件监听，资格恢复只替换私有连接池。 */
        private final OkHttpClient http;
        /** 本范围的服务端客户端。 */
        private final MinioClient internal;
        /** 单页盘点与精确multipart中止使用同一物理取消范围。 */
        private final MinioAsyncClient async;
        /** 单页multipart身份解析兼容固定服务端空StorageClass。 */
        private final MultipartInventoryClient multipart;
        /** 本范围的外部签名客户端。 */
        private final MinioClient external;
        /** 退出操作后需要注销的短周期探针。 */
        private final ScheduledFuture<?> probe;
        /** 构造共享资源但独立事件监听及超时的客户端。 */
        Scope(VersionedStorageControl control) {
            this.control = control;
            check();
            http = baseHttp.newBuilder().eventListener(this)
                    .callTimeout(Math.max(1, TimeUnit.NANOSECONDS.toMillis(control.remainingNanos())),
                            TimeUnit.MILLISECONDS)
                    .readTimeout(Math.max(1, TimeUnit.NANOSECONDS.toMillis(control.remainingNanos())),
                            TimeUnit.MILLISECONDS).build();
            internal = MinioClient.builder().endpoint(internalEndpoint).credentials(accessKey, secretKey)
                    .httpClient(http).build();
            async = MinioAsyncClient.builder().endpoint(internalEndpoint).credentials(accessKey, secretKey)
                    .httpClient(http).build();
            multipart = new MultipartInventoryClient(async);
            external = MinioClient.builder().endpoint(externalEndpoint).credentials(accessKey, secretKey)
                    .httpClient(http).build();
            synchronized (MinioVersionedPrivateObjectStorage.this) {
                if (closed.get()) { throw failure(Reason.CANCELLED, false); }
                scopes.add(this);
                probe = watchdog.scheduleAtFixedRate(this::poll, 0, 10, TimeUnit.MILLISECONDS);
            }
        }
        /** 只进行非阻塞状态观察，外部信号异常也取消物理调用。 */
        private void poll() {
            try {
                if (closed.get() || control.cancelled()) { cancel(Reason.CANCELLED); }
                else if (control.timedOut()) { cancel(Reason.TIMEOUT); }
            } catch (RuntimeException error) { cancel(Reason.CANCELLED); }
        }
        /** 保存失败原因并取消当前操作所有实际Call。 */
        private void cancel(Reason reason) {
            if (aborted == null) { aborted = reason; }
            calls.forEach(Call::cancel);
        }
        /** 同步调用边界也检查取消，避免短请求绕过后台探针。 */
        private void check() {
            if (Thread.currentThread().isInterrupted()) { cancel(Reason.CANCELLED); }
            poll();
            if (aborted != null) { throw failure(aborted, mayHaveWritten); }
        }
        /** Call开始后立即登记，已终止范围不允许新增网络工作。 */
        @Override public void callStart(Call call) {
            calls.add(call);
            if (finished || aborted != null || closed.get()) { call.cancel(); }
        }
        /** 响应完整关闭后移除物理调用引用。 */
        @Override public void callEnd(Call call) { calls.remove(call); }
        /** 物理请求失败后移除引用，不记录不可信异常。 */
        @Override public void callFailed(Call call, IOException failure) { calls.remove(call); }
        /** 无论成功或失败都注销探针并取消本范围残余网络动作。 */
        @Override public void close() {
            finished = true;
            probe.cancel(false);
            calls.forEach(Call::cancel);
            scopes.remove(this);
        }
    }
}
