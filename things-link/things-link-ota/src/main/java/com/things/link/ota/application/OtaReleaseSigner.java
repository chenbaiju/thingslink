package com.things.link.ota.application;

import java.util.UUID;

/** 外部不可导出发布密钥的供应商无关端口，不提供本地私钥或缺配置成功实现。 */
@FunctionalInterface
public interface OtaReleaseSigner {
    /** 对固定身份和已加域字节签名；拒绝或结果未知由适配器抛出异常，调用方不自动重试。 */
    Response sign(Request request);

    /**
     * 由协调器构造的合法请求；字节数组不会通过构造参数或访问器被外部修改。
     * @param requestId 本次请求的稳定身份
     * @param trustDomain 可信签名域
     * @param keyVersion 不可变密钥版本标识
     * @param profile 冻结签名算法合同
     * @param fingerprint 完整可信 SPKI 的 SHA-256 指纹
     * @param signingInput 已加固定域的规范签名字节
     */
    record Request(UUID requestId, String trustDomain, String keyVersion, OtaSignatureProfile profile,
                   String fingerprint, byte[] signingInput) {
        /** 防御复制签名字节；身份和字段资格由构造本请求的协调器保证。 */
        public Request {
            signingInput = signingInput.clone();
        }

        /** 返回签名输入副本，禁止供应商修改协调器保留的内容。 */
        @Override
        public byte[] signingInput() {
            return signingInput.clone();
        }
    }

    /**
     * 未受信任的供应商响应，所有身份及签名必须由协调器复验。
     * @param requestId 供应商回显的请求身份
     * @param keyVersion 实际使用的不可变密钥版本
     * @param profile 供应商声明的签名合同
     * @param spki 完整公开 SPKI DER
     * @param signature 冻结合同规定的签名字节
     * @param receipt 不含秘密的供应商回执标识
     */
    record Response(UUID requestId, String keyVersion, OtaSignatureProfile profile,
                    byte[] spki, byte[] signature, String receipt) {
        /** 复制非空数组，保留空字段供协调器统一报告响应无效。 */
        public Response {
            spki = spki == null ? null : spki.clone();
            signature = signature == null ? null : signature.clone();
        }

        /** 返回公开密钥副本；畸形响应允许返回 null。 */
        @Override
        public byte[] spki() {
            return spki == null ? null : spki.clone();
        }

        /** 返回签名副本；畸形响应允许返回 null。 */
        @Override
        public byte[] signature() {
            return signature == null ? null : signature.clone();
        }
    }
}
