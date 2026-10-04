package com.things.link.ota.application;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import com.things.link.ota.application.OtaSigningTrustSource.Binding;

/** 校验 manifest、可信绑定和外部签名回执；无副作用重试、无运行时默认实现。 */
public final class OtaSigningCoordinator {
    /** 外部不可导出密钥签名端口。 */
    private final OtaReleaseSigner signer;
    /** 签名前后独立读取的可信绑定源。 */
    private final OtaSigningTrustSource trustSource;
    /** 字段、规范化及签名字节验真共用合同入口。 */
    private final OtaManifestCodec codec = new OtaManifestCodec();
    /** 只读取已通过完整字段校验的规范对象。 */
    private final OtaCanonicalJson json = new OtaCanonicalJson();

    /** 依赖必须显式提供，缺配置不得形成可成功的协调器。 */
    public OtaSigningCoordinator(OtaReleaseSigner signer, OtaSigningTrustSource trustSource) {
        this.signer = Objects.requireNonNull(signer, "signer");
        this.trustSource = Objects.requireNonNull(trustSource, "trustSource");
    }

    /** 单次发送固定域请求并复验响应；成功结果仍需后续 READY 事务核对绑定修订号。 */
    public Result sign(UUID requestId, byte[] manifest) {
        byte[] canonical;
        Map<String, Object> fields;
        try {
            if (requestId == null) {
                throw new IllegalArgumentException();
            }
            canonical = codec.canonicalize(manifest);
            fields = json.parseObject(canonical);
        } catch (RuntimeException exception) {
            throw new Failure(Reason.INVALID_MANIFEST);
        }
        String domain = (String) fields.get("trustDomain");
        Binding binding = readBinding(domain);
        if (!domain.equals(binding.trustDomain())
                || !fields.get("signatureProfile").equals(binding.profile().value())
                || !fields.get("signingKeyFingerprint").equals(binding.fingerprint())) {
            throw new Failure(Reason.TRUST_MISMATCH);
        }
        OtaReleaseSigner.Request request = new OtaReleaseSigner.Request(requestId, domain,
                binding.keyVersion(), binding.profile(), binding.fingerprint(),
                OtaReleaseSignatureVerifier.signingInput(canonical));
        OtaReleaseSigner.Response response;
        try {
            response = signer.sign(request);
        } catch (RuntimeException exception) {
            throw new Failure(Reason.SIGNER_UNAVAILABLE);
        }
        if (response == null || !requestId.equals(response.requestId())
                || !binding.keyVersion().equals(response.keyVersion()) || binding.profile() != response.profile()
                || !identifier(response.receipt())
                || !codec.verifySignature(canonical, response.spki(), binding.fingerprint(), response.signature())) {
            throw new Failure(Reason.INVALID_RESPONSE);
        }
        if (!binding.equals(readBinding(domain))) {
            throw new Failure(Reason.TRUST_CHANGED);
        }
        return new Result(requestId, canonical, binding, response.spki(), response.signature(), response.receipt());
    }

    /** 捕获可信适配器的普通失败，拒绝空或畸形绑定且不透出不可信异常正文。 */
    private Binding readBinding(String domain) {
        Binding binding;
        try {
            binding = trustSource.activeKey(domain);
        } catch (RuntimeException exception) {
            throw new Failure(Reason.TRUST_UNAVAILABLE);
        }
        if (binding == null || binding.trustDomain() == null
                || !binding.trustDomain().matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
                || !identifier(binding.keyVersion()) || binding.profile() == null
                || binding.fingerprint() == null || !binding.fingerprint().matches("[0-9a-f]{64}")
                || binding.revision() <= 0) {
            throw new Failure(Reason.TRUST_UNAVAILABLE);
        }
        return binding;
    }

    /** 版本和回执限定可审计标识字符，禁止空白、查询串、控制字符和无限长度。 */
    private static boolean identifier(String value) {
        return value != null && value.matches("[A-Za-z0-9._:/-]{1,256}");
    }

    /**
     * 本地复验成功的签名结果，不等价于固件 READY 或生产 KMS/HSM 资格。
     * @param requestId 本次请求身份
     * @param canonicalManifest 完整校验后的规范 manifest
     * @param binding 签名前后相同的可信绑定快照
     * @param spki 本地复验通过的公开密钥
     * @param signature 本地复验通过的签名
     * @param receipt 不含秘密的供应商回执标识
     */
    public record Result(UUID requestId, byte[] canonicalManifest, Binding binding,
                         byte[] spki, byte[] signature, String receipt) {
        /** 防御复制通过验真的字节，避免调用方修改结果快照。 */
        public Result {
            canonicalManifest = canonicalManifest.clone();
            spki = spki.clone();
            signature = signature.clone();
        }

        /** 返回规范 manifest 的独立副本。 */
        @Override
        public byte[] canonicalManifest() {
            return canonicalManifest.clone();
        }

        /** 返回公开密钥的独立副本。 */
        @Override
        public byte[] spki() {
            return spki.clone();
        }

        /** 返回签名的独立副本。 */
        @Override
        public byte[] signature() {
            return signature.clone();
        }
    }

    /** 只包含固定分类的失败，不携带供应商正文、凭据或底层异常链。 */
    public static final class Failure extends RuntimeException {
        /** 稳定序列化版本。 */
        private static final long serialVersionUID = 1L;
        /** 可供上层持久化或映射的固定失败分类。 */
        private final Reason reason;

        /** 构造安全消息，避免传播外部异常数据。 */
        private Failure(Reason reason) {
            super(reason.name());
            this.reason = reason;
        }

        /** 返回固定分类，调用方不得据此自动重复签名。 */
        public Reason reason() {
            return reason;
        }
    }

    /** 签名协调失败的有界分类。 */
    public enum Reason {
        /** 请求身份或 manifest 字段不合法。 */
        INVALID_MANIFEST,
        /** 可信源不可用、缺配置或返回无效绑定。 */
        TRUST_UNAVAILABLE,
        /** 有效绑定与 manifest 的声明身份不一致。 */
        TRUST_MISMATCH,
        /** 外部 signer 拒绝、不可用或结果未知。 */
        SIGNER_UNAVAILABLE,
        /** 响应身份、回执或签名字节未通过复验。 */
        INVALID_RESPONSE,
        /** 调用期间可信资格快照发生变化。 */
        TRUST_CHANGED
    }
}
