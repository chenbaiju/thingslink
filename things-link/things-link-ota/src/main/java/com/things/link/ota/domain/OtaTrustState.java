package com.things.link.ota.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** 项目域当前信任快照；数组构造和读取均复制，不允许调用方篡改签名正文。
 * @param tenantId 所有者租户
 * @param projectId 所属项目
 * @param trustDomain 全局唯一信任域
 * @param rootProfile 不可变根算法
 * @param rootFingerprint 不可变根指纹
 * @param policyRevision 受控配置单调版本
 * @param policyHash 完整配置摘要
 * @param revision 领域修订
 * @param bundleVersion 当前不可变包版本
 * @param bundleSha256 当前包摘要
 * @param canonicalBundle 规范包字节
 * @param signature 根签名字节
 * @param createdAt 域创建时间
 * @param updatedAt 当前导入时间
 */
public record OtaTrustState(UUID tenantId,UUID projectId,String trustDomain,String rootProfile,
        String rootFingerprint,long policyRevision,String policyHash,long revision,long bundleVersion,
        String bundleSha256,byte[] canonicalBundle,byte[] signature,Instant createdAt,Instant updatedAt) {
    /** 不保存外部可变数组引用。 */
    public OtaTrustState {
        canonicalBundle=Objects.requireNonNull(canonicalBundle,"canonicalBundle").clone();
        signature=Objects.requireNonNull(signature,"signature").clone();
    }
    /** 返回独立正文副本。 */
    @Override public byte[] canonicalBundle(){return canonicalBundle.clone();}
    /** 返回独立签名副本。 */
    @Override public byte[] signature(){return signature.clone();}
}
