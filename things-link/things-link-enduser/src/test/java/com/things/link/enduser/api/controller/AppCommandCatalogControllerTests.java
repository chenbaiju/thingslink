package com.things.link.enduser.api.controller;

import com.things.link.device.application.AppCommandDefinition;
import com.things.link.enduser.application.AppCommandCatalogService;
import com.things.link.shared.error.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.jwt.Jwt;
import tools.jackson.databind.json.JsonMapper;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 真实JSON编码保证总字节与nullable字段，不以字符数替代UTF-8预算。 */
class AppCommandCatalogControllerTests {
    /** 仅授权应用入口采用替身，编码真实执行。 */
    private final AppCommandCatalogService service = mock(AppCommandCatalogService.class);
    /** 使用Jackson3实际编码器。 */
    private final AppCommandCatalogController controller = new AppCommandCatalogController(service, JsonMapper.builder().build());
    /** 真实结构身份声明。 */
    private final UUID project = UUID.randomUUID();
    /** App身份。 */
    private final UUID user = UUID.randomUUID();
    /** 目标设备。 */
    private final UUID device = UUID.randomUUID();

    /** 可空Schema字段仍必须存在，且响应始终no-store。 */
    @Test void writesExactFieldsWithNullSchemas() {
        when(service.list(project, user, device)).thenReturn(List.of(
                new AppCommandDefinition("restart", "重启", null, null, null, 30)));
        var servlet = new MockHttpServletResponse();
        var response = controller.list(jwt(), device, servlet);
        var json = JsonMapper.builder().build().readTree(response.getBody());
        assertThat(json.size()).isEqualTo(2);
        assertThat(json.path("deviceId").asString()).isEqualTo(device.toString());
        assertThat(json.path("commands").get(0).size()).isEqualTo(6);
        assertThat(json.path("commands").get(0).path("inputSchema").isNull()).isTrue();
        assertThat(servlet.getHeader("Cache-Control")).isEqualTo("no-store");
    }
    /** 单项Schema合法但合计超256KiB时不能发送部分目录。 */
    @Test void rejectsWholeUtf8ResponseBeforeSending() {
        when(service.list(project, user, device)).thenReturn(Collections.nCopies(10,
                new AppCommandDefinition("restart", "重启", null, "中".repeat(16000), null, 30)));
        var servlet = new MockHttpServletResponse();
        assertThatThrownBy(() -> controller.list(jwt(), device, servlet))
                .isInstanceOfSatisfying(BusinessException.class,
                        failure -> assertThat(failure.errorCode().code()).isEqualTo(30064));
        assertThat(servlet.isCommitted()).isFalse();
        assertThat(servlet.getContentAsByteArray()).isEmpty();
        assertThat(servlet.getHeader("Cache-Control")).isEqualTo("no-store");
    }
    /** 固定上限按字节精确含边界，下一字节拒绝。 */
    @Test void boundedOutputAllowsExactLimitOnly() {
        var output = new AppCommandCatalogController.BoundedOutput();
        output.write(new byte[256 * 1024], 0, 256 * 1024);
        assertThat(output.bytes()).hasSize(256 * 1024);
        assertThatThrownBy(() -> output.write(0)).isInstanceOf(BusinessException.class);
    }
    /** Controller只读取已验证JWT；签名验证由真实安全链负责。 */
    private Jwt jwt() {
        return Jwt.withTokenValue("fixture").header("alg", "none").subject(user.toString())
                .claim("pid", project.toString()).build();
    }
}
