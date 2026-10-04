package com.things.link.project.domain.plan;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 某个产品修订版的功能权益投影（S14-1b，架构文档 §3.3）。
 *
 * <p>ADR0163：当前本类型只被目录/摘要使用，不能据其布尔值推断运行授权或部署能力。
 * 统一商业能力门禁仍为D-183开卖前置；未来业务入口必须经application公开端口，不能跨域
 * 查询订单或散落套餐编码分支。ENABLED/DISABLED保留修订版包含声明，不携带数值额度。
 *
 * <p>未知 capability code 的 {@link #enabled(String)} 返回 {@code false}（安全默认），
 * 但调用方若需要区分「明确禁用」与「编码不存在」，应使用 {@link #find(String)}，
 * 不能用 {@code enabled == false} 推断额度为 0。
 *
 * @param planRevisionId 产品修订版 ID
 * @param planCode 稳定档位编码，如 {@code FREE}
 * @param revisionCode 产品修订版标识，如 {@code product-revision-1}
 * @param quotaPolicyId 该修订版引用的配额模板 ID；用于把配额模板的失效事件投递到权益缓存
 * @param quotaPolicyVersion 配额模板单调版本；模板更新时凭它判断缓存是否需要回源
 * @param entitlements 该修订版的完整权益闭集，按 capability code 升序
 */
public record EffectiveEntitlement(
        UUID planRevisionId,
        String planCode,
        String revisionCode,
        UUID quotaPolicyId,
        long quotaPolicyVersion,
        List<PlanEntitlement> entitlements) {

    /** 权益闭集不得为空标识、不得有重复编码，也不得携带 null 状态。 */
    public EffectiveEntitlement {
        Objects.requireNonNull(planRevisionId, "产品修订版 ID 不得为空");
        Objects.requireNonNull(planCode, "档位编码不得为空");
        Objects.requireNonNull(revisionCode, "产品修订版标识不得为空");
        Objects.requireNonNull(quotaPolicyId, "配额模板 ID 不得为空");
        if (quotaPolicyVersion <= 0) {
            throw new IllegalArgumentException("配额模板版本必须为正数");
        }
        entitlements = List.copyOf(entitlements);
        Map<String, Long> duplicates = entitlements.stream()
                .collect(Collectors.groupingBy(PlanEntitlement::code, Collectors.counting()));
        duplicates.entrySet().stream().filter(entry -> entry.getValue() > 1).findFirst()
                .ifPresent(entry -> {
                    throw new IllegalArgumentException("权益编码重复: " + entry.getKey());
                });
    }

    /**
     * 判断某能力是否启用。
     *
     * @param capabilityCode 冻结 capability code
     * @return 该编码存在且状态为 {@code ENABLED} 时返回 {@code true}；不存在或 {@code DISABLED} 返回 {@code false}
     */
    public boolean enabled(String capabilityCode) {
        return find(capabilityCode).map(PlanEntitlement::enabled).orElse(false);
    }

    /**
     * 判断某能力是否被明确禁用。
     *
     * @param capabilityCode 冻结 capability code
     * @return 编码存在且状态为 {@code DISABLED} 时返回 {@code true}；编码不存在返回 {@code false}
     */
    public boolean disabled(String capabilityCode) {
        return find(capabilityCode).map(entitlement -> !entitlement.enabled()).orElse(false);
    }

    /**
     * 按编码查找权益状态。
     *
     * @param capabilityCode 冻结 capability code
     * @return 该修订版未声明该编码时返回空
     */
    public Optional<PlanEntitlement> find(String capabilityCode) {
        if (capabilityCode == null) {
            return Optional.empty();
        }
        return entitlements.stream().filter(entitlement -> entitlement.code().equals(capabilityCode))
                .findFirst();
    }

    /**
     * 返回按 capability code 索引的不可变映射，供需要整体遍历的业务入口使用。
     *
     * @return 编码到权益状态的映射，保持列表顺序
     */
    public Map<String, PlanEntitlement> byCode() {
        return entitlements.stream().collect(Collectors.toMap(
                PlanEntitlement::code, Function.identity(), (left, right) -> left, LinkedHashMap::new));
    }
}
