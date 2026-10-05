package com.things.link.assistant.application;

import com.things.link.alarm.application.DeviceAlarmStatusSnapshot.State;
import com.things.link.device.application.ConsoleDeviceEvidence.Availability;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PersonalFactReportServiceTests {
    final Instant at=Instant.parse("2026-10-04T00:00:00Z");
    final UUID device=UUID.randomUUID(),model=UUID.randomUUID(),id=UUID.randomUUID();
    final JsonMapper json=JsonMapper.builder().build();
    PersonalEvidenceRecordView.Detail detail(List<PersonalEvidenceSnapshot.Property> properties) {
        var snapshot=new PersonalEvidenceSnapshot(1,device,model,at,at.plusSeconds(1),
                new DeviceEvidenceSnapshot.Device("OFFLINE",null,at),new DeviceEvidenceSnapshot.AlarmSummary(State.NORMAL,at),properties);
        return new PersonalEvidenceRecordView.Detail(new PersonalEvidenceRecordView(id,device,model,at,at.plusSeconds(2592000),"a".repeat(64)),snapshot);
    }
    PersonalEvidenceSnapshot.Property p(String key,Availability state,String value,boolean omitted) {
        return new PersonalEvidenceSnapshot.Property(key,state,value==null?null:json.readTree(value),omitted,null,
                state==Availability.PRESENT?model:null,at);
    }
    @Test void deterministicMarkdownKeepsRawNumericBooleanValuesAndExplicitCoverageGaps() {
        var source=detail(List.of(p("number",Availability.PRESENT,"12.3400",false),p("flag",Availability.PRESENT,"false",false),
                p("text",Availability.PRESENT,null,true),p("missing",Availability.MISSING,"null",false),p("unknown",Availability.SOURCE_UNKNOWN,null,false)));
        var first=PersonalFactReportService.render(source);var again=PersonalFactReportService.render(source);
        assertThat(first).isEqualTo(again);assertThat(first.mode()).isEqualTo("FACTS_ONLY");
        assertThat(first.contentSha256()).isEqualTo(PersonalEvidenceRecordService.sha256(first.markdown()));
        assertThat(first.markdown()).contains(id.toString(),"a".repeat(64),"选择了 5 个属性","可用值 2 个","未取得可用值 3 个","已省略 1 个",
                "值 false","单位未提供","不代表全部设备属性","未做模型诊断","来源状态 SOURCE_UNKNOWN","无告警不能推断设备正常");
        assertThat(first.toString()).doesNotContain("12.34",id.toString(),"a".repeat(64));
    }
    @Test void missingAndOmittedSurviveActualStoredJsonRoundTrip() {
        var source=detail(List.of(p("missing",Availability.MISSING,null,false),p("text",Availability.PRESENT,null,true)));
        var decoded=json.readValue(json.writeValueAsString(source),PersonalEvidenceRecordView.Detail.class);
        assertThat(PersonalFactReportService.render(decoded).markdown()).contains("未提供可用值","已省略（非数字或布尔）");
    }
    @Test void malformedPropertiesAndUncontrolledTextAreRejectedInsteadOfPrinted() {
        for(var property:List.of(p("bad`name",Availability.PRESENT,"1",false),p("x",Availability.MISSING,"1",false),
                p("x",Availability.PRESENT,"\"sensitive instruction\"",false),p("x",Availability.PRESENT,null,false),p("x",Availability.PRESENT,"true",true)))
            assertThatThrownBy(()->PersonalFactReportService.render(detail(List.of(property)))).isInstanceOf(IllegalStateException.class);
        var duplicate=p("x",Availability.PRESENT,"1",false);
        assertThatThrownBy(()->PersonalFactReportService.render(detail(List.of(duplicate,duplicate)))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->PersonalFactReportService.render(detail(List.of()))).isInstanceOf(IllegalStateException.class);
    }
    @Test void sourceMustRemainAuthorizedAfterRendering() {
        var records=mock(PersonalEvidenceRecordService.class);UUID project=UUID.randomUUID();
        var service=new PersonalFactReportService(records);var source=detail(List.of(p("x",Availability.PRESENT,"1",false)));
        when(records.read(project,id)).thenReturn(source).thenThrow(new IllegalStateException("access revoked"));
        assertThatThrownBy(()->service.generate(project,id)).isInstanceOf(IllegalStateException.class);verify(records,times(2)).read(project,id);
    }
    @Test void arbitraryDeviceStatusCannotIntroduceUncontrolledTextIntoReport() {
        var source=detail(List.of(p("x",Availability.PRESENT,"true",false)));var s=source.snapshot();
        var corrupted=new PersonalEvidenceRecordView.Detail(source.record(),new PersonalEvidenceSnapshot(1,device,model,at,at,
                new DeviceEvidenceSnapshot.Device("<img src=secret>",null,at),s.alarmSummary(),s.properties()));
        assertThatThrownBy(()->PersonalFactReportService.render(corrupted)).isInstanceOf(IllegalStateException.class);
    }
    @Test void returnedMetadataCannotDriftFromFrozenSource() {
        var records=mock(PersonalEvidenceRecordService.class);UUID project=UUID.randomUUID();var source=detail(List.of(p("x",Availability.PRESENT,"1",false)));
        var changed=new PersonalEvidenceRecordView.Detail(new PersonalEvidenceRecordView(id,device,model,at,at.plusSeconds(2592000),"b".repeat(64)),source.snapshot());
        when(records.read(project,id)).thenReturn(source,changed);
        assertThatThrownBy(()->new PersonalFactReportService(records).generate(project,id)).isInstanceOf(com.things.link.shared.error.BusinessException.class);
    }
}
