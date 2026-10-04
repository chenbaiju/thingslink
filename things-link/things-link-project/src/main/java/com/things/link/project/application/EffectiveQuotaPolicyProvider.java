package com.things.link.project.application;

import java.util.UUID;

/**
 * 运行时读取有效租户配额策略的跨模块公开端口。
 *
 * <p>数据面传入的是设备确权后得到的可信租户 ID；控制面传入项目 ID 时，本模块自行解析项目所有者租户，
 * 从而避免跨租户协作者错误使用 JWT 自身租户。客户端不得直接提供 tenantId。
 */
public interface EffectiveQuotaPolicyProvider {

    /**
     * 按可信租户 ID 读取有效策略。
     *
     * @param trustedTenantId 已由设备确权、服务端身份或受控项目投影确定的租户 ID
     * @return 有效策略或依赖故障时的有限安全默认
     */
    EffectiveQuotaPolicy resolveTrustedTenant(UUID trustedTenantId);

    /**
     * 按可信项目 ID 读取其所有者租户的有效策略。
     *
     * @param trustedProjectId 已通过成员授权或内部服务验证的项目 ID
     * @return 项目所有者租户的有效策略
     */
    EffectiveQuotaPolicy resolveTrustedProject(UUID trustedProjectId);

    /**
     * 按设备确权服务已解析的租户与项目二元组读取运行时策略。
     *
     * <p>这不是控制台、REST 或 WebSocket 的授权入口；两个参数只能来自
     * {@code DeviceAccessScopeService} 的服务端确权结果。数据库函数同时核对二者真实归属，
     * 因而不会为没有 JWT {@code TenantContext} 的 MQTT 回调伪造账户上下文。
     *
     * @param trustedTenantId 已由设备确权解析的 owner tenant ID
     * @param trustedProjectId 已由同一次设备确权解析的 project ID
     * @return 二元组真实匹配时的有效策略，基础设施故障时为有限安全默认
     */
    EffectiveQuotaPolicy resolveTrustedDeviceProject(UUID trustedTenantId, UUID trustedProjectId);

    /**
     * 按产品修订版解析其配额模板，供有效权益合成使用（S14-1b）。
     *
     * <p>这是 {@code EffectiveQuotaPolicy} 的「模板侧」入口：不涉及租户绑定、订单或价格，
     * 只回答「这个产品修订版的冻结额度是多少」。返回快照的 {@code tenantId} 为 {@code null}、
     * {@code assignmentVersion} 为 {@code 0}，{@code planQuota} 必定非空。
     *
     * <p>默认实现抛出不支持异常，保证既有测试替身无需为新能力补桩；生产实现
     * {@code CachedEffectiveQuotaPolicyProvider} 复用 S7 的 TTL/版本失效协议。
     *
     * @param planRevisionId 产品修订版 ID
     * @return 该修订版的配额模板快照
     * @throws UnsupportedOperationException 提供者不支持按产品修订版解析
     */
    default EffectiveQuotaPolicy resolvePlanRevision(UUID planRevisionId) {
        throw new UnsupportedOperationException("当前配额策略提供者不支持按产品修订版解析");
    }
}
