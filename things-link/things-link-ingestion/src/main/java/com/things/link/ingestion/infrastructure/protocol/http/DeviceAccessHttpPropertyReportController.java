package com.things.link.ingestion.infrastructure.protocol.http;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import com.things.link.ingestion.application.access.DeviceAccessAccessPayloads;
import com.things.link.ingestion.application.access.DeviceAccessHandoffUnavailableException;
import com.things.link.ingestion.application.access.DeviceAccessIdempotencyConflictException;
import com.things.link.ingestion.application.access.DeviceAccessRateLimitedException;
import com.things.link.ingestion.application.access.DeviceAccessUplinkIngressService;
import com.things.link.ingestion.application.access.DeviceAccessUplinkMessage;
import com.things.link.ingestion.application.InvalidUplinkMessageException;
import com.things.link.shared.message.AuthenticatedDeviceIdentity;
import com.things.link.shared.message.TransportProtocol;
import com.things.link.support.trace.TraceContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 设备面属性上报端点（接入合同 §3.2 的 {@code POST /device-access/v1/property/report}）。
 *
 * <p>端点本身只做四件事：取已认证身份、检查媒体类型与体积、把原始字节交给受理用例、把受理结果映射成冻结合同的
 * 响应。业务校验（载荷契约、幂等、预算、总线接管）全部在受理用例里，控制器不重复判断，也不解析设备 JSON。</p>
 *
 * <p>统一 HTTP 目录按设备接入功能登记本端点；认证、原始载荷和错误体仍沿设备协议合同。
 * 文档登记不把设备身份转换为管理面 JWT，也不表示生产开放接口文档。</p>
 *
 * <p>错误映射逐行对照 §3.5：载荷非法 400、体积超限 413、媒体类型不符 415、同键异载荷 409、预算 429（带
 * {@code Retry-After}）、总线未接管 503。响应体只含稳定错误码与一句说明，绝不回显载荷或凭据。</p>
 */
@ConditionalOnProperty(name = "things-link.deployment.role",
        havingValue = "device-access", matchIfMissing = true)
@io.swagger.v3.oas.annotations.tags.Tag(name = "设备 HTTP 上报", description = "设备身份认证后的持久受理与幂等上报")
@RestController
public class DeviceAccessHttpPropertyReportController {

    /** 属性上报路径，与合同 §3.2 逐字一致。 */
    public static final String PROPERTY_REPORT_PATH = "/device-access/v1/property/report";

    /** 请求体上限：64 KiB（接入合同 §6）；超出即 413，不先落盘也不截断。 */
    private final int maxBodyBytes;

    /** 新协议上行受理用例。 */
    private final DeviceAccessUplinkIngressService ingress;

    /**
     * @param maxBodyBytes 请求体上限（字节）
     * @param ingress 新协议上行受理用例
     */
    public DeviceAccessHttpPropertyReportController(
            @Value("${things-link.access.http.max-body-bytes:65536}") int maxBodyBytes,
            DeviceAccessUplinkIngressService ingress) {
        this.maxBodyBytes = maxBodyBytes;
        this.ingress = ingress;
    }

