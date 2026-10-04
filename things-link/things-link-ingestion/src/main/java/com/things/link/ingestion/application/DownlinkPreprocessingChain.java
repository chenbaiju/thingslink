package com.things.link.ingestion.application;

import com.things.link.shared.message.DeviceCommandDispatch;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * 按稳定顺序执行协议编码前下行预处理，并保护已经确权的命令与路由字段。
 *
 * <p>S4-3 只落空链直通，S8 的租户规则 Bean 可以直接接入。身份复核不能留给各实现自律：一旦规则能把
 * connectionDeviceId 改成其他设备，平台发布者的 Broker 权限会把一个普通规则错误放大成跨设备下发。</p>
 */
public class DownlinkPreprocessingChain {

    /** 排序后不可变的处理器列表。 */
    private final List<DownlinkPreprocessor> preprocessors;

    /**
     * 创建下行预处理链。
     *
     * @param preprocessors 当前容器中的扩展实现
     */
    public DownlinkPreprocessingChain(List<DownlinkPreprocessor> preprocessors) {
        this.preprocessors = preprocessors.stream()
                .sorted(Comparator.comparingInt(DownlinkPreprocessor::order))
                .toList();
    }

    /**
     * 依次执行预处理器，并拒绝任何路由事实漂移。
     *
     * @param source telemetry 已冻结路由的派发信封
     * @return 最终待 MQTT 编码的信封
     */
    public DeviceCommandDispatch apply(DeviceCommandDispatch source) {
        DeviceCommandDispatch current = source;
        for (DownlinkPreprocessor preprocessor : preprocessors) {
            DeviceCommandDispatch next = preprocessor.preprocess(current);
            if (next == null) {
                throw new InvalidDownlinkMessageException("下行预处理器不得返回空信封");
            }
            requireTrustedRoute(source, next);
            current = next;
        }
        return current;
    }

    /** 只允许 inputJson 发生变化；其余字段均是受理事务已经冻结的事实。 */
    private static void requireTrustedRoute(DeviceCommandDispatch source, DeviceCommandDispatch candidate) {
        if (!Objects.equals(source.eventId(), candidate.eventId())
                || !Objects.equals(source.tenantId(), candidate.tenantId())
                || !Objects.equals(source.projectId(), candidate.projectId())
                || !Objects.equals(source.commandId(), candidate.commandId())
                || !Objects.equals(source.attemptId(), candidate.attemptId())
                || source.attemptNo() != candidate.attemptNo()
                || !Objects.equals(source.targetDeviceId(), candidate.targetDeviceId())
                || !Objects.equals(source.targetDeviceKey(), candidate.targetDeviceKey())
                || !Objects.equals(source.connectionDeviceId(), candidate.connectionDeviceId())
                || !Objects.equals(source.connectionDeviceKey(), candidate.connectionDeviceKey())
                || !Objects.equals(source.projectKey(), candidate.projectKey())
                || source.operationType() != candidate.operationType()
                || !Objects.equals(source.commandKey(), candidate.commandKey())
                || !Objects.equals(source.deadlineAt(), candidate.deadlineAt())
                || !Objects.equals(source.traceId(), candidate.traceId())) {
            throw new InvalidDownlinkMessageException("下行预处理器不得修改已确权的命令或设备路由");
        }
    }
}
