package com.things.link.ingestion.application.access;

import com.things.link.device.application.DeviceAccessSessionPort;
import com.things.link.device.application.DeviceAuthenticationPort;
import com.things.link.project.application.EffectiveQuotaPolicy;
import com.things.link.project.application.EffectiveQuotaPolicyProvider;
import com.things.link.project.application.ProjectAccessPolicy;
import com.things.link.project.application.ProjectLifecycleAccessService;
import com.things.link.project.application.QuotaRuntimeMetrics;
import com.things.link.shared.message.AuthenticatedDeviceIdentity;
import com.things.link.shared.message.TransportProtocol;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.Optional;

/**
 * 设备认证与接入平面资格的默认实现：一处完成凭据校验、平面资格与活动记录。
 *
 * <p>拒绝码是冻结语义：凭据不对一律 {@code AUTH_FAILED}（不区分项目、设备与密钥哪一项不对，避免把公开端点
 * 变成枚举器）；凭据正确但设备未开通该平面或配置被关闭一律按 {@code DEVICE_DISABLED} 拒绝——设备在这个平面上
 * 确实不可用，而不是「密钥错了」；项目已归档只读时按 {@code PROJECT_UNAVAILABLE} 拒绝，且**不记活动**：冻结
 * 期间的活动既不是有效业务，也会让调试面显示一个其实不可写的设备。只有全部通过才记录活动事实。</p>
 *
 * <p><b>业务预算在这里扣减</b>：设备面每个已认证请求（上报、领取、回复）都算一次业务请求，预算因此必须扣在
 * 这一道唯一入口上，而不是散落在各端点里——散落实现迟早会漏掉某个端点，而「不得用轮询绕过预算」正是要靠
 * 这一点成立。认证面另有自己的两道窗口，二者互不替代：认证预算挡爆破，业务预算挡请求速率。</p>
 */
@Service
public class DefaultDeviceAccessDeviceAuthenticator implements DeviceAccessDeviceAuthenticator {

    /** 协议无关凭据校验端口。 */
    private final DeviceAuthenticationPort authenticationPort;

    /** 接入配置与会话事实端口。 */
    private final DeviceAccessSessionPort sessionPort;

    /** 项目生命周期读写资格端口；设备写入必须先确认项目仍可写。 */
    private final ProjectLifecycleAccessService projectLifecycle;

    /** 业务请求预算的唯一扣减点；与 MQTT 共享同一份额度。 */
    private final DeviceAccessBusinessBudget businessBudget;

    /**
     * @param authenticationPort 协议无关凭据校验端口
     * @param sessionPort 接入配置与会话事实端口
     * @param projectLifecycle 项目生命周期读写资格端口
     * @param businessBudget 业务请求预算扣减点
     */
    public DefaultDeviceAccessDeviceAuthenticator(DeviceAuthenticationPort authenticationPort,
                                                  DeviceAccessSessionPort sessionPort,
                                                  ProjectLifecycleAccessService projectLifecycle,
                                                  DeviceAccessBusinessBudget businessBudget) {
        this.authenticationPort = authenticationPort;
        this.sessionPort = sessionPort;
        this.projectLifecycle = projectLifecycle;
        this.businessBudget = businessBudget;
    }

    @Override
    public AuthenticatedDeviceIdentity authenticate(TransportProtocol protocol, String projectKey, String deviceKey,
                                                    String secret) {
        Objects.requireNonNull(protocol, "接入协议不能为空");
        Optional<AuthenticatedDeviceIdentity> identity = authenticationPort.authenticate(projectKey, deviceKey, secret);
        if (identity.isEmpty()) {
            throw new DeviceAccessDeviceAuthenticationException(DeviceAccessAuthFailureReason.AUTH_FAILED);
        }
        AuthenticatedDeviceIdentity authenticated = identity.orElseThrow();
        if (!sessionPort.planeEnabled(authenticated.tenantId(), authenticated.projectId(),
                authenticated.deviceId(), protocol)) {
            // 凭据正确但这个平面没开通：设备在本平面上不可用，不能按密钥错误上报，也不能记活动。
            throw new DeviceAccessDeviceAuthenticationException(DeviceAccessAuthFailureReason.PLANE_NOT_ENABLED);
        }
        ProjectAccessPolicy policy;
        try {
            policy = projectLifecycle.snapshot(authenticated.tenantId(), authenticated.projectId());
        } catch (RuntimeException exception) {
            // 生命周期投影故障不能放行设备写入：这里按不可写拒绝，且不记活动。
            throw new DeviceAccessDeviceAuthenticationException(DeviceAccessAuthFailureReason.PROJECT_UNAVAILABLE);
        }
        if (!policy.writeAllowed()) {
            throw new DeviceAccessDeviceAuthenticationException(DeviceAccessAuthFailureReason.PROJECT_UNAVAILABLE);
        }
        requireBudget(authenticated);
        var result=sessionPort.recordAuthenticatedActivity(authenticated,protocol);
        if(result!=DeviceAccessSessionPort.ActivityResult.ACCEPTED) {
            if (result == DeviceAccessSessionPort.ActivityResult.CREDENTIAL_CHANGED) {
                throw new DeviceAccessDeviceAuthenticationException(DeviceAccessAuthFailureReason.AUTH_FAILED);
            }
            throw new DeviceAccessDeviceAuthenticationException(result==DeviceAccessSessionPort.ActivityResult.PLANE_NOT_ENABLED
                ?DeviceAccessAuthFailureReason.PLANE_NOT_ENABLED:DeviceAccessAuthFailureReason.PROJECT_UNAVAILABLE);
        }
        return authenticated;
    }

    /** 扣减与 MQTT 同一份额度；HTTP／CoAP 每个请求都经本闸门，因此「每请求一次」在此成立。 */
    private void requireBudget(AuthenticatedDeviceIdentity identity) {
        businessBudget.charge(identity.tenantId(), identity.projectId(), identity.deviceId());
    }
}
