package com.things.link.ingestion.infrastructure.protocol.http;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import com.things.link.ingestion.application.access.DeviceAccessAccessPayloads;
import com.things.link.ingestion.application.access.DeviceAccessDeviceAuthenticationException;
import com.things.link.shared.message.AuthenticatedDeviceIdentity;
import com.things.link.support.trace.TraceContext;
import com.things.link.telemetry.application.DeviceCommandAccessReplyPort;
import com.things.link.telemetry.application.DeviceCommandClaimPort;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 设备面命令领取与业务回复端点（接入合同 §3.2 的 {@code command/claim} 与 {@code command/reply}）。
 *
 * <p>两个端点都是「薄适配」：领取把请求换算成租约与条数并交给既有领取用例，回复把报文交给统一回复入口。
 * 命令终态、租约、重投与幂等全部由 telemetry 域的命令事实决定，接入面不新增第二套命令语义（§5.1）。</p>
 *
 * <p>与属性上报端点的差别只在语义：领取成功是 200（有命令）或 204（无命令，设备按 {@code pollAfterMillis}
 * 再轮询），回复成功与上报同为 202——回复本身是设备提交的**业务结果**，命令终态由命令事实追踪，不由响应码承诺。</p>
 */
@Hidden
@ConditionalOnProperty(name = "things-link.deployment.role",
        havingValue = "device-access", matchIfMissing = true)
@RestController
public class DeviceAccessHttpCommandController {

    /** 命令领取路径。 */
    public static final String COMMAND_CLAIM_PATH = "/device-access/v1/command/claim";

    /** 命令回复路径。 */
    public static final String COMMAND_REPLY_PATH = "/device-access/v1/command/reply";

    /** 领取请求体上限：与上报同级，领取请求本身很短。 */
    private final int maxBodyBytes;

    /** 命令领取用例。 */
    private final DeviceCommandClaimPort claimPort;

    /** 统一业务回复入口。 */
    private final DeviceCommandAccessReplyPort replyPort;

    /** 统一 JSON 映射器。 */
    private final ObjectMapper objectMapper;

    /**
     * @param maxBodyBytes 请求体上限（字节）
     * @param claimPort 命令领取用例
     * @param replyPort 统一业务回复入口
     * @param objectMapper 统一 JSON 映射器
     */
    public DeviceAccessHttpCommandController(
            @Value("${things-link.access.http.max-body-bytes:65536}") int maxBodyBytes,
            DeviceCommandClaimPort claimPort,
            DeviceCommandAccessReplyPort replyPort,
            ObjectMapper objectMapper) {
        this.maxBodyBytes = maxBodyBytes;
        this.claimPort = claimPort;
        this.replyPort = replyPort;
        this.objectMapper = objectMapper;
    }

    /**
     * 领取待执行命令。
     *
     * @param request 当前请求
     * @return 200 与命令列表；没有可领取命令时 204
     * @throws IOException 读取请求体失败
     */
    @PostMapping(COMMAND_CLAIM_PATH)
    public ResponseEntity<Map<String, Object>> claim(HttpServletRequest request) throws IOException {
        AuthenticatedDeviceIdentity identity = identity(request);
        if (identity == null) {
            return error(HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED", "缺少设备认证身份");
        }
        DeviceAccessAccessPayloads.ClaimRequest claimRequest;
        try {
            claimRequest = DeviceAccessAccessPayloads.parseClaim(readBounded(request), objectMapper);
        } catch (DeviceAccessHttpSupport.BodyTooLargeException exception) {
            return error(HttpStatus.PAYLOAD_TOO_LARGE, "PAYLOAD_TOO_LARGE", "请求体超过 64 KiB");
        } catch (JacksonException | IllegalArgumentException exception) {
            return error(HttpStatus.BAD_REQUEST, "PAYLOAD_INVALID", "领取请求不符合冻结契约");
        }

        Duration lease = claimRequest.leaseSeconds() == null ? DeviceCommandClaimPort.DEFAULT_LEASE
                : Duration.ofSeconds(claimRequest.leaseSeconds());
        List<DeviceCommandClaimPort.Claimed> claimed = claimPort.claim(new DeviceCommandClaimPort.ClaimRequest(
                identity.tenantId(), identity.projectId(), identity.deviceId(), lease,
                claimRequest.limit() == null ? DeviceCommandClaimPort.DEFAULT_LIMIT : claimRequest.limit()));
        if (claimed.isEmpty()) {
            // 无命令时不返回空列表正文：204 让设备按 pollAfterMillis 再问，避免把「没有」编码成业务数据。
            return ResponseEntity.noContent().header("Retry-After",
                    String.valueOf(Math.max(1L, DeviceCommandClaimPort.DEFAULT_LEASE.toSeconds()))).build();
        }
        // 领取体形状与 CoAP 共用一份实现（attempt 只是诊断序号，设备去重必须用 commandId，§5.2）。
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON)
                .body(DeviceAccessAccessPayloads.claimBody(claimed, lease));
    }

