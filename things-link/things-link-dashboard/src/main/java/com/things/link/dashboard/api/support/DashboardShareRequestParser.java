package com.things.link.dashboard.api.support;

import com.things.link.dashboard.application.publication.DashboardShareCreateRequest;
import com.things.link.dashboard.application.publication.DashboardShareVariableRequest;
import com.things.link.dashboard.domain.DashboardErrorCode;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.CommonErrorCode;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.json.JsonMapper;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 分享合同第2节严格封闭信封；先检查每层重复键及UTF-8，再读取有限结构。
 * 不对unknown字段、解析器原文或客户端提供的文本构造动态错误消息。
 */
@Component
public final class DashboardShareRequestParser {
    /** 创建字段全部显式提供，默认值属于表单选择而非缺失字段的隐式授权。 */
    private static final Set<String> FIELDS = Set.of("dashboardVersionId", "expectedDashboardPublicationRevision",
            "expiresInSeconds", "refererPolicy", "hostCompatibility", "variables");
    /** HostRange的有限字段闭集。 */
    private static final Set<String> HOST_FIELDS = Set.of("minInclusive", "maxExclusive");
    /** 每个设备变量的字段闭集。 */
    private static final Set<String> VARIABLE_FIELDS = Set.of("variableKey", "deviceIds");
    /** 使用默认严格JSON语法，不打开注释或非标准数值容忍。 */
    private final ObjectReader reader = JsonMapper.builder().build().reader();

    /**
     * 把已验证的原始信封转成领域请求；UUID文本语法错误属于60049而非JSON类型错误。
     * @param source 原始UTF-8请求体
     * @return 无未知字段和重复键的请求
     */
    public DashboardShareCreateRequest parseCreate(byte[] source) {
        try {
            if (source == null || source.length == 0) {
                throw malformed();
            }
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(source)).toString();
            if (text.charAt(0) == '\uFEFF') {
                throw malformed();
            }
            // 每一层先消费token，不能让建树覆盖重复键后再做字段集合检查。
            try (JsonParser parser = reader.createParser(text)) {
                inspect(parser, parser.nextToken(), 0);
                if (parser.nextToken() != null) {
                    throw malformed();
                }
            }
            JsonNode root = reader.readTree(text);
            requireFields(root, FIELDS);
            UUID version = uuid(string(root.get("dashboardVersionId")));
            String revision = string(root.get("expectedDashboardPublicationRevision"));
            String policy = string(root.get("refererPolicy"));
            JsonNode ttl = root.get("expiresInSeconds");
            if (!ttl.isIntegralNumber()) {
                throw malformed();
            }
            if (!ttl.canConvertToInt()) {
                throw invalid();
            }
            JsonNode host = root.get("hostCompatibility");
            requireFields(host, HOST_FIELDS);
            string(host.get("minInclusive"));
            string(host.get("maxExclusive"));
            JsonNode variables = root.get("variables");
            if (!variables.isArray()) {
                throw malformed();
            }
            if (variables.size() > 20) {
                throw invalid();
            }
            List<DashboardShareVariableRequest> scopes = new ArrayList<>();
            for (JsonNode variable : variables) {
                requireFields(variable, VARIABLE_FIELDS);
                String key = string(variable.get("variableKey"));
                JsonNode devices = variable.get("deviceIds");
                if (!devices.isArray()) {
                    throw malformed();
                }
                if (devices.isEmpty() || devices.size() > 20) {
                    throw invalid();
                }
                List<UUID> ids = new ArrayList<>();
                for (JsonNode device : devices) {
                    ids.add(uuid(string(device)));
                }
                scopes.add(new DashboardShareVariableRequest(key, ids));
            }
            return new DashboardShareCreateRequest(version, revision, ttl.intValue(), policy, host, scopes);
        } catch (JacksonException | CharacterCodingException exception) {
            throw malformed();
        }
    }

    /** 撤销合同明确无body；空对象或空白也不转化成另一种请求。 */
    public void requireNoBody(byte[] source) {
        if (source != null && source.length != 0) {
            throw malformed();
        }
    }

    /** 递归检查有限JSON信封与所有字符串标量；深层攻击在建树前拒绝。 */
    private static void inspect(JsonParser parser, JsonToken token, int depth) {
        if (token == null || depth > 8) {
            throw malformed();
        }
        if (token == JsonToken.START_OBJECT) {
            Set<String> seen = new HashSet<>();
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                if (parser.currentToken() != JsonToken.PROPERTY_NAME) {
                    throw malformed();
                }
                String name = parser.getString();
                unicode(name);
                if (!seen.add(name)) {
                    throw malformed();
                }
                inspect(parser, parser.nextToken(), depth + 1);
            }
        } else if (token == JsonToken.START_ARRAY) {
            while (parser.nextToken() != JsonToken.END_ARRAY) {
                inspect(parser, parser.currentToken(), depth + 1);
            }
        } else if (token == JsonToken.VALUE_STRING) {
            unicode(parser.getString());
        } else if (token != JsonToken.VALUE_NUMBER_INT && token != JsonToken.VALUE_NUMBER_FLOAT
                && token != JsonToken.VALUE_TRUE && token != JsonToken.VALUE_FALSE && token != JsonToken.VALUE_NULL) {
            throw malformed();
        }
    }

    /** 对象成员必须精确匹配闭集，所有字段均禁止null。 */
    private static void requireFields(JsonNode node, Set<String> fields) {
        if (node == null || !node.isObject() || node.size() != fields.size()
                || fields.stream().anyMatch(field -> !node.hasNonNull(field))) {
            throw malformed();
        }
    }

    /** 仅允许已通过Unicode检查的非null字符串，不trim或重写用户选择。 */
    private static String string(JsonNode node) {
        if (node == null || !node.isString()) {
            throw malformed();
        }
        return node.stringValue();
    }

    /** UUID必须是规范小写完整文本，避免缩写或不同表示绕过摘要身份。 */
    private static UUID uuid(String value) {
        try {
            UUID id = UUID.fromString(value);
            if (!id.toString().equals(value)) {
                throw invalid();
            }
            return id;
        } catch (IllegalArgumentException exception) {
            throw invalid();
        }
    }

    /** 拒绝NUL与未配对代理字符，保持既有严格管理信封规则。 */
    private static void unicode(String value) {
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (current == 0 || Character.isLowSurrogate(current)) {
                throw malformed();
            }
            if (Character.isHighSurrogate(current)) {
                if (++index >= value.length() || !Character.isLowSurrogate(value.charAt(index))) {
                    throw malformed();
                }
            }
        }
    }

    /** 返回稳定JSON信封错误，不回显客户端原文。 */
    private static BusinessException malformed() {
        return new BusinessException(CommonErrorCode.MALFORMED_REQUEST);
    }

    /** 返回稳定分享参数错误，领域服务继续校验直接调用。 */
    private static BusinessException invalid() {
        return new BusinessException(DashboardErrorCode.SHARE_INVALID);
    }
}
