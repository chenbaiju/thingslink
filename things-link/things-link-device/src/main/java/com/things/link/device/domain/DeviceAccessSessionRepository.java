package com.things.link.device.domain;

import com.things.link.shared.message.TransportProtocol;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * 设备生效接入配置与接入会话的仓储端口。
 *
 * <p>调用方必须已经在事务内建立完整的项目 RLS 范围；仓储不做身份解析，也不提供跨项目入口。接管与代次
 * 递增都由数据库仲裁：活跃会话的唯一性与 {@code FOR UPDATE} 锁在同一事务内完成，应用层不判断并发。</p>
 */
public interface DeviceAccessSessionRepository {

    /**
     * 读取设备的生效接入配置。
     *
     * @param projectId 项目 ID
     * @param deviceId 设备 ID
     * @return 已开通的配置；没有配置行时为空，由调用方合成存量 MQTT 默认配置
     */
    Optional<DeviceAccessBinding> findBinding(UUID projectId, UUID deviceId);

    /**
     * 写入或更新生效接入配置，只有配置真正变化时才递增代次。
     *
     * @param tenantId 归属租户
     * @param projectId 归属项目
     * @param deviceId 设备 ID
     * @param protocol 接入协议
     * @param heartbeatSeconds 可选心跳周期（秒）
     * @return 写入后的配置（含最终代次）
     */
    DeviceAccessBinding bind(UUID tenantId, UUID projectId, UUID deviceId, TransportProtocol protocol,
                             Integer heartbeatSeconds);

    /**
     * 在设备锁内比较并修改配置；调用方须在同一事务维护关闭边沿与审计，不能直接暴露为管理接口。
     * 无行MQTT同值不创建配置，凭据变化通过forceAdvance强制推进一次；旧expected即使同值仍冲突。
     *
     * @param tenantId 真实设备租户
     * @param projectId 项目范围
     * @param deviceId 设备主键
     * @param expectedVersion 期望配置版本，无行时为0
     * @param protocol 目标协议
     * @param enabled 目标启用状态
     * @param forceAdvance 凭据实际变化时为true
     * @return 最终配置，未变化时保持原代次和活动事实
     */
    DeviceAccessBinding changeBinding(UUID tenantId, UUID projectId, UUID deviceId, long expectedVersion,
                                      TransportProtocol protocol, boolean enabled, boolean forceAdvance);

    /**
     * 记录一次认证通过的接入活动（HTTP／CoAP 无会话，只有活动）。
     *
     * @param projectId 项目 ID
     * @param deviceId 设备 ID
     * @param at 活动时刻
     * @return 是否命中一条生效接入配置；没有配置行的存量 MQTT 设备返回 false
     */
    boolean touchActivity(UUID projectId, UUID deviceId, Instant at);

    /**
     * 为设备建立接入会话并接管既有活跃会话。
     *
     * <p>同一设备的会话建立被数据库串行化：先对设备上锁，再关闭该设备既有活跃会话（记接管原因），最后
     * 以本设备当前最大会话代次 +1 写入新会话。因此并发建立只会有一个「最新」，旧实例的后续心跳会因
     * 归属或代次不符而被拒绝。</p>
     *
     * @param tenantId 归属租户
     * @param projectId 归属项目
     * @param deviceId 设备 ID
     * @param protocol 本次连接的传输协议
     * @param sessionId 会话标识（TCP 连接标识／CoAP 会话标识）
     * @param ownerInstance 处理本次会话的接入实例
     * @param configVersion 建立时的接入配置代次
     * @param clientIp 设备端 IP；未知为空
     * @param at 建立时刻
     * @return 新会话事实与是否接管了既有会话
     */
    EstablishedSession establish(UUID tenantId, UUID projectId, UUID deviceId, TransportProtocol protocol,
                                 String sessionId, String ownerInstance, long configVersion, String clientIp,
                                 Instant at, Long heartbeatIntervalMillis);

    /**
     * 更新会话最近活动时刻，仅当归属实例与会话仍活跃时生效。
     *
     * @param projectId 项目 ID
     * @param deviceId 设备 ID
     * @param sessionRowId 会话行 ID
     * @param ownerInstance 期望的归属实例
     * @param at 最近活动时刻
     * @return 是否命中一条仍由该实例持有的活跃会话
     */
    boolean touch(UUID projectId, UUID deviceId, UUID sessionRowId, String ownerInstance, Instant at);

    /**
     * 关闭会话，仅当归属实例与会话仍活跃时生效。
     *
     * @param projectId 项目 ID
     * @param deviceId 设备 ID
     * @param sessionRowId 会话行 ID
     * @param ownerInstance 期望的归属实例
     * @param reason 断开原因
     * @param at 断开时刻
     * @return 是否命中一条仍由该实例持有的活跃会话
     */
    boolean close(UUID projectId, UUID deviceId, UUID sessionRowId, String ownerInstance, String reason, Instant at);

    /**
     * 读取设备当前活跃会话（代次最大的那条）。
     *
     * @param projectId 项目 ID
     * @param deviceId 设备 ID
     * @return 活跃会话；设备当前没有活跃会话时为空
     */
    Optional<ActiveSession> findActive(UUID projectId, UUID deviceId);

    /**
     * 读取该设备最近一次断开的会话事实，供连接诊断展示断开原因。
     *
     * @param projectId 项目 ID
     * @param deviceId 设备 ID
     * @return 最近一次断开原因与时刻；设备从未断开会话时为空
     */
    Optional<LastDisconnect> findLastDisconnect(UUID projectId, UUID deviceId);

    /**
     * 最近一次断开的会话事实。
     *
     * @param reason 断开原因
     * @param disconnectedAt 断开时刻
     */
    record LastDisconnect(String reason, Instant disconnectedAt) {
    }

    /**
     * 新建立的会话事实。
     *
     * @param rowId 会话行 ID
     * @param sessionId 会话标识
     * @param generation 会话代次（本设备内单调递增）
     * @param configVersion 建立时的接入配置代次
     * @param replacedPriorSession 是否接管并关闭了既有活跃会话
     */
    record EstablishedSession(UUID rowId, String sessionId, long generation, long configVersion,
                              boolean replacedPriorSession) {
    }

    /**
     * 活跃会话视图。
     *
     * @param rowId 会话行 ID
     * @param deviceId 设备 ID
     * @param protocol 传输协议
     * @param sessionId 会话标识
     * @param ownerInstance 归属接入实例；历史行可能为空
     * @param generation 会话代次
     * @param configVersion 建立时的接入配置代次
     * @param connectedAt 会话建立时刻
     * @param lastSeenAt 最近活动时刻；从未心跳时为空
     */
    record ActiveSession(UUID rowId, UUID deviceId, TransportProtocol protocol, String sessionId,
                         String ownerInstance, long generation, long configVersion, Instant connectedAt,
                         Instant lastSeenAt) {
    }
}
