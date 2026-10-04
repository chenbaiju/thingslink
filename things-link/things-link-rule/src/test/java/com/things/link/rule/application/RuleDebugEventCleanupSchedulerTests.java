package com.things.link.rule.application;

import com.things.link.rule.domain.RuleDebugEventRepository;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** 调试事件清理器单测：调度层只触发仓储冻结的限批语义。 */
class RuleDebugEventCleanupSchedulerTests {

    /** 每轮固定删除上限，不能由外部配置放大成长事务。 */
    @Test
    void deletesOneBoundedBatch() {
        RuleDebugEventRepository repository = mock(RuleDebugEventRepository.class);

        new RuleDebugEventCleanupScheduler(repository).cleanExpired();

        verify(repository).deleteExpired(1_000);
    }
}