    /**
     * 受理一次属性上报。
     *
     * @param request 当前请求
     * @return 202 与受理结果；失败由下方异常处理器映射
     * @throws IOException 读取请求体失败
     */
    @io.swagger.v3.oas.annotations.Operation(operationId = "reportDeviceAccessProperties", summary = "受理一次属性上报", description = "受理一次属性上报。")
    @PostMapping(PROPERTY_REPORT_PATH)
    public ResponseEntity<Map<String, Object>> report(HttpServletRequest request) throws IOException {
        Object attribute = request.getAttribute(DeviceAccessHttpAuthenticationFilter.IDENTITY_ATTRIBUTE);
        if (!(attribute instanceof AuthenticatedDeviceIdentity identity)) {
            // 过滤器缺失意味着装配错误，但绝不能因此放行未认证请求。
            return error(HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED", "缺少设备认证身份");
        }
        if (!DeviceAccessHttpSupport.isJson(request.getContentType())) {
            return error(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_CONTENT_TYPE",
                    "请求体必须是 application/json");
        }
        byte[] body;
        try {
            body = DeviceAccessHttpSupport.readBounded(request, maxBodyBytes);
        } catch (DeviceAccessHttpSupport.BodyTooLargeException exception) {
            return error(HttpStatus.PAYLOAD_TOO_LARGE, "PAYLOAD_TOO_LARGE", "请求体超过 64 KiB");
        }
        if (body.length == 0) {
            return error(HttpStatus.BAD_REQUEST, "PAYLOAD_INVALID", "请求体不能为空");
        }

        DeviceAccessUplinkIngressService.DeviceAccessUplinkAcceptance acceptance = ingress.accept(
                new DeviceAccessUplinkMessage(identity.tenantId(), identity.projectId(), identity.deviceId(),
                        TransportProtocol.HTTP, body, Instant.now(),
                        TraceContext.resolve(TraceContext.current()), identity));
        // 响应体形状与 CoAP 共用一份实现：跨协议同源不能只靠"两边都写一遍"。
        return ResponseEntity.accepted().contentType(MediaType.APPLICATION_JSON)
                .body(DeviceAccessAccessPayloads.acceptedBody(acceptance.messageId(), acceptance.receivedAt()));
    }

    /**
     * 载荷违反冻结契约：重放不会成功，返回 400。
     *
     * @param exception 受理用例抛出的协议错误
     * @return 400 响应
     */
    @ExceptionHandler(InvalidUplinkMessageException.class)
    ResponseEntity<Map<String, Object>> invalidPayload(InvalidUplinkMessageException exception) {
        return error(HttpStatus.BAD_REQUEST, "PAYLOAD_INVALID", "设备报文不符合冻结契约");
    }

    /**
     * 同键异载荷：返回 409 并保持首次事实不变。
     *
     * @param exception 冲突异常
     * @return 409 响应
     */
    @ExceptionHandler(DeviceAccessIdempotencyConflictException.class)
    ResponseEntity<Map<String, Object>> idempotencyConflict(DeviceAccessIdempotencyConflictException exception) {
        return error(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT", "同一 messageId 的载荷与首次受理不一致");
    }

    /**
     * 超预算：返回 429 与 Retry-After。
     *
     * @param exception 预算拒绝异常
     * @return 429 响应
     */
    @ExceptionHandler(DeviceAccessRateLimitedException.class)
    ResponseEntity<Map<String, Object>> rateLimited(DeviceAccessRateLimitedException exception) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", String.valueOf(Math.max(1L, Duration.ofSeconds(1).toSeconds())))
                .contentType(MediaType.APPLICATION_JSON)
                .body(body("RATE_LIMITED", "设备业务请求超出接入预算"));
    }

    /**
     * 总线未接管：绝不返回受理，返回 503 让设备退避重试。
     *
     * @param exception 交接不可用异常
     * @return 503 响应
     */
    @ExceptionHandler(DeviceAccessHandoffUnavailableException.class)
    ResponseEntity<Map<String, Object>> handoffUnavailable(DeviceAccessHandoffUnavailableException exception) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header("Retry-After", "1")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body("HANDOFF_UNAVAILABLE", "消息总线未确认接管，请稍后重试"));
    }

    /** 组装统一错误体；与命令端点共用同一实现。 */
    private static ResponseEntity<Map<String, Object>> error(HttpStatus status, String errorCode, String message) {
        return DeviceAccessHttpSupport.error(status, errorCode, message);
    }

    /** 错误体只有错误码与说明。 */
    private static Map<String, Object> body(String errorCode, String message) {
        return DeviceAccessHttpSupport.body(errorCode, message);
    }

}
