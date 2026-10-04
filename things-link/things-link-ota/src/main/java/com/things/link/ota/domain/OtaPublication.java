package com.things.link.ota.domain;

import java.time.Instant;
import java.util.UUID;

/** 持久发布事实，数组防御复制；API必须另行白名单投影。
 * @param id 发布唯一身份
 * @param tenantId 不可变租户身份
 * @param projectId 不可变项目身份
 * @param firmwareId 目标固件身份
 * @param uploadSessionId 精确已验证上传身份
 * @param createdBy 发起账号身份
 * @param requestId 不可复用签名请求身份
 * @param projectGeneration 创建时项目生命周期代次
 * @param firmwareRevision 创建时固件版本围栏
 * @param uploadRevision 创建时已验证上传版本围栏
 * @param canonicalManifest 冻结的规范清单字节
 * @param trustSnapshot 冻结的信任根及策略授权快照字节
 * @param status 发布尝试持久状态
 * @param revision 发布事实单调版本
 * @param spki 已复验签名公钥SPKI
 * @param signature 已复验Ed25519签名字节
 * @param receipt 外部签名服务回执标识
 * @param failureCode 固定失败原因码
 * @param leaseToken 当前处理者不可复用租约能力
 * @param leaseUntil 数据库真实时钟租约截止
 * @param createdAt 创建时间
 * @param updatedAt 最近事实更新时间
 */
public record OtaPublication(
        UUID id,
        UUID tenantId,
        UUID projectId,
        UUID firmwareId,
        UUID uploadSessionId,
        UUID createdBy,
        UUID requestId,
        long projectGeneration,
        long firmwareRevision,
        long uploadRevision,
        byte[] canonicalManifest,
        byte[] trustSnapshot,
        String status,
        long revision,
        byte[] spki,
        byte[] signature,
        String receipt,
        String failureCode,
        UUID leaseToken,
        Instant leaseUntil,
        Instant createdAt,
        Instant updatedAt) {
    /** 拷贝输入，未签名尝试允许签名字段为空。 */
    public OtaPublication {
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
