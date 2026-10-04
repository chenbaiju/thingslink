package com.things.link.project.infrastructure.persistence;

import com.things.link.shared.authz.ProjectRole;
import com.things.link.project.domain.DeletedProject;
import com.things.link.project.domain.Project;
import com.things.link.project.domain.ProjectMember;
import com.things.link.project.domain.ProjectMembership;
import com.things.link.project.domain.ProjectRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

/**
 * 基于 JDBC 的项目仓储实现。
 */
@Repository
public class JdbcProjectRepository implements ProjectRepository {

    /**
     * 列前缀 {@code p.} 是必须的：查询里 project 与 project_member 都有
     * {@code id} / {@code created_at}，不加前缀取到的会是成员记录的值。
     */
    private static final RowMapper<ProjectMembership> MEMBERSHIP_MAPPER = (rs, rowNum) ->
            new ProjectMembership(
                    new Project(
                            rs.getObject("id", UUID.class),
                            rs.getObject("tenant_id", UUID.class),
                            rs.getString("name"),
                            rs.getString("region"),
                            rs.getString("timezone"),
                            rs.getString("project_key"),
                            Project.Status.valueOf(rs.getString("status")),
                            rs.getTimestamp("created_at").toInstant(),
                            rs.getLong("lifecycle_generation")),
                    ProjectRole.valueOf(rs.getString("member_role")));

    /** 成员列表的行映射。只查 project_member 一张表，因此不需要列前缀。 */
    private static final RowMapper<ProjectMember> MEMBER_MAPPER = (rs, rowNum) ->
            new ProjectMember(
                    rs.getObject("id", UUID.class),
                    rs.getObject("account_id", UUID.class),
                    ProjectRole.valueOf(rs.getString("role")),
                    rs.getTimestamp("created_at").toInstant());

    /** 回收站查询与恢复排他锁共用同一事实映射，避免期限算法在两个入口漂移。 */
    private static final RowMapper<DeletedProject> DELETED_PROJECT_MAPPER = (rs, rowNum) ->
            new DeletedProject(
                    new Project(
                            rs.getObject("id", UUID.class),
                            rs.getObject("tenant_id", UUID.class),
                            rs.getString("name"),
                            rs.getString("region"),
                            rs.getString("timezone"),
                            rs.getString("project_key"),
                            Project.Status.valueOf(rs.getString("status")),
                            rs.getTimestamp("created_at").toInstant(),
                            rs.getLong("lifecycle_generation")),
                    rs.getTimestamp("deleted_at").toInstant(),
                    rs.getTimestamp("restore_deadline").toInstant(),
                    rs.getBoolean("restorable"));

    private final JdbcTemplate jdbcTemplate;

    public JdbcProjectRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void create(Project project) {
        jdbcTemplate.update("""
                        INSERT INTO sys_project (
                            id, tenant_id, name, region, timezone, project_key, status, lifecycle_generation
                        )
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                project.id(), project.tenantId(), project.name(),
                project.region(), project.timezone(), project.projectKey(), project.status().name(),
                project.lifecycleGeneration());
    }

    @Override
    public void addMember(UUID memberId, UUID projectId, UUID accountId, ProjectRole role) {
        jdbcTemplate.update("""
                INSERT INTO sys_project_member (id, project_id, account_id, role)
                VALUES (?, ?, ?, ?)
                """, memberId, projectId, accountId, role.name());
    }

    /** {@inheritDoc} */
    @Override
    public void lockTenantProjectQuota(UUID tenantId) {
        // 命名空间 12014 与设备 735、仪表盘 12012/12013、OTA 13026 互不相同：
        // 同一租户的「自有项目计数→创建」串行化，但不会阻塞设备或仪表盘创建。
        // sys_project 豁免 RLS，本锁也不能读取它，否则会与锁顺序交织出死锁。
        jdbcTemplate.queryForObject("""
                SELECT 1 FROM pg_advisory_xact_lock(hashtextextended(
                    concat_ws(':', 'tenant-owned-project-quota-v1', ?::text), 12014::bigint))
                """, Integer.class, tenantId);
    }

    /** {@inheritDoc} */
    @Override
    public int countOwnedProjects(UUID tenantId) {
        // 不 join 成员关系：外部协作身份属于他人租户的项目，不能消耗本租户名额。
        Integer count = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM sys_project
                 WHERE tenant_id = ? AND deleted_at IS NULL
                """, Integer.class, tenantId);
        return count == null ? 0 : count;
    }

