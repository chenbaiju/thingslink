package com.things.link.bootstrap.project.quota;

import com.things.link.project.application.ProjectService;
import com.things.link.project.application.QuotaPolicyAssignmentService;
import com.things.link.project.application.TenantProvisioning;
import com.things.link.project.domain.ProjectErrorCode;
import com.things.link.project.domain.ProjectMembership;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import com.things.link.testing.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * S14-2b：FREE 冻结 {@code projects_max = 1} 的真实 PostgreSQL 验收。
 *
 * <p>本类钉住四件事：① FREE 租户第一个自有项目成功、第二个被 50020 拒绝；② 只在他人租户项目里
 * 当外部协作者的账号不占用自己租户的名额；③ 通过 {@link QuotaPolicyAssignmentService} 切到付费模板后
 * 可建多个自有项目；④ 同一 FREE 租户的并发创建恰好成功一个，证明「租户锁下计数→创建」确是原子的。
 *
 * <p>不自造事务回滚夹具：方法级不用 {@code @Transactional}，每个创建都在自己的事务里提交，
 * 因此并发用例看到的确实是其他连接已提交的事实；清理显式按依赖顺序删除。
 */
@DisplayName("S14-2b FREE 自有项目数上限")
class TenantFreeProjectQuotaIntegrationTests extends AbstractIntegrationTest {

    /** 项目、成员与配额事实的核验入口。 */
    @Autowired
    private JdbcTemplate jdbcTemplate;
    /** 项目创建的模块唯一入口；守卫就在这个用例里。 */
    @Autowired
    private ProjectService projectService;
    /** 租户创建的模块唯一入口；新租户在同一事务里绑定 PLAN_R1_FREE。 */
    @Autowired
    private TenantProvisioning tenantProvisioning;
    /** 测试专用绑定切换，复用生产 CAS 与提交后缓存失效。 */
    @Autowired
    private QuotaPolicyAssignmentService quotaPolicyAssignmentService;
    /** 为 {@code MANDATORY} 的租户创建和显式事务用例提供外层事务。 */
    @Autowired
    private TransactionTemplate transactionTemplate;

    /** 本用例创建的租户。 */
    private final Set<UUID> tenantIds = new LinkedHashSet<>();
    /** 本用例创建的账号，必须先移除成员关系才能删。 */
    private final Set<UUID> accountIds = new LinkedHashSet<>();
    /** 本用例创建的项目，必须在删除账号前回收。 */
    private final Set<UUID> projectIds = new LinkedHashSet<>();

    /** 按外键依赖顺序回收共享容器夹具，最后清线程范围。 */
    @AfterEach
    void cleanUp() {
        try {
            for (UUID projectId : projectIds) {
                jdbcTemplate.update("DELETE FROM sys_project_member WHERE project_id = ?", projectId);
                jdbcTemplate.update("DELETE FROM sys_project WHERE id = ?", projectId);
            }
            for (UUID tenantId : tenantIds) {
                jdbcTemplate.update("DELETE FROM sys_tenant_subscription WHERE tenant_id = ?", tenantId);
                jdbcTemplate.update("DELETE FROM sys_tenant_member WHERE tenant_id = ?", tenantId);
            }
            for (UUID accountId : accountIds) {
                jdbcTemplate.update("DELETE FROM sys_account WHERE id = ?", accountId);
            }
            for (UUID tenantId : tenantIds) {
                jdbcTemplate.update("DELETE FROM sys_tenant WHERE id = ?", tenantId);
            }
        } finally {
            tenantIds.clear();
            accountIds.clear();
            projectIds.clear();
            TenantContext.clear();
        }
    }

    /** FREE 租户第一个自有项目成功，第二个以冻结码 50020 拒绝且不落库。 */
    @Test
    void freeTenantAllowsFirstOwnedProjectAndRefusesSecondWithFrozenCode() {
        UUID tenantId = createFreeTenant("S14-2b 免费限额租户");
        UUID accountId = createAccountAndMembership(tenantId, "s14-2b-free");

        ProjectMembership first = createOwnedProject(tenantId, accountId, "第一个项目");
        assertThat(first.project().tenantId()).isEqualTo(tenantId);

        BusinessException refusal = catchThrowableOfType(
                () -> createOwnedProject(tenantId, accountId, "第二个项目"), BusinessException.class);
        assertThat(refusal).isNotNull();
        assertThat(refusal.errorCode()).isEqualTo(ProjectErrorCode.PROJECT_QUOTA_EXCEEDED);
        assertThat(refusal.errorCode().code()).isEqualTo(50020);
        assertThat(refusal.errorCode().httpStatus()).isEqualTo(429);
        assertThat(ownedProjectCount(tenantId))
                .as("被拒的创建不能留下项目行")
                .isEqualTo(1);
    }

