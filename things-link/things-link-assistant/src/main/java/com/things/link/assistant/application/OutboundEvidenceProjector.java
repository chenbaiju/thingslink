package com.things.link.assistant.application;

import com.things.link.assistant.application.PreparedModelEvidence.*;
import com.things.link.device.application.ConsoleDeviceEvidence;
import org.springframework.stereotype.Service;
import java.util.*;

/** 无网络/数据库能力；输入必须由调用方先通过既有平台权限链采集。 */
@Service
public class OutboundEvidenceProjector {
    private final OutboundPropertyPolicy policy;
    public OutboundEvidenceProjector(OutboundPropertyPolicy policy) { this.policy = policy; }

    /**
     * 按精确匹配的服务端白名单生成脱敏投影，不猜测属性语义或单位。
     * 缺项记录原因；原始项目、设备标识、名称和自由文本不进入模型输入。
     * @param snapshot 调用方经平台权限链取得的可信设备证据，最多十个属性
     * @param template 封闭问题模板，不接受用户自由文本
     * @return 固定设备别名、允许属性及枚举证据，另附内部来源映射和策略摘要
     * @throws IllegalArgumentException 可信证据结构、状态或属性集合不满足契约
     */
    public PreparedModelEvidence prepare(DeviceEvidenceSnapshot snapshot, Template template) {
        if (snapshot == null || template == null || snapshot.schemaVersion() != 1
                || snapshot.projectId() == null || snapshot.modelVersionId() == null
                || snapshot.device() == null || snapshot.device().readAt() == null
                || snapshot.alarmSummary() == null || snapshot.alarmSummary().state() == null
                || snapshot.alarmSummary().observedAt() == null
                || snapshot.collectionStartedAt() == null || snapshot.collectionFinishedAt() == null
                || snapshot.properties().size() > 10)
            throw new IllegalArgumentException("invalid trusted device evidence");
        DeviceStatus status;
        try { status = DeviceStatus.valueOf(snapshot.device().status()); }
        catch (RuntimeException invalid) { throw new IllegalArgumentException("invalid trusted device status"); }
        List<Reading> readings = new ArrayList<>(); List<Omission> omissions = new ArrayList<>();
        Map<String,Integer> positions = new HashMap<>(); Set<String> keys = new HashSet<>();
        for (int index = 0; index < snapshot.properties().size(); index++) {
            var property = snapshot.properties().get(index);
            if (property == null || property.key() == null || !keys.add(property.key()))
                throw new IllegalArgumentException("invalid trusted property collection");
            var binding = policy.find(snapshot.projectId(), snapshot.modelVersionId(), property.key());
            OmissionReason omitted = null; Scalar value = null;
            if (binding.isEmpty()) omitted = OmissionReason.UNCONFIGURED;
            else if (property.availability() != ConsoleDeviceEvidence.Availability.PRESENT) omitted = OmissionReason.UNAVAILABLE;
            else if (!snapshot.modelVersionId().equals(property.sourceModelVersionId())) omitted = OmissionReason.SOURCE_MISMATCH;
            else if (property.occurredAt() == null || property.readAt() == null) omitted = OmissionReason.MISSING_TIME;
            else if (property.value() == null) omitted = OmissionReason.INVALID_VALUE;
            else if (binding.get().semantic().isBoolean()) {
                if (property.value().isBoolean()) value = new BooleanValue(property.value().asBoolean());
                else omitted = OmissionReason.INVALID_VALUE;
            } else {
                // 禁止强制转换字符串、对数值取整，或将非有限读数替换为零。
                if (!property.value().isNumber() || !Double.isFinite(property.value().doubleValue()))
                    omitted = OmissionReason.INVALID_VALUE;
                else value = new NumberValue(property.value().decimalValue());
            }
            if (omitted != null) { omissions.add(new Omission(index, omitted)); continue; }
            String id = "e-property-" + (index + 1);
            readings.add(new Reading(id, binding.get().semantic(), binding.get().unit(), value, property.occurredAt(), property.readAt()));
            positions.put(id, index);
        }
        var device = new Device("e-device", status, snapshot.device().lastOnlineAt(), snapshot.device().readAt());
        var alarm = new Alarm("e-alarm", AlarmState.valueOf(snapshot.alarmSummary().state().name()), snapshot.alarmSummary().observedAt());
        var input = new Input(template, "device-1", snapshot.collectionStartedAt(), snapshot.collectionFinishedAt(), device, alarm, readings);
        return new PreparedModelEvidence(input, policy.fingerprint(), positions, omissions);
    }
}