    /** {@inheritDoc} */
    @Override
    public List<ProjectMembership> findMembershipsByAccount(UUID accountId) {
        // ⚠️ project 与 project_member 都豁免 RLS，**这条 join 就是唯一的隔离手段**。
        // 少了 m.account_id = ? 这个条件，返回的就是全平台所有项目 ——
        // 而且不会有任何症状：接口 200、页面正常渲染，只是列出了别人的项目
        return jdbcTemplate.query("""
                SELECT p.id, p.tenant_id, p.name, p.region, p.timezone, p.project_key, p.status, p.created_at,
                       p.lifecycle_generation,
                       m.role AS member_role
                  FROM sys_project p
                  JOIN sys_project_member m ON m.project_id = p.id
                 WHERE m.account_id = ?
                   AND m.status = 'ACTIVE'
                   AND p.deleted_at IS NULL
                   AND p.status IN ('ACTIVE', 'ARCHIVED')
                 ORDER BY p.created_at DESC
                """, MEMBERSHIP_MAPPER, accountId);
    }

    /** {@inheritDoc} */
    @Override
    public Optional<ProjectMembership> findMembership(UUID projectId, UUID accountId) {
        // ADR0064决策5：与列表同样限定项目、账号及可读状态；保留成员行不等于保留删除项目资格。
        return jdbcTemplate.query("""
                SELECT p.id, p.tenant_id, p.name, p.region, p.timezone, p.project_key, p.status, p.created_at,
                       p.lifecycle_generation,
                       m.role AS member_role
                  FROM sys_project p
                  JOIN sys_project_member m ON m.project_id = p.id
                 WHERE p.id = ?
                   AND m.account_id = ?
                   AND m.status = 'ACTIVE'
                   AND p.deleted_at IS NULL
                   AND p.status IN ('ACTIVE', 'ARCHIVED')
                """, MEMBERSHIP_MAPPER, projectId, accountId).stream().findFirst();
    }

    @Override
    public List<ProjectMember> findMembers(UUID projectId) {
        return jdbcTemplate.query("""
                SELECT m.id, m.account_id, m.role, m.created_at
                  FROM sys_project_member m
                 WHERE m.project_id = ?
                   AND m.status = 'ACTIVE'
                 ORDER BY m.created_at
                """, MEMBER_MAPPER, projectId);
    }

    @Override
    public int updateMemberRole(UUID projectId, UUID accountId, ProjectRole role) {
        // 条件里带 role <> 'OWNER'：即便上层的检查被绕过或写错，
        // 数据库这一步也不会把所有者降级。所有者是项目最后的管理入口，
        // 值得有第二道保险（部分唯一索引只挡「多出一个 OWNER」，挡不住「少一个」）
        return jdbcTemplate.update("""
                UPDATE sys_project_member
                   SET role = ?, updated_at = now()
                 WHERE project_id = ?
                   AND account_id = ?
                   AND role <> 'OWNER'
                """, role.name(), projectId, accountId);
    }

