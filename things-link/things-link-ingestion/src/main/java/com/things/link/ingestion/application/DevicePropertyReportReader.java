package com.things.link.ingestion.application;

import com.things.link.shared.message.DevicePropertyReport;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectReader;

/**
 * 设备业务载荷到冻结报文契约的统一读取器。
 *
 * <p>MQTT 原始信封与新协议的 HTTP／TCP／CoAP 载荷同源（接入合同 §3.1），必须共用同一份读取配置。
 * 两处各建一个 {@code ObjectReader} 会让数字精度、未知字段容错等语义随协议漂移，最终表现为同一份
 * 设备 JSON 在 MQTT 上落库为 {@code BigDecimal}、在新协议上变成 {@code double}。</p>
 */
public class DevicePropertyReportReader {

    /** Jackson 由 Boot 统一配置，确保 RFC3339 时间与其他 API 使用同一序列化规则。 */
    private final ObjectReader reportReader;

    /**
     * 创建业务载荷读取器。
     *
     * @param objectMapper 应用统一 JSON 映射器
     */
    public DevicePropertyReportReader(ObjectMapper objectMapper) {
        // 仅设备载荷 reader 使用十进制，不能改全局 HTTP 映射器。
        this.reportReader = objectMapper.readerFor(DevicePropertyReport.class)
                .with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    }

    /**
     * 读取设备业务载荷。
     *
     * @param payload 设备业务 JSON 字节；MQTT 为报文载荷，新协议为帧或请求体载荷
     * @return 通过冻结传输契约校验的属性上报
     * @throws InvalidUplinkMessageException 载荷为空、JSON 非法或违反 {@link DevicePropertyReport} 契约
     */
    public DevicePropertyReport read(byte[] payload) {
        if (payload == null || payload.length == 0) {
            throw new InvalidUplinkMessageException("设备业务载荷不能为空");
        }
        try {
            return reportReader.readValue(payload);
        } catch (JacksonException | IllegalArgumentException exception) {
            // 此类错误由相同字节重放仍会失败，必须进入 DLQ 而不是占用基础设施重试次数。
            throw new InvalidUplinkMessageException("设备属性报文不符合 DevicePropertyReport 契约", exception);
        }
    }
}
