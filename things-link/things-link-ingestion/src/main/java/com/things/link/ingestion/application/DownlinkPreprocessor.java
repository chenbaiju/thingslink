package com.things.link.ingestion.application;

import com.things.link.shared.message.DeviceCommandDispatch;

/**
 * 已完成命令路由、进入协议编码之前的下行预处理扩展点。
 *
 * <p>ADR 0017 把这个位置冻结在“目标设备 / 实际连接设备”已经确定之后，避免 S8 加规则时重新切开
 * 命令事务。处理器只允许变换 {@link DeviceCommandDispatch#inputJson()}，不得改写命令、项目、设备、
 * 尝试次数、截止时间或 traceId 等已经确权的路由事实。</p>
 */
public interface DownlinkPreprocessor {

    /**
     * 处理一条冻结的命令派发信封。
     *
     * @param dispatch 上一个处理器产出的派发信封
     * @return 处理后的信封，不得返回 {@code null}
     */
    DeviceCommandDispatch preprocess(DeviceCommandDispatch dispatch);

    /**
     * 返回稳定执行顺序；数值越小越先执行，同序时沿用 Spring 注入顺序。
     *
     * @return 顺序值
     */
    default int order() {
        return 0;
    }
}
