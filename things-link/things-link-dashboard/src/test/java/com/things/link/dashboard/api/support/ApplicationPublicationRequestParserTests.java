package com.things.link.dashboard.api.support;

import com.things.link.dashboard.api.dto.request.ApplicationPublicationRevisionRequest;
import com.things.link.dashboard.api.dto.request.PublishApplicationVersionRequest;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.CommonErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** S12-1e2c应用发布信封在DTO绑定前的严格词法、字段闭集和原始字符串保留测试。 */
@DisplayName("应用发布信封原文解析")
class ApplicationPublicationRequestParserTests {

    /** 被测解析器无外部状态，直接实例化可把失败定位到HTTP信封边界。 */
    private final ApplicationPublicationRequestParser parser = new ApplicationPublicationRequestParser();

    /** 两种字段顺序都合法，且revision原文不在HTTP层trim、转数值或规范化。 */
    @Test
    @DisplayName("发布信封保留双revision字符串且不依赖字段顺序")
    void preservesBothRevisionStringsInEitherFieldOrder() {
        PublishApplicationVersionRequest publicationFirst = parser.parsePublish(bytes("""
                { "expectedPublicationRevision" : " 11 ",
                  "expectedDraftRevision" : "007" }
                """));
        PublishApplicationVersionRequest draftFirst = parser.parsePublish(bytes("""
                { "expectedDraftRevision" : "0009",
                  "expectedPublicationRevision" : "+12" }
                """));

        assertThat(publicationFirst.expectedDraftRevision()).isEqualTo("007");
        assertThat(publicationFirst.expectedPublicationRevision()).isEqualTo(" 11 ");
        assertThat(draftFirst.expectedDraftRevision()).isEqualTo("0009");
        assertThat(draftFirst.expectedPublicationRevision()).isEqualTo("+12");
    }

    /** 非法UTF-8、BOM、Unicode标量、截断、非对象根和尾随值均统一沿10002失败关闭。 */
    @Test
    @DisplayName("拒绝发布信封字节与JSON词法异常")
    void rejectsInvalidEncodingRootAndJsonSyntax() {
        List<byte[]> invalid = new ArrayList<>();
        invalid.add(new byte[0]);
        invalid.add(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF, '{', '}'});
        invalid.add(new byte[]{'{', '"', 'e', 'x', 'p', 'e', 'c', 't', 'e', 'd', 'D', 'r', 'a', 'f', 't',
                'R', 'e', 'v', 'i', 's', 'i', 'o', 'n', '"', ':', '"', (byte) 0xC3, '(', '"', '}'});
        invalid.add(bytes("{\"expectedDraftRevision\":\"\\u0000\",\"expectedPublicationRevision\":\"0\"}"));
        invalid.add(bytes("{\"expectedDraftRevision\":\"\\uD800\",\"expectedPublicationRevision\":\"0\"}"));
        invalid.add(bytes("[]"));
        invalid.add(bytes("\"not-an-object\""));
        invalid.add(bytes("{\"expectedDraftRevision\":\"0\",\"expectedPublicationRevision\":\"0\"}{}"));
        invalid.add(bytes("{\"expectedDraftRevision\":\"0\""));

