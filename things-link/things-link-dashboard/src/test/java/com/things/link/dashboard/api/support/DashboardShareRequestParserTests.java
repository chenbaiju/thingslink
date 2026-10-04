package com.things.link.dashboard.api.support;

import com.things.link.shared.error.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.charset.StandardCharsets;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 分享签发严格信封的词法、类型与业务语法边界，不能让建树吞掉重复授权字段。 */
class DashboardShareRequestParserTests {
    /** 无外部依赖的实际HTTP解析器。 */
    private final DashboardShareRequestParser parser = new DashboardShareRequestParser();
    /** 纯静态看板仍要求显式空variables及有限HostRange。 */
    private static final String VALID = """
            {"dashboardVersionId":"00000000-0000-0000-0000-000000000001",
             "expectedDashboardPublicationRevision":"1","expiresInSeconds":3600,
             "refererPolicy":"HOST_ORIGIN","hostCompatibility":{"minInclusive":"1.0.0","maxExclusive":"1.0.1"},
             "variables":[]}
            """;

    /** 合法请求保留期限与范围原值，后续领域服务核实际版本与资格。 */
    @Test
    void acceptsExplicitStaticShare() {
        var request = parser.parseCreate(bytes(VALID));
        assertThat(request.expiresInSeconds()).isEqualTo(3600);
        assertThat(request.refererPolicy()).isEqualTo("HOST_ORIGIN");
        assertThat(request.variables()).isEmpty();
    }

    /** 重复键、尾随值和Unicode攻击在任何层都不能被JSON树覆盖。 */
    @ParameterizedTest
    @ValueSource(strings = {"duplicateRoot", "duplicateHost", "duplicateVariable", "unknown", "missing",
            "null", "float", "trailing", "bom", "surrogate", "nul", "wrongArray", "rootArray", "truncated"})
    void rejectsMalformedEnvelope(String scenario) {
        String value = switch (scenario) {
            case "duplicateRoot" -> VALID.replace("3600", "3600,\"expiresInSeconds\":300");
            case "duplicateHost" -> VALID.replace("\"1.0.0\"", "\"1.0.0\",\"minInclusive\":\"2.0.0\"");
            case "duplicateVariable" -> VALID.replace("[]", "[{\"variableKey\":\"x\",\"variableKey\":\"y\",\"deviceIds\":[]}]");
            case "unknown" -> VALID.replace("3600", "3600,\"unexpected\":true");
            case "missing" -> VALID.replace("\"expiresInSeconds\":3600,", "");
            case "null" -> VALID.replace("3600", "null");
            case "float" -> VALID.replace("3600", "3600.0");
            case "trailing" -> VALID + "{}";
            case "bom" -> "\uFEFF" + VALID;
            case "surrogate" -> VALID.replace("HOST_ORIGIN", "\\uD800");
            case "nul" -> VALID.replace("HOST_ORIGIN", "\\u0000");
            case "wrongArray" -> VALID.replace("[]", "{}");
            case "rootArray" -> "[" + VALID + "]";
            case "truncated" -> VALID.substring(0, VALID.length() - 3);
            default -> throw new AssertionError(scenario);
        };
        assertCode(bytes(value), 10002);
    }

    /** 合法JSON中非法UUID及无法表达的期限不冒充词法错误。 */
    @Test
    void rejectsBusinessSyntaxWithoutLeakingInput() {
        assertCode(bytes(VALID.replace("00000000-0000-0000-0000-000000000001", "1-1-1-1-1")), 60049);
        assertCode(bytes(VALID.replace("3600", "2147483648")), 60049);
    }

    /** 请求体编码错误不能用替换字符掩盖。 */
    @Test
    void rejectsInvalidUtf8() {
        assertCode(new byte[] {(byte) 0xc3, 0x28}, 10002);
    }

    /** 无body合同不把空对象或空白接受为合法撤销信封。 */
    @Test
    void revokeAcceptsOnlyAbsentOrEmptyBody() {
        parser.requireNoBody(null);
        parser.requireNoBody(new byte[0]);
        assertThatThrownBy(() -> parser.requireNoBody(bytes("{}"))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> parser.requireNoBody(bytes(" "))).isInstanceOf(BusinessException.class);
    }

    /** 断言稳定错误码，不依赖异常原文或动态字段。 */
    private void assertCode(byte[] source, int code) {
        assertThatThrownBy(() -> parser.parseCreate(source)).isInstanceOfSatisfying(BusinessException.class,
                error -> assertThat(error.errorCode().code()).isEqualTo(code));
    }

    /** 固定UTF-8输入，不使用本机默认字符集。 */
    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
