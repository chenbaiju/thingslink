package com.things.link.ota.domain;

import java.time.Instant;
import java.util.UUID;

/** 持久发布事实，数组防御复制；API必须另行白名单投影。
 * @param id 发布唯一身份
 * @param tenantId 不可变租户身份
 * @param projectId 不可变项目身份
 * @param firmwareId 目标固件身份
 * @param uploadSessionId 精确已验证上传身份
 * @param publicationId 发布尝试身份
 * @param canonicalManifest 冻结的规范清单字节
 * @param trustSnapshot 冻结的信任根及策略授权快照字节
 * @param spki 已复验签名公钥SPKI
 * @param signature 已复验Ed25519签名字节
 * @param receipt 外部签名服务回执标识
 * @param createdAt 创建时间
 */
public record OtaRelease(
        UUID id,
        UUID tenantId,
        UUID projectId,
        UUID firmwareId,
        UUID uploadSessionId,
        UUID publicationId,
        byte[] canonicalManifest,
        byte[] trustSnapshot,
        byte[] spki,
        byte[] signature,
        String receipt,
        Instant createdAt) {
    /** 拷贝输入，未签名尝试允许签名字段为空。 */
    public OtaRelease {
        canonicalManifest=canonicalManifest==null?null:canonicalManifest.clone();
        trustSnapshot=trustSnapshot==null?null:trustSnapshot.clone();
        spki=spki==null?null:spki.clone();
        signature=signature==null?null:signature.clone();
    }
    /** 返回独立字节副本。 */
    @Override public byte[] canonicalManifest(){return canonicalManifest==null?null:canonicalManifest.clone();}
    /** 返回独立字节副本。 */
    @Override public byte[] trustSnapshot(){return trustSnapshot==null?null:trustSnapshot.clone();}
    /** 返回独立字节副本。 */
    @Override public byte[] spki(){return spki==null?null:spki.clone();}
    /** 返回独立字节副本。 */
    @Override public byte[] signature(){return signature==null?null:signature.clone();}
}
