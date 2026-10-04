package com.things.link.ota.domain;

/** 持久历史预检裁决，只是当时观察，不是回退执行令牌。 */
public record OtaRollbackPreflightResult(OtaRollbackPreflightReceipt receipt, String disposition,
        String reason, byte[] qualificationCanonical) {
    /** 冻结当时资格完整证据。 */
    public OtaRollbackPreflightResult { qualificationCanonical = qualificationCanonical.clone(); }
    /** 返回独立历史证据。 */
    @Override public byte[] qualificationCanonical() { return qualificationCanonical.clone(); }
}
