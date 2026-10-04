package com.things.link.shared.message;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 网关批量属性上报（{@code up/batch/report}）中的单个子设备条目。
 *
 * <p>与单设备 {@link DevicePropertyReport} 的差异仅在多了 {@code deviceKey}：子设备没有独立连接，
 * 其身份由已认证网关代报，因此条目必须携带子设备在项目内的稳定标识。{@code messageId} 仍必须由
 * 网关 SDK 为每个子设备独立生成 UUIDv7，平台拆分时不得生成或改写——否则 at-least-once 重投会让
 * {@code sys_inbox_message}（全局主键去重）失效并产生重复写入。</p>
 *
 * @param messageId 网关为本次子设备上报生成的 UUIDv7 消息标识，重试时保持不变
 * @param deviceKey 子设备在项目内的稳定标识
 * @param occurredAt 子设备采集时刻（RFC3339 UTC，设备时钟不可信但用于影子 CAS 与时序落位）
 * @param modelVersion 子设备本次上报使用的物模型语义版本
 * @param payload 非空属性键值对象
 */
public record SubDeviceReport(UUID messageId, String deviceKey, Instant occurredAt,
                              String modelVersion, Map<String, Object> payload) {

    /** 子设备标识沿用物模型安全字符集，与 MQTT Topic 的 deviceKey 段一致。 */
    private static final String KEY_PATTERN = "[a-zA-Z0-9][a-zA-Z0-9_-]{0,63}";

    /** 属性标识符沿用物模型安全字符集，禁止借键名构造 JSON 路径歧义。 */
    private static final String PROPERTY_KEY_PATTERN = "[A-Za-z0-9_-]{1,64}";

    /**
     * 冻结单条子设备上报的不可变约束。
     */
    public SubDeviceReport {
        requireUuidV7(messageId);
        if (deviceKey == null || !deviceKey.matches(KEY_PATTERN)) {
            throw new IllegalArgumentException("子设备标识不符合安全字符集");
        }
        if (occurredAt == null) {
            throw new IllegalArgumentException("occurredAt 不能为空");
        }
        // 存量标量子设备可在 X-01 §5.2 兼容窗口内省略 modelVersion，省略值由 device 域推断为已绑定初始版本。
        if (modelVersion != null && !modelVersion.matches("(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)")) {
            throw new IllegalArgumentException("modelVersion 必须是 major.minor.patch");
        }
        if (payload == null || payload.isEmpty()) {
            throw new IllegalArgumentException("payload 必须包含至少一个属性");
        }
        if (payload.keySet().stream().anyMatch(key -> key == null || !key.matches(PROPERTY_KEY_PATTERN))) {
            throw new IllegalArgumentException("payload 包含非法属性标识符");
        }
        // 防御性复制避免发布后调用方继续修改 Map，造成日志内容与实际发送内容不一致。
        payload = StandardUplinkMessage.immutablePayload(payload);
    }

    /**
     * 校验设备消息使用 UUIDv7；版本位错误意味着网关 SDK 没有遵守不可回退的消息契约。
     *
     * @param value 待校验标识
     */
    private static void requireUuidV7(UUID value) {
        if (value == null || value.version() != 7) {
            throw new IllegalArgumentException("messageId 必须是 UUIDv7");
        }
    }
}
