package com.things.link.shared.id;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.TestMethodOrder;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * UUIDv7 生成器测试。
 *
 * <p>这些断言守的是「ID 类型不可后改」这个前提（决策 #4）：版本位写错或单调性
 * 不成立，都要等到数据量上来、索引开始劣化时才会被察觉，那时已有海量存量数据。
 */
@DisplayName("UUIDv7 生成器")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Uuid7Tests {

    @Test
    @Order(2)
    @DisplayName("版本位为 7，变体位符合 RFC 9562")
    void generatesCorrectVersionAndVariant() {
        UUID uuid = Uuid7.generate();

        assertThat(uuid.version())
                .as("UUID 版本应为 7")
                .isEqualTo(7);
        assertThat(uuid.variant())
                .as("变体应为 RFC 9562 定义的 2")
                .isEqualTo(2);
    }

    /**
     * 单调性是选择 v7 的全部理由。不成立的话不如直接用 v4。
     */
    @Test
    @Order(3)
    @DisplayName("连续生成严格单调递增")
    void generatesMonotonicallyIncreasingIds() {
        List<UUID> ids = IntStream.range(0, 10_000)
                .mapToObj(i -> Uuid7.generate())
                .toList();

        for (int i = 1; i < ids.size(); i++) {
            assertThat(compareUnsigned(ids.get(i - 1), ids.get(i)))
                    .as("第 %d 个 ID 应大于前一个：%s vs %s", i, ids.get(i - 1), ids.get(i))
                    .isNegative();
        }
    }

    @Test
    @Order(1)
    @DisplayName("高 48 位是当前毫秒时间戳")
    void encodesCurrentTimestamp() {
        long before = System.currentTimeMillis();
        UUID uuid = Uuid7.generate();
        long after = System.currentTimeMillis();

        long timestamp = uuid.getMostSignificantBits() >>> 16;

        // 同一 JVM 的其他测试可能已生成 ID；实现为保证严格单调会把逻辑毫秒向前推进，不能要求等于墙钟。
        assertThat(timestamp).isGreaterThanOrEqualTo(before).isLessThanOrEqualTo(after + 1_000);
    }

    /**
     * 并发下不重复，是主键的基本要求。
     */
    @Test
    @Order(4)
    @DisplayName("并发生成不产生重复")
    void generatesUniqueIdsUnderConcurrency() throws Exception {
        int threads = 16;
        int perThread = 2_000;

        try (ExecutorService executor = Executors.newFixedThreadPool(threads)) {
            List<Callable<List<UUID>>> tasks = IntStream.range(0, threads)
                    .mapToObj(t -> (Callable<List<UUID>>) () -> IntStream.range(0, perThread)
                            .mapToObj(i -> Uuid7.generate())
                            .toList())
                    .toList();

            Set<UUID> all = executor.invokeAll(tasks).stream()
                    .flatMap(future -> {
                        try {
                            return future.get().stream();
                        } catch (Exception e) {
                            throw new IllegalStateException(e);
                        }
                    })
                    .collect(java.util.stream.Collectors.toSet());

            assertThat(all).hasSize(threads * perThread);
        }
    }

    /**
     * 按无符号比较两个 UUID。
     *
     * <p>不能直接用 {@link UUID#compareTo}：它把两个 long 当有符号处理，
     * 而 UUID 的位模式是无符号的，符号位翻转时会给出错误的顺序。
     */
    private static int compareUnsigned(UUID left, UUID right) {
        int high = Long.compareUnsigned(left.getMostSignificantBits(), right.getMostSignificantBits());
        return high != 0
                ? high
                : Long.compareUnsigned(left.getLeastSignificantBits(), right.getLeastSignificantBits());
    }

}
