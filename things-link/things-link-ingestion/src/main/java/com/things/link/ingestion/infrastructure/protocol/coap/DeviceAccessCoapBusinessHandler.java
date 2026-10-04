package com.things.link.ingestion.infrastructure.protocol.coap;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import com.things.link.ingestion.application.InvalidUplinkMessageException;
import com.things.link.ingestion.application.access.DeviceAccessAccessPayloads;
import com.things.link.ingestion.application.access.DeviceAccessHandoffUnavailableException;
import com.things.link.ingestion.application.access.DeviceAccessIdempotencyConflictException;
import com.things.link.ingestion.application.access.DeviceAccessRateLimitedException;
import com.things.link.ingestion.application.access.DeviceAccessUplinkIngressService;
import com.things.link.ingestion.application.access.DeviceAccessUplinkMessage;
import com.things.link.ingestion.application.access.coap.DeviceAccessCoapAccessService;
import com.things.link.ingestion.application.access.coap.DeviceAccessCoapResourceHandler;
import com.things.link.ingestion.application.access.coap.DeviceAccessCoapResponse;
import com.things.link.ingestion.application.access.coap.DeviceAccessCoapResponse.CoapCode;
import com.things.link.shared.message.AuthenticatedDeviceIdentity;
import com.things.link.shared.message.TransportProtocol;
import com.things.link.support.trace.TraceContext;
import com.things.link.telemetry.application.DeviceCommandAccessReplyPort;
import com.things.link.telemetry.application.DeviceCommandClaimPort;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * CoAP 三个冻结资源的业务实现（接入合同 §3.1／§5.2／§5.4）。
 *
 * <p>与 HTTP、TCP 平面调用**同一批用例**：属性上报走 {@link DeviceAccessUplinkIngressService}，领取走
 * {@link DeviceCommandClaimPort}，回复走 {@link DeviceCommandAccessReplyPort}；报文形状与校验复用
 * {@link DeviceAccessAccessPayloads}。因此三协议之间不存在"某个协议的受理语义略不同"的空间——差异只在响应码。</p>
 */
@ConditionalOnProperty(name = "things-link.deployment.role",
        havingValue = "device-access", matchIfMissing = true)
@Component
public class DeviceAccessCoapBusinessHandler implements DeviceAccessCoapResourceHandler {

    /** 与 HTTP／TCP 共用的上行受理用例。 */
    private final DeviceAccessUplinkIngressService uplinkIngress;

    /** 协议无关的领取端口。 */
    private final DeviceCommandClaimPort claimPort;

    /** 统一业务回复入口。 */
    private final DeviceCommandAccessReplyPort replyPort;

    /** 统一 JSON 映射器。 */
    private final ObjectMapper objectMapper;