    @Override
    public boolean transferOwnership(UUID projectId, UUID currentOwnerId, UUID newOwnerId) {
        // 顺序不能反：sys_project_member_single_owner_uk 保证同一项目最多一个 OWNER。
        // 先升新 OWNER 会撞唯一索引；先降旧 OWNER 再升新 OWNER，若第二步失败，
        // 外层事务会回滚，项目不会落到“没有 OWNER”的半成品状态。
        int demoted = jdbcTemplate.update("""
                UPDATE sys_project_member
                   SET role = 'ADMIN', updated_at = now()
                 WHERE project_id = ?
                   AND account_id = ?
                   AND role = 'OWNER'
                   AND status = 'ACTIVE'
                """, projectId, currentOwnerId);
        int promoted = jdbcTemplate.update("""
                UPDATE sys_project_member
                   SET role = 'OWNER', updated_at = now()
                 WHERE project_id = ?
                   AND account_id = ?
                   AND role <> 'OWNER'
                   AND status = 'ACTIVE'
                """, projectId, newOwnerId);
        return demoted == 1 && promoted == 1;
    }

    @Override
    public int removeMember(UUID projectId, UUID accountId) {
        // 同上：OWNER 在 SQL 层就删不掉
        return jdbcTemplate.update("""
                DELETE FROM sys_project_member
                 WHERE project_id = ?
                   AND account_id = ?
                   AND role <> 'OWNER'
                """, projectId, accountId);
    }

    @Override
    public int updateName(UUID projectId, String name) {
        return jdbcTemplate.update("""
                UPDATE sys_project
                   SET name = ?, updated_at = now()
                 WHERE id = ?
                   AND deleted_at IS NULL
                """, name, projectId);
    }

    @Override
    public int countActiveMembers(UUID projectId) {
        return jdbcTemplate.queryForObject("""
                SELECT count(*)
                  FROM sys_project_member
                 WHERE project_id = ?
                   AND status = 'ACTIVE'
                """, Integer.class, projectId);
    }

    @Override
    public int softDelete(UUID projectId) {
        return jdbcTemplate.update("""
                UPDATE sys_project
                   SET status = 'DELETING',
                       deleted_at = now(),
                       updated_at = now(),
                       lifecycle_generation = lifecycle_generation + 1
                 WHERE id = ?
                   AND deleted_at IS NULL
                   AND status = 'ACTIVE'
                """, projectId);
    }

    /** {@inheritDoc} */
    @Override
    public List<DeletedProject> findDeletedOwnedBy(UUID accountId) {
        // 两表均豁免RLS；accountId、ACTIVE成员和OWNER角色缺一都会泄露其他账号的回收站。
        return jdbcTemplate.query("""
                SELECT p.id, p.tenant_id, p.name, p.region, p.timezone, p.project_key,
                       p.status, p.created_at, p.lifecycle_generation, p.deleted_at,
                       p.deleted_at + interval '30 days' AS restore_deadline,
                       clock_timestamp() < p.deleted_at + interval '30 days' AS restorable
                  FROM public.sys_project p
                  JOIN public.sys_project_member m ON m.project_id = p.id
                 WHERE m.account_id = ? AND m.status = 'ACTIVE' AND m.role = 'OWNER'
                   AND p.status = 'DELETING' AND p.deleted_at IS NOT NULL
                 ORDER BY p.deleted_at DESC, p.id
                """, DELETED_PROJECT_MAPPER, accountId);
    }

