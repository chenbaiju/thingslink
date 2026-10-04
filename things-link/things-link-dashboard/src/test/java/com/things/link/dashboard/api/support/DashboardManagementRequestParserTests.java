package com.things.link.dashboard.api.support;

import com.things.link.dashboard.api.dto.request.CreateDashboardRequest;
import com.things.link.dashboard.api.dto.request.SaveDashboardDraftRequest;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.CommonErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** S12-1d4看板写信封在DTO建树前的严格语法与原始content切片测试。 */
@DisplayName("看板管理写信封原文解析")
class DashboardManagementRequestParserTests {

    /** 被测解析器无外部状态，直接实例化可把失败定位到token边界。 */
    private final DashboardManagementRequestParser parser = new DashboardManagementRequestParser();

    /** 创建信封只忽略外层字段顺序与空白，名称和content原始字节必须精确保留并隔离。 */
    @Test
    @DisplayName("创建信封保留管理名称与content原始字节")
    void preservesAndIsolatesCreationInput() {
        String content = "{ \"title\" : \"甲\",\n\"title\":\"乙\" }";
        byte[] source = bytes(" { \"content\" : " + content + " , \"managementName\" : \" 新看板😀 \" } ");

        CreateDashboardRequest request = parser.parseCreate(source);
        assertThat(request.managementName()).isEqualTo(" 新看板😀 ");
        assertThat(request.content()).containsExactly(bytes(content));

        Arrays.fill(source, (byte) 'x');
        byte[] exposed = request.content();
        Arrays.fill(exposed, (byte) 'y');
        assertThat(request.content()).containsExactly(bytes(content));
    }

    /** 合法改名必须保留Title原文，不能在HTTP层隐式trim或Unicode规范化。 */
    @Test
    @DisplayName("改名信封保留管理名称原文")
    void preservesRenameTextExactly() {
        String body = "{\"managementName\":\" 新名称😀 \"}";

        assertThat(parser.parseRename(bytes(body)).managementName()).isEqualTo(" 新名称😀 ");
    }

    /** content内部空白与重复键必须逐字节下传，并在构造和访问两侧防御复制。 */
    @Test
    @DisplayName("草稿信封精确截取并隔离content原始字节")
    void preservesAndIsolatesRawContentBytes() {
        String content = "{ \"title\" : \"甲\",\n\"title\":\"乙\" }";
        byte[] source = bytes(" { \"content\" : " + content + " , \"expectedRevision\" : \"7\" } ");

        SaveDashboardDraftRequest request = parser.parseSaveDraft(source);
        assertThat(request.expectedRevision()).isEqualTo("7");
        assertThat(request.content()).containsExactly(bytes(content));

        Arrays.fill(source, (byte) 'x');
        byte[] exposed = request.content();
        Arrays.fill(exposed, (byte) 'y');
        assertThat(request.content()).containsExactly(bytes(content));
    }

    /** 非法UTF-8、BOM、Unicode标量和JSON尾随值统一失败关闭且不泄露解析原文。 */
    @Test
    @DisplayName("拒绝外层字节与JSON词法异常")
    void rejectsInvalidOuterEncodingAndJsonSyntax() {
        List<byte[]> invalid = new ArrayList<>();
        invalid.add(new byte[0]);
        invalid.add(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF, '{', '}'});
        invalid.add(new byte[]{'{', '"', 'm', 'a', 'n', 'a', 'g', 'e', 'm', 'e', 'n', 't', 'N', 'a', 'm', 'e',
                '"', ':', '"', (byte) 0xC3, '(', '"', '}'});
        invalid.add(bytes("{\"managementName\":\"\\u0000\"}"));
        invalid.add(bytes("{\"managementName\":\"\\uD800\"}"));
        invalid.add(bytes("{\"managementName\":\"甲\"}{}"));
        invalid.add(bytes("{\"managementName\":\"甲\""));

        invalid.forEach(source -> assertMalformed(() -> parser.parseRename(source)));
    }

    /** 三种信封均为精确字段闭集，缺失、null、错类型、未知与解码后重复都归10002。 */
    @Test
    @DisplayName("拒绝外层字段集合与类型异常")
    void rejectsInvalidEnvelopeFieldsAndTypes() {
        List<String> invalidCreate = List.of(
                "{}",
                "{\"managementName\":\"甲\"}",
                "{\"content\":{}}",
                "{\"managementName\":null,\"content\":{}}",
                "{\"managementName\":1,\"content\":{}}",
                "{\"managementName\":\"甲\",\"content\":null}",
                "{\"managementName\":\"甲\",\"content\":[]}",
                "{\"managementName\":\"甲\",\"content\":{},\"unknown\":true}",
                "{\"managementName\":\"甲\",\"management\\u004eame\":\"乙\",\"content\":{}}"
        );
        invalidCreate.forEach(value -> assertMalformed(() -> parser.parseCreate(bytes(value))));

        List<String> invalidRename = List.of(
                "{}",
                "{\"managementName\":null}",
                "{\"managementName\":1}",
                "{\"managementName\":\"甲\",\"unknown\":true}",
                "{\"managementName\":\"甲\",\"management\\u004eame\":\"乙\"}"
        );
        invalidRename.forEach(value -> assertMalformed(() -> parser.parseRename(bytes(value))));

        List<String> invalidSave = List.of(
                "{}",
                "{\"expectedRevision\":\"0\"}",
                "{\"content\":{}}",
                "{\"expectedRevision\":null,\"content\":{}}",
                "{\"expectedRevision\":0,\"content\":{}}",
                "{\"expectedRevision\":\"0\",\"content\":null}",
                "{\"expectedRevision\":\"0\",\"content\":[]}",
                "{\"expectedRevision\":\"0\",\"content\":{},\"unknown\":true}",
                "{\"expectedRevision\":\"0\",\"expectedRevision\":\"1\",\"content\":{}}"
        );
        invalidSave.forEach(value -> assertMalformed(() -> parser.parseSaveDraft(bytes(value))));
    }

    /** 所有外层拒绝只暴露通用错误，未知字段、解析器异常和请求正文均不得进入细节。 */
    private static void assertMalformed(Runnable invocation) {
        BusinessException failure;
        try {
            invocation.run();
            throw new AssertionError("非法信封未被拒绝");
        } catch (BusinessException exception) {
            failure = exception;
        }

        assertThat(failure.errorCode()).isEqualTo(CommonErrorCode.MALFORMED_REQUEST);
        assertThat(failure.getMessage()).isEqualTo(CommonErrorCode.MALFORMED_REQUEST.defaultMessage());
        assertThat(failure.details()).isEmpty();
    }

    /** 以固定UTF-8编码建立HTTP原文，避免平台默认字符集影响字节断言。 */
    private static byte[] bytes(String source) {
        return source.getBytes(StandardCharsets.UTF_8);
    }
}