    /**
     * 只作为他人项目外部协作者的账号不占自己租户名额。
     *
     * <p>先把 A 邀请进 B 的项目，再让 A 建自己的第一个项目：若守卫错误地按「参与项目数」计数，
     * A 会在这一步被拒。第二个自有项目才应被拒。
     */
    @Test
    void externalCollaboratorMembershipDoesNotConsumeOwnedProjectSlot() {
        UUID tenantA = createFreeTenant("S14-2b 协作方租户");
        UUID accountA = createAccountAndMembership(tenantA, "s14-2b-collab-a");
        UUID tenantB = createFreeTenant("S14-2b 项目主租户");
        UUID accountB = createAccountAndMembership(tenantB, "s14-2b-collab-b");

        UUID projectB = createOwnedProject(tenantB, accountB, "B 的自有项目").project().id();
        projectIds.add(projectB);
        jdbcTemplate.update("""
                INSERT INTO sys_project_member (id, project_id, account_id, role)
                VALUES (?, ?, ?, 'VIEWER')
                """, Uuid7.generate(), projectB, accountA);

        createOwnedProject(tenantA, accountA, "A 的第一个自有项目");

        BusinessException refusal = catchThrowableOfType(
                () -> createOwnedProject(tenantA, accountA, "A 的第二个自有项目"), BusinessException.class);
        assertThat(refusal).isNotNull();
        assertThat(refusal.errorCode()).isEqualTo(ProjectErrorCode.PROJECT_QUOTA_EXCEEDED);
        assertThat(ownedProjectCount(tenantA))
                .as("外部协作成员关系不能消耗 A 租户的自有项目名额")
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM sys_project_member WHERE account_id = ?
                """, Integer.class, accountA))
                .as("A 仍是 B 项目的外部协作者，只是不计入自有项目数")
                .isEqualTo(2);
    }

    /** 测试专用切到 PLAN_R1_STANDARD（projects_max = 5）后，FREE 的 1 个上限不再拦住第二个自有项目。 */
    @Test
    void paidTemplateAssignmentAllowsMoreThanOneOwnedProject() {
        UUID tenantId = createFreeTenant("S14-2b 付费模板租户");
        UUID accountId = createAccountAndMembership(tenantId, "s14-2b-paid");

        UUID standardPolicyId = jdbcTemplate.queryForObject(
                "SELECT id FROM sys_quota_policy WHERE code = 'PLAN_R1_STANDARD'", UUID.class);
        long assignmentVersion = jdbcTemplate.queryForObject(
                "SELECT quota_policy_assignment_version FROM sys_tenant WHERE id = ?", Long.class, tenantId);
        transactionTemplate.executeWithoutResult(status ->
                quotaPolicyAssignmentService.assign(tenantId, standardPolicyId, assignmentVersion));

        createOwnedProject(tenantId, accountId, "付费第一个项目");
        createOwnedProject(tenantId, accountId, "付费第二个项目");

        assertThat(ownedProjectCount(tenantId)).isEqualTo(2);
    }

    /** 同一 FREE 租户的两个并发创建恰好成功一个，另一个以 50020 拒绝。 */
    @Test
    void concurrentCreationsForSameFreeTenantYieldExactlyOneProject() throws Exception {
        UUID tenantId = createFreeTenant("S14-2b 并发租户");
        UUID accountId = createAccountAndMembership(tenantId, "s14-2b-race");
        TenantScope scope = new TenantScope(tenantId, null, accountId);

        int attempts = 2;
        CyclicBarrier barrier = new CyclicBarrier(attempts);
        ExecutorService executor = Executors.newFixedThreadPool(attempts);
        List<Future<Object>> results = new ArrayList<>();
        try {
            for (int index = 0; index < attempts; index++) {
                int sequence = index;
                results.add(executor.submit(() -> {
                    TenantContext.set(scope);
                    try {
                        barrier.await(15, TimeUnit.SECONDS);
                        // 拒绝必须让本事务回滚后再捕获：在事务回调内吞掉异常会把事务标成
                        // rollback-only，提交时变成 UnexpectedRollbackException，掩盖真实业务码。
                        return transactionTemplate.execute(
                                status -> projectService.create("并发项目-" + sequence, "sh-1", null));
                    } catch (BusinessException exception) {
                        return exception;
                    } finally {
                        TenantContext.clear();
                    }
                }));
            }
            long created = 0;
            long refused = 0;
            for (Future<Object> result : results) {
                Object outcome = result.get(30, TimeUnit.SECONDS);
                if (outcome instanceof ProjectMembership membership) {
                    created++;
                    projectIds.add(membership.project().id());
                } else if (outcome instanceof BusinessException exception
                        && exception.errorCode() == ProjectErrorCode.PROJECT_QUOTA_EXCEEDED) {
                    refused++;
                }
            }
            assertThat(created).as("并发创建必须恰好成功一个").isEqualTo(1);
            assertThat(refused).as("另一个必须拿到冻结的 50020，而不是数据库异常").isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }

        assertThat(ownedProjectCount(tenantId))
                .as("租户锁下计数→创建必须原子，数据库里只能有一个自有项目")
                .isEqualTo(1);
    }

    /** 缺产品投影拒绝新增，既有项目保留；绑定合法模板后恢复创建。 */
    @Test
    void legacyPolicyCannotExpandAndValidAssignmentRestoresCreation() {
        UUID tenant = createFreeTenant("R1 legacy");
        UUID account = createAccountAndMembership(tenant, "r1-legacy");
        createOwnedProject(tenant, account, "既有项目");
        assignPolicy(tenant, "FREE");
        BusinessException failure = catchThrowableOfType(
                () -> createOwnedProject(tenant, account, "未知额度项目"), BusinessException.class);
        assertThat(failure).isNotNull();
        assertThat(failure.errorCode().code()).isEqualTo(50047);
        assertThat(failure.errorCode().httpStatus()).isEqualTo(503);
        assertThat(ownedProjectCount(tenant)).isEqualTo(1);
        assignPolicy(tenant, "PLAN_R1_STANDARD");
        createOwnedProject(tenant, account, "恢复项目");
        assertThat(ownedProjectCount(tenant)).isEqualTo(2);
    }

    private void assignPolicy(UUID tenant, String code) {
        UUID policy = jdbcTemplate.queryForObject("SELECT id FROM sys_quota_policy WHERE code=?", UUID.class, code);
        long version = jdbcTemplate.queryForObject("SELECT quota_policy_assignment_version FROM sys_tenant WHERE id=?", Long.class, tenant);
        transactionTemplate.executeWithoutResult(status -> quotaPolicyAssignmentService.assign(tenant, policy, version));
    }

    /**
     * 走真实租户创建入口，拿到 S14-2a 的默认 FREE 订阅与 PLAN_R1_FREE 绑定。
     *
     * @param label 租户名
     * @return 新租户 ID
     */
    private UUID createFreeTenant(String label) {
        UUID tenantId = transactionTemplate.execute(status -> tenantProvisioning.createTenant(label));
        assertThat(tenantId).isNotNull();
        tenantIds.add(tenantId);
        return tenantId;
    }

    /**
     * 建一个已验证账号并加入租户；配额守卫只依赖账号身份，不驱动登录链路。
     *
     * @param tenantId 归属租户
     * @param prefix 邮箱前缀
     * @return 账号 ID
     */
    private UUID createAccountAndMembership(UUID tenantId, String prefix) {
        UUID accountId = Uuid7.generate();
        jdbcTemplate.update("""
                INSERT INTO sys_account (id, email, password_hash, display_name)
                VALUES (?, ?, '{noop}unused', 'S14-2b 配额账号')
                """, accountId, prefix + "-" + accountId + "@example.com");
        jdbcTemplate.update("INSERT INTO sys_tenant_member (id, tenant_id, account_id) VALUES (?, ?, ?)",
                Uuid7.generate(), tenantId, accountId);
        accountIds.add(accountId);
        return accountId;
    }

    /**
     * 在独立事务里以指定租户身份创建自有项目。
     *
     * @param tenantId 项目归属租户
     * @param accountId 创建者账号
     * @param name 项目名
     * @return 创建结果
     */
    private ProjectMembership createOwnedProject(UUID tenantId, UUID accountId, String name) {
        TenantContext.set(new TenantScope(tenantId, null, accountId));
        try {
            ProjectMembership membership = transactionTemplate.execute(
                    status -> projectService.create(name, "sh-1", null));
            assertThat(membership).isNotNull();
            projectIds.add(membership.project().id());
            return membership;
        } finally {
            TenantContext.clear();
        }
    }

    /** @param tenantId 归属租户 @return 未软删的自有项目数 */
    private int ownedProjectCount(UUID tenantId) {
        return jdbcTemplate.queryForObject("""
                SELECT count(*) FROM sys_project WHERE tenant_id = ? AND deleted_at IS NULL
                """, Integer.class, tenantId);
    }
}
