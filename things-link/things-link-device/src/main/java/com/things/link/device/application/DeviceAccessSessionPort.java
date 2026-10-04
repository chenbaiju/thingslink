package com.things.link.device.application;

import com.things.link.shared.message.TransportProtocol;

import java.util.Optional;
import java.util.UUID;

/**
 * 协议无关的接入会话端口（接入合同 §4.1／§4.5）。
 *
 * <p>三种新协议共用同一份会话事实：TCP 用它表达长连接与代次，HTTP／CoAP 用它表达「最近活动」，MQTT 继续走
 * 既有 Broker 回调写入的同一条记录。端口只回答四件事——配置是否允许该协议接入、会话是否建立（含接管）、
 * 本实例是否仍持有该会话、会话是否关闭——协议层据此拒绝旧代次的帧与投递。</p>
 */
public interface DeviceAccessSessionPort {

    /** 会话建立被拒绝的稳定原因。 */
    enum Rejection {
        /** 设备已开通其他协议：一台设备同一时刻只有一份生效接入配置。 */
        PROTOCOL_MISMATCH,
        /** 接入配置被关闭。 */
        DISABLED,
        /** MQTT 会话由 Broker 回调路径写入，接入面不得二次建立。 */
        MQTT_OWNED_BY_BROKER,
        /** 设备不存在或不属于该认证范围。 */
        DEVICE_NOT_FOUND,
        /** 项目在原事务内已不可写。 */
        PROJECT_UNAVAILABLE,
        /** 原认证凭据已在最终受理前失效。 */
        CREDENTIAL_CHANGED
    }

    /**
     * 会话建立请求。
     *
     * @param tenantId 认证时权威租户
     * @param projectId 认证时权威项目，同时是 RLS 范围
     * @param deviceId 认证时权威设备
     * @param protocol 本次连接的传输协议
     * @param sessionId 会话标识（TCP 连接标识／CoAP 会话标识）
     * @param ownerInstance 处理本次会话的接入实例
     * @param clientIp 设备端 IP；未知为空
     * @param heartbeatIntervalMillis TCP实际下发周期，其他协议为空
     */
    record EstablishmentRequest(UUID tenantId, UUID projectId, UUID deviceId,
                                TransportProtocol protocol, String sessionId, String ownerInstance, String clientIp, Long heartbeatIntervalMillis) {

        /** 冻结会话建立请求的必填字段。 */
        public EstablishmentRequest {
            if (tenantId == null || projectId == null || deviceId == null || protocol == null) {
                throw new IllegalArgumentException("会话建立归属与协议不能为空");
            }
            if (protocol == TransportProtocol.TCP && (heartbeatIntervalMillis == null || heartbeatIntervalMillis < 10_000 || heartbeatIntervalMillis > 715_827_882)
                    || protocol != TransportProtocol.TCP && heartbeatIntervalMillis != null) {
                throw new IllegalArgumentException("TCP必须提供合法实际下发心跳周期，其他协议不能携带");
            }
            if (sessionId == null || sessionId.isBlank() || ownerInstance == null || ownerInstance.isBlank()) {
                throw new IllegalArgumentException("会话标识与归属实例不能为空");
            }
        }
    }

    /**
     * 已建立的会话。
     *
     * @param rowId 会话行 ID，后续心跳与关闭都用它
     * @param sessionId 会话标识
     * @param generation 会话代次，旧代次的帧与投递一律拒绝
     * @param configVersion 建立时的接入配置代次
     * @param replacedPriorSession 是否接管并关闭了既有活跃会话
     */
    record Established(UUID rowId, String sessionId, long generation, long configVersion,
                       boolean replacedPriorSession) {
    }

    /**
     * 当前活跃会话视图，供跨实例路由与推送使用。
     *
     * @param rowId 会话行 ID
     * @param protocol 传输协议
     * @param sessionId 会话标识
     * @param ownerInstance 归属接入实例；历史行可能为空
     * @param generation 会话代次
     * @param configVersion 建立时的接入配置代次
     * @param connectedAt 会话建立时刻
     * @param lastSeenAt 最近活动时刻；从未心跳时为空
     */
    record Active(UUID rowId, TransportProtocol protocol, String sessionId, String ownerInstance, long generation,
                  long configVersion, java.time.Instant connectedAt, java.time.Instant lastSeenAt) {
    }

    /** 会话建立结果。 */
    sealed interface Establishment {

        /**
         * 会话已建立。
         *
         * @param established 会话事实
         */
        record Allowed(Established established) implements Establishment {
        }

        /**
         * 会话被拒绝。
         *
         * @param reason 稳定拒绝原因
         */
        record Rejected(Rejection reason) implements Establishment {
        }
    }

