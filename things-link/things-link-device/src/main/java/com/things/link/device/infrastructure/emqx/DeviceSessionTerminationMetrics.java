package com.things.link.device.infrastructure.emqx;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;

/** D-038 会话强制断开的低基数结果指标，业务 ID 只进日志而不进时序标签。 */
@Component
public class DeviceSessionTerminationMetrics {

    /** Prometheus 会为 Counter 追加 {@code _total}。 */
    public static final String ATTEMPTS = "thingslink.device.session.termination";
    /** 固定结果到计数器的映射。 */
    private final Map<Result, Counter> counters = new EnumMap<>(Result.class);

    /** @param registry Micrometer 注册表 */
    public DeviceSessionTerminationMetrics(MeterRegistry registry) {
        for (Result result : Result.values()) {
            counters.put(result, Counter.builder(ATTEMPTS)
                    .description("凭据撤销或轮换后强制断开 MQTT 会话的结果")
                    .tag("result", result.tagValue)
                    .register(registry));
        }
    }

    /** @param result 固定结果 */
    public void record(Result result) {
        counters.get(result).increment();
    }

    /** 会话断开的有限结果集。 */
    public enum Result {
        /** EMQX 确认已断开。 */
        DISCONNECTED("disconnected"),
        /** 会话已自然离线，404 按幂等成功收敛。 */
        ALREADY_ABSENT("already_absent"),
        /** 未注入管理 API 凭据，禁止回退为设备密钥。 */
        CREDENTIALS_MISSING("credentials_missing"),
        /** 超时、连接失败或 EMQX 拒绝。 */
        FAILED("failed");

        /** Prometheus 低基数标签值。 */
        private final String tagValue;

        /** @param tagValue 固定标签值 */
        Result(String tagValue) {
            this.tagValue = tagValue;
        }
    }
}
