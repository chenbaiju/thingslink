package com.things.link.support.audit;

import com.things.link.shared.page.CursorPage;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 项目审计只读查询服务。
 *
 * <p><b>调用方必须先完成项目范围授权</b>：本端口只保证「不会越过传入的
 * tenantId+projectId 读取」，它不知道当前账号是否属于该项目，也不做任何角色判定。
 * 授权刻意留在各业务模块的 HTTP 边界完成，是为了让审计读取复用那个模块既有的
 * 权限语义（例如 OTA 的 {@code ota:deploy}），而不是在 support 里另起一套判定；
 * 两套判定一旦分叉，症状就是「按钮藏起来了但接口照样放行」。
 *
 * <p>只读事务与 5 秒超时：项目历史很长时时间线会命中大量行，长事务会占住数据面
 * 连接；readOnly 也让 PostgreSQL 可以选择只读执行路径。
 */
@Service
@Transactional(readOnly = true, timeout = 5)
public class AuditQueryService {

    /** 只读仓储。 */
    private final AuditQueryRepository repository;

    /**
     * 注入只读仓储。
     *
     * @param repository 审计只读仓储
     */
    public AuditQueryService(AuditQueryRepository repository) {
        this.repository = repository;
    }

    /**
     * 读取项目审计时间线，最新在前。
     *
     * @param tenantId     项目真实归属租户，必填
     * @param projectId    项目 ID，必填
     * @param actionPrefix 动作前缀，可为空；按字面字符匹配
     * @param action       精确动作编码，可为空
     * @param cursor       游标，可为空表示首页
     * @param limit        每页条数，必须落在 1..100
     * @return 游标分页结果
     */
    public CursorPage<AuditQueryRepository.Record> page(UUID tenantId, UUID projectId, String actionPrefix,
            String action, String cursor, int limit) {
        return repository.page(tenantId, projectId, actionPrefix, action, cursor, limit);
    }
}
