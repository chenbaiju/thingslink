package com.things.link.shared.page;

import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.CommonErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("游标编解码")
class CursorTests {

    @Test
    @DisplayName("编码后可还原")
    void roundTrips() {
        String payload = "2026-08-01T00:00:00Z|01923f1e-0000-7000-8000-000000000000";

        assertThat(Cursor.decode(Cursor.encode(payload))).isEqualTo(payload);
    }

    /**
     * 游标出现在查询参数里。含 {@code + / =} 的标准 Base64 需要额外转义，
     * 容易在各层代理与客户端之间出错。
     */
    @Test
    @DisplayName("编码结果是 URL 安全且无填充的")
    void producesUrlSafeOutput() {
        String encoded = Cursor.encode("需要中文与特殊字符 ??>>~~ 来触发 + / = 的场景");

        assertThat(encoded).doesNotContain("+", "/", "=");
    }

    /**
     * 游标来自客户端，属于不可信输入。解不开是客户端的问题（400），
     * 不能让 IllegalArgumentException 冒泡成 500。
     */
    @Test
    @DisplayName("非法游标抛业务异常而非运行时异常")
    void rejectsMalformedCursor() {
        assertThatThrownBy(() -> Cursor.decode("这不是合法的 base64!!!"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).errorCode())
                        .isEqualTo(CommonErrorCode.INVALID_PARAMETER));
    }

}
