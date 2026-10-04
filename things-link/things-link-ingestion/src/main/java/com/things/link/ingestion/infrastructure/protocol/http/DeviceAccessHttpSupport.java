package com.things.link.ingestion.infrastructure.protocol.http;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 设备面 HTTP 的请求读取与错误响应工具：两个端点必须给出完全一致的体积、媒体类型与错误体。
 *
 * <p>抽成一处不是为了少写几行，而是为了让「64 KiB 上限」「只认 application/json」「错误体只有错误码与说明」
 * 这三条冻结合同只有一份实现——两处各写一份，早晚会在某个端点上漏掉其中一条，而漏掉的那条通常正是安全边界。</p>
 */
final class DeviceAccessHttpSupport {

    /** 工具类不允许实例化。 */
    private DeviceAccessHttpSupport() {
    }

    /**
     * 判断媒体类型是否为 JSON；带 charset 参数仍然接受。
     *
     * @param contentType 请求的 Content-Type
     * @return 是否为 application/json
     */
    static boolean isJson(String contentType) {
        if (contentType == null) {
            return false;
        }
        int separator = contentType.indexOf(';');
        String mediaType = (separator < 0 ? contentType : contentType.substring(0, separator)).trim();
        return MediaType.APPLICATION_JSON_VALUE.equalsIgnoreCase(mediaType);
    }

    /**
     * 按上限读取请求体。
     *
     * <p>只看 {@code Content-Length} 不够：分块传输可以不带长度，因此必须同时限制实际读取字节数。</p>
     *
     * @param request 当前请求
     * @param maxBodyBytes 上限
     * @return 请求体字节
     * @throws IOException 读取失败
     * @throws BodyTooLargeException 超出上限
     */
    static byte[] readBounded(HttpServletRequest request, int maxBodyBytes) throws IOException {
        long declared = request.getContentLengthLong();
        if (declared > maxBodyBytes) {
            throw new BodyTooLargeException();
        }
        InputStream input = request.getInputStream();
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(declared > 0 ? (int) declared : 1024);
        byte[] chunk = new byte[8192];
        int read;
        while ((read = input.read(chunk)) != -1) {
            if (buffer.size() + read > maxBodyBytes) {
                throw new BodyTooLargeException();
            }
            buffer.write(chunk, 0, read);
        }
        return buffer.toByteArray();
    }

    /**
     * 组装统一错误响应。
     *
     * @param status HTTP 状态码
     * @param errorCode 冻结错误码
     * @param message 面向设备的说明
     * @return 错误响应
     */
    static ResponseEntity<Map<String, Object>> error(HttpStatus status, String errorCode, String message) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON)
                .body(body(errorCode, message));
    }

    /**
     * 组装错误体。
     *
     * @param errorCode 冻结错误码
     * @param message 面向设备的说明
     * @return 错误体
     */
    static Map<String, Object> body(String errorCode, String message) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("errorCode", errorCode);
        payload.put("message", message);
        return payload;
    }

    /** 体积超限的内部信号，只在设备面内部流转。 */
    static final class BodyTooLargeException extends RuntimeException {
    }
}
