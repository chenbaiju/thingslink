package com.things.link.ingestion.application;

import java.util.Arrays;
import java.util.concurrent.TimeUnit;

/** 分享合同§4：固定61桶保守滑动60秒预算，不随帧数分配内存。 */
final class DashboardShareFrameBudget {
    /** 每连接所有实际尝试发送的UTF-8文本总上限。 */
    private static final long LIMIT = 2L * 1024 * 1024;
    /** 边界秒保留到整桶退出窗口，最多保守多计不足一秒。 */
    private final long[] seconds = new long[61];
    /** 固定槽累积字节，失败发送也不退款。 */
    private final long[] bytes = new long[61];

    /** 初始化空桶，生产单调时钟不要求从零开始。 */
    DashboardShareFrameBudget() { Arrays.fill(seconds, Long.MIN_VALUE); }

    /** @return 能否在发送前不可逆记账；拒绝不改变已有窗口。 */
    synchronized boolean reserve(int encodedBytes, long nowNanos) {
        if (encodedBytes < 0 || encodedBytes > 32 * 1024) return false;
        long second = Math.floorDiv(nowNanos, TimeUnit.SECONDS.toNanos(1));
        long total = 0;
        for (int index = 0; index < seconds.length; index++) {
            if (seconds[index] >= second - 60 && seconds[index] <= second) total += bytes[index];
        }
        if (total + encodedBytes > LIMIT) return false;
        int slot = Math.floorMod(second, seconds.length);
        if (seconds[slot] != second) { seconds[slot] = second; bytes[slot] = 0; }
        bytes[slot] += encodedBytes;
        return true;
    }
}
