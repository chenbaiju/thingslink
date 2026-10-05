package com.things.link.assistant.domain;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import com.things.link.assistant.domain.ProbeLedger.*;

/** 探针批次及机会持久端口；调用方须先鉴权并建立事务级行隔离范围。 */
public interface ProbeLedgerRepository {
    /**
     * 建立或读取授权绑定的批次；已有批次不被覆盖或重置。
     * @param candidate 服务端构造的候选批次，含项目、授权及配置版本
     * @return 当前隔离范围内对应批次；不可见时为空
     */
    Optional<Batch> ensure(Batch candidate);
    /**
     * 原子认领批次内的样本序号，重复认领不新增机会。
     * @param candidate 已鉴权的候选机会，包含创建者及原始期限
     * @return 首次成功插入机会时为真
     */
    boolean claim(Attempt candidate);
    /**
     * 按完整身份范围读取机会，不允许跨创建者或跨项目查询。
     * @param tenant 已鉴权的归属租户
     * @param project 已鉴权的当前项目
     * @param creator 当前调用者账号
     * @param id 探针机会标识
     * @return 匹配的持久机会；不可见或不存在时为空
     */
    Optional<Attempt> find(UUID tenant, UUID project, UUID creator, UUID id);
    /**
     * 仅从认领状态原子推进终态；调用方负责校验期限及目标状态。
     * @param original 已在当前身份范围内读取的机会
     * @param target 已校验的目标终态
     * @param at 服务端记录的完成时间
     * @return 当前认领记录成功更新时为真
     */
    boolean finish(Attempt original, Status target, Instant at);
}
