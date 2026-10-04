package com.things.link.enduser.api.support;

import com.things.link.enduser.api.dto.request.UpdateAppUserDashboardGrantRequest;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.CommonErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 终端用户看板授权更新信封在DTO绑定前的严格词法、字段闭集与原始字符串保留测试。 */
@DisplayName("终端用户看板授权信封原文解析")
class AppUserDashboardGrantRequestParserTests {

    /** 解析器无外部状态，直接实例化可把失败定位到HTTP原始信封边界。 */
    private final AppUserDashboardGrantRequestParser parser = new AppUserDashboardGrantRequestParser();

    /** 两种字段顺序均合法，且业务字符串不会在HTTP层trim、改大小写或转换为Long。 */
    @Test
    @DisplayName("保留revision和状态原始字符串且不依赖字段顺序")
    void preservesBusinessStringsInEitherFieldOrder() {
        UpdateAppUserDashboardGrantRequest statusFirst = parser.parseUpdate(bytes("""
                { "status" : " active ", "expectedRevision" : "007" }
                """));
        UpdateAppUserDashboardGrantRequest revisionFirst = parser.parseUpdate(bytes("""
                { "expectedRevision" : "+12", "status" : "REVOKED" }
                """));

        assertThat(statusFirst.expectedRevision()).isEqualTo("007");
        assertThat(statusFirst.status()).isEqualTo(" active ");
        assertThat(revisionFirst.expectedRevision()).isEqualTo("+12");
        assertThat(revisionFirst.status()).isEqualTo("REVOKED");
    }

    /** 合法补充平面字符属于业务字符串，解析器只拒绝不成对代理项。 */
    @Test
    @DisplayName("保留合法补充平面Unicode字符")
    void preservesSupplementaryUnicodeCharacters() {
        UpdateAppUserDashboardGrantRequest request = parser.parseUpdate(bytes(
                "{\"expectedRevision\":\"0\",\"status\":\"ACTIVE😀\"}"));

        assertThat(request.status()).isEqualTo("ACTIVE😀");
    }

    /** 空正文、非法UTF-8、BOM、非法Unicode、截断、非对象根和尾随值统一沿10002拒绝。 */
    @Test
    @DisplayName("拒绝编码、Unicode与JSON词法异常")
    void rejectsInvalidEncodingRootAndJsonSyntax() {
        assertMalformed(() -> parser.parseUpdate(null));
        List<byte[]> invalid = new ArrayList<>();
        invalid.add(new byte[0]);
        invalid.add(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF, '{', '}'});
        invalid.add(new byte[]{'{', '"', 's', 't', 'a', 't', 'u', 's', '"', ':', '"',
                (byte) 0xC3, '(', '"', '}'});
        invalid.add(new byte[]{(byte) 0xC0, (byte) 0xAF});
        invalid.add(new byte[]{(byte) 0xED, (byte) 0xA0, (byte) 0x80});
        invalid.add(new byte[]{(byte) 0xF0, (byte) 0x9F, (byte) 0x98});
        invalid.add(bytes("{\"expectedRevision\":\"0\",\"status\":\"\\u0000\"}"));
        invalid.add(bytes("{\"expectedRevision\":\"0\",\"status\":\"\\uD800\"}"));
        invalid.add(bytes("[]"));
        invalid.add(bytes("\"not-an-object\""));
        invalid.add(bytes("{\"expectedRevision\":\"0\",\"status\":\"ACTIVE\"}{}"));
        invalid.add(bytes("{\"expectedRevision\":\"0\""));
        String validJson = "{\"expectedRevision\":\"0\",\"status\":\"ACTIVE\"}";
        invalid.add(validJson.getBytes(StandardCharsets.UTF_16LE));
        invalid.add(validJson.getBytes(StandardCharsets.UTF_16BE));
        invalid.add(validJson.getBytes(Charset.forName("UTF-32LE")));
        invalid.add(validJson.getBytes(Charset.forName("UTF-32BE")));

        invalid.forEach(source -> assertMalformed(() -> parser.parseUpdate(source)));
    }

    /** 信封必须精确包含两个字符串字段，转义后的重名也不能绕过重复字段检测。 */
    @Test
    @DisplayName("拒绝未知重复缺失字段以及null和错误类型")
    void rejectsInvalidEnvelopeFieldsAndTypes() {
        List<String> invalid = List.of(
                "{}",
                "{\"expectedRevision\":\"0\"}",
                "{\"status\":\"ACTIVE\"}",
                "{\"expectedRevision\":null,\"status\":\"ACTIVE\"}",
                "{\"expectedRevision\":0,\"status\":\"ACTIVE\"}",
                "{\"expectedRevision\":true,\"status\":\"ACTIVE\"}",
                "{\"expectedRevision\":{},\"status\":\"ACTIVE\"}",
                "{\"expectedRevision\":[],\"status\":\"ACTIVE\"}",
                "{\"expectedRevision\":\"0\",\"status\":null}",
                "{\"expectedRevision\":\"0\",\"status\":0}",
                "{\"expectedRevision\":\"0\",\"status\":false}",
                "{\"expectedRevision\":\"0\",\"status\":{}}",
                "{\"expectedRevision\":\"0\",\"status\":[]}",
                "{\"expectedRevision\":\"0\",\"status\":\"ACTIVE\",\"unknown\":true}",
                "{\"expectedRevision\":\"0\",\"expectedRevision\":\"1\",\"status\":\"ACTIVE\"}",
                "{\"expectedRevision\":\"0\",\"expected\\u0052evision\":\"1\",\"status\":\"ACTIVE\"}",
                "{\"expectedRevision\":\"0\",\"status\":\"ACTIVE\",\"status\":\"REVOKED\"}",
                "{\"expectedRevision\":\"0\",\"status\":\"ACTIVE\",\"sta\\u0074us\":\"REVOKED\"}",
                "{\"expectedRevision\":\"0\",\"sta\\u0000tus\":\"ACTIVE\"}",
                "{\"expectedRevision\":\"0\",\"sta\\uD800tus\":\"ACTIVE\"}"
        );

        invalid.forEach(source -> assertMalformed(() -> parser.parseUpdate(bytes(source))));
    }

    /** 所有外层拒绝只暴露通用错误，不回显Jackson消息、字段名或请求原文。 */
    private static void assertMalformed(Runnable invocation) {
        BusinessException failure;
        try {
            invocation.run();
            throw new AssertionError("非法终端用户看板授权信封未被拒绝");
        } catch (BusinessException exception) {
            failure = exception;
        }

        assertThat(failure.errorCode()).isEqualTo(CommonErrorCode.MALFORMED_REQUEST);
        assertThat(failure.getMessage()).isEqualTo(CommonErrorCode.MALFORMED_REQUEST.defaultMessage());
        assertThat(failure.details()).isEmpty();
    }

    /** 固定使用UTF-8建立HTTP原文，避免平台默认字符集改变字节反例。 */
    private static byte[] bytes(String source) {
        return source.getBytes(StandardCharsets.UTF_8);
    }
}
