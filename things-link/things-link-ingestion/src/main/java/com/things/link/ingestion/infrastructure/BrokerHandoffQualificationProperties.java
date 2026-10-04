package com.things.link.ingestion.infrastructure;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

/**
 * C4a-1c 本机真实故障矩阵专用配置。
 *
 * <p>生产默认关闭；启用时逐 handoff 证据与 ACK 屏障都使用隔离输出目录。该配置不是业务开关，
 * 不得用来改变 durable handoff 的正常确认语义。</p>
 *
 * @param enabled 是否启用真实资格证据
 * @param evidencePath 跨应用重启追加的 JSONL 路径
 * @param scenarioPath 当前场景控制文件
 * @param ackBarrierPath ACK 歧义场景的已持久、未 ACK 标记文件
 */
@ConfigurationProperties(prefix = "things-link.ingress.handoff.qualification")
public record BrokerHandoffQualificationProperties(boolean enabled, Path evidencePath,
                                                    Path scenarioPath, Path ackBarrierPath) {

    /** 启用时三个路径必须互不相同，且调用者只能提供文件路径。 */
    public void requireValidWhenEnabled() {
        if (!enabled) return;
        if (evidencePath == null || scenarioPath == null || ackBarrierPath == null) {
            throw new IllegalStateException("handoff qualification 启用时三个路径均必填");
        }
        Path evidence = evidencePath.toAbsolutePath().normalize();
        Path scenario = scenarioPath.toAbsolutePath().normalize();
        Path barrier = ackBarrierPath.toAbsolutePath().normalize();
        if (evidence.equals(scenario) || evidence.equals(barrier) || scenario.equals(barrier)) {
            throw new IllegalStateException("handoff qualification 三个路径必须互不相同");
        }
    }
}
