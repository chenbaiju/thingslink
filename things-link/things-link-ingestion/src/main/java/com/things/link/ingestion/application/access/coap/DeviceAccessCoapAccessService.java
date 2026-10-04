package com.things.link.ingestion.application.access.coap;

import com.things.link.ingestion.application.access.DefaultDeviceAccessDeviceAuthenticator;
import com.things.link.ingestion.application.access.DeviceAccessAuthBudget;
import com.things.link.ingestion.application.access.DeviceAccessAuthFailureReason;
import com.things.link.ingestion.application.access.DeviceAccessBusinessBudget;
import com.things.link.ingestion.application.access.DeviceAccessDeviceAuthenticationException;
import com.things.link.ingestion.application.access.DeviceAccessRateLimitedException;
import com.things.link.shared.message.AuthenticatedDeviceIdentity;
import com.things.link.shared.message.TransportProtocol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import com.things.link.support.tenant.DatabaseWorkload;
import com.things.link.support.tenant.DatabaseWorkloadContext;

/**
 * 设备面 CoAP 的访问决策层（接入合同 §3.4／§3.5），与 DTLS 传输解耦。
 *
 * <p>决策顺序固定，且与 HTTP／TCP 平面**同源**：先判资源与内容语义（4.04／4.15／4.13），再判凭据存在性（4.01），
 * 然后扣认证面预算（4.29）、走唯一认证入口（4.01／4.03），最后扣业务预算（4.29）并交给业务端口。
 * 认证与两份预算都复用既有实现，因此"换一个协议换一份额度"这类隐蔽绕过在这里同样不成立。</p>
 *
 * <p>与 TCP 的教训一致：认证面预算必须从第一天就接上，否则 CoAP 会成为唯一未被限流的凭据入口。</p>
 */
@Service
public class DeviceAccessCoapAccessService {

    /** 只记录资源路径与拒绝原因，凭据与载荷永不进日志。 */
    private static final Logger LOGGER = LoggerFactory.getLogger(DeviceAccessCoapAccessService.class);

    /** 属性上报资源。 */
    public static final String PROPERTY_REPORT = "/device-access/v1/property/report";

    /** 命令领取资源。 */
    public static final String COMMAND_CLAIM = "/device-access/v1/command/claim";

    /** 命令回复资源。 */
    public static final String COMMAND_REPLY = "/device-access/v1/command/reply";

    /** 冻结的三个资源；其余路径一律 4.04。 */
    public static final List<String> RESOURCES = List.of(PROPERTY_REPORT, COMMAND_CLAIM, COMMAND_REPLY);

    /** CoAP Content-Format：{@code application/json}（RFC 7252 注册表号 50）。 */
    public static final int CONTENT_FORMAT_APPLICATION_JSON = 50;

    /** 请求体上限 64 KiB（§3.4／§6）。 */
    public static final int MAX_BODY_BYTES = 64 * 1024;

    /** 唯一设备认证入口。 */
    private final DefaultDeviceAccessDeviceAuthenticator authenticator;

    /** 认证面预算（每 IP 窗口＋每 projectKey/deviceKey 失败窗口与退避）。 */
    private final DeviceAccessAuthBudget authBudget;

    /** 业务预算（跨协议共享）。 */
    private final DeviceAccessBusinessBudget businessBudget;

    /** 已接线的业务处理器；AX-4b／4c 提供实现，未提供时资源一律 5.01。 */
    private final ObjectProvider<DeviceAccessCoapResourceHandler> handlers;

    /**
     * @param authenticator 唯一设备认证入口
     * @param authBudget 认证面预算
     * @param businessBudget 业务预算
     * @param handlers 业务处理器（可缺失）
     */
    public DeviceAccessCoapAccessService(DefaultDeviceAccessDeviceAuthenticator authenticator,
                                         DeviceAccessAuthBudget authBudget,
                                         DeviceAccessBusinessBudget businessBudget,
                                         ObjectProvider<DeviceAccessCoapResourceHandler> handlers) {
        this.authenticator = authenticator;
        this.authBudget = authBudget;
        this.businessBudget = businessBudget;
        this.handlers = handlers;
    }

    /**
     * 处理一次 CoAP 请求。
     *
     * @param request 传输层解析出的请求视图
     * @return 冻结语义的响应
     */
    public DeviceAccessCoapResponse handle(DeviceAccessCoapRequest request) {
        // 设备面入口按 ADR 0045 走数据池：Californium 线程没有过滤器链，这里就是入口；范围必须在任何业务事务之前建立，
        // 否则并发领取/回复会占满控制池并把管理面与新协议一起拖慢（D-196，AX-6d 实测）。
        try (DatabaseWorkloadContext.Scope ignored = DatabaseWorkloadContext.enter(DatabaseWorkload.DATA)) {
            return handleInDataPool(request);
        }
    }

