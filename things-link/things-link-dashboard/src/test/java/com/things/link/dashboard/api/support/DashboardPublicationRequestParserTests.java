package com.things.link.dashboard.api.support;

import com.things.link.dashboard.api.dto.request.DashboardPublicationRevisionRequest;
import com.things.link.dashboard.api.dto.request.PublishDashboardVersionRequest;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.CommonErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** S12-1e1b至1e1c看板发布与回滚信封在DTO绑定前的严格词法和字段闭集测试。 */
@DisplayName("看板发布信封原文解析")
class DashboardPublicationRequestParserTests {

    /** 被测解析器无外部状态，直接实例化可把失败定位到HTTP信封边界。 */
    private final DashboardPublicationRequestParser parser = new DashboardPublicationRequestParser();

    /** 合法信封只提取两条revision原文，不在HTTP层trim、转数值或规范化。 */
    @Test
    @DisplayName("发布信封保留双revision字符串")
    void preservesBothRevisionStrings() {
        PublishDashboardVersionRequest request = parser.parsePublish(bytes("""
                { "expectedPublicationRevision" : " 11 ",
                  "expectedDraftRevision" : "007" }
                """));

        assertThat(request.expectedDraftRevision()).isEqualTo("007");
        assertThat(request.expectedPublicationRevision()).isEqualTo(" 11 ");
    }

    /** 非法UTF-8、BOM、Unicode标量、截断JSON和尾随值均统一沿10002失败关闭。 */
    @Test
    @DisplayName("拒绝发布信封字节与JSON词法异常")
    void rejectsInvalidEncodingAndJsonSyntax() {
        List<byte[]> invalid = new ArrayList<>();
        invalid.add(new byte[0]);
        invalid.add(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF, '{', '}'});
        invalid.add(new byte[]{'{', '"', 'e', 'x', 'p', 'e', 'c', 't', 'e', 'd', 'D', 'r', 'a', 'f', 't',
                'R', 'e', 'v', 'i', 's', 'i', 'o', 'n', '"', ':', '"', (byte) 0xC3, '(', '"', '}'});
        invalid.add(bytes("{\"expectedDraftRevision\":\"\\u0000\",\"expectedPublicationRevision\":\"0\"}"));
        invalid.add(bytes("{\"expectedDraftRevision\":\"\\uD800\",\"expectedPublicationRevision\":\"0\"}"));
        invalid.add(bytes("{\"expectedDraftRevision\":\"0\",\"expectedPublicationRevision\":\"0\"}{}"));
        invalid.add(bytes("{\"expectedDraftRevision\":\"0\""));

        invalid.forEach(source -> assertMalformed(() -> parser.parsePublish(source)));
    }

    /** 发布信封必须精确包含两个字符串字段，未知、缺失、null、错类型和解码后重复均归10002。 */
    @Test
    @DisplayName("拒绝发布信封字段集合与类型异常")
    void rejectsInvalidEnvelopeFieldsAndTypes() {
        List<String> invalid = List.of(
                "{}",
                "{\"expectedDraftRevision\":\"0\"}",
                "{\"expectedPublicationRevision\":\"0\"}",
                "{\"expectedDraftRevision\":null,\"expectedPublicationRevision\":\"0\"}",
                "{\"expectedDraftRevision\":0,\"expectedPublicationRevision\":\"0\"}",
                "{\"expectedDraftRevision\":\"0\",\"expectedPublicationRevision\":null}",
                "{\"expectedDraftRevision\":\"0\",\"expectedPublicationRevision\":0}",
                "{\"expectedDraftRevision\":\"0\",\"expectedPublicationRevision\":\"0\",\"unknown\":true}",
                "{\"expectedDraftRevision\":\"0\",\"expected\\u0044raftRevision\":\"1\","
                        + "\"expectedPublicationRevision\":\"0\"}"
        );

        invalid.forEach(source -> assertMalformed(() -> parser.parsePublish(bytes(source))));
    }

    /** 合法回滚信封只保留publicationRevision原文，数值语义统一留给领域层裁决。 */
    @Test
    @DisplayName("回滚信封保留publicationRevision字符串")
    void preservesRollbackPublicationRevisionString() {
        DashboardPublicationRevisionRequest request = parser.parsePublicationRevision(bytes("""
                { "expectedPublicationRevision" : " 11 " }
                """));

        assertThat(request.expectedPublicationRevision()).isEqualTo(" 11 ");
    }

    /** 回滚信封必须精确包含唯一字符串字段，发布专属字段、未知、重复和错类型均归10002。 */
    @Test
    @DisplayName("拒绝回滚信封字段集合与类型异常")
    void rejectsInvalidRollbackEnvelopeFieldsAndTypes() {
        List<String> invalid = List.of(
                "{}",
                "{\"expectedPublicationRevision\":null}",
                "{\"expectedPublicationRevision\":1}",
                "{\"expectedPublicationRevision\":\"1\",\"expectedDraftRevision\":\"0\"}",
                "{\"expectedPublicationRevision\":\"1\",\"unknown\":true}",
                "{\"expectedPublicationRevision\":\"1\",\"expectedPublicationRevision\":\"2\"}",
                "{\"expectedPublicationRevision\":\"1\",\"expected\\u0050ublicationRevision\":\"2\"}"
        );

        invalid.forEach(source -> assertMalformed(() -> parser.parsePublicationRevision(bytes(source))));
    }

    /** 回滚解析与发布共享严格UTF-8、Unicode标量和单根JSON边界，不能出现较宽松的旁路。 */
    @Test
    @DisplayName("拒绝回滚信封字节与JSON词法异常")
    void rejectsInvalidRollbackEncodingAndJsonSyntax() {
        List<byte[]> invalid = List.of(
                new byte[0],
                new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF, '{', '}'},
                bytes("{\"expectedPublicationRevision\":\"\\uD800\"}"),
                bytes("{\"expectedPublicationRevision\":\"1\"}{}"),
                bytes("{\"expectedPublicationRevision\":\"1\"")
        );

        invalid.forEach(source -> assertMalformed(() -> parser.parsePublicationRevision(source)));
    }

    /** 所有外层拒绝只暴露通用错误，不回显解析器异常或请求原文。 */
    private static void assertMalformed(Runnable invocation) {
        BusinessException failure;
        try {
            invocation.run();
            throw new AssertionError("非法发布信封未被拒绝");
        } catch (BusinessException exception) {
            failure = exception;
        }

        assertThat(failure.errorCode()).isEqualTo(CommonErrorCode.MALFORMED_REQUEST);
        assertThat(failure.getMessage()).isEqualTo(CommonErrorCode.MALFORMED_REQUEST.defaultMessage());
        assertThat(failure.details()).isEmpty();
    }

    /** 以固定UTF-8编码建立HTTP原文，避免平台默认字符集影响字节反例。 */
    private static byte[] bytes(String source) {
        return source.getBytes(StandardCharsets.UTF_8);
    }
}