        invalid.forEach(source -> assertMalformed(() -> parser.parsePublish(source)));
    }

    /** 信封必须精确包含两个字符串字段，未知、重复、缺失、null和错误类型均归10002。 */
    @Test
    @DisplayName("拒绝发布信封字段集合与类型异常")
    void rejectsInvalidEnvelopeFieldsAndTypes() {
        List<String> invalid = List.of(
                "{}",
                "{\"expectedDraftRevision\":\"0\"}",
                "{\"expectedPublicationRevision\":\"0\"}",
                "{\"expectedDraftRevision\":null,\"expectedPublicationRevision\":\"0\"}",
                "{\"expectedDraftRevision\":0,\"expectedPublicationRevision\":\"0\"}",
                "{\"expectedDraftRevision\":true,\"expectedPublicationRevision\":\"0\"}",
                "{\"expectedDraftRevision\":{},\"expectedPublicationRevision\":\"0\"}",
                "{\"expectedDraftRevision\":\"0\",\"expectedPublicationRevision\":null}",
                "{\"expectedDraftRevision\":\"0\",\"expectedPublicationRevision\":0}",
                "{\"expectedDraftRevision\":\"0\",\"expectedPublicationRevision\":[]}",
                "{\"expectedDraftRevision\":\"0\",\"expectedPublicationRevision\":\"0\",\"unknown\":true}",
                "{\"expectedDraftRevision\":\"0\",\"expectedDraftRevision\":\"1\","
                        + "\"expectedPublicationRevision\":\"0\"}",
                "{\"expectedDraftRevision\":\"0\",\"expected\\u0044raftRevision\":\"1\","
                        + "\"expectedPublicationRevision\":\"0\"}",
                "{\"expectedDraftRevision\":\"0\",\"expectedPublicationRevision\":\"0\","
                        + "\"expectedPublicationRevision\":\"1\"}"
        );

        invalid.forEach(source -> assertMalformed(() -> parser.parsePublish(bytes(source))));
    }

    /** 回滚、撤回与软删共享单revision词法合同，解析层必须保留空白和前导零供领域层判定。 */
    @Test
    @DisplayName("单revision信封保留原始字符串")
    void preservesPublicationRevisionString() {
        ApplicationPublicationRevisionRequest request = parser.parsePublicationRevision(bytes("""
                { "expectedPublicationRevision" : " 007 " }
                """));

        assertThat(request.expectedPublicationRevision()).isEqualTo(" 007 ");
    }

    /** 单revision信封只允许一个字符串字段，转义后重名也必须被重复键检测捕获。 */
    @Test
    @DisplayName("拒绝单revision信封字段集合与类型异常")
    void rejectsInvalidPublicationRevisionFieldsAndTypes() {
        List<String> invalid = List.of(
                "{}",
                "{\"expectedPublicationRevision\":null}",
                "{\"expectedPublicationRevision\":0}",
                "{\"expectedPublicationRevision\":true}",
                "{\"expectedPublicationRevision\":{}}",
                "{\"expectedPublicationRevision\":[]}",
                "{\"expectedPublicationRevision\":\"0\",\"unknown\":true}",
                "{\"expectedPublicationRevision\":\"0\",\"expectedPublicationRevision\":\"1\"}",
                "{\"expectedPublicationRevision\":\"0\",\"expectedPublication\\u0052evision\":\"1\"}"
        );

        invalid.forEach(source -> assertMalformed(() -> parser.parsePublicationRevision(bytes(source))));
    }

    /** 非法编码、JSON根、尾随值和截断对单revision入口同样统一沿10002失败关闭。 */
    @Test
    @DisplayName("拒绝单revision信封字节与JSON词法异常")
    void rejectsInvalidPublicationRevisionEncodingRootAndSyntax() {
        List<byte[]> invalid = new ArrayList<>();
        invalid.add(new byte[0]);
        invalid.add(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF, '{', '}'});
        invalid.add(new byte[]{'{', '"', 'e', 'x', 'p', 'e', 'c', 't', 'e', 'd', 'P', 'u', 'b', 'l', 'i', 'c',
                'a', 't', 'i', 'o', 'n', 'R', 'e', 'v', 'i', 's', 'i', 'o', 'n', '"', ':', '"',
                (byte) 0xC3, '(', '"', '}'});
        invalid.add(bytes("{\"expectedPublicationRevision\":\"\\u0000\"}"));
        invalid.add(bytes("{\"expectedPublicationRevision\":\"\\uD800\"}"));
        invalid.add(bytes("[]"));
        invalid.add(bytes("\"not-an-object\""));
        invalid.add(bytes("{\"expectedPublicationRevision\":\"0\"}{}"));
        invalid.add(bytes("{\"expectedPublicationRevision\":\"0\""));

        invalid.forEach(source -> assertMalformed(() -> parser.parsePublicationRevision(source)));
    }

    /** 所有外层拒绝只暴露通用错误，不回显解析器异常或请求原文。 */
    private static void assertMalformed(Runnable invocation) {
        BusinessException failure;
        try {
            invocation.run();
            throw new AssertionError("非法应用发布信封未被拒绝");
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
