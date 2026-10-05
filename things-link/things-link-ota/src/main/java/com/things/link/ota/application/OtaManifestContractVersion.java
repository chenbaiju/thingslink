package com.things.link.ota.application;

/**
 * OTA不可变发布manifest合同版本。
 *
 * <p>值来自ADR0053第1节；后续实现只能显式分派已登记版本，不能在同一字符串下
 * 静默改变签名输入语义。</p>
 */
public enum OtaManifestContractVersion {
    /** ADR0053冻结的首个发布manifest合同。 */
    V1("tc-ota-manifest/v1");

    /** 进入manifest并参与JCS签名的稳定协议值。 */
    private final String value;

    /**
     * 创建已登记manifest合同版本。
     *
     * @param value 稳定协议值
     */
    OtaManifestContractVersion(String value) {
        this.value = value;
    }

    /**
     * 返回稳定协议值。
     *
     * @return 固定的升级清单契约版本 {@code tc-ota-manifest/v1}
     */
    public String value() {
        return value;
    }
}
