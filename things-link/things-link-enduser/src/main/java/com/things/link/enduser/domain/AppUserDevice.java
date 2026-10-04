package com.things.link.enduser.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * 终端用户对设备的授权关系（ADR 0035 / 0037）。
 *
 * <p>这是 App 数据面设备授权的<b>权威事实</b>，走项目轴 RLS。S11-1b 只提供只读的
 * 「设备绑定概览」，写入（绑定/解绑/转移/共享）在 S11-3 落地 —— 因此本域对象先以
 * 只读投影出现，S11-3 再补状态机与并发仲裁。
 *
 * <p>{@code deviceId} 不设跨模块外键：{@code dev_device} 在 device 模块，跨模块外键
 * 会让 enduser 反向依赖 device 的表结构（ADR 0038）。归属校验在 S11-3 由 device 公开
 * 端口完成。
 *
 * @param id           关系记录 ID（UUIDv7）
 * @param tenantId     归属租户，由复合外键锁死与项目、用户同租户
 * @param projectId    所属项目
 * @param appUserId    终端用户
 * @param deviceId     设备 ID
 * @param relationRole 设备关系角色
 * @param status       关系状态
 * @param createdAt    建立关系时刻
 */
public record AppUserDevice(
        UUID id,
        UUID tenantId,
        UUID projectId,
        UUID appUserId,
        UUID deviceId,
        RelationRole relationRole,
        Status status,
        Instant createdAt) {

    /**
     * 设备关系角色。刻意不用控制台 {@code ProjectRole.OWNER} 命名，
     * 避免「设备主控」与「项目所有者」混淆（ADR 0035）。
     */
    public enum RelationRole {
        /** 主控。同一设备同时只能有一个有效 PRIMARY，由部分唯一索引仲裁。 */
        PRIMARY,
        /** 成员。 */
        MEMBER,
        /** 只读。 */
        READ_ONLY
    }

    /** 关系状态。与迁移中的 CHECK 约束保持一致，改动要同时改迁移。 */
    public enum Status {
        /** 有效。 */
        ACTIVE,
        /** 已关闭。解绑是关闭关系而非物理删除，保留历史（ADR 0037）。 */
        CLOSED
    }
}
