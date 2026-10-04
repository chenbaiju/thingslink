package com.things.link.ingestion.application;

import com.things.link.shared.message.StandardUplinkMessage;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * 按稳定顺序执行上行预处理端口，并保护接入层已经确权的信封身份。
 *
 * <p>链位于 normalized consumer 与 telemetry 事务之间，对应架构文档 5 节冻结的位置；当前没有实现时
 * 是零成本直通。后处理不能放在这里，S8 应由与业务事务同提交的 outbox 事件可靠触发。</p>
 */
public class UplinkPreprocessingChain {
    /** 排序后不可变的处理器列表。 */
    private final List<UplinkPreprocessor> preprocessors;

    /**
     * 创建预处理链。
     * @param preprocessors 当前容器中的扩展实现
     */
    public UplinkPreprocessingChain(List<UplinkPreprocessor> preprocessors) {
        this.preprocessors = preprocessors.stream()
                .sorted(Comparator.comparingInt(UplinkPreprocessor::order))
                .toList();
    }

    /**
     * 依次处理信封并检查归属字段没有被租户规则篡改。
     * @param source 接入层确权后的标准信封
     * @return 最终待物模型校验的信封
     */
    public StandardUplinkMessage apply(StandardUplinkMessage source) {
        StandardUplinkMessage current = source;
        for (UplinkPreprocessor preprocessor : preprocessors) {
            StandardUplinkMessage next = preprocessor.preprocess(current);
            if (next == null) {
                throw new InvalidUplinkMessageException("上行预处理器不得返回空信封");
            }
            requireTrustedIdentity(source, next);
            current = next;
        }
        return current;
    }

    /** 确权字段只能由接入层派生，预处理规则仅允许变换业务载荷。 */
    private static void requireTrustedIdentity(StandardUplinkMessage source, StandardUplinkMessage candidate) {
        if (!Objects.equals(source.messageId(), candidate.messageId())
                || !Objects.equals(source.tenantId(), candidate.tenantId())
                || !Objects.equals(source.projectId(), candidate.projectId())
                || !Objects.equals(source.deviceId(), candidate.deviceId())
                || !Objects.equals(source.gatewayId(), candidate.gatewayId())
                || source.protocol() != candidate.protocol()
                || source.direction() != candidate.direction()
                || source.type() != candidate.type()
                || !Objects.equals(source.modelVersion(), candidate.modelVersion())
                || !Objects.equals(source.occurredAt(), candidate.occurredAt())
                || !Objects.equals(source.receivedAt(), candidate.receivedAt())
                || !Objects.equals(source.traceId(), candidate.traceId())
                || source.rawBytes() != candidate.rawBytes()) {
            throw new InvalidUplinkMessageException("上行预处理器不得修改已确权的信封身份");
        }
    }
}
