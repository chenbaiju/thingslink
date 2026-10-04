package com.things.link.project.application;

import java.util.UUID;

/**
 * 为项目设置提供设备数量这一配额事实的只读端口。
 *
 * <p>接口故意归 project application 所有：配额页面属于 project 域，但设备表只能由
 * device 模块读取（架构文档 10.3）。device 模块实现本端口，避免 project 反向依赖
 * device 或直接查询 {@code dev_device}。
 */
public interface ProjectDeviceQuotaContributor {

    /**
     * 统计项目当前有效设备数。
     *
     * <p>这是长期存量，不写入 {@code sys_usage_counter_daily}；日计数表只承载 UTC
     * 窗口内会增长的计量事实。将存量伪装成日计数会使跨日后读数归零，进而错误放宽设备上限。
     *
     * @param projectId 当前已授权项目 ID
     * @return 未软删除设备数量
     */
    ProjectDeviceQuotaUsage countActiveDevices(UUID projectId);

    /**
     * 统计无HTTP账号上下文的已确权设备项目存量。
     *
     * <p>实现必须在数据库内重新核对二元组并在无效时失败，不能把任意UUID或空聚合解释成
     * 合法零存量。调用者须先取得同一owner tenant的设备配额事务锁。</p>
     *
     * @param trustedTenantId 已认证项目的owner租户ID
     * @param trustedProjectId 已认证项目ID
     * @return 当前项目贡献与owner租户共享总量
     */
    ProjectDeviceQuotaUsage countActiveDevices(UUID trustedTenantId, UUID trustedProjectId);

    /**
     * 设备存量在当前项目和所属租户共享池中的投影。
     *
     * @param projectUsed 当前项目有效设备数
     * @param tenantUsed 所属租户全部项目有效设备数
     */
    record ProjectDeviceQuotaUsage(long projectUsed, long tenantUsed) {
    }
}
