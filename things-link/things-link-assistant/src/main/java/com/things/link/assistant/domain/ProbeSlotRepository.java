package com.things.link.assistant.domain;

import com.things.link.assistant.domain.ProbeLedger.Attempt;
import java.util.UUID;
/** 探针共享并发槽端口；与普通分析共同受用户及项目并发限制。 */
public interface ProbeSlotRepository {
    /**
     * 在原始机会期限内获取共享槽，不重新认领或延长付费机会。
     * @param attempt 已鉴权且仍有效的持久机会
     * @param token 本次派发生成的槽令牌，释放时须原样匹配
     * @return 数据库固定准入分类码，由应用层映射为许可或拒绝
     */
    String acquire(Attempt attempt,UUID token);
    /**
     * 按机会身份及令牌释放匹配槽，不返还已消耗次数。
     * @param attempt 已在当前项目及创建者范围内重新读取的机会
     * @param token 原始派发槽令牌
     * @return 匹配槽成功释放时为真
     */
    boolean release(Attempt attempt,UUID token);
}