    /**
     * 建立会话并接管既有活跃会话。
     *
     * 可信内部兼容入口；TCP网络入口必须使用establishAuthenticated在最终写入点复核原身份。
     * @param request 可信会话建立请求
     * @return 允许（含会话代次与是否接管）或拒绝原因
     */
    Establishment establish(EstablishmentRequest request);

    /** TCP网络专用：最终建立时持锁复核原凭据及当前配置，不能以旧认证升级会话。 */
    Establishment establishAuthenticated(EstablishmentRequest request,
            com.things.link.shared.message.AuthenticatedDeviceIdentity identity);

    /**
     * 刷新会话最近活动时刻。
     *
     * @param tenantId 认证时权威租户
     * @param projectId 项目 ID，同时是 RLS 范围
     * @param deviceId 设备 ID
     * @param rowId 会话行 ID
     * @param ownerInstance 期望的归属实例
     * @return 是否仍由该实例持有该会话；false 表示会话已被接管或关闭，调用方必须停止使用它
     */
    boolean touch(UUID tenantId, UUID projectId, UUID deviceId, UUID rowId, String ownerInstance);

    /**
     * 关闭会话。
     *
     * @param tenantId 认证时权威租户
     * @param projectId 项目 ID，同时是 RLS 范围
     * @param deviceId 设备 ID
     * @param rowId 会话行 ID
     * @param ownerInstance 期望的归属实例
     * @param reason 断开原因
     * @return 是否由本次调用关闭；false 表示会话已被接管或已关闭
     */
    boolean close(UUID tenantId, UUID projectId, UUID deviceId, UUID rowId, String ownerInstance,
                  String reason);

    /**
     * 记录一次认证通过的接入活动。
     *
     * @param tenantId 认证时权威租户
     * @param projectId 项目 ID，同时是 RLS 范围
     * @param deviceId 设备 ID
     * @return 是否命中一条生效接入配置；没有配置行的存量 MQTT 设备返回 false
     */
    boolean touchActivity(UUID tenantId, UUID projectId, UUID deviceId);

    /** 可信内部兼容活动；外部原生认证须使用recordAuthenticatedActivity复核原凭据版本。 */
    ActivityResult recordActivity(UUID tenantId, UUID projectId, UUID deviceId, TransportProtocol expected);

    /** 原生认证专用：完整原身份在活动写入的设备锁内复核，不能以旧认证恢复新活动。 */
    ActivityResult recordAuthenticatedActivity(com.things.link.shared.message.AuthenticatedDeviceIdentity identity,
            TransportProtocol expected);

    /** Stable internal admission classification, mapped to existing device protocol errors. */
    enum ActivityResult { ACCEPTED, PLANE_NOT_ENABLED, PROJECT_UNAVAILABLE, CREDENTIAL_CHANGED }

    /**
     * 读取设备当前活跃会话。
     *
     * @param tenantId 认证时权威租户
     * @param projectId 项目 ID，同时是 RLS 范围
     * @param deviceId 设备 ID
     * @return 活跃会话；设备当前没有活跃会话时为空
     */
    Optional<Active> active(UUID tenantId, UUID projectId, UUID deviceId);

    /**
     * 生效接入配置的跨模块视图。
     *
     * <p>只暴露接入面确实需要的字段：协议、配置代次、心跳与启用位。领域类型不外泄，否则其他模块会被迫跟着
     * 设备域的重构走（架构规则 2/3 禁止跨模块引用 domain 包）。</p>
     *
     * @param protocol 接入平面使用的传输协议
     * @param configVersion 配置代次；存量 MQTT 为 0
     * @param heartbeatSeconds TCP 心跳周期（秒）；为空表示取默认档
     * @param enabled 是否允许该协议接入
     */
    record Config(TransportProtocol protocol, long configVersion, Integer heartbeatSeconds, boolean enabled) {
    }

    /**
     * 判断设备是否已开通并可用的指定接入平面。
     *
     * @param tenantId 设备归属租户
     * @param projectId 项目 ID
     * @param deviceId 设备 ID
     * @param protocol 本次连接的传输协议
     * @return 已开通、协议一致且未被关闭
     */
    boolean planeEnabled(UUID tenantId, UUID projectId, UUID deviceId, TransportProtocol protocol);

    /**
     * 读取生效接入配置的跨模块视图。
     *
     * @param tenantId 设备归属租户
     * @param projectId 项目 ID
     * @param deviceId 设备 ID
     * @return 已开通的配置视图；没有配置行时为空，由调用方按存量 MQTT 默认档处理
     */
    Optional<Config> config(UUID tenantId, UUID projectId, UUID deviceId);
}
