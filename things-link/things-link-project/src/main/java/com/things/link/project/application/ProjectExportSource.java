package com.things.link.project.application;

import java.time.Instant;
import java.util.UUID;

/**
 * ADR0075 项目导出在 project 域的公开事实端口。
 *
 * <p>导出编排器只能通过本端口取得项目和成员，不能跨模块读取 {@code sys_project} 或
 * {@code sys_project_member}。所有方法都加入调用方已经建立的事务，避免分页调用产生多个快照。</p>
 */
public interface ProjectExportSource {

    /**
     * 在请求事务中复核有效账号和保留 OWNER，并持有项目 SHARE 锁。
     *
     * @param accountId 已认证账号
     * @param projectId 待导出项目
     * @return 可信任务身份
     */
    ProjectExportScope authorizeRequest(UUID accountId, UUID projectId);

    /**
     * 在下载签发事务中先锁项目和OWNER行，再把当前归属与代次交给任务锁。
     *
     * <p>DELETING是导出的原始下载窗口；同代恢复后的ACTIVE与既有ARCHIVED
     * 只读状态也可继续签发。再次删除必须由任务仓储的generation匹配拒绝。</p>
     *
     * @param accountId 已认证账号
     * @param projectId 路径中的项目
     * @return 项目锁后的当前归属与代次
     */
    ProjectDownloadScope authorizeDownload(UUID accountId, UUID projectId);

    /**
     * 下载签发在项目锁后取得的当前持久身份。
     * @param tenantId 项目owner租户
     * @param projectId 项目 ID
     * @param generation 当前生命周期代次
     */
    record ProjectDownloadScope(UUID tenantId, UUID projectId, long generation) {
        /** 锁后身份必须完整且代次非负。 */
        public ProjectDownloadScope {
            if (tenantId == null || projectId == null || generation < 0) {
                throw new IllegalArgumentException("项目导出下载范围不完整");
            }
        }
    }

    /**
     * 在 worker 的 REPEATABLE READ 事务中以持久任务身份锁定删除项目。
     *
     * <p>本方法必须是事务中的第一条业务查询，成功取得的 SHARE 锁保证恢复和后续清理
     * 不会与快照生成交错。ADR0075 的“只读”描述指业务不写表；PostgreSQL READ ONLY
     * 事务禁止 FOR SHARE，因此调用方事务不得声明 readOnly。</p>
     *
     * @param tenantId 项目真实归属租户
     * @param projectId 项目身份
     * @param generation 删除时生命周期代次
     * @return 项目白名单事实及数据库快照时刻
     */
    ProjectExportProject lockSnapshot(UUID tenantId, UUID projectId, long generation);

    /**
     * 按稳定主键顺序流出全部保留成员，包括 DISABLED 历史成员。
     *
     * @param tenantId 项目真实归属租户
     * @param projectId 项目身份
     * @param sink 单行接收器
     * @return 输出行数
     */
    long streamMembers(UUID tenantId, UUID projectId, MemberSink sink);

    /**
     * 请求成功后固化到任务的可信项目身份。
     *
     * @param tenantId 项目 owner tenant
     * @param projectId 项目 ID
     * @param generation 删除后的生命周期代次
     * @param deletedAt 软删除时刻
     * @param storageQuotaConfigured 当前策略是否已经启用对象存储字节上限
     */
    record ProjectExportScope(UUID tenantId, UUID projectId, long generation, Instant deletedAt,
                              boolean storageQuotaConfigured) {
        /** 保证任务身份完整且代次非负。 */
        public ProjectExportScope {
            if (tenantId == null || projectId == null || generation < 0 || deletedAt == null) {
                throw new IllegalArgumentException("项目导出身份不完整");
            }
        }
    }

    /**
     * {@code project.json} 的逐字段白名单事实。
     *
     * @param id 项目 ID
     * @param name 项目名称
     * @param region 区域
     * @param timezone 时区
     * @param status 生命周期状态
     * @param createdAt 创建时刻
     * @param updatedAt 最后修改时刻
     * @param deletedAt 删除时刻
     * @param snapshotAt PostgreSQL 事务快照时刻
     */
    record ProjectExportProject(UUID id, String name, String region, String timezone, String status,
                                Instant createdAt, Instant updatedAt, Instant deletedAt, Instant snapshotAt) {
        /** 拒绝缺字段或非删除状态事实进入归档。 */
        public ProjectExportProject {
            if (id == null || name == null || region == null || timezone == null || status == null
                    || createdAt == null || updatedAt == null || deletedAt == null || snapshotAt == null) {
                throw new IllegalArgumentException("项目导出快照不完整");
            }
        }
    }

    /**
     * {@code members.jsonl} 的逐字段白名单事实。
     *
     * @param id 成员记录 ID
     * @param accountId 账号 ID
     * @param role 项目角色
     * @param status 成员状态
     * @param createdAt 加入时刻
     * @param updatedAt 最后修改时刻
     */
    record ProjectExportMember(UUID id, UUID accountId, String role, String status,
                               Instant createdAt, Instant updatedAt) {
    }

    /** 单行成员回调；实现必须在回调返回后再读取下一行，保持常量内存。 */
    @FunctionalInterface
    interface MemberSink {
        /** @param member 当前成员事实 */
        void accept(ProjectExportMember member);
    }
}
