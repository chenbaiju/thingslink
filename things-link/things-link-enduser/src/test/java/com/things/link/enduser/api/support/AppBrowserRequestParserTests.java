package com.things.link.enduser.api.support;

import com.things.link.shared.error.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 三入口闭集与原始编码边界，不接受旧JSON refreshToken字段。 */
class AppBrowserRequestParserTests {
    /** 规范16随机字节的测试代次编码。 */
    private static final String EPOCH = "be_" + "A".repeat(22);
    /** 独立严格解析器。 */
    private final AppBrowserRequestParser parser = new AppBrowserRequestParser();
    /** 正常输入保留登录密码原文，不做trim破坏密码语义。 */
    @Test void acceptsOnlyClosedLoginAndEpochShape() throws Exception {
        var login = parser.login(request("{\"projectKey\":\"project\",\"username\":\"user\",\"password\":\" secret \",\"browserEpoch\":\"" + EPOCH + "\"}"));
        assertThat(login.password()).isEqualTo(" secret ");
        assertThat(parser.epoch(request("{\"browserEpoch\":\"" + EPOCH + "\"}"))).isEqualTo(EPOCH);
    }
    /** 重复字段、旧refresh、BOM、尾随JSON和非法代次全拒绝。 */
    @Test void rejectsAmbiguousOrLegacyBodies() {
        for (String body : new String[] {"{}", "{\"browserEpoch\":\"x\"}",
                "{\"browserEpoch\":\"" + EPOCH + "\",\"refreshToken\":\"secret\"}",
                "{\"browserEpoch\":\"" + EPOCH + "\",\"browserEpoch\":\"" + EPOCH + "\"}", "\ufeff{}", "{}{}"}) {
            assertThatThrownBy(() -> parser.epoch(request(body))).isInstanceOf(BusinessException.class);
        }
    }
    /** 真实读取限制覆盖未知Content-Length，不能只依赖Header。 */
    @Test void refusesRawBodyBeyondEightKibibytes() {
        assertThatThrownBy(() -> parser.epoch(request(" ".repeat(8193)))).isInstanceOfSatisfying(BusinessException.class,
                failure -> assertThat(failure.errorCode().code()).isEqualTo(10013));
    }
    /** UTF-8原始HTTP正文。 */
    private static MockHttpServletRequest request(String body) {
        var request = new MockHttpServletRequest(); request.setContent(body.getBytes(StandardCharsets.UTF_8)); return request;
    }
}
