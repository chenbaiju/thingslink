package com.things.link.assistant.application;

import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.CommonErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

/** 有界历史事实汇编；复用本人来源读取和统一元数据复核，不取得新设备数据或模型许可。 */
@Service
public class PersonalFactCollectionService {
    private final PersonalEvidenceRecordService records;
    /** @param records 原个人事实与当前权限端口，持久化仍由所属应用负责 */
    public PersonalFactCollectionService(PersonalEvidenceRecordService records) { this.records=records; }

    /**
     * 手动汇编最多五设备，每设备一条本人历史来源；全部成功且统一复核后才返回正文。
     * @param project 当前受权项目
     * @param recordIds 一至五个不同个人来源标识，不接受报告正文或实时设备查询
     * @return 来源绑定的纯事实集合；重复生成无写入或付费，任一来源失效整体拒绝
     */
    @Transactional(propagation=Propagation.NOT_SUPPORTED)
    public PersonalFactCollectionReport generate(UUID project,List<UUID> recordIds) {
        if (recordIds==null || recordIds.isEmpty() || recordIds.size()>5 || recordIds.stream().anyMatch(java.util.Objects::isNull)
                || new HashSet<>(recordIds).size()!=recordIds.size()) throw invalid();
        var sources=new ArrayList<PersonalEvidenceRecordView>();var reports=new ArrayList<String>();
        var devices=new HashSet<UUID>();Instant first=null,last=null,expires=null;
        int total=0,available=0,omitted=0;
        for (UUID id:recordIds.stream().sorted(Comparator.comparing(UUID::toString)).toList()) {
            var source=records.read(project,id);
            if (!id.equals(source.record().id())) throw new IllegalStateException("集合事实来源标识不一致");
            var report=PersonalFactReportService.render(source);
            if (!devices.add(source.record().deviceId())) throw invalid();
            sources.add(source.record());reports.add(report.markdown());
            var snapshot=source.snapshot();
            first=first==null || snapshot.collectionStartedAt().isBefore(first)?snapshot.collectionStartedAt():first;
            last=last==null || snapshot.collectionFinishedAt().isAfter(last)?snapshot.collectionFinishedAt():last;
            expires=expires==null || source.record().expiresAt().isBefore(expires)?source.record().expiresAt():expires;
            total+=snapshot.properties().size();
            for(var property:snapshot.properties()) {
                if(property.value()!=null && (property.value().isNumber() || property.value().isBoolean())) available++;
                if(property.valueOmitted()) omitted++;
            }
        }
        var text=new StringBuilder("# 个人设备集合历史事实报告\n\n");
        text.append("本报告只汇编本人明确选择的历史记录，未做实时巡检、模型诊断、健康判定或设备控制。\n\n")
                .append("## 选择范围与缺项\n\n")
                .append("选定设备 ").append(devices.size()).append(" 个，来源记录 ").append(sources.size()).append(" 条。\n")
                .append("所选属性总数 ").append(total).append("，数值或布尔可用值 ").append(available)
                .append("，未取得可用值 ").append(total-available).append("，其中省略值 ").append(omitted).append("。\n")
                .append("统计分母仅限所选来源，不代表全部项目设备、实时覆盖率或诊断成功率；缺项不代表正常。\n")
                .append("历史采集包络：").append(first).append(" 至 ").append(last)
                .append("；包络内不保证连续数据或同时刻原子快照。\n")
                .append("最早来源可见期限：").append(expires).append("；任一来源删除、到期或撤权，不能再生成本集合。\n\n")
                .append("## 各设备历史来源\n\n");
        for (String report:reports) text.append(report).append("\n---\n\n");
        String markdown=text.toString();
        if (markdown.getBytes(StandardCharsets.UTF_8).length>1048576) throw new IllegalStateException("集合事实报告超出固定字节上界");
        var result=new PersonalFactCollectionReport(1,"FACTS_ONLY","SELECTED_PERSONAL_RECORDS",sources,
                new PersonalFactCollectionReport.Coverage(devices.size(),total,available,total-available,omitted),
                first,last,expires,PersonalEvidenceRecordService.sha256(markdown),markdown);
        records.verifyReportSources(project,sources);
        return result;
    }
    private static BusinessException invalid() { return new BusinessException(CommonErrorCode.INVALID_PARAMETER); }
}
