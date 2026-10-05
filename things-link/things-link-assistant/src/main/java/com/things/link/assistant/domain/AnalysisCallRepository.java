package com.things.link.assistant.domain;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** 个人分析调用元数据持久端口；调用方负责当前权限和事务隔离，不保存模型正文。 */
public interface AnalysisCallRepository {
    /**
     * 按完整身份范围及幂等键摘要读取记录，不保存原始键。
     * @param tenant 归属租户
     * @param project 归属项目
     * @param creator 当前调用者账号
     * @param keyHash 规范幂等键的摘要
     * @return 当前调用者可见的匹配记录，不存在时为空
     */
    Optional<AnalysisCall> findByKey(UUID tenant, UUID project, UUID creator, String keyHash);
    /**
     * 读取当前调用者拥有的记录。
     * @param tenant 归属租户
     * @param project 归属项目
     * @param creator 当前调用者账号
     * @param id 调用记录标识
     * @return 匹配记录，不可见或不存在时为空
     */
    Optional<AnalysisCall> find(UUID tenant, UUID project, UUID creator, UUID id);
    /**
     * 插入一次预留元数据，项目、创建者及键摘要重复时拒绝新增。
     * @param call 已校验的预留记录
     * @return 首次成功插入时为真
     */
    boolean insert(AnalysisCall call);
    /**
     * 按原始状态原子推进记录，调用方负责状态机及期限校验。
     * @param call 当前身份范围内读取的原始记录
     * @param target 已校验的目标状态
     * @param now 服务端状态推进时间
     * @return 原始状态仍匹配且更新成功时为真
     */
    boolean transition(AnalysisCall call, AnalysisCall.Status target, Instant now);
    /**
     * 按数据库时间限批删除过保留期记录，不延长幂等键有效期。
     * @param tenant 归属租户
     * @param project 当前清理项目
     * @param limit 本次最大删除数量，实现限制为零至一百
     * @return 实际删除数量
     */
    int deleteExpired(UUID tenant, UUID project, int limit);
}
