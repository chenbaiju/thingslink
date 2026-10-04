package com.things.link.rule.domain;

/** 调试事件事实与保留清理端口。 */
public interface RuleDebugEventRepository {

    /** 保存一条真实执行事实。 */
    void save(RuleDebugEvent event);

    /** @param batchSize 单批上限 @return 实际删除行数 */
    int deleteExpired(int batchSize);
}
