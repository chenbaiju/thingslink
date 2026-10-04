package com.things.link.support.audit;

import com.things.link.shared.page.CursorPage;
import java.time.Instant;
import java.util.UUID;

/**
 * 项目审计事实的只读游标读取端口。
 *
 * <p>为什么在写入端口之外单独开一个读端口，而不是让业务模块自己查
 * {@code sys_audit_log}：该表刻意不启用 RLS（见 {@code V20260801_1320__audit_log.sql}
 * 的注释），数据库在这里不会替调用方兜底越权。把「必须同时给出 tenant 与 project」
 * 收进唯一入口后，调用方只能按这两个维度读取，无法顺手写一条更宽的 WHERE。
 *
 * <p>本端口只按给定的 tenant+project 收窄，<b>不做任何角色或成员资格判定</b>；
 * 授权由调用方在自己的 HTTP 边界完成。
 */
public interface AuditQueryRepository {

    /**
     * 读取项目审计时间线，最新在前。
     *
     * @param tenantId     项目真实归属租户，必填；为空时 fail-closed
     * @param projectId    项目 ID，必填；为空时 fail-closed
     * @param actionPrefix 动作前缀，可为空；按字面字符做前缀匹配，不作为 LIKE 模式解释
     * @param action       精确动作编码，可为空；与前缀同时给出时两个条件同时生效
     * @param cursor       游标，可为空表示首页；载荷绑定项目身份
     * @param limit        每页条数，必须落在 1..100
     * @return 游标分页结果
     * @throws com.things.link.shared.error.BusinessException 范围为空、条数越界或游标非法时
     */
    CursorPage<Record> page(UUID tenantId, UUID projectId, String actionPrefix, String action, String cursor,
            int limit);

    /**
     * 一条审计只读事实。
     *
     * <p>{@code details} 保持数据库里的原始 jsonb 文本：仓储层不解析 JSON，
     * 是为了让「按行解析」这类开销留在展现层，并且让解析失败的处理只有一处。
     *
     * @param id             审计 ID
     * @param tenantId       归属租户
     * @param projectId      归属项目
     * @param actorAccountId 行为人账号，系统任务可为空
     * @param targetType     目标类型
     * @param targetId       目标 ID，可为空
     * @param action         稳定动作编码
     * @param traceId        链路 ID，可为空
     * @param details        原始 jsonb 文本
     * @param createdAt      记录时刻
     */
    record Record(
            UUID id,
            UUID tenantId,
            UUID projectId,
            UUID actorAccountId,
            String targetType,
            UUID targetId,
            String action,
            String traceId,
            String details,
            Instant createdAt) {
    }
}
