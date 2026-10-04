package com.things.link.support.storage;

import io.minio.GetPresignedObjectUrlArgs;
import io.minio.Http;
import io.minio.MinioClient;
import io.minio.MinioAsyncClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 使用两个固定endpoint的MinIO私有对象存储适配器。
 *
 * <p>support会被IAM等独立测试应用扫描；只有四项存储配置齐全时才装配，避免无导出能力的模块被迫提供凭据。
 * Bootstrap中的export模块仍强依赖本端口，因此生产缺项会在启动期fail-closed。</p>
 */
@Component
@ConditionalOnProperty(prefix = "things-link.storage", name = {
        "internal-endpoint", "external-endpoint", "access-key", "secret-key"
})
public class MinioPrivateObjectStorage implements PrivateObjectStorage {

    /** 服务端上传和删除客户端。 */
    private final MinioClient internalClient;
    /** 支持按单次业务剩余预算取消的异步上传客户端。 */
    private final MinioAsyncClient internalUploadClient;
    /** 只用于产生浏览器可访问签名的客户端。 */
    private final MinioClient externalClient;

    /**
     * 使用环境配置创建生产适配器；访问密钥没有默认值，缺失时应用启动即失败。
     * @param internalEndpoint 服务端endpoint
     * @param externalEndpoint 签名endpoint
     * @param accessKey 访问密钥
     * @param secretKey 私密密钥
     */
    @Autowired
    public MinioPrivateObjectStorage(
            @Value("${things-link.storage.internal-endpoint}") String internalEndpoint,
            @Value("${things-link.storage.external-endpoint}") String externalEndpoint,
            @Value("${things-link.storage.access-key}") String accessKey,
            @Value("${things-link.storage.secret-key}") String secretKey) {
        this(MinioClient.builder().endpoint(internalEndpoint).credentials(accessKey, secretKey).build(),
                MinioClient.builder().endpoint(externalEndpoint).credentials(accessKey, secretKey).build(),
                MinioAsyncClient.builder().endpoint(internalEndpoint).credentials(accessKey, secretKey).build());
    }

    /**
     * 测试及显式装配入口，允许分别注入internal/external客户端验证签名主机。
     * @param internalClient 服务端客户端
     * @param externalClient 外部签名客户端
     */
    public MinioPrivateObjectStorage(MinioClient internalClient, MinioClient externalClient) {
        this(internalClient, externalClient, null);
    }

    /**
     * 创建可显式控制异步上传的适配器。
     * @param internalClient 服务端同步客户端
     * @param externalClient 外部签名客户端
     * @param internalUploadClient 服务端异步上传客户端；仅旧测试装配可为空
     */
    public MinioPrivateObjectStorage(MinioClient internalClient, MinioClient externalClient,
                                     MinioAsyncClient internalUploadClient) {
        this.internalClient = java.util.Objects.requireNonNull(internalClient, "internalClient");
        this.externalClient = java.util.Objects.requireNonNull(externalClient, "externalClient");
        this.internalUploadClient = internalUploadClient;
    }

    /** {@inheritDoc} */
    @Override
    public void upload(String bucket, String objectKey, Path source, String contentType,
                       Map<String, String> metadata) {
        try (InputStream input = Files.newInputStream(source)) {
            long size = Files.size(source);
            internalClient.putObject(PutObjectArgs.builder()
                    .bucket(bucket).object(objectKey).contentType(contentType)
                    .userMetadata(metadata == null ? Map.of() : Map.copyOf(metadata))
                    .stream(input, size, -1L).build());
        } catch (Exception exception) {
            throw new ObjectStorageException("私有对象上传失败", exception);
        }
    }

    /** {@inheritDoc} */
    @Override
    public void upload(String bucket, String objectKey, Path source, String contentType,
                       Map<String, String> metadata, ObjectUploadControl control) {
        // 旧测试显式注入同步客户端时仍走真实MinIO；生产装配必须使用可取消异步客户端。
        if (internalUploadClient == null) {
            PrivateObjectStorage.super.upload(bucket, objectKey, source, contentType, metadata, control);
            return;
        }
        CompletableFuture<?> upload = null;
        try (InputStream input = Files.newInputStream(source)) {
            if (control.getAsBoolean()) {
                throw aborted(control, "私有对象上传开始前尝试已终止");
            }
            long size = Files.size(source);
            upload = internalUploadClient.putObject(PutObjectArgs.builder()
                    .bucket(bucket).object(objectKey).contentType(contentType)
                    .userMetadata(metadata == null ? Map.of() : Map.copyOf(metadata))
                    .stream(input, size, -1L).build());
            awaitUpload(upload, control);
        } catch (ObjectUploadAbortedException exception) {
            if (upload != null) upload.cancel(true);
            throw exception;
        } catch (InterruptedException exception) {
            if (upload != null) upload.cancel(true);
            Thread.currentThread().interrupt();
            throw new ObjectUploadAbortedException("私有对象上传线程被中断", false);
        } catch (Exception exception) {
            if (upload != null) upload.cancel(true);
            throw new ObjectStorageException("私有对象上传失败", exception);
        }
    }

    /**
     * 按短轮询等待异步结果，使租约失权不必等待完整网络超时。
     * @param upload MinIO异步上传结果
     * @param control 尝试截止与取消信号
     */
    private static void awaitUpload(CompletableFuture<?> upload, ObjectUploadControl control)
            throws ExecutionException, InterruptedException {
        while (true) {
            if (control.getAsBoolean()) {
                throw aborted(control, "私有对象上传期间尝试已终止");
            }
            long waitNanos = Math.min(control.remainingNanos(), TimeUnit.MILLISECONDS.toNanos(250));
            try {
                upload.get(Math.max(1L, waitNanos), TimeUnit.NANOSECONDS);
                if (control.getAsBoolean()) {
                    throw aborted(control, "私有对象上传确认时尝试已终止");
                }
                return;
            } catch (TimeoutException ignored) {
                // 继续复核单调截止和外部租约；不能把正常网络等待误判为一次存储失败。
            } catch (CancellationException exception) {
                throw new ObjectStorageException("私有对象上传被底层客户端取消", exception);
            }
        }
    }

    /**
     * 将取消原因稳定区分为时间上限或外部失权。
     * @param control 尝试截止与取消信号
     * @param message 稳定诊断
     * @return 可供业务层分类的主动终止异常
     */
    private static ObjectUploadAbortedException aborted(ObjectUploadControl control, String message) {
        return new ObjectUploadAbortedException(message, control.timedOut());
    }

    /** {@inheritDoc} */
    @Override
    public void delete(String bucket, String objectKey) {
        try {
            internalClient.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(objectKey).build());
        } catch (Exception exception) {
            throw new ObjectStorageException("私有对象删除失败", exception);
        }
    }

    /** {@inheritDoc} */
    @Override
    public URI presignGet(String bucket, String objectKey, Duration ttl) {
        if (ttl == null || ttl.isZero() || ttl.isNegative() || ttl.compareTo(Duration.ofDays(7)) > 0) {
            throw new IllegalArgumentException("对象签名有效期必须在1秒到7天之间");
        }
        try {
            String value = externalClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Http.Method.GET).bucket(bucket).object(objectKey)
                    .expiry(Math.toIntExact(ttl.toSeconds())).build());
            return URI.create(value);
        } catch (Exception exception) {
            throw new ObjectStorageException("私有对象签名失败", exception);
        }
    }
}
