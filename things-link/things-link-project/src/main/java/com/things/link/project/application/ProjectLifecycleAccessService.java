package com.things.link.project.application;

import com.things.link.project.domain.Project;
import com.things.link.project.domain.ProjectRepository;
import com.things.link.project.domain.ProjectErrorCode;
import com.things.link.shared.error.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.UUID;
import java.util.OptionalLong;

/**
 * ADR0064：向已确权调用方提供生命周期快照与原业务事务内的项目写许可。
 * tenant匹配只核验项目归属，不能代替成员、App角色或设备身份授权；控制台跨租户协作须先取得项目真实归属。
 */
@Service
public class ProjectLifecycleAccessService {

    /**
     * 控制面明确写路径的早期只读分类，不授予持续写许可；原业务事务必须再次加锁。
     * @param accountId 已认证账号
     * @param projectId 已选择项目
     */
    @Transactional(readOnly = true)
    public void requireWritableAccountSnapshot(UUID accountId, UUID projectId) {
        ProjectAccessPolicy policy = tokenSnapshot(accountId, projectId);
        if (!policy.writeAllowed()) throw new BusinessException(policy.readAllowed()
                ? ProjectErrorCode.PROJECT_READ_ONLY : ProjectErrorCode.PROJECT_NOT_FOUND);
    }

    /** 缺失、错配和删除共享拒绝值，调用方不能据此枚举项目。 */
    private static final ProjectAccessPolicy DENIED = new ProjectAccessPolicy(false, false, -1L);
    /** project域自己读取生命周期事实，不要求业务模块跨域查表。 */
    private final ProjectRepository repository;

    /** @param repository 项目权威事实与事务行锁仓储 */
    public ProjectLifecycleAccessService(ProjectRepository repository) {
        this.repository = repository;
    }

    /**
     * 只观察当前快照；已通过此检查的读请求可能与随后删除重叠，不能把返回值当作持锁凭据。
     * @param tenantId 已确权的项目归属租户，不能使用跨租户协作者自己的租户代替
     * @param projectId 已确权项目
     * @return 继续原授权的读写资格；数据库异常原样传播，不伪装业务拒绝
     */
    @Transactional(readOnly = true)
    public ProjectAccessPolicy snapshot(UUID tenantId, UUID projectId) {
        requireIdentity(tenantId, projectId);
        return repository.findLiveByIdentity(tenantId, projectId)
                .filter(project -> tenantId.equals(project.tenantId()) && projectId.equals(project.id()))
                // 普通业务快照保持既有不可见投影；只有账号约束的tokenSnapshot可观察删除代次。
                .map(project -> project.status() == Project.Status.ACTIVE
                        ? new ProjectAccessPolicy(true, true, project.lifecycleGeneration())
                        : project.status() == Project.Status.ARCHIVED
                            ? new ProjectAccessPolicy(true, false, project.lifecycleGeneration())
                            : DENIED)
                .orElse(DENIED);
    }

    /**
     * 按已验签账号读取项目令牌所需的当前代次；成员关系是本豁免RLS查询不可省略的隔离条件。
     * 删除项目仍返回不可读策略与真实代次，使认证入口能永久拒绝删除前令牌而不泄露项目状态。
     * @param accountId 已验签控制台账号
     * @param projectId 令牌声明中的项目
     * @return 当前项目读写资格与代次；非成员或不存在返回统一拒绝策略
     */
    @Transactional(readOnly = true)
    public ProjectAccessPolicy tokenSnapshot(UUID accountId, UUID projectId) {
        requireIdentity(accountId, projectId);
        return repository.findForProjectToken(accountId, projectId)
                .filter(project -> projectId.equals(project.id()))
                .map(project -> new ProjectAccessPolicy(
                        project.status() == Project.Status.ACTIVE || project.status() == Project.Status.ARCHIVED,
                        project.status() == Project.Status.ACTIVE,
                        project.lifecycleGeneration()))
                .orElse(DENIED);
    }

