package com.things.link.shared.page;

import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.CommonErrorCode;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 游标的编解码。
 *
 * <p>游标对客户端<b>必须是不透明的</b>：编码成 Base64 不是为了保密（它可以被轻易
 * 解开），而是为了传达「这是内部结构，别解析」这个信号。一旦客户端开始解析游标，
 * 服务端就再也改不动它的内部格式了。
 *
 * <p>用 URL-safe 且无填充的 Base64：游标出现在查询参数里，标准 Base64 的
 * {@code +} {@code /} {@code =} 都需要额外转义，容易在各层代理与客户端之间出错。
 */
public final class Cursor {

    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private Cursor() {
        // 工具类，不允许实例化
    }

    /**
     * 把游标载荷编码为不透明字符串。
     *
     * @param payload 游标载荷，通常是排序键的组合，例如 {@code "2026-08-01T00:00:00Z|<uuid>"}
     * @return URL 安全的不透明游标
     */
    public static String encode(String payload) {
        return ENCODER.encodeToString(payload.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 解码客户端回传的游标。
     *
     * <p>游标来自客户端，属于不可信输入 —— 可能被手工构造、被截断、或者是上一个
     * 版本遗留的格式。解不开时抛业务异常返回 400，而不是让 IllegalArgumentException
     * 冒泡成 500：这是客户端的问题，不是服务端故障。
     *
     * @param cursor 客户端回传的游标
     * @return 原始载荷
     * @throws BusinessException 游标无法解码时
     */
    public static String decode(String cursor) {
        try {
            return new String(DECODER.decode(cursor), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(CommonErrorCode.INVALID_PARAMETER, "分页游标无效");
        }
    }

}
