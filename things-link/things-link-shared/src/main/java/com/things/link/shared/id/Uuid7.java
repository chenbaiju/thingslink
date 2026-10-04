package com.things.link.shared.id;

import java.security.SecureRandom;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * UUIDv7 生成器（RFC 9562）。
 *
 * <p>全系统主键统一用 UUIDv7（{@code docs/DEVELOPMENT_ROADMAP.md} 决策 #4）。
 * <b>ID 类型是全表主键，绝对不可后改</b> —— 换类型意味着重建每一张表、每一个
 * 外键和每一条已有数据。
 *
 * <h2>为什么是 v7 而不是 v4</h2>
 * v7 的高 48 位是毫秒时间戳，因此<b>按生成时间单调递增</b>。这对本项目有两个直接
 * 好处，而它们都是 v4 拿不到的：
 * <ul>
 *   <li><b>B-tree 索引局部性</b>。v4 完全随机，插入点均匀散布在整棵索引树上，
 *       高写入量下页分裂频繁、缓存命中率低。设备消息、时序点、审计日志都是持续
 *       高频写入，这个差距会随数据量放大</li>
 *   <li><b>主键自带时间序</b>。按主键排序即近似按创建时间排序，省掉很多场景下
 *       对 created_at 的额外索引</li>
 * </ul>
 *
 * <h2>为什么不用数据库生成</h2>
 * PostgreSQL 17 没有内置 {@code uuidv7()}（18 才有）。更重要的是：应用侧生成让
 * 聚合在<b>入库前</b>就拥有身份，领域事件、outbox 记录和日志可以在同一个事务里
 * 引用同一个 ID，不必先 insert 再回读。
 *
 * <h2>位布局</h2>
 * <pre>
 * 0                   1                   2                   3
 * |unix_ts_ms (48 bit)                            |ver(4)|rand_a(12)|
 * |var(2)|                    rand_b (62 bit)                       |
 * </pre>
 *
 * <p><b>本类不依赖 Spring，也不应该依赖</b>（架构文档 10.2：shared 模块的硬性约束）。
 */
public final class Uuid7 {

    /** 版本号 7，占据 {@code most significant bits} 的第 12–15 位。 */
    private static final long VERSION_7 = 0x7000L;

    /** RFC 9562 变体位 {@code 10xx}，占据 {@code least significant bits} 最高两位。 */
    private static final long VARIANT_RFC9562 = 0x8000_0000_0000_0000L;

    /** 时间戳占 48 位，即毫秒值的低 48 位。 */
    private static final long TIMESTAMP_MASK = 0xFFFF_FFFF_FFFFL;

    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * 上一次使用的毫秒时间戳，用于保证同毫秒内多次调用仍然单调。
     *
     * <p>没有这个的话，同一毫秒内生成的多个 ID 只有随机位不同，相互之间没有顺序，
     * 局部有序性在高频写入时就会退化 —— 而高频写入恰恰是唯一需要它的场景。
     */
    private static final AtomicLong LAST_TIMESTAMP = new AtomicLong(0);

    private Uuid7() {
        // 工具类，不允许实例化
    }

    /**
     * 生成一个新的 UUIDv7。
     *
     * <p>线程安全。同一毫秒内的并发调用通过 {@link #LAST_TIMESTAMP} 的 CAS 推进
     * 逻辑时间戳，保证返回值严格单调递增。
     *
     * <p>时钟回拨（NTP 校正、虚拟机迁移）时不会返回比已生成值更小的 ID：逻辑时间戳
     * 只增不减。代价是回拨期间生成的 ID 时间戳略微超前真实时间 —— 相比破坏单调性，
     * 这是更能接受的取舍。
     *
     * @return 新的 UUIDv7
     */
    public static UUID generate() {
        long timestamp = nextMonotonicTimestamp();

        // 高 64 位：48 位时间戳 + 4 位版本 + 12 位随机
        long mostSignificantBits = (timestamp & TIMESTAMP_MASK) << 16
                | VERSION_7
                | (RANDOM.nextLong() & 0x0FFFL);

        // 低 64 位：2 位变体 + 62 位随机
        long leastSignificantBits = (RANDOM.nextLong() & 0x3FFF_FFFF_FFFF_FFFFL)
                | VARIANT_RFC9562;

        return new UUID(mostSignificantBits, leastSignificantBits);
    }

    /**
     * 取一个不小于上次返回值的时间戳。
     *
     * @return 单调递增的毫秒时间戳
     */
    private static long nextMonotonicTimestamp() {
        while (true) {
            long previous = LAST_TIMESTAMP.get();
            long now = System.currentTimeMillis();
            // 时钟未前进（同毫秒内并发，或时钟回拨）时，逻辑上推进 1 毫秒
            long candidate = now > previous ? now : previous + 1;
            if (LAST_TIMESTAMP.compareAndSet(previous, candidate)) {
                return candidate;
            }
        }
    }

}
