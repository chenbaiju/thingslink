package com.things.link.ota.application;

/** 发布签名的可信配置源，负责 ACTIVE、有效期及策略授权，不能从 manifest 自声明构造。 */
@FunctionalInterface
public interface OtaSigningTrustSource {
    /** 获取域内当前合法绑定；任何资格变化必须增加修订号，缺配置不能返回默认成功绑定。 */
    Binding activeKey(String trustDomain);

    /**
     * 可信配置快照；协调器双读仅检测调用期间变化，不替代 READY 事务内原子资格检查。
     * @param trustDomain 已获授权的信任域
     * @param keyVersion 不可变密钥版本标识
     * @param profile 冻结签名合同
     * @param fingerprint 完整 SPKI 的 SHA-256 小写指纹
     * @param revision 大于零的单调修订号，任意资格变化必须更新
     */
    record Binding(String trustDomain, String keyVersion, OtaSignatureProfile profile,
                   String fingerprint, long revision) { }
}
