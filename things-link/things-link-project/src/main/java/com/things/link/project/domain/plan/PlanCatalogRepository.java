package com.things.link.project.domain.plan;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 套餐目录的持久化端口。
 *
 * <p>实现位于 {@code infrastructure}；接口只暴露读取与「幂等补齐、从不改写」的写入，
 * 不提供任何 UPDATE 语义 —— 已发布的修订版必须新建 {@code revisionNo}，不得原地修改。
 */
public interface PlanCatalogRepository {

    /**
     * 读取指定产品修订版的全部套餐快照（播种校验用）。
     *
     * @param productRevision 产品修订版标识，如 {@code product-revision-1}
     * @return 按展示顺序排列的四个档位快照；没有匹配修订版时返回空列表
     */
    List<PlanCatalogEntry> findByProductRevision(String productRevision);

    /**
     * 读取某时刻生效的平台目录：每个套餐取生效区间内修订序号最高的那一版。
     *
     * <p>有效区间为 {@code [validFrom, validUntil)}；{@code validUntil} 为 {@code null}
     * 表示未设失效时刻。
     *
     * @param at 目录生效判定时刻（UTC）
     * @return 按展示顺序排列的当前档位快照
     */
    List<PlanCatalogEntry> findEffectiveCatalog(Instant at);

    /**
     * 读取某时刻生效的单个套餐快照：该套餐取生效区间内修订序号最高的那一版。
     *
     * <p>语义与 {@link #findEffectiveCatalog(Instant)} 完全一致，只是按稳定编码收窄一行；
     * 平台全局目录没有租户列，因此不做任何租户过滤，也不会因为调用者不属于某项目而隐藏档位。
     *
     * @param planCode 稳定套餐编码，如 {@code STANDARD}
     * @param at 目录生效判定时刻（UTC）
     * @return 该套餐当前生效快照；没有匹配编码或编码当前无生效修订版时为空
     */
    Optional<PlanCatalogEntry> findEffectiveByPlanCode(String planCode, Instant at);

    /**
     * 取得目录播种的事务级排他锁，串行化同一产品修订版的并发播种。
     *
     * <p>调用方必须处于事务中，否则锁会在语句结束时释放、失去意义。
     *
     * @param productRevision 产品修订版标识
     */
    void lockSeed(String productRevision);

    /**
     * 幂等补齐全套套餐修订版：已存在的行一律不改写，已有修订版也不补插权益/维度。
     *
     * <p>这样重复调用不会静默改值；真正的漂移由播种后的校验显式失败暴露。
     *
     * @param definition 冻结定义
     * @param validFrom 新修订版的生效时刻（UTC）
     * @return 本次实际新建了修订版时为 {@code true}；已存在时为 {@code false}
     */
    boolean insertIfAbsent(PlanDefinition definition, Instant validFrom);
}
