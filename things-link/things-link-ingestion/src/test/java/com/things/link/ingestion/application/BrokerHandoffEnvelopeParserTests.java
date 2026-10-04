package com.things.link.ingestion.application;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** Broker 信封解析测试，覆盖有效 v1、未知字段和传输不变量漂移。 */
class BrokerHandoffEnvelopeParserTests {

    /** 精确 v1 信封应保留 Broker 接收时刻与原始身份字段。 */
    @Test
    void parsesExactVersionOneEnvelope() {
        BrokerHandoffEnvelopeParser parser  =  new BrokerHandoffEnvelopeParser(new ObjectMapper());

        BrokerHandoffEnvelope envelope  =  parser.parse(validJson()).orElseThrow();

        assertThat(envelope.handoffId()).isEqualTo("0001AABB");
        assertThat(envelope.topic()).isEqualTo("tc/v1/project_1/device_1/up/property/report");
        assertThat(envelope.publishedAtMs()).isEqualTo(1234L);
    }

    /** 未知字段、错误版本、QoS/retain 漂移和非法 Base64 必须 fail-closed。 */
    @Test
    void rejectsSchemaAndTransportDrift() {
        BrokerHandoffEnvelopeParser parser  =  new BrokerHandoffEnvelopeParser(new ObjectMapper());
        String valid  =  new String(validJson(), StandardCharsets.UTF_8);

        assertThat(parser.parse(valid.replace("\"brokerNode\"","\"unknown\":1,\"brokerNode\"")
                .getBytes(StandardCharsets.UTF_8))).isEmpty();
        assertThat(parser.parse(valid.replace("\"schemaVersion\":1","\"schemaVersion\":2")
                .getBytes(StandardCharsets.UTF_8))).isEmpty();
        assertThat(parser.parse(valid.replace("\"qos\":1","\"qos\":0")
                .getBytes(StandardCharsets.UTF_8))).isEmpty();
        assertThat(parser.parse(valid.replace("\"retained\":false","\"retained\":true")
                .getBytes(StandardCharsets.UTF_8))).isEmpty();
        assertThat(parser.parse(valid.replace("e30=","%%%")
                .getBytes(StandardCharsets.UTF_8))).isEmpty();
    }

    /** 新版本保留认证时代际，旧信封和明确null不会被升级为当前身份。 */
    @Test
    void preservesVersionTwoIdentityAndNullCompatibility() {
        var parser = new BrokerHandoffEnvelopeParser(new ObjectMapper());
        String first = new String(validJson(), StandardCharsets.UTF_8);
        String v2 = first.replace("\"schemaVersion\":1","\"schemaVersion\":2").replace("\"brokerNode\"","\"authenticatedIdentity\":"+identity()+",\"brokerNode\"");
        assertThat(parser.parse(v2.getBytes(StandardCharsets.UTF_8)).orElseThrow().authenticatedIdentity().credentialVersion()).isEqualTo(Long.MAX_VALUE);
        assertThat(parser.parse(first.getBytes(StandardCharsets.UTF_8)).orElseThrow().authenticatedIdentity()).isNull();
        assertThat(parser.parse(v2.replace(identity(),"null").getBytes(StandardCharsets.UTF_8)).orElseThrow().authenticatedIdentity()).isNull();
        for (String invalid:java.util.List.of(
                identity().replace("9223372036854775807","9223372036854775808"),
                identity().replace("9223372036854775807","01"), identity().replace("9223372036854775807","-1"),
                identity().replace("\"9223372036854775807\"","1"),
                identity().replace("\"tenantId\"","\"extra\":\"value\",\"tenantId\""),
                identity().replace("\"projectId\"","\"tenantId\":\"00000000-0000-0000-0000-000000000001\",\"projectId\""),
                identity().replace("00000000-0000-0000-0000-000000000001","00000000-0000-0000-0000-00000000000A"))) {
            assertThat(parser.parse(v2.replace(identity(), invalid).getBytes(StandardCharsets.UTF_8))).isEmpty();
        }
        assertThat(parser.parse(v2.replace("\"schemaVersion\":2","\"schemaVersion\":2,\"schemaVersion\":2").getBytes(StandardCharsets.UTF_8))).isEmpty();
    }
    /** 认证字段均为规范字符串，使用long上边界证明未经double取值。 */
    private static String identity() {
        return """
                {"tenantId":"00000000-0000-0000-0000-000000000001",\
                "projectId":"00000000-0000-0000-0000-000000000002",\
                "deviceId":"00000000-0000-0000-0000-000000000003",\
                "credentialVersion":"9223372036854775807"}
                """.strip();
    }

    /** 构造字段集合与 ADR 0046 完全一致的 JSON。 */
    static byte[] validJson() {
        return"""
                {"schemaVersion":1,"handoffId":"0001AABB","username":"project_1/device_1", \
"topic":"tc/v1/project_1/device_1/up/property/report","payloadBase64":"e30=", \
"qos":1,"retained":false,"clientId":"device-client","publishedAtMs":1234, \
"brokerNode":"emqx@127.0.0.1"}
""".getBytes(StandardCharsets.UTF_8);
    }
}
