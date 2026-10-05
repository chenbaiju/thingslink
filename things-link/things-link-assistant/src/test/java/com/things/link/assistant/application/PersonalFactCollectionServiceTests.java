package com.things.link.assistant.application;

import com.things.link.alarm.application.DeviceAlarmStatusSnapshot.State;
import com.things.link.device.application.ConsoleDeviceEvidence.Availability;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PersonalFactCollectionServiceTests {
    final PersonalEvidenceRecordService records=mock(PersonalEvidenceRecordService.class);
    final PersonalFactCollectionService service=new PersonalFactCollectionService(records);
    final UUID project=UUID.randomUUID(), a=new UUID(0,1), b=new UUID(0,2), deviceA=UUID.randomUUID(),deviceB=UUID.randomUUID(),model=UUID.randomUUID();
    final Instant at=Instant.parse("2026-10-04T00:00:00Z");
    final JsonMapper json=JsonMapper.builder().build();
    PersonalEvidenceRecordView.Detail source(UUID id,UUID device,int seconds) {
        var metadata=new PersonalEvidenceRecordView(id,device,model,at,at.plusSeconds(2592000-seconds),"a".repeat(64));
        var properties=List.of(new PersonalEvidenceSnapshot.Property("n",Availability.PRESENT,json.readTree("0"),false,at,model,at),
                new PersonalEvidenceSnapshot.Property("missing",Availability.MISSING,null,false,null,null,at),
                new PersonalEvidenceSnapshot.Property("text",Availability.PRESENT,null,true,at,model,at));
        return new PersonalEvidenceRecordView.Detail(metadata,new PersonalEvidenceSnapshot(1,device,model,at.plusSeconds(seconds),at.plusSeconds(seconds+1),
                new DeviceEvidenceSnapshot.Device("OFFLINE",null,at),new DeviceEvidenceSnapshot.AlarmSummary(State.NORMAL,at),properties));
    }
    @Test void canonicalOrderKeepsCoverageSelectedAndTimeEnvelopeExplicit() {
        var first=source(a,deviceA,0);var second=source(b,deviceB,60);
        when(records.read(project,a)).thenReturn(first);when(records.read(project,b)).thenReturn(second);
        var report=service.generate(project,List.of(b,a));var again=service.generate(project,List.of(a,b));
        assertThat(report).isEqualTo(again);assertThat(report.sourceRecords()).containsExactly(first.record(),second.record());
        assertThat(report.coverage()).isEqualTo(new PersonalFactCollectionReport.Coverage(2,6,2,4,2));
        assertThat(report.earliestCollectionAt()).isEqualTo(at);assertThat(report.latestCollectionAt()).isEqualTo(at.plusSeconds(61));
        assertThat(report.expiresAt()).isEqualTo(second.record().expiresAt());
        assertThat(report.markdown()).contains("未做实时巡检","统计分母仅限所选来源","不保证连续数据","来源状态 MISSING","值 0");
        assertThat(report.contentSha256()).isEqualTo(PersonalEvidenceRecordService.sha256(report.markdown()));
        assertThat(report.toString()).doesNotContain(a.toString(),"统计分母","a".repeat(64));
        verify(records,times(2)).verifyReportSources(project,List.of(first.record(),second.record()));
    }
    @Test void oneRecordPerDeviceAndFiveSourceLimitAreEnforced() {
        when(records.read(project,a)).thenReturn(source(a,deviceA,0));when(records.read(project,b)).thenReturn(source(b,deviceA,1));
        assertThatThrownBy(()->service.generate(project,List.of(a,b))).isInstanceOf(RuntimeException.class);
        verify(records,never()).verifyReportSources(any(),any());reset(records);
        for(List<UUID> ids:List.of(List.<UUID>of(),List.of(a,a),Collections.<UUID>singletonList(null),List.of(a,b,UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID())))
            assertThatThrownBy(()->service.generate(project,ids)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->service.generate(project,null)).isInstanceOf(RuntimeException.class);verifyNoInteractions(records);
    }
    @Test void fiveDistinctSourcesAreAcceptedWithoutAnyExtraEvidencePort() {
        var ids=java.util.stream.LongStream.rangeClosed(1,5).mapToObj(n->new UUID(0,n)).toList();
        for(var id:ids) when(records.read(project,id)).thenReturn(source(id,UUID.randomUUID(),0));
        assertThat(service.generate(project,ids).coverage().devices()).isEqualTo(5);
        verify(records,times(5)).read(eq(project),any());verify(records).verifyReportSources(eq(project),any());verifyNoMoreInteractions(records);
    }
    @Test void anyUnavailableSourceOrFinalVerificationFailureRejectsWholeReport() {
        when(records.read(project,a)).thenReturn(source(a,deviceA,0));when(records.read(project,b)).thenThrow(new IllegalStateException("invisible"));
        assertThatThrownBy(()->service.generate(project,List.of(a,b))).isInstanceOf(IllegalStateException.class);verify(records,never()).verifyReportSources(any(),any());
        doThrow(new IllegalStateException("source deleted before final check")).when(records).verifyReportSources(eq(project),any());
        assertThatThrownBy(()->service.generate(project,List.of(a))).isInstanceOf(IllegalStateException.class);
    }
    @Test void sourceIdentityOrMalformedBodyCannotBeSilentlySubstituted() {
        when(records.read(project,a)).thenReturn(source(b,deviceA,0));
        assertThatThrownBy(()->service.generate(project,List.of(a))).isInstanceOf(IllegalStateException.class);
        var bad=source(a,deviceA,0);var s=bad.snapshot();
        when(records.read(project,a)).thenReturn(new PersonalEvidenceRecordView.Detail(bad.record(),new PersonalEvidenceSnapshot(1,deviceA,model,at,at,
                s.device(),s.alarmSummary(),List.of())));
        assertThatThrownBy(()->service.generate(project,List.of(a))).isInstanceOf(IllegalStateException.class);verify(records,never()).verifyReportSources(any(),any());
    }
}