    /**
     * ADR0073：凭据签发事务按账号成员关系读取ACTIVE项目代次，并持有SHARE锁阻塞并发删除。
     * 返回空只表示确定不可签发，数据库异常原样传播，调用域负责映射自己的无效凭据错误。
     * @param accountId 已完成控制台账号认证的账号
     * @param projectId 将写入访问或刷新凭据的项目
     * @return 当前代次；非成员、归档、删除或不存在时为空
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public OptionalLong lockActiveGenerationForProjectToken(UUID accountId, UUID projectId) {
        requireIdentity(accountId, projectId);
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("项目凭据签发必须加入已有非只读事务");
        }
        return repository.lockActiveGenerationForProjectToken(accountId, projectId);
    }

    /**
     * ADR0064决策2：SHARE锁必须持续到原业务事务结束，不能自己开一个很快释放的资格事务。
     * 使用原JDBC语句/外层事务预算；实际连接只接受READ COMMITTED或PG等价READ UNCOMMITTED，
     * 不能通过注解覆盖已经开始的事务timeout、readOnly或隔离级。
     * @param tenantId 已确权项目归属租户
     * @param projectId 已确权项目
     * @return 是否持有当前ACTIVE项目的写许可；false仅可拒绝，后续快照不能替代失败许可放行
     * @throws IllegalStateException 缺少非只读事务或实际隔离级不支持锁后新快照
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean lockActiveForWrite(UUID tenantId, UUID projectId) {
        requireWriteTransaction(tenantId, projectId);
        return repository.lockActiveForWrite(tenantId, projectId);
    }

    /**
     * ADR0073决策1：在原业务事务中同时锁定ACTIVE项目并比较凭据签发时的生命周期代次。
     * @param tenantId 已确权项目归属租户
     * @param projectId 已确权项目
     * @param expectedGeneration 凭据签发时冻结的非负代次
     * @return 是否持有代次一致的ACTIVE项目写许可
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean lockActiveForWrite(UUID tenantId, UUID projectId, long expectedGeneration) {
        requireWriteTransaction(tenantId, projectId);
        if (expectedGeneration < 0) {
            throw new IllegalArgumentException("项目生命周期代次不能为负数");
        }
        return repository.lockActiveForWrite(tenantId, projectId, expectedGeneration);
    }

    /** ADR0177：读事件受理与生命周期清理串行，不授予用户访问权或业务写资格。 */
    @Transactional(propagation=Propagation.MANDATORY)
    public OptionalLong lockReadableGeneration(UUID tenantId,UUID projectId){
        requireWriteTransaction(tenantId,projectId);return repository.lockReadableGeneration(tenantId,projectId);
    }

    /**
     * ADR0067决策1：供已完成原授权的控制面调用方取得持续写许可，并由project域保留拒绝码归属。
     * @param tenantId 持久业务事实中的项目归属租户，不能替换为跨租户协作者自己的租户
     * @param projectId 已经完成原权限校验的项目
     * @throws BusinessException 归档只读为50017，其余确定拒绝为50001；分类快照不能恢复失败许可
     * @throws IllegalStateException 缺少原非只读事务或实际隔离级不支持锁后新快照
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void requireActiveForWrite(UUID tenantId, UUID projectId) {
        // 复用原方法的身份/实际事务防御；同类调用不依赖代理新建事务或缩短持锁范围。
        if (lockActiveForWrite(tenantId, projectId)) return;
        ProjectAccessPolicy policy = snapshot(tenantId, projectId);
        // 许可false已决定拒绝；并发恢复ACTIVE只影响分类，不能重新放行业务。
        throw new BusinessException(policy.readAllowed() && !policy.writeAllowed()
                ? ProjectErrorCode.PROJECT_READ_ONLY : ProjectErrorCode.PROJECT_NOT_FOUND);
    }

    /**
     * 在原业务事务中取得带代次的项目写许可；代次不符统一按项目不存在拒绝，避免泄露恢复状态。
     * @param tenantId 持久业务事实中的项目归属租户
     * @param projectId 已完成原授权的项目
     * @param expectedGeneration 凭据签发时冻结的非负代次
     * @throws BusinessException 代次不符、删除或错配为50001；同代次归档为50017
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void requireActiveForWrite(UUID tenantId, UUID projectId, long expectedGeneration) {
        if (lockActiveForWrite(tenantId, projectId, expectedGeneration)) return;
        ProjectAccessPolicy policy = snapshot(tenantId, projectId);
        if (!policy.matchesGeneration(expectedGeneration)) {
            throw new BusinessException(ProjectErrorCode.PROJECT_NOT_FOUND);
        }
        throw new BusinessException(policy.readAllowed() && !policy.writeAllowed()
                ? ProjectErrorCode.PROJECT_READ_ONLY : ProjectErrorCode.PROJECT_NOT_FOUND);
    }

    /** 统一校验身份与原非只读事务，避免带代次与兼容入口出现不同锁持有边界。 */
    private static void requireWriteTransaction(UUID tenantId, UUID projectId) {
        requireIdentity(tenantId, projectId);
        // 显式检查也防止绕过代理直调时静默使用auto-commit而立即丢失行锁。
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("项目写许可必须加入已有非只读事务");
        }
    }

    /** 缺失身份属于编程前置错误，不能发SQL或由环境ThreadLocal补齐。 */
    private static void requireIdentity(UUID tenantId, UUID projectId) {
        if (tenantId == null || projectId == null) {
            throw new IllegalArgumentException("项目生命周期查询必须提供租户与项目身份");
        }
    }
}
