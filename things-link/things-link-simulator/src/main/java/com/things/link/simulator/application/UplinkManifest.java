package com.things.link.simulator.application;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.EnumMap;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 按消息类型拆分、可归档的负载 manifest。
 *
 * <p>A4-0 冻结的 §6 要求各消息类型分别维护 manifest：property-report / command-reply / config-reply /
 * batch-report 的成功集合不能混进同一个等式，否则「平台同时缺一条、额外多一条时数量仍相等」无法被发现。
 * 这里把每种类型分开计数，并把每个 PUBACK 确认的 messageId 追加写入独立归档文件——内存里只保留计数器，
 * 不长期堆积 UUID 集合，避免 24h 长稳形成发生器自身的单调内存增长。</p>
 *
 * <p>四个数量概念的边界（A4-0 §1.1「统计计数与实际生成的唯一 messageId 清单一致」依赖它们不可混用）：</p>
 * <ul>
 *   <li><b>PUBACK 信封数</b>（{@link Counters#confirmed}）：一次 {@code publish} 调用收到一个 PUBACK 记 1，
 *       批量上报一次 PUBACK 仍记 1。</li>
 *   <li><b>逻辑 messageId 数</b>（{@link Counters#confirmedMessages}）：一次 PUBACK 携带的子 messageId 个数之和，
 *       批量上报一次携带 N 个子 messageId 就记 N；command-reply 的 QoS 1 重投会重复同一 messageId，也如实记入。</li>
 *   <li><b>原始投递日志</b>：归档文件按 PUBACK 到达顺序追加 messageId 行，含重投造成的重复，只增不改。</li>
 *   <li><b>离线唯一集合</b>：操作者对归档做 {@code sort -u} 得到，用于与平台入站唯一消息数对账（§6「无额外、无缺失」）。
 *       内存里不维护该集合，避免长稳期间内存增长。</li>
 * </ul>
 *
 * <p>落盘失败与发布成败是两类事实，不能互相顶替：PUBACK 已到达就必须记为确认（即便随后写盘失败），
 * 写盘失败只把本轮 manifest 标记为「无效」，使整轮资格失效，而不会把磁盘错误伪装成发布失败。</p>
 */
public final class UplinkManifest implements AutoCloseable {

    /** 上行消息类型；manifest 与计数器都按此拆分。 */
    public enum Type {
        /** 单设备属性上报（主负载）。 */
        PROPERTY_REPORT,
        /** 命令回复。 */
        COMMAND_REPLY,
        /** 网关配置回执。 */
        CONFIG_REPLY,
        /** 网关批量子设备上报。 */
        BATCH_REPORT
    }

    /**
     * 单类型计数快照。
     *
     * @param initiated 发起的 PUBACK 信封数（每次 publish 调用记 1）
     * @param confirmed 已 PUBACK 确认的信封数
     * @param confirmedMessages 已确认信封携带的逻辑 messageId 总数（批量上报一个信封多个 messageId）
     * @param failed 发起后未收到 PUBACK 的信封数
     */
    public record Counters(long initiated, long confirmed, long confirmedMessages, long failed) {
    }

    /** 每种类型四个原子计数的下标：0=initiated, 1=confirmed, 2=confirmedMessages, 3=failed。 */
    private static final int INITIATED = 0;
    private static final int CONFIRMED = 1;
    private static final int CONFIRMED_MESSAGES = 2;
    private static final int FAILED = 3;

    /** 每种类型一个计数四元组。 */
    private final EnumMap<Type, AtomicLong[]> counters = new EnumMap<>(Type.class);

    /** 每种类型一个追加写归档；未 open 或 open 失败时为 null。 */
    private final EnumMap<Type, BufferedWriter> writers = new EnumMap<>(Type.class);

    /**
     * 本轮 manifest 是否健康。任何打开/写入/关闭失败都会置 false，使整轮资格失效，
     * 但计数继续累加——PUBACK 是事实，磁盘故障只否定归档，不否定计数。
     */
    private final AtomicBoolean healthy = new AtomicBoolean(true);

    /** 首次置为不健康的原因，供 stats 与操作者定位；健康时为空。 */
    private volatile String failureReason;

    /** 是否已经为一轮模拟打开过归档；从未 open 的纯内存冒烟不受关闭后写入规则约束。 */
    private boolean runOpened;

    /** 当前轮次是否仍接受计数与归档；close 后的任何迟到记录都会使本轮证据失效。 */
    private boolean acceptingRecords;

    /** 创建空的 manifest（尚未 open，计数全零、不落盘）。 */
    public UplinkManifest() {
        for (Type type : Type.values()) {
            counters.put(type, new AtomicLong[] {new AtomicLong(), new AtomicLong(), new AtomicLong(), new AtomicLong()});
        }
    }

    /**
     * 打开归档目录并按类型创建（截断重写）文件，同时把计数与健康状态归零；重复 open 会先关闭旧文件。
     *
     * <p>每次 {@code start} 调用一次，代表一轮全新运行；上一轮 manifest 由操作者在启动前自行归档。
     * 打开失败不抛异常：本轮降级为「只计数不落盘」并标记无效，由 stats 暴露，而不是把失败伪装成发布失败。</p>
     *
     * @param directory 归档目录，不存在时创建
     */
    public synchronized void open(Path directory) {
        acceptingRecords = false;
        closeWriters();
        for (Type type : Type.values()) {
            AtomicLong[] c = counters.get(type);
            c[INITIATED].set(0);
            c[CONFIRMED].set(0);
            c[CONFIRMED_MESSAGES].set(0);
            c[FAILED].set(0);
        }
        healthy.set(true);
        failureReason = null;
        runOpened = true;
        acceptingRecords = true;
        try {
            Files.createDirectories(directory);
            for (Type type : Type.values()) {
                Path file = directory.resolve(type.name().toLowerCase(Locale.ROOT) + ".log");
                writers.put(type, Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING));
            }
        } catch (IOException exception) {
            markUnhealthy("打开上行 manifest 失败: " + exception.getMessage());
            // 已打开的部分 writer 一并关闭，避免句柄泄漏；未打开的保持 null（只计数不落盘）。
            closeWriters();
        }
    }

    /** 记录一次发起（一次 publish 调用）。 */
    public synchronized void recordInitiated(Type type) {
        markLateRecordIfNecessary("发起", type);
        counters.get(type)[INITIATED].incrementAndGet();
    }

    /**
     * 记录一次 PUBACK 确认，并追加归档确认的 messageId。
     *
     * <p>计数永远先行且不可回滚：PUBACK 已到达即 {@code confirmed + 1}、{@code confirmedMessages + N}。
     * 写盘失败只标记 manifest 无效，不把已确认的事实改成失败。批量上报一次 PUBACK 可能确认多个子消息
     * messageId，故按变参传入；单消息上报传一个。</p>
     *
     * @param type 消息类型
     * @param messageIds 本次确认携带的 messageId（单消息上报一个，批量上报多个）
     */
    public synchronized void recordConfirmed(Type type, UUID... messageIds) {
        markLateRecordIfNecessary("PUBACK 确认", type);
        AtomicLong[] c = counters.get(type);
        c[CONFIRMED].incrementAndGet();
        c[CONFIRMED_MESSAGES].addAndGet(messageIds.length);
        BufferedWriter writer = writers.get(type);
        if (writer == null) {
            return; // 未 open 或 open 失败时只计数不落盘，供无归档的冒烟场景
        }
        try {
            for (UUID messageId : messageIds) {
                writer.write(messageId.toString());
                writer.newLine();
            }
        } catch (IOException exception) {
            markUnhealthy("写入上行 manifest 失败 [" + type + "]: " + exception.getMessage());
        }
    }

    /** 记录一次失败（发起后未收到 PUBACK 的信封）。 */
    public synchronized void recordFailed(Type type) {
        markLateRecordIfNecessary("失败", type);
        counters.get(type)[FAILED].incrementAndGet();
    }

    /** 单类型计数快照。 */
    public Counters countersOf(Type type) {
        AtomicLong[] c = counters.get(type);
        return new Counters(c[INITIATED].get(), c[CONFIRMED].get(), c[CONFIRMED_MESSAGES].get(), c[FAILED].get());
    }

    /** @return 本轮 manifest 是否健康（无任何打开/写入/关闭失败） */
    public boolean healthy() {
        return healthy.get();
    }

    /** @return 首次置为不健康的原因；健康时为空 */
    public String failureReason() {
        return failureReason;
    }

    /** 关闭并刷盘所有归档。 */
    @Override
    public synchronized void close() {
        acceptingRecords = false;
        closeWriters();
    }

    /**
     * 由模拟器生命周期主动判定本轮证据无效，例如停止时任务未在期限内收敛。
     *
     * @param reason 首个失效原因
     */
    public synchronized void invalidate(String reason) {
        markUnhealthy(reason);
    }

    /** 首次失败即记录原因；后续失败不覆盖首个原因，避免把「根因」冲掉。 */
    private void markUnhealthy(String reason) {
        if (healthy.get()) {
            // 先写原因再发布 unhealthy；读取方一旦看到 false，就必须同时看到非空根因。
            failureReason = reason;
            healthy.set(false);
        }
    }

    /** close 后仍有记录到达说明最终快照不稳定，必须让资格结果失败，不能静默只改计数器。 */
    private void markLateRecordIfNecessary(String operation, Type type) {
        if (runOpened && !acceptingRecords) {
            markUnhealthy("manifest 关闭后仍收到" + operation + "记录: " + type);
        }
    }

    /**
     * 关闭所有 writer；关闭失败标记 manifest 无效并保留原因，不抛出——归档不完整由 stats 的
     * {@link #healthy()} 暴露，而不是在收尾线程抛一个无人观察的异常。
     */
    private void closeWriters() {
        for (BufferedWriter writer : writers.values()) {
            try {
                writer.close();
            } catch (IOException exception) {
                markUnhealthy("关闭上行 manifest 失败: " + exception.getMessage());
            }
        }
        writers.clear();
    }
}
