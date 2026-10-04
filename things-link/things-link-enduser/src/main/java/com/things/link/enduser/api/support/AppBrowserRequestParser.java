package com.things.link.enduser.api.support;

import com.things.link.enduser.api.dto.request.AppBrowserLoginRequest;
import com.things.link.enduser.application.AppBrowserCookieBinding;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.CommonErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/** 8KiB原始UTF-8 JSON闭集解析，先限流量再建树，不触发Servlet表单参数解析。 */
@Component
public final class AppBrowserRequestParser {
    /** 三个字段64/64/128及25字符代次即使全Unicode转义也在8KiB内。 */
    public static final int MAX_BYTES = 8 * 1024;
    /** 重复键/尾随JSON必须拒绝，不能靠默认对象绑定悄悄覆盖。 */
    private static final JsonMapper JSON = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    /** 登录不能偷偷接收旧JSON refresh或任意租户身份。 */
    public AppBrowserLoginRequest login(HttpServletRequest request) throws IOException {
        JsonNode body = object(request, Set.of("projectKey", "username", "password", "browserEpoch"));
        return new AppBrowserLoginRequest(text(body, "projectKey", 64), text(body, "username", 64),
                text(body, "password", 128), epoch(body));
    }
    /** refresh/logout仅期望代次，原始refresh只从HttpOnly Cookie验证后取得。 */
    public String epoch(HttpServletRequest request) throws IOException { return epoch(object(request, Set.of("browserEpoch"))); }
    /** 期望代次必须先于读取Cookie进行规范检查。 */
    private static String epoch(JsonNode body) {
        String value = text(body, "browserEpoch", 25);
        if (!AppBrowserCookieBinding.validEpoch(value)) throw invalid();
        return value;
    }
    /** 字符串不强制转换，拒绝单独代理字符以免UTF-8与密码语义分叉。 */
    private static String text(JsonNode body, String key, int max) {
        JsonNode node = body.path(key);
        if (!node.isString()) throw invalid();
        String value = node.stringValue();
        if (value.isBlank() || value.length() > max) throw invalid();
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            if (Character.isHighSurrogate(current)) {
                if (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i))) throw invalid();
            } else if (Character.isLowSurrogate(current)) throw invalid();
        }
        return value;
    }
    /** raw body最多读取limit+1，query与BOM不是可忽略的兼容输入。 */
    private static JsonNode object(HttpServletRequest request, Set<String> fields) throws IOException {
        if (request.getQueryString() != null) throw invalid();
        byte[] bytes = request.getInputStream().readNBytes(MAX_BYTES + 1);
        if (bytes.length > MAX_BYTES) throw new BusinessException(CommonErrorCode.PAYLOAD_TOO_LARGE);
        try {
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            if (text.isEmpty() || text.startsWith("\ufeff")) throw invalid();
            JsonNode node = JSON.readTree(text);
            if (node == null || !node.isObject() || !node.propertyNames().equals(fields)) throw invalid();
            return node;
        } catch (BusinessException failure) { throw failure; }
        catch (Exception failure) { throw invalid(); }
    }
    /** 词法拒绝保留通用参数错误，不附输入详情。 */
    private static BusinessException invalid() { return new BusinessException(CommonErrorCode.INVALID_PARAMETER); }
}
