package com.things.link.project.application;

import com.things.link.project.domain.TenantRepository;
import com.things.link.shared.id.Uuid7;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * 开租户。
 *
 * <p>本类是 project 模块<b>对 iam 暴露的唯一入口</b>（架构文档 10.4 规则 2：
 * 跨模块只能引用对方 application 包下的 public 类型）。
 *
 * <h2>为什么 iam 不直接 INSERT INTO sys_tenant</h2>
 * 那样等于 iam 知道了 project 的表结构。租户表将来会长出套餐、配额、区域、
 * 计费状态这些列，每加一样，「谁负责在创建时把它填对」就多一处要同步的地方 ——
 * 而漏填的症状通常是几个月后某个配额检查读到 null。
 *
 * <p>入口收在这里之后，project 模块可以单方面改表结构，iam 只依赖这个方法签名。
 * {@code ArchitectureRulesTests} 的规则 2/3 会在有人绕过它时让构建失败。
 *
 * <p>S14-2a 起这里也是「新租户默认 FREE」的唯一落点（架构文档 §3.2）：租户行落库后同一个事务
 * 创建 FREE 零价订阅并把有效策略指针绑到 {@code PLAN_R1_FREE}。把它放在这里而不是 iam，
 * 是为了让「谁负责在创建时把套餐相关事实填对」继续只有一个答案。
 */
@Service
public class TenantProvisioning {

    private final TenantRepository tenantRepository;
    private final TenantSubscriptionProvisioning tenantSubscriptionProvisioning;

    /**
     * @param tenantRepository 租户持久化端口
     * @param tenantSubscriptionProvisioning 新租户的 FREE 订阅与有效策略绑定
     */
    public TenantProvisioning(TenantRepository tenantRepository,
                              TenantSubscriptionProvisioning tenantSubscriptionProvisioning) {
        this.tenantRepository = tenantRepository;
        this.tenantSubscriptionProvisioning = tenantSubscriptionProvisioning;
    }

    /**
     * 创建一个租户，并在同一事务里补齐 FREE 订阅与有效策略指针。
     *
     * <p><b>不自己开事务。</b>注册流程要求「租户 + 账号 + 成员关系」三者原子成立
     * （ADR 0008），因此本方法必须并入调用方的事务：任何一步失败都要整体回滚，
     * 否则会留下没有归属账号的孤儿租户 —— 那种数据没有任何人能进入，也没有入口能删。
     * FREE 订阅与策略绑定同样计入这一个事务（架构文档 §3.2），要么三样都在，要么一样都没有。
     *
     * <p>{@code MANDATORY} 而不是默认的 {@code REQUIRED}：后者在调用方忘了开事务时
     * 会静默地自己开一个，于是「原子性」在没人察觉的情况下消失。用 MANDATORY 的话，
     * 没有外层事务会直接抛异常。
     *
     * @param name 租户名称
     * @return 新租户 ID
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public UUID createTenant(String name) {
        UUID id = Uuid7.generate();
        tenantRepository.create(id, name);
        tenantSubscriptionProvisioning.provisionFreeSubscription(id);
        return id;
    }

}