    /** {@inheritDoc} */
    @Override
    public boolean isRetainedActiveOwner(UUID projectId, UUID accountId) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject("""
                SELECT EXISTS (
                    SELECT 1
                      FROM public.sys_project p
                      JOIN public.sys_project_member m ON m.project_id = p.id
                     WHERE p.id = ? AND p.status = 'DELETING' AND p.deleted_at IS NOT NULL
                       AND m.account_id = ? AND m.status = 'ACTIVE' AND m.role = 'OWNER'
                )
                """, Boolean.class, projectId, accountId));
    }

    /** {@inheritDoc} */
    @Override
    public Optional<DeletedProject> lockDeletedForRecovery(UUID projectId) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("项目恢复排他锁必须加入已有非只读事务");
        }
        // 锁后必须读取成员新快照；RR/Serializable会保留锁前权限事实，故沿用管理锁的RC/RU约束。
        String isolation = jdbcTemplate.queryForObject("SHOW transaction_isolation", String.class);
        if (!"read committed".equals(isolation) && !"read uncommitted".equals(isolation)) {
            throw new IllegalStateException("项目恢复排他锁要求READ COMMITTED或READ UNCOMMITTED事务");
        }
        return jdbcTemplate.query("""
                SELECT p.id, p.tenant_id, p.name, p.region, p.timezone, p.project_key,
                       p.status, p.created_at, p.lifecycle_generation, p.deleted_at,
                       p.deleted_at + interval '30 days' AS restore_deadline,
                       clock_timestamp() < p.deleted_at + interval '30 days' AS restorable
                  FROM public.sys_project p
                 WHERE p.id = ? AND p.status = 'DELETING' AND p.deleted_at IS NOT NULL
                   FOR UPDATE OF p
                """, DELETED_PROJECT_MAPPER, projectId).stream().findFirst();
    }

    /** {@inheritDoc} */
    @Override
    public int restoreWithinWindow(UUID projectId) {
        // 单次取数据库墙钟同时用于期限与updated_at，避免锁等待跨过截止后复用事务起点now()。
        return jdbcTemplate.update("""
                WITH recovery_clock AS (SELECT clock_timestamp() AS value)
                UPDATE public.sys_project p
                   SET status = 'ACTIVE', deleted_at = NULL, updated_at = recovery_clock.value
                  FROM recovery_clock
                 WHERE p.id = ? AND p.status = 'DELETING' AND p.deleted_at IS NOT NULL
                   AND recovery_clock.value < p.deleted_at + interval '30 days'
                """, projectId);
    }

    @Override public Optional<ProjectRole> lockMemberRole(UUID projectId,UUID accountId){
        return jdbcTemplate.query("SELECT role FROM sys_project_member WHERE project_id=? AND account_id=? FOR SHARE",(r,n)->ProjectRole.valueOf(r.getString(1)),projectId,accountId).stream().findFirst();
    }

    /** {@inheritDoc} */
    @Override
    public Optional<ProjectRole> findRole(UUID projectId, UUID accountId) {
        // ADR0064决策5：DELETING即使尚无deleted_at也已失权；ARCHIVED只读仍保留原成员角色。
        return jdbcTemplate.query("""
                        SELECT m.role AS member_role
                          FROM sys_project_member m
                          JOIN sys_project p ON p.id = m.project_id
                         WHERE m.project_id = ?
                           AND m.account_id = ?
                           AND m.status = 'ACTIVE'
                           AND p.deleted_at IS NULL
                           AND p.status IN ('ACTIVE', 'ARCHIVED')
                        """,
                        (rs, rowNum) -> ProjectRole.valueOf(rs.getString("member_role")),
                        projectId, accountId)
                .stream().findFirst();
    }

    /** {@inheritDoc} */
    @Override
    public Optional<Project> findByProjectKey(String projectKey) {
        return jdbcTemplate.query("""
                        SELECT id, tenant_id, name, region, timezone, project_key, status, created_at,
                               lifecycle_generation
                          FROM sys_project
                         WHERE project_key = ? AND deleted_at IS NULL AND status = 'ACTIVE'
                        """, (rs, rowNum) -> new Project(
                        rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class),
                        rs.getString("name"), rs.getString("region"), rs.getString("timezone"), rs.getString("project_key"),
                        Project.Status.valueOf(rs.getString("status")), rs.getTimestamp("created_at").toInstant(),
                        rs.getLong("lifecycle_generation")),
                projectKey).stream().findFirst();
    }

    /** {@inheritDoc} */
    @Override
    public Optional<Project> findById(UUID projectId) {
        return jdbcTemplate.query("""
                        SELECT id, tenant_id, name, region, timezone, project_key, status, created_at,
                               lifecycle_generation
                          FROM sys_project
                         WHERE id = ? AND deleted_at IS NULL AND status = 'ACTIVE'
                        """, (rs, rowNum) -> new Project(
                        rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class),
                        rs.getString("name"), rs.getString("region"), rs.getString("timezone"), rs.getString("project_key"),
                        Project.Status.valueOf(rs.getString("status")), rs.getTimestamp("created_at").toInstant(),
                        rs.getLong("lifecycle_generation")),
                projectId).stream().findFirst();
    }

    /** {@inheritDoc} */
    @Override
    public Optional<Project> findLiveByIdentity(UUID tenantId, UUID projectId) {
        // sys_project明文豁免RLS，二元组必须进入SQL；只过滤deleted_at以保留ARCHIVED只读语义。
        return jdbcTemplate.query("""
                SELECT id, tenant_id, name, region, timezone, project_key, status, created_at,
                       lifecycle_generation
                  FROM public.sys_project
                 WHERE tenant_id = ? AND id = ? AND deleted_at IS NULL
                """, (rs, rowNum) -> new Project(
                rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class), rs.getString("name"),
                rs.getString("region"), rs.getString("timezone"), rs.getString("project_key"),
                Project.Status.valueOf(rs.getString("status")), rs.getTimestamp("created_at").toInstant(),
                rs.getLong("lifecycle_generation")),
                tenantId, projectId).stream().findFirst();
    }

    @Override
    @org.springframework.transaction.annotation.Transactional(propagation=org.springframework.transaction.annotation.Propagation.MANDATORY)
    public Optional<Project> lockLiveForBackground(UUID tenantId, UUID projectId) {
        String isolation=jdbcTemplate.queryForObject("SHOW transaction_isolation",String.class);
        if(!"read committed".equals(isolation))throw new IllegalStateException("后台项目许可要求READ COMMITTED");
        // sys_project明文豁免RLS，二元组必须进入SQL；只过滤deleted_at以保留ARCHIVED只读语义。
        return jdbcTemplate.query("""
                SELECT id, tenant_id, name, region, timezone, project_key, status, created_at,
                       lifecycle_generation
                  FROM public.sys_project
                 WHERE tenant_id = ? AND id = ? AND deleted_at IS NULL AND status IN ('ACTIVE','ARCHIVED') FOR SHARE
                """, (rs, rowNum) -> new Project(
                rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class), rs.getString("name"),
                rs.getString("region"), rs.getString("timezone"), rs.getString("project_key"),
                Project.Status.valueOf(rs.getString("status")), rs.getTimestamp("created_at").toInstant(),
                rs.getLong("lifecycle_generation")),
                tenantId, projectId).stream().findFirst();
    }

    /** {@inheritDoc} */
    @Override
    public Optional<Project> findForProjectToken(UUID accountId, UUID projectId) {
        // JWT中的pid已验签，但成员可能被移除；显式账号连接是本豁免RLS查询唯一的授权边界。
        return jdbcTemplate.query("""
                SELECT p.id, p.tenant_id, p.name, p.region, p.timezone, p.project_key,
                       CASE WHEN p.deleted_at IS NULL THEN p.status ELSE 'DELETING' END AS status, p.created_at,
                       p.lifecycle_generation
                  FROM public.sys_project p
                  JOIN public.sys_project_member m ON m.project_id = p.id
                 WHERE p.id = ? AND m.account_id = ? AND m.status = 'ACTIVE'
                """, (rs, rowNum) -> new Project(
                rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class), rs.getString("name"),
                rs.getString("region"), rs.getString("timezone"), rs.getString("project_key"),
                Project.Status.valueOf(rs.getString("status")), rs.getTimestamp("created_at").toInstant(),
                rs.getLong("lifecycle_generation")), projectId, accountId).stream().findFirst();
    }

    /** {@inheritDoc} */
    @Override
    public OptionalLong lockActiveGenerationForProjectToken(UUID accountId, UUID projectId) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("项目凭据代次锁必须加入已有非只读事务");
        }
        String isolation = jdbcTemplate.queryForObject("SHOW transaction_isolation", String.class);
        if (!"read committed".equals(isolation) && !"read uncommitted".equals(isolation)) {
            throw new IllegalStateException("项目凭据代次锁要求READ COMMITTED或READ UNCOMMITTED事务");
        }
        // 成员条件防止豁免RLS查询越权；只锁项目行即可阻塞删除代次递增，成员移除仍由每次认证回查即时生效。
        List<Long> generations = jdbcTemplate.query("""
                SELECT p.lifecycle_generation
                  FROM public.sys_project p
                  JOIN public.sys_project_member m ON m.project_id = p.id
                 WHERE p.id = ? AND m.account_id = ? AND m.status = 'ACTIVE'
                   AND p.status = 'ACTIVE' AND p.deleted_at IS NULL
                   FOR SHARE OF p
                """, (rs, rowNum) -> rs.getLong("lifecycle_generation"), projectId, accountId);
        return generations.isEmpty() ? OptionalLong.empty() : OptionalLong.of(generations.getFirst());
    }

    /** {@inheritDoc} */
    @Override
    public Optional<Project> lockForManagement(UUID projectId) {
        // 直调仓储也不能在auto-commit中观察隔离级别后换连接加锁；必须沿原非只读事务连接执行。
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("项目管理排他锁必须加入已有非只读事务");
        }
        // ADR0064决策2/5要求锁后成员新快照；RR即使等到项目锁仍可能读取锁前成员，不能沿旧权限放行。
        // 与D-117轮询领取相同，核验实际连接而非Spring隔离元数据；PG的RU等价RC，不改变调用方事务。
        String isolation = jdbcTemplate.queryForObject("SHOW transaction_isolation", String.class);
        if (!"read committed".equals(isolation) && !"read uncommitted".equals(isolation)) {
            throw new IllegalStateException("项目管理排他锁要求READ COMMITTED或READ UNCOMMITTED事务");
        }
        // ADR0064决策2/5：跨租户协作先由成员预检确权，按项目身份直接加排他锁，禁止SHARE升级。
        // 等待后由数据库重验状态；ARCHIVED保留给已授权调用方分类为50017，删除不可见。
        return jdbcTemplate.query("""
                SELECT p.id, p.tenant_id, p.name, p.region, p.timezone, p.project_key, p.status, p.created_at,
                       p.lifecycle_generation
                  FROM public.sys_project p
                 WHERE p.id = ? AND p.deleted_at IS NULL AND p.status IN ('ACTIVE', 'ARCHIVED')
                   FOR UPDATE OF p
                """, (rs, rowNum) -> new Project(
                rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class), rs.getString("name"),
                rs.getString("region"), rs.getString("timezone"), rs.getString("project_key"),
                Project.Status.valueOf(rs.getString("status")), rs.getTimestamp("created_at").toInstant(),
                rs.getLong("lifecycle_generation")),
                projectId).stream().findFirst();
    }

    /** {@inheritDoc} */
    @Override
    public boolean lockActiveForWrite(UUID tenantId, UUID projectId) {
        return lockActiveForWrite(tenantId, projectId, null);
    }

    /** {@inheritDoc} */
    @Override
    public boolean lockActiveForWrite(UUID tenantId, UUID projectId, long expectedGeneration) {
        if (expectedGeneration < 0) {
            throw new IllegalArgumentException("项目生命周期代次不能为负数");
        }
        return lockActiveForWrite(tenantId, projectId, Long.valueOf(expectedGeneration));
    }

    @Override
    public java.util.OptionalLong lockReadableGeneration(UUID tenantId,UUID projectId){
        if(!TransactionSynchronizationManager.isActualTransactionActive()||TransactionSynchronizationManager.isCurrentTransactionReadOnly())
            throw new IllegalStateException("可读项目锁要求原非只读事务");
        String isolation=jdbcTemplate.queryForObject("SHOW transaction_isolation",String.class);
        if(!"read committed".equals(isolation)&&!"read uncommitted".equals(isolation))throw new IllegalStateException("可读项目锁要求READ COMMITTED");
        var rows=jdbcTemplate.query("SELECT lifecycle_generation FROM sys_project WHERE tenant_id=? AND id=? AND status IN ('ACTIVE','ARCHIVED') AND deleted_at IS NULL FOR SHARE",(r,n)->r.getLong(1),tenantId,projectId);
        return rows.isEmpty()?java.util.OptionalLong.empty():java.util.OptionalLong.of(rows.getFirst());
    }

    /** 以可选代次执行同一ACTIVE SHARE锁语句；null只供无凭据后台入口沿用既有合同。 */
    private boolean lockActiveForWrite(UUID tenantId, UUID projectId, Long expectedGeneration) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("项目SHARE写许可必须加入已有非只读事务");
        }
        String isolation = jdbcTemplate.queryForObject("SHOW transaction_isolation", String.class);
        if (!"read committed".equals(isolation) && !"read uncommitted".equals(isolation)) {
            throw new IllegalStateException("项目SHARE写许可要求READ COMMITTED或READ UNCOMMITTED事务");
        }
        // FOR KEY SHARE挡不住status/deleted_at非键更新；SHARE在业务提交前阻止删除成功。
        return !jdbcTemplate.query("""
                SELECT p.id FROM public.sys_project p
                 WHERE p.tenant_id = ? AND p.id = ? AND p.status = 'ACTIVE' AND p.deleted_at IS NULL
                   AND (?::bigint IS NULL OR p.lifecycle_generation = ?)
                   FOR SHARE OF p
                """, (rs, rowNum) -> rs.getObject("id", UUID.class), tenantId, projectId,
                expectedGeneration, expectedGeneration).isEmpty();
    }

    /** {@inheritDoc} */
    @Override
    public java.util.List<UUID> findOwnedLiveProjectIds(UUID tenantId) {
        // created_at 相同时以 id 兜底，保证「最早创建」在重跑之间稳定选出同一个项目。
        return jdbcTemplate.query("""
                SELECT id FROM sys_project
                 WHERE tenant_id = ? AND deleted_at IS NULL
                 ORDER BY created_at, id
                """, (rs, rowNum) -> rs.getObject("id", UUID.class), tenantId);
    }

    /** {@inheritDoc} */
    @Override
    public java.util.List<UUID> findActiveOwnedLiveProjectIds(UUID tenantId) {
        return jdbcTemplate.query("""
                SELECT id FROM sys_project WHERE tenant_id=? AND deleted_at IS NULL AND status='ACTIVE'
                 ORDER BY created_at,id
                """,(rs,row) -> rs.getObject("id",UUID.class),tenantId);
    }

    /** {@inheritDoc} */
    @Override
    public int restrictCommercialWrite(java.util.Collection<UUID> projectIds) {
        if (projectIds.isEmpty()) {
            return 0;
        }
        // S14-3c：复用既有 ARCHIVED 只读语义，不新增项目列、不改写门禁 SQL。
        // 只把仍 ACTIVE 的自有项目转为 ARCHIVED，商业受限事实另由台账表记录。
        return jdbcTemplate.update(connection -> {
            java.sql.PreparedStatement statement = connection.prepareStatement("""
                    UPDATE sys_project
                       SET status = 'ARCHIVED', updated_at = now()
                     WHERE id = ANY (?) AND status = 'ACTIVE' AND deleted_at IS NULL
                    """);
            statement.setArray(1, connection.createArrayOf("uuid", projectIds.toArray(UUID[]::new)));
            return statement;
        });
    }

    /** {@inheritDoc} */
    @Override
    public int restoreCommercialWrite(java.util.Collection<UUID> projectIds) {
        if (projectIds.isEmpty()) {
            return 0;
        }
        // 只把台账里登记为本片受限的项目恢复为 ACTIVE；用户主动归档不在台账内，不受影响。
        return jdbcTemplate.update(connection -> {
            java.sql.PreparedStatement statement = connection.prepareStatement("""
                    UPDATE sys_project
                       SET status = 'ACTIVE', updated_at = now()
                     WHERE id = ANY (?) AND status = 'ARCHIVED' AND deleted_at IS NULL
                    """);
            statement.setArray(1, connection.createArrayOf("uuid", projectIds.toArray(UUID[]::new)));
            return statement;
        });
    }

}
