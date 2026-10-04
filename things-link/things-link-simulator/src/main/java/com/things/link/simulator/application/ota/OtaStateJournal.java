package com.things.link.simulator.application.ota;

import java.util.List;

/**
 * OTA 尝试的耐久、追加写日志端口。
 *
 * <p><b>为什么需要独立日志而不是内存状态：</b>断电/进程崩溃后，设备唯一可以相信的就是已经 fsync 的记录。
 * 内存里的 offset、下载重试次数甚至「是否已经刷写」全部丢失，因此本片把「已确认偏移 + 已确认前缀摘要
 * + artifact 身份 + 阶段 + 原因」作为唯一恢复依据。读取必须按追加顺序返回，恢复逻辑只看最后一条。</p>
 *
 * <p>实现必须保证：{@link #append(OtaJournalRecord)} 返回后记录已经持久（落盘并 fsync），否则
 * 断点续传测试无法区分「记录成功」与「写进页缓存后又掉电」。</p>
 */
public interface OtaStateJournal {

    /**
     * 追加一条耐久记录。
     *
     * <p>追加失败必须抛异常，调用方据此判定该分片尚未耐久，绝不能继续推进内存 offset。</p>
     *
     * @param record 待追加记录
     */
    void append(OtaJournalRecord record);

    /**
     * 按追加顺序读取全部记录。
     *
     * <p>第一次运行时日志可能还不存在，此时返回空列表，由状态机写入首条 {@link OtaStage#DISPATCHED} 记录。</p>
     *
     * @return 不可变的记录列表，顺序与追加顺序一致
     */
    List<OtaJournalRecord> read();
}
