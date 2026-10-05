package com.things.link.dashboard.application;

/**
 * 看板声明式Schema合同版本。
 *
 * <p>X-02只冻结跨服务端与共享客户端都可识别的稳定版本值；解析、发布与持久化不得
 * 用未登记字符串绕过版本分派。来源见看板Schema合同第1节。</p>
 */
public enum DashboardSchemaContractVersion {
    /** 首个封闭声明式看板合同。 */
    V1("tc.dashboard/v1");

    /** 跨HTTP、存储和共享客户端使用的稳定协议值。 */
    private final String value;

    /**
     * 创建已登记合同版本。
     *
     * @param value 稳定协议值
     */
    DashboardSchemaContractVersion(String value) {
        this.value = value;
    }

    /**
     * 返回稳定协议值。
     *
     * @return 固定的看板契约版本 {@code tc.dashboard/v1}
     */
    public String value() {
        return value;
    }
}
