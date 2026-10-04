package com.things.link.dashboard.application;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 看板Schema合同版本测试。
 *
 * <p>X-02把协议值作为后续服务端、生成类型和共享客户端的汇合点；测试防止普通重命名
 * 静默改变持久化及HTTP可见值。</p>
 */
@DisplayName("看板Schema合同版本")
class DashboardSchemaContractVersionTests {
    /** 看板V1必须保持设计合同冻结的稳定值。 */
    @Test
    @DisplayName("V1使用冻结的tc.dashboard/v1值")
    void versionOneUsesFrozenProtocolValue() {
        assertThat(DashboardSchemaContractVersion.V1.value()).isEqualTo("tc.dashboard/v1");
    }
}
