package com.things.link.simulator.application.ota;

import java.util.Objects;

/**
 * 故障注入计划（不可变值对象），描述这一次设备执行要演练哪些异常。
 *
 * <p>故障只影响<b>这一次执行</b>：设备重启后由调用方决定是否再注入。这一点很关键——掉电是场景输入，
 * 不是设备状态；把「上次掉过电」写进日志会让恢复逻辑永远无法前进。</p>
 *
 * <p>各故障的语义：</p>
 * <ul>
 *   <li>{@code powerLossAt(stage)}：在指定阶段的工作点模拟掉电，让执行落在 {@link OtaStage#SAFETY_PAUSED}
 *       并以 {@link OtaReasonCode#POWER_LOST} 记录；安全阶段上的掉电重启后不得重新刷写。</li>
 *   <li>{@code failFirstChunk()}：第一个分片就失败，按首错安全暂停处理，不自动重试。</li>
 *   <li>{@code rejectResume()}：设备已有耐久进度时，平台/传输明确拒绝续传，保持暂停且绝不从零重来。</li>
 *   <li>{@code cancelAtSafePoint(stage)}：在该阶段边界投递一次取消请求。安装窗口打开前接受（{@link OtaStage#CANCELLED}），
 *       打开后拒绝并如实上报真实阶段（{@link OtaReasonCode#CANCEL_REFUSED_AFTER_SAFETY_STAGE}）。</li>
 * </ul>
 *
 * @param powerLossAt 注入掉电的阶段；不注入时为 {@code null}
 * @param failFirstChunk 是否注入首分片失败
 * @param rejectResume 是否让平台/传输拒绝续传
 * @param cancelAtSafePoint 投递取消请求的阶段；不投递时为 {@code null}
 */
public record OtaFaultPlan(OtaStage powerLossAt, boolean failFirstChunk, boolean rejectResume,
                           OtaStage cancelAtSafePoint) {

    /** 不做任何故障注入的计划。 */
    public static final OtaFaultPlan NONE = new OtaFaultPlan(null, false, false, null);

    /**
     * 返回无故障计划。
     *
     * @return 不注入任何故障的计划
     */
    public static OtaFaultPlan none() {
        return NONE;
    }

    /**
     * 创建构建器。
     *
     * @return 新的故障计划构建器
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 故障计划构建器；方法名与场景描述一一对应，便于测试直接读出意图。
     */
    public static final class Builder {

        /** 掉电阶段。 */
        private OtaStage powerLossAt;

        /** 是否注入首分片失败。 */
        private boolean failFirstChunk;

        /** 是否拒绝续传。 */
        private boolean rejectResume;

        /** 取消投递阶段。 */
        private OtaStage cancelAtSafePoint;

        /** 构建器只允许通过 {@link OtaFaultPlan#builder()} 创建。 */
        private Builder() {
        }

        /**
         * 在指定阶段注入掉电。
         *
         * @param stage 掉电发生的阶段
         * @return 本构建器
         */
        public Builder powerLossAt(OtaStage stage) {
            this.powerLossAt = Objects.requireNonNull(stage, "stage");
            return this;
        }

        /**
         * 注入首分片失败（首错安全暂停，不自动重试）。
         *
         * @return 本构建器
         */
        public Builder failFirstChunk() {
            this.failFirstChunk = true;
            return this;
        }

        /**
         * 让平台/传输拒绝续传。
         *
         * @return 本构建器
         */
        public Builder rejectResume() {
            this.rejectResume = true;
            return this;
        }

        /**
         * 在指定阶段边界投递取消请求。
         *
         * @param stage 取消请求到达时设备所处的阶段
         * @return 本构建器
         */
        public Builder cancelAtSafePoint(OtaStage stage) {
            this.cancelAtSafePoint = Objects.requireNonNull(stage, "stage");
            return this;
        }

        /**
         * 冻结计划。
         *
         * @return 不可变故障计划
         */
        public OtaFaultPlan build() {
            return new OtaFaultPlan(powerLossAt, failFirstChunk, rejectResume, cancelAtSafePoint);
        }
    }
}