    /**
     * 提交一次业务回复。
     *
     * <p>请求体同样按上限自读而不是交给消息转换器：交给框架会把超大请求先完整缓冲再判断，等于把上限
     * 挪到内存之后。</p>
     *
     * @param request 当前请求
     * @return 202 受理结果；找不到命令 404，同键异结果 409
     * @throws IOException 读取请求体失败
     */
    @PostMapping(COMMAND_REPLY_PATH)
    public ResponseEntity<Map<String, Object>> reply(HttpServletRequest request) throws IOException {
        AuthenticatedDeviceIdentity identity = identity(request);
        if (identity == null) {
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
        DeviceAccessAccessPayloads.ReplyRequest parsed;
        try {
            parsed = objectMapper.readValue(body, DeviceAccessAccessPayloads.ReplyRequest.class);
        } catch (JacksonException | IllegalArgumentException exception) {
            return error(HttpStatus.BAD_REQUEST, "PAYLOAD_INVALID", "命令回复不符合冻结契约");
        }
        DeviceCommandAccessReplyPort.Reply reply;
        try {
            // 归属一律取认证身份；装配与 CoAP 共用一份实现，避免两个平面校验出不同结果。
            reply = DeviceAccessAccessPayloads.createReply(identity, parsed, Instant.now(),
                    TraceContext.resolve(TraceContext.current()));
        } catch (IllegalArgumentException | NullPointerException exception) {
            // 标识形状、长度与状态取值都在统一入口校验，这里只把校验失败映射成 400。
            return error(HttpStatus.BAD_REQUEST, "PAYLOAD_INVALID", "命令回复不符合冻结契约");
        }

        DeviceCommandAccessReplyPort.Outcome outcome = replyPort.apply(reply);
        if (outcome == DeviceCommandAccessReplyPort.Outcome.NOT_FOUND) {
            return error(HttpStatus.NOT_FOUND, "COMMAND_NOT_FOUND", "命令不存在或不属于该设备");
        }
        if (outcome == DeviceCommandAccessReplyPort.Outcome.CONFLICT) {
            return error(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT", "同一 messageId 的回复结果与首次不一致");
        }
        // 与上报一致：受理只代表平台已接管这次提交，命令终态由命令事实追踪。
        return ResponseEntity.accepted().contentType(MediaType.APPLICATION_JSON).body(
                DeviceAccessAccessPayloads.replyAcceptedBody(parsed.commandId(), parsed.messageId(),
                        reply.receivedAt()));
    }

    /** 领取出错时不吞异常：认证与预算失败由过滤器处理，这里只兜住装配问题。 */
    @ExceptionHandler(DeviceAccessDeviceAuthenticationException.class)
    ResponseEntity<Map<String, Object>> authenticationFailure(DeviceAccessDeviceAuthenticationException exception) {
        return error(HttpStatus.valueOf(exception.reason().httpStatus()), exception.reason().errorCode(),
                exception.reason().message());
    }

    /** 按 64 KiB 上限读取请求体；空体返回空数组（领取允许空体）。 */
    private byte[] readBounded(HttpServletRequest request) throws IOException {
        if (request.getContentLengthLong() == 0) {
            return new byte[0];
        }
        return DeviceAccessHttpSupport.readBounded(request, maxBodyBytes);
    }

    /** 取过滤器写入的认证身份。 */
    private static AuthenticatedDeviceIdentity identity(HttpServletRequest request) {
        Object attribute = request.getAttribute(DeviceAccessHttpAuthenticationFilter.IDENTITY_ATTRIBUTE);
        return attribute instanceof AuthenticatedDeviceIdentity identity ? identity : null;
    }

    /** 组装统一错误体。 */
    private static ResponseEntity<Map<String, Object>> error(HttpStatus status, String errorCode, String message) {
        return DeviceAccessHttpSupport.error(status, errorCode, message);
    }

}
