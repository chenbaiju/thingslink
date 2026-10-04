package com.things.link.ota.application;

import com.things.link.ota.domain.OtaReleaseDownloadErrorCode;
import com.things.link.shared.error.BusinessException;
import com.things.link.support.storage.VersionedPrivateObjectStorage;
import com.things.link.support.storage.VersionedStorageControl;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 无网络长事务的60秒管理地址签发；URL绝不传入审计或持久响应。 */
@Service
public class OtaReleaseDownloadIssuer {
    /** 前后两次独立资格短事务。 */ private final OtaReleaseDownloadService service;
    /** 缺失或歧义配置均关闭下载。 */ private final ObjectProvider<VersionedPrivateObjectStorage> storages;
    /** 只允许显式开发配置开启本机明文回环。 */ private final boolean allowInsecureLoopback;
    /** 固定签名有效期，不接收客户端TTL。 */ private static final Duration TTL=Duration.ofSeconds(60);
    /** 私桶资格与签发共用技术预算。 */ private static final Duration BUDGET=Duration.ofSeconds(5);

    /** 注入版本化私桶端口，默认只允许HTTPS。 */
    public OtaReleaseDownloadIssuer(OtaReleaseDownloadService service,ObjectProvider<VersionedPrivateObjectStorage> storages,
            @Value("${things-link.ota.download.allow-insecure-loopback:false}") boolean allowInsecureLoopback) {
        this.service=service;this.storages=storages;this.allowInsecureLoopback=allowInsecureLoopback;
    }

    /** 网络必须处于事务外，签发后失权只丢弃地址，不返回半完成授权。 */
    @Transactional(propagation=Propagation.NEVER)
    public Download issue(UUID projectId,UUID firmwareId) {
        Instant expiresAt=Instant.now().truncatedTo(ChronoUnit.SECONDS).plus(TTL);
        OtaReleaseDownloadService.Snapshot snapshot=service.read(projectId,firmwareId);
        var configured=storages.orderedStream().limit(2).toList();
        if(configured.size()!=1) throw unavailable();
        Thread caller=Thread.currentThread();
        var control=new VersionedStorageControl(BUDGET,caller::isInterrupted);
        URI url;
        try {
            var upload=snapshot.upload();
            url=configured.getFirst().presignGet(new VersionedPrivateObjectStorage.VersionRef(
                    upload.bucket(),upload.objectKey(),upload.versionId()),TTL,control);
            if(control.timedOut() || control.cancelled()) throw unavailable();
            requireSafeUrl(url);
        } catch (RuntimeException failure) {
            // 不传播供应商异常链，避免签名查询串或存储端点进入错误日志。
            throw unavailable();
        }
        if(!Instant.now().isBefore(expiresAt)) throw unavailable();
        service.confirm(snapshot);
        if(!Instant.now().isBefore(expiresAt) || Thread.currentThread().isInterrupted()) throw unavailable();
        return new Download(firmwareId,url,expiresAt);
    }

    /** 默认HTTPS，开发例外精确匹配回环主机，不做DNS推测或任意子域匹配。 */
    private void requireSafeUrl(URI uri) {
        if(uri==null || !uri.isAbsolute() || uri.getRawUserInfo()!=null || uri.getRawFragment()!=null
                || uri.getHost()==null || uri.getHost().isBlank()) throw unavailable();
        if("https".equalsIgnoreCase(uri.getScheme())) return;
        String host=uri.getHost();
        if(allowInsecureLoopback && "http".equalsIgnoreCase(uri.getScheme())
                && ("localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host)
                    || "::1".equals(host) || "[::1]".equals(host))) return;
        throw unavailable();
    }
    /** 固定错误，不携带URL或供应商正文。 */
    private static BusinessException unavailable() { return new BusinessException(OtaReleaseDownloadErrorCode.UNAVAILABLE); }
    /** 临时返回值，不能保存到幂等响应、日志或审计。
     * @param firmwareId 授权固件身份
     * @param downloadUrl 60秒固定版本bearer地址
     * @param expiresAt 自请求开始按秒向下截断计算的保守截止
     */
    public record Download(UUID firmwareId,URI downloadUrl,Instant expiresAt) {
        /** 默认record字符串会暴露签名查询串，明确仅输出固件身份和截止。 */
        @Override public String toString() { return "Download[firmwareId="+firmwareId+", expiresAt="+expiresAt+"]"; }
    }
}
