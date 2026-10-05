package com.things.link.assistant.domain;

import java.util.UUID;

/** 显式范围的短期槽位，SQL只操作assistant自有表。 */
public interface AnalysisSlotRepository {
    /** 准入结果：已占用槽、并发饱和或状态校验拒绝。 */
    enum Admission { ADMITTED, BUSY, REJECTED }
    /**
     * 原子获取用户与项目共享槽，不延长原始运行期限。
     * @param call 已重新鉴权且有效的调用记录
     * @param token 本次派发生成的槽令牌
     * @return 固定准入分类
     */
    Admission acquire(AnalysisCall call, UUID token);
    /**
     * 按槽令牌认领唯一执行机会，重复调用不得再次消费。
     * @param call 当前身份范围内的已派发记录
     * @param token 原始派发槽令牌
     * @return 首次执行认领成功时为真
     */
    boolean claim(AnalysisCall call, UUID token);
    /**
     * 查询是否已有持久执行认领事实，不代表供应商请求成功。
     * @param call 当前身份范围内的调用记录
     * @return 存在执行认领事实时为真
     */
    boolean wasClaimed(AnalysisCall call);
    /**
     * 仅供确认本地传输停止后的可信执行器释放槽。
     * @param call 当前身份范围内的调用记录
     * @param token 原始派发槽令牌
     * @return 匹配槽成功释放时为真
     */
    boolean release(AnalysisCall call, UUID token);
    /**
     * 清理指定隔离范围的过期槽，不续期或返还付费执行机会。
     * @param tenant 归属租户
     * @param project 当前项目
     * @return 实际清理数量
     */
    int expire(UUID tenant, UUID project);
}
