package com.things.link.dashboard.api.controller;

import com.things.link.shared.error.BusinessException;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 匿名Schema先受有界编码保护，不能写出半个大对象再发现超过单次预算。 */
class DashboardShareRuntimeEncodingTests {
    /** 精确边界接受；额外一字节拒绝且原缓冲保持完整、不追加部分新块。 */
    @Test
    void acceptsExactLimitAndRejectsNextByteWithoutPartialWrite() {
        var output = new DashboardShareRuntimeController.BoundedJsonOutput(4);
        output.write(new byte[] {1, 2, 3}, 0, 3);
        output.write(4);
        assertThat(output.bytes()).containsExactly(1, 2, 3, 4);
        assertThatThrownBy(() -> output.write(5)).isInstanceOfSatisfying(BusinessException.class,
                error -> assertThat(error.errorCode().code()).isEqualTo(60055));
        assertThat(output.bytes()).containsExactly(1, 2, 3, 4);
    }

    /** UTF-8按字节计量，中文字符数不能用于规避HTTP上限。 */
    @Test
    void countsEncodedUtf8BytesAndRejectsWholeOversizedBlock() {
        var output = new DashboardShareRuntimeController.BoundedJsonOutput(4);
        byte[] value = "分享".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> output.write(value, 0, value.length)).isInstanceOf(BusinessException.class);
        assertThat(output.bytes()).isEmpty();
        output.write(value, 0, 3);
        assertThat(output.bytes()).hasSize(3);
    }

    /** 生产端点上限来自冻结合同，不能把包装上限当context上限。 */
    @Test
    void keepsContextAndSchemaBudgetsIndependent() {
        assertThat(DashboardShareRuntimeController.CONTEXT_LIMIT).isEqualTo(16 * 1024);
        assertThat(DashboardShareRuntimeController.SCHEMA_LIMIT).isEqualTo(768 * 1024);
    }
}