    /**
     * 在已建立的数据面范围内处理一次 CoAP 请求。
     *
     * @param request 传输层解析出的请求视图
     * @return 冻结语义的响应
     */
    private DeviceAccessCoapResponse handleInDataPool(DeviceAccessCoapRequest request) {
        if (request == null || request.resourcePath() == null || !RESOURCES.contains(request.resourcePath())) {
            return DeviceAccessCoapResponse.of(DeviceAccessCoapResponse.CoapCode.NOT_FOUND);
        }
        byte[] body = request.body() == null ? new byte[0] : request.body();
        if (body.length > MAX_BODY_BYTES) {
            return DeviceAccessCoapResponse.of(DeviceAccessCoapResponse.CoapCode.REQUEST_ENTITY_TOO_LARGE);
        }
        // 有载荷就必须声明 application/json：缺声明或声明了别的格式都不能当作本协议报文。
        if (body.length > 0 && (request.contentFormat() == null
                || request.contentFormat() != CONTENT_FORMAT_APPLICATION_JSON)) {
            return DeviceAccessCoapResponse.of(DeviceAccessCoapResponse.CoapCode.UNSUPPORTED_CONTENT_FORMAT);
        }
        String deviceKeyOption = request.deviceKeyOption();
        String credential = request.credentialOption();
        if (deviceKeyOption == null || deviceKeyOption.isBlank() || credential == null || credential.isBlank()) {
            // 缺少凭据属 AUTH_REQUIRED：与 HTTP 口径一致，不扣预算（没有可归属的身份）。
            return DeviceAccessCoapResponse.of(DeviceAccessCoapResponse.CoapCode.UNAUTHORIZED);
        }
        int separator = deviceKeyOption.indexOf('/');
        if (separator <= 0 || separator == deviceKeyOption.length() - 1) {
            return DeviceAccessCoapResponse.of(DeviceAccessCoapResponse.CoapCode.UNAUTHORIZED);
        }
        String projectKey = deviceKeyOption.substring(0, separator);
        String deviceKey = deviceKeyOption.substring(separator + 1);
        switch (authBudget.check(request.clientIp(), projectKey, deviceKey)) {
            case RATE_LIMITED, BACKOFF -> {
                return DeviceAccessCoapResponse.of(DeviceAccessCoapResponse.CoapCode.TOO_MANY_REQUESTS);
            }
            case ALLOW -> {
                // 继续校验凭据。
            }
        }

        AuthenticatedDeviceIdentity identity;
        try {
            identity = authenticator.authenticate(TransportProtocol.COAP, projectKey, deviceKey, credential);
            authBudget.recordSuccess(projectKey, deviceKey);
        } catch (DeviceAccessDeviceAuthenticationException exception) {
            authBudget.recordFailure(projectKey, deviceKey);
            return DeviceAccessCoapResponse.of(coapCode(exception.reason()));
        } catch (DeviceAccessRateLimitedException exception) {
            // 认证入口自身也会扣业务预算；此处超限即 4.29，不泄露更多信息。
            return DeviceAccessCoapResponse.of(DeviceAccessCoapResponse.CoapCode.TOO_MANY_REQUESTS);
        }

        try {
            businessBudget.charge(identity.tenantId(), identity.projectId(), identity.deviceId());
        } catch (DeviceAccessRateLimitedException exception) {
            return DeviceAccessCoapResponse.of(DeviceAccessCoapResponse.CoapCode.TOO_MANY_REQUESTS);
        }

        for (DeviceAccessCoapResourceHandler handler : handlers) {
            Optional<DeviceAccessCoapResponse> response = handler.handle(identity, request.resourcePath(), body);
            if (response.isPresent()) {
                return response.get();
            }
        }
        // 资源已冻结但业务尚未接线：回 5.01 而不是 2.04，避免把"还没做"报成"已受理"。
        LOGGER.debug("设备面 CoAP 资源尚未接线 path={}", request.resourcePath());
        return DeviceAccessCoapResponse.of(DeviceAccessCoapResponse.CoapCode.NOT_IMPLEMENTED);
    }

    /** 认证失败原因映射到 §3.5 的 CoAP 响应码。 */
    private static DeviceAccessCoapResponse.CoapCode coapCode(DeviceAccessAuthFailureReason reason) {
        return switch (reason) {
            case AUTH_FAILED -> DeviceAccessCoapResponse.CoapCode.UNAUTHORIZED;
            // 平面未开通与项目不可写都是"身份可信但当前不允许"，是 4.03 而不是 4.01。
            case PLANE_NOT_ENABLED, PROJECT_UNAVAILABLE -> DeviceAccessCoapResponse.CoapCode.FORBIDDEN;
        };
    }
}
