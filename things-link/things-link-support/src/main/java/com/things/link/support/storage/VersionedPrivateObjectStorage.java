package com.things.link.support.storage;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.List;

/** 固定版本私有对象技术端口，不替代项目授权、持久上传会话及业务幂等。 */
public interface VersionedPrivateObjectStorage {
    /** 检查私桶且所有键启用版本化，配置读取无权限必须失败。 */
    void requireQualifiedBucket(String bucket, VersionedStorageControl control);
    /** 上传调用方持有的不可变文件，失败可能已经产生版本，不自动重试。 */
    VersionRef upload(WriteRequest request, VersionedStorageControl control);
    /** 在独占且不可复用键前置下探测请求身份，通信失败不能当成不存在。 */
    ProbeResult probe(WriteIdentity identity, VersionedStorageControl control);
    /** 固定版本流式读至EOF，独立验证长度和SHA256。 */
    VerifiedObject verify(VersionRef version, long expectedLength, String expectedSha256,
                          VersionedStorageControl control);
    /** 为固定版本签发1至300秒GET地址，调用方禁止记录URL。 */
    URI presignGet(VersionRef version, Duration ttl, VersionedStorageControl control);
    /** 只删除指定版本，不存在按成功处理，禁止退回删除当前键。 */
    void delete(VersionRef version, VersionedStorageControl control);
    /** 只盘点精确键的一页版本与删除标记；空页不证明未来不会出现迟到写入。 */
    InventoryPage<VersionItem> listVersions(String bucket, String objectKey, String cursor, int limit,
                                           VersionedStorageControl control);
    /** 只盘点精确键的一页未完成multipart，不自动跨provider页补齐。 */
    InventoryPage<MultipartRef> listMultipartUploads(String bucket, String objectKey, String cursor, int limit,
                                                    VersionedStorageControl control);
    /** 只中止精确上传ID，不存在按幂等成功处理，不能替代后续再次盘点。 */
    void abortMultipart(MultipartRef upload, VersionedStorageControl control);
    /**
     * 固定版本盘点条目，删除标记同样需要精确版本回收。
     * @param version 精确版本身份
     * @param deleteMarker 是否为删除标记
     */
    record VersionItem(VersionRef version, boolean deleteMarker) { }
    /**
     * 未完成上传身份，不包含业务凭据。
     * @param bucket 私有桶
     * @param objectKey 独占对象键
     * @param uploadId 服务端精确上传ID
     */
    record MultipartRef(String bucket, String objectKey, String uploadId) { }
    /**
     * 单个provider页面的不可变投影，过滤相邻键后items可能为空而hasMore仍为true。
     * @param items 精确键匹配条目
     * @param nextCursor 绑定种类、桶和键的下页游标，末页为空
     * @param hasMore provider是否还有下页
     * @param <T> 盘点条目类型
     */
    record InventoryPage<T>(List<T> items, String nextCursor, boolean hasMore) {
        /** 复制条目并保留provider分页事实，不把过滤后空页改为完成。 */
        public InventoryPage { items = List.copyOf(items); }
    }
    /**
     * 固定对象身份，不等于WORM保留资格。
     * @param bucket 私有桶
     * @param objectKey 独占对象键
     * @param versionId 不可为空或null版本的服务端版本ID
     */
    record VersionRef(String bucket, String objectKey, String versionId) {
        /** 固定版本引用不能表示SDK会解释为当前对象的null版本。 */
        public VersionRef {
            if (bucket == null || bucket.isBlank() || objectKey == null || objectKey.isBlank()
                    || versionId == null || versionId.isBlank() || "null".equals(versionId)) {
                throw new IllegalArgumentException("固定对象版本身份不合法");
            }
        }
    }
    /**
     * 由上层持久会话分配且禁止并发复用的写入身份。
     * @param bucket 私有桶
     * @param objectKey 不可复用独占键
     * @param requestId 稳定请求UUID
     */
    record WriteIdentity(String bucket, String objectKey, UUID requestId) { }
    /**
     * 上传输入，本层不取得本地文件所有权。
     * @param identity 稳定写入身份
     * @param source 调用期间保持不可变的本地文件
     */
    record WriteRequest(WriteIdentity identity, Path source) { }
    /** 明确探测结果，网络和配置失败不属于该集合。 */
    enum ProbeStatus {
        /** 服务端明确返回NoSuchKey。 */
        ABSENT,
        /** 请求身份匹配，可固定版本继续验真。 */
        MATCHED,
        /** 当前对象属于其他请求或缺少请求标识。 */
        CONFLICT
    }
    /**
     * 键探测结果，不证明正文摘要已通过。
     * @param status 有界结果分类
     * @param version 仅MATCHED时有固定版本
     */
    record ProbeResult(ProbeStatus status, VersionRef version) { }
    /**
     * 完整正文核验结果，不证明设备或发布授权。
     * @param version 已读验的固定版本
     * @param size 实际读取长度
     * @param sha256 实际正文SHA256
     */
    record VerifiedObject(VersionRef version, long size, String sha256) { }
}
