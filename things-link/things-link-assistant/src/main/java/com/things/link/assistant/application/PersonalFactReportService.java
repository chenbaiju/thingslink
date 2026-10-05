package com.things.link.assistant.application;

import com.things.link.device.application.ConsoleDeviceEvidence.Availability;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.CommonErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** 从已保存且当次受权的历史事实汇编，Java持有身份边界，未调用模型或读取新设备状态。 */
@Service
public class PersonalFactReportService {
    private final PersonalEvidenceRecordService records;
    public PersonalFactReportService(PersonalEvidenceRecordService records) { this.records=records; }

    /**
     * 明确请求才汇编；结束前独立事务复验来源有效性与当前权限，重复生成无持久副作用。
     * @param project 当前项目标识，沿来源记录当前权限检查
     * @param record 本人且未到期的来源记录
     * @return 来源绑定的确定性事实报告，不包含农艺建议或设备控制
     */
    @Transactional(propagation=Propagation.NOT_SUPPORTED)
    public PersonalFactReport generate(UUID project, UUID record) {
        var source=records.read(project,record);
        var report=render(source);
        var verified=records.read(project,record);
        if (!source.record().equals(verified.record())) throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND);
        return report;
    }

    /** @param source 服务端已校验完整性及访问权的历史记录 @return 固定第一版汇编格式，异常历史合同拒绝报告 */
    static PersonalFactReport render(PersonalEvidenceRecordView.Detail source) {
        var metadata=source.record();var snapshot=source.snapshot();
        if (snapshot.schemaVersion()!=1 || !metadata.deviceId().equals(snapshot.deviceId())
                || !metadata.modelVersionId().equals(snapshot.modelVersionId()) || snapshot.properties().isEmpty()
                || snapshot.properties().size()>10 || snapshot.collectionStartedAt().isAfter(snapshot.collectionFinishedAt())
                || snapshot.device()==null || snapshot.alarmSummary()==null || snapshot.device().readAt()==null
                || snapshot.device().status()==null || !Set.of("INACTIVE","ONLINE","OFFLINE").contains(snapshot.device().status())
                || snapshot.alarmSummary().state()==null || snapshot.alarmSummary().observedAt()==null)
            throw new IllegalStateException("历史事实报告来源合同异常");
        int available=0,omitted=0;var keys=new HashSet<String>();
        for (var p:snapshot.properties()) {
            boolean hasValue=p.value()!=null && !p.value().isNull();
            boolean safe=p.value()!=null && (p.value().isNumber() || p.value().isBoolean());
            if (p.key()==null || !p.key().matches("[A-Za-z0-9_-]{1,64}") || !keys.add(p.key()) || p.availability()==null
                    || p.readAt()==null || (hasValue && !safe)
                    || (p.availability()!=Availability.PRESENT && (hasValue || p.valueOmitted()))
                    || (p.availability()==Availability.PRESENT && safe==p.valueOmitted()))
                throw new IllegalStateException("历史属性报告来源合同异常");
            if (safe) available++;
            if (p.valueOmitted()) omitted++;
        }
        var text=new StringBuilder("# 个人设备历史事实报告\n\n");
        text.append("本报告只汇编已保存的历史事实，不代表设备当前状态，未做模型诊断或健康判定。\n\n")
                .append("## 报告依据\n\n")
                .append("- 来源记录：").append(metadata.id()).append('\n')
                .append("- 来源正文摘要：").append(metadata.contentSha256()).append('\n')
                .append("- 设备标识：").append(metadata.deviceId()).append('\n')
                .append("- 采集时物模型：").append(metadata.modelVersionId()).append('\n')
                .append("- 历史采集区间：").append(snapshot.collectionStartedAt()).append(" 至 ").append(snapshot.collectionFinishedAt()).append('\n')
                .append("- 来源保存时间：").append(metadata.createdAt()).append('\n')
                .append("- 来源可见期限：").append(metadata.expiresAt()).append("；到期或删除后不能再生成。\n\n")
                .append("## 记录范围与缺项\n\n")
                .append("本条记录选择了 ").append(snapshot.properties().size()).append(" 个属性，数字或布尔可用值 ")
                .append(available).append(" 个，未取得可用值 ").append(snapshot.properties().size()-available)
                .append(" 个，其中非数字或布尔值已省略 ").append(omitted).append(" 个。\n")
                .append("此统计只覆盖记录选择的属性，不代表全部设备属性、全项目覆盖率或诊断成功率。\n\n")
                .append("## 采集时摘要\n\n")
                .append("- 设备状态：").append(snapshot.device().status()).append("；读取时间：").append(snapshot.device().readAt()).append('\n')
                .append("- 最近在线时间：").append(time(snapshot.device().lastOnlineAt())).append('\n')
                .append("- 告警摘要：").append(snapshot.alarmSummary().state()).append("；观察时间：").append(snapshot.alarmSummary().observedAt()).append("\n\n")
                .append("## 属性事实\n\n");
        for (var p:snapshot.properties()) {
            text.append("- 属性 `").append(p.key()).append("`：来源状态 ").append(p.availability()).append("；值 ")
                    .append(p.valueOmitted()?"已省略（非数字或布尔）":p.value()==null || p.value().isNull()?"未提供可用值":p.value().toString())
                    .append("；单位未提供，不推断业务语义。\n")
                    .append("  上报时间：").append(time(p.occurredAt())).append("；来源物模型：")
                    .append(p.sourceModelVersionId()==null?"未提供":p.sourceModelVersionId()).append("；读取时间：").append(p.readAt()).append('\n');
        }
        text.append("\n## 能力边界\n\n")
                .append("缺项、值省略或无告警不能推断设备正常，也不能推断故障原因。本报告未分析历史时序、告警全明细或行业阈值，未生成设备指令。\n")
                .append("多个来源有各自读取时间，采集区间不是全系统的原子快照。记录以后换版或设备删除，不改写本报告的历史含义。\n");
        String markdown=text.toString();
        if (markdown.getBytes(StandardCharsets.UTF_8).length>131072) throw new IllegalStateException("历史报告超出固定字节上界");
        return new PersonalFactReport(1,"FACTS_ONLY",metadata,PersonalEvidenceRecordService.sha256(markdown),markdown);
    }
    private static String time(java.time.Instant value) { return value==null?"未提供":value.toString(); }
}
