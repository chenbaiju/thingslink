package com.things.link.support.storage;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

/**
 * S3兼容私有对象存储端口。
 *
 * <p>端口只表达对象技术操作，不理解项目、导出任务或权限。业务模块负责对象键和授权，
 * support适配器负责严格区分服务端internal endpoint与浏览器签名external endpoint。</p>
 */
public interface PrivateObjectStorage {

    /**
     * 上传完整本地文件；返回前服务端必须已经确认对象写入。
     * @param bucket 私有桶
     * @param objectKey 对象键
     * @param source 本地文件
     * @param contentType 媒体类型
     * @param metadata 非敏感对象元数据
     */
    void upload(String bucket, String objectKey, Path source, String contentType, Map<String, String> metadata);

    /**
     * 在业务尝试的剩余预算内上传完整文件。
     *
     * <p>默认实现保留既有存储适配器兼容性；需要执行长上传的生产适配器必须覆写本方法，
     * 在网络调用期间持续响应截止与取消，而不能只在返回后检查。</p>
     *
     * @param bucket 私有桶
     * @param objectKey 对象键
     * @param source 本地文件
     * @param contentType 媒体类型
     * @param metadata 非敏感对象元数据
     * @param control 单调截止与外部取消信号
     */
    default void upload(String bucket, String objectKey, Path source, String contentType,
                        Map<String, String> metadata, ObjectUploadControl control) {
        if (control.getAsBoolean()) {
            throw new ObjectUploadAbortedException("对象上传开始前尝试已终止", control.timedOut());
        }
        upload(bucket, objectKey, source, contentType, metadata);
        if (control.getAsBoolean()) {
            throw new ObjectUploadAbortedException("对象上传返回时尝试已终止", control.timedOut());
        }
    }

    /**
     * 删除对象；对象不存在按幂等成功处理。
     * @param bucket 私有桶
     * @param objectKey 对象键
     */
    void delete(String bucket, String objectKey);

    /**
     * 使用external endpoint签发短时GET地址，调用方不得再改写host。
     * @param bucket 私有桶
     * @param objectKey 对象键
     * @param ttl 有效期
     * @return 可访问URI
     */
    URI presignGet(String bucket, String objectKey, Duration ttl);
}