    /**
     * @param uplinkIngress 上行受理用例
     * @param claimPort 命令领取端口
     * @param replyPort 业务回复入口
     * @param objectMapper 统一 JSON 映射器
     */
    public DeviceAccessCoapBusinessHandler(DeviceAccessUplinkIngressService uplinkIngress,
                                           DeviceCommandClaimPort claimPort,
                                           DeviceCommandAccessReplyPort replyPort,
                                           ObjectMapper objectMapper) {
        this.uplinkIngress = uplinkIngress;
        this.claimPort = claimPort;
        this.replyPort = replyPort;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<DeviceAccessCoapResponse> handle(AuthenticatedDeviceIdentity identity, String resourcePath,
                                                     byte[] body) {
        return switch (resourcePath) {
            case DeviceAccessCoapAccessService.PROPERTY_REPORT -> Optional.of(report(identity, body));
            case DeviceAccessCoapAccessService.COMMAND_CLAIM -> Optional.of(claim(identity, body));
            case DeviceAccessCoapAccessService.COMMAND_REPLY -> Optional.of(reply(identity, body));
            default -> Optional.empty();
        };
    }

    /** 属性上报：成功 2.04 与受理体；失败按 §3.5 的 CoAP 列映射。 */
    private DeviceAccessCoapResponse report(AuthenticatedDeviceIdentity identity, byte[] body) {
        try {
            DeviceAccessUplinkIngressService.DeviceAccessUplinkAcceptance acceptance = uplinkIngress.accept(
                    new DeviceAccessUplinkMessage(identity.tenantId(), identity.projectId(), identity.deviceId(),
                            TransportProtocol.COAP, body, Instant.now(),
                            TraceContext.resolve(TraceContext.current()), identity));
            return json(CoapCode.CHANGED,
                    DeviceAccessAccessPayloads.acceptedBody(acceptance.messageId(), acceptance.receivedAt()));
        } catch (InvalidUplinkMessageException exception) {
            return DeviceAccessCoapResponse.of(CoapCode.BAD_REQUEST);
        } catch (DeviceAccessIdempotencyConflictException exception) {
            return DeviceAccessCoapResponse.of(CoapCode.IDEMPOTENCY_CONFLICT);
        } catch (DeviceAccessRateLimitedException exception) {
            return DeviceAccessCoapResponse.of(CoapCode.TOO_MANY_REQUESTS);
        } catch (DeviceAccessHandoffUnavailableException exception) {
            // 总线未接管就不能算受理：5.03 让设备退避重试，而不是静默当作成功。
            return DeviceAccessCoapResponse.of(CoapCode.SERVICE_UNAVAILABLE);
        }
    }

    /** 命令领取：有命令 2.05＋命令体；无命令 2.04＋空体（不把「没有」编码成业务数据）。 */
    private DeviceAccessCoapResponse claim(AuthenticatedDeviceIdentity identity, byte[] body) {
        DeviceAccessAccessPayloads.ClaimRequest request;
        try {
            request = DeviceAccessAccessPayloads.parseClaim(body, objectMapper);
        } catch (RuntimeException exception) {
            return DeviceAccessCoapResponse.of(CoapCode.BAD_REQUEST);
        }
        Duration lease = request.leaseSeconds() == null ? DeviceCommandClaimPort.DEFAULT_LEASE
                : Duration.ofSeconds(request.leaseSeconds());
        List<DeviceCommandClaimPort.Claimed> claimed = claimPort.claim(new DeviceCommandClaimPort.ClaimRequest(
                identity.tenantId(), identity.projectId(), identity.deviceId(), lease,
                request.limit() == null ? DeviceCommandClaimPort.DEFAULT_LIMIT : request.limit()));
        if (claimed.isEmpty()) {
            return DeviceAccessCoapResponse.of(CoapCode.CHANGED);
        }
        return json(CoapCode.CONTENT, DeviceAccessAccessPayloads.claimBody(claimed, lease));
    }

    /** 业务回复：2.04；找不到命令 4.04；同键异结果 4.09。 */
    private DeviceAccessCoapResponse reply(AuthenticatedDeviceIdentity identity, byte[] body) {
        DeviceAccessAccessPayloads.ReplyRequest parsed;
        try {
            parsed = objectMapper.readValue(body, DeviceAccessAccessPayloads.ReplyRequest.class);
        } catch (RuntimeException exception) {
            return DeviceAccessCoapResponse.of(CoapCode.BAD_REQUEST);
        }
        DeviceCommandAccessReplyPort.Reply reply;
        try {
            reply = DeviceAccessAccessPayloads.createReply(identity, parsed, Instant.now(),
                    TraceContext.resolve(TraceContext.current()));
        } catch (IllegalArgumentException | NullPointerException exception) {
            return DeviceAccessCoapResponse.of(CoapCode.BAD_REQUEST);
        }
        DeviceCommandAccessReplyPort.Outcome outcome = replyPort.apply(reply);
        return switch (outcome) {
            case NOT_FOUND -> DeviceAccessCoapResponse.of(CoapCode.NOT_FOUND);
            case CONFLICT -> DeviceAccessCoapResponse.of(CoapCode.IDEMPOTENCY_CONFLICT);
            case APPLIED, DUPLICATE -> json(CoapCode.CHANGED, DeviceAccessAccessPayloads.replyAcceptedBody(
                    parsed.commandId(), parsed.messageId(), reply.receivedAt()));
        };
    }

    /** 把一个响应体序列化成 JSON 载荷。 */
    private DeviceAccessCoapResponse json(CoapCode code, Object payload) {
        return new DeviceAccessCoapResponse(code, objectMapper.writeValueAsString(payload));
    }
}
