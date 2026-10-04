package com.things.link.ingestion.application;

import java.security.Principal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 经 WebSocket 握手 JWT 校验后固定的控制台身份范围。
 *
 * <p>浏览器连接建立后不再携带 HTTP SecurityContext；订阅消息处理必须使用本投影恢复
 * {@code TenantContext}，否则 WebSocket 工作线程会在无 RLS 范围下访问项目数据。</p>
 *
 * @param accountId 控制台账号 ID
 * @param tenantId 当前租户 ID
 * @param projectId 当前令牌选择的项目 ID，未选择项目时握手直接拒绝
 * @param projectGeneration JWT签发时冻结的项目生命周期代次；旧令牌缺失声明时为0
 * @param expiresAt JWT 的绝对失效时刻，发送门不得在此后继续出队属性帧
 */
public record RealtimePrincipal(UUID accountId, UUID tenantId, UUID projectId, long projectGeneration,
                                Instant expiresAt) implements Principal {

    /** 身份与失效时刻都必须来自已验证JWT，缺失字段不能降级为无限连接。 */
    public RealtimePrincipal {
        Objects.requireNonNull(accountId, "实时账号身份不能为空");
        Objects.requireNonNull(tenantId, "实时租户身份不能为空");
        Objects.requireNonNull(projectId, "实时项目身份不能为空");
        if (projectGeneration < 0) {
            throw new IllegalArgumentException("实时项目生命周期代次不能为负数");
        }
        Objects.requireNonNull(expiresAt, "实时JWT失效时刻不能为空");
    }

    /**
     * 兼容ADR0073落地前的调用点；旧令牌只在尚未经历删除的0代项目继续有效。
     * @param accountId 控制台账号ID
     * @param tenantId 当前租户ID
     * @param projectId 当前项目ID
     * @param expiresAt JWT绝对失效时刻
     */
    public RealtimePrincipal(UUID accountId, UUID tenantId, UUID projectId, Instant expiresAt) {
        this(accountId, tenantId, projectId, 0L, expiresAt);
    }

    /**
     * @param now 当前UTC时刻
     * @return JWT是否已经到期；等于截止时刻即视为失效
     */
    public boolean expiredAt(Instant now) {
        return !Objects.requireNonNull(now, "当前时刻不能为空").isBefore(expiresAt);
    }

    /**
     * Spring WebSocket 的 Principal 名称只作连接诊断，不能替代完整账号 ID 字段。
     *
     * @return 稳定账号标识
     */
    @Override
    public String getName() {
        return accountId.toString();
    }
}
