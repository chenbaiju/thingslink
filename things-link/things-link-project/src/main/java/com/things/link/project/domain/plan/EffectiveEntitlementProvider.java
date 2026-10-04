package com.things.link.project.domain.plan;

import java.util.UUID;

/**
 * project内部读取目录功能声明的端口（S14-1b，架构文档 §3.3）。
 *
 * <p>ADR0163：当前没有运行门禁调用方；D-183仍为开卖前置，不得把本投影当作已实施授权。
 * 目录/摘要调用方只依赖 capability code 与 {@link EffectiveEntitlement}，不得查询订单、订阅或
 * {@code sys_plan} 编码表；缓存与失效协议复用 S7 的模板版本事件，不另造第二套版本方案。
 */
public interface EffectiveEntitlementProvider {

    /**
     * 按产品修订版 ID 解析权益投影。
     *
     * @param planRevisionId 产品修订版 ID
     * @return 该修订版的完整权益闭集，{@code DISABLED} 语义原样保留
     * @throws IllegalArgumentException 修订版不存在或未绑定配额模板
     */
    EffectiveEntitlement resolvePlanRevision(UUID planRevisionId);

    /**
     * 按产品修订版标识与档位编码解析权益投影。
     *
     * @param revisionCode 产品修订版标识，如 {@code product-revision-1}
     * @param planCode 档位编码，如 {@code FREE}
     * @return 该修订版的完整权益闭集
     * @throws IllegalArgumentException 没有匹配修订版
     */
    EffectiveEntitlement resolvePlanRevision(String revisionCode, String planCode);
}
