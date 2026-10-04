package com.things.link.ingestion.application;

import com.things.link.shared.message.StandardUplinkMessage;

/**
 * 标准上行在物模型校验与落库之前的预处理扩展点。
 *
 * <p>S4 前只冻结端口，S8 才提供租户规则实现。实现可变换 payload，但不得改变接入层已经从认证 Topic
 * 确权的 tenantId、projectId、deviceId、messageId、方向和消息类型。</p>
 */
public interface UplinkPreprocessor {
    /**
     * 处理一条标准上行信封。
     * @param message 上一处理器产出的信封
     * @return 变换后的信封，不得返回 null
     */
    StandardUplinkMessage preprocess(StandardUplinkMessage message);

    /**
     * 返回稳定执行顺序；数值越小越先执行，同序时按 Spring 注入顺序。
     * @return 顺序值
     */
    default int order() {
        return 0;
    }
}
