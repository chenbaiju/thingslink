package com.things.link.assistant.application;

import com.things.link.alarm.application.DeviceAlarmStatusSnapshot;
import com.things.link.assistant.application.OutboundPropertyPolicy.*;
import com.things.link.assistant.application.PreparedModelEvidence.*;
import com.things.link.device.application.ConsoleDeviceEvidence;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class OutboundEvidenceProjectorTests {
    final UUID project=UUID.randomUUID(), model=UUID.randomUUID(), device=UUID.randomUUID();
    final Instant now=Instant.parse("2026-10-03T00:00:00Z");
    final JsonMapper json=JsonMapper.builder().build();
    Binding binding(String key,Semantic semantic,Unit unit) { return new Binding(project,model,key,semantic,unit); }
    OutboundEvidenceProjector projector(Binding... bindings) { return new OutboundEvidenceProjector(new OutboundPropertyPolicy(List.of(bindings))); }
    ConsoleDeviceEvidence.Property property(String key,JsonNode value) {
        return new ConsoleDeviceEvidence.Property(key,value,now,"7",model,now,ConsoleDeviceEvidence.Availability.PRESENT);
    }
    DeviceEvidenceSnapshot snapshot(ConsoleDeviceEvidence.Property... properties) {
        return new DeviceEvidenceSnapshot(1,project,device,model,now,now,
                new DeviceEvidenceSnapshot.Device("INACTIVE",null,now),List.of(properties),
                new DeviceEvidenceSnapshot.AlarmSummary(DeviceAlarmStatusSnapshot.State.ACTIVE,now));
    }
    @Test void emptyPolicyOmitsEverythingWithoutGuessingFromKeyNames() {
        var prepared=projector().prepare(snapshot(property("temperature",json.readTree("23.5"))),Template.STATUS_SUMMARY);
        assertThat(prepared.input().readings()).isEmpty();
        assertThat(prepared.omissions()).containsExactly(new Omission(0,OmissionReason.UNCONFIGURED));
        assertThat(prepared.input().device().status()).isEqualTo(DeviceStatus.INACTIVE);
        assertThat(prepared.input().device().lastOnlineAt()).isNull();
        assertThat(prepared.input().alarm().state()).isEqualTo(AlarmState.ACTIVE);
        assertThat(prepared.input().evidenceIds()).containsExactlyInAnyOrder("e-device","e-alarm");
    }
    @Test void numbersAndFalseRemainExactWithoutConversionOrZeroFilling() {
        var p=projector(binding("private_numeric",Semantic.TEMPERATURE,Unit.KELVIN),binding("private_boolean",Semantic.BINARY_STATE,Unit.UNITLESS));
        var prepared=p.prepare(snapshot(property("private_numeric",JsonNodeFactory.instance.numberNode(new BigDecimal("273.1500"))),
                property("private_boolean",json.readTree("false"))),Template.ALARM_EXPLANATION);
        assertThat(((NumberValue)prepared.input().readings().get(0).value()).value()).isEqualTo(new BigDecimal("273.1500"));
        assertThat(((BooleanValue)prepared.input().readings().get(1).value()).value()).isFalse();
        assertThat(prepared.input().readings().get(0).unit()).isEqualTo(Unit.KELVIN);
        assertThat(prepared.propertyPositions()).containsEntry("e-property-1",0).containsEntry("e-property-2",1);
        assertThat(prepared.omissions()).isEmpty();
    }
    @Test void bindingIsExactForProjectAndPublishedVersion() {
        for(Binding foreign:List.of(new Binding(UUID.randomUUID(),model,"temperature",Semantic.TEMPERATURE,Unit.CELSIUS),
                new Binding(project,UUID.randomUUID(),"temperature",Semantic.TEMPERATURE,Unit.CELSIUS))) {
            var prepared=projector(foreign).prepare(snapshot(property("temperature",json.readTree("1"))),Template.STATUS_SUMMARY);
            assertThat(prepared.input().readings()).isEmpty();
        }
    }
    @Test void unavailablePropertiesNeverBecomeZeroOrFalse() {
        var p=projector(binding("temperature",Semantic.TEMPERATURE,Unit.CELSIUS));
        for(var availability:List.of(ConsoleDeviceEvidence.Availability.MISSING,ConsoleDeviceEvidence.Availability.SOURCE_UNKNOWN,
                ConsoleDeviceEvidence.Availability.MODEL_MISMATCH)) {
            var source=new ConsoleDeviceEvidence.Property("temperature",json.readTree("30"),now,"7",model,now,availability);
            var prepared=p.prepare(snapshot(source),Template.STATUS_SUMMARY);
            assertThat(prepared.input().readings()).isEmpty();
            assertThat(prepared.omissions()).containsExactly(new Omission(0,OmissionReason.UNAVAILABLE));
        }
    }
    @Test void presentButUnknownOrWrongSourceIsStillOmitted() {
        var p=projector(binding("temperature",Semantic.TEMPERATURE,Unit.CELSIUS));
        for(UUID source:Arrays.asList(null,UUID.randomUUID())) {
            var property=new ConsoleDeviceEvidence.Property("temperature",json.readTree("1"),now,"7",source,now,ConsoleDeviceEvidence.Availability.PRESENT);
            assertThat(p.prepare(snapshot(property),Template.STATUS_SUMMARY).omissions()).containsExactly(new Omission(0,OmissionReason.SOURCE_MISMATCH));
        }
    }
    @Test void missingSourceTimeIsNotReplacedByCollectionTime() {
        var source=new ConsoleDeviceEvidence.Property("temperature",json.readTree("1"),null,"7",model,now,ConsoleDeviceEvidence.Availability.PRESENT);
        var p=projector(binding("temperature",Semantic.TEMPERATURE,Unit.CELSIUS));
        assertThat(p.prepare(snapshot(source),Template.STATUS_SUMMARY).omissions()).containsExactly(new Omission(0,OmissionReason.MISSING_TIME));
    }
    @Test void stringsObjectsArraysNullAndNonFiniteNumbersAreNeverCoerced() {
        var p=projector(binding("temperature",Semantic.TEMPERATURE,Unit.CELSIUS));
        List<JsonNode> values=new ArrayList<>(List.of(json.readTree("\"synthetic-private-text\""),json.readTree("{}"),json.readTree("[]"),
                json.readTree("true"),json.readTree("null"),JsonNodeFactory.instance.numberNode(Double.NaN),JsonNodeFactory.instance.numberNode(Double.POSITIVE_INFINITY)));
        values.add(null);
        for(JsonNode value:values) {
            var prepared=p.prepare(snapshot(property("temperature",value)),Template.STATUS_SUMMARY);
            assertThat(prepared.input().readings()).isEmpty();
            assertThat(prepared.omissions()).containsExactly(new Omission(0,OmissionReason.INVALID_VALUE));
        }
        var bool=projector(binding("switch",Semantic.BINARY_STATE,Unit.UNITLESS));
        assertThat(bool.prepare(snapshot(property("switch",json.readTree("1"))),Template.STATUS_SUMMARY).input().readings()).isEmpty();
    }
    @Test void serializedInputHasOnlySafeScalarsEnumsAndTimes() {
        var prepared=projector(binding("private_property",Semantic.TEMPERATURE,Unit.CELSIUS))
                .prepare(snapshot(property("private_property",json.readTree("23.5"))),Template.STATUS_SUMMARY);
        String body=json.writeValueAsString(prepared.input());
        assertThat(body).doesNotContain(project.toString(),model.toString(),device.toString(),"private_property","reportedRevision","sourceModelVersionId","policyFingerprint","propertyPositions");
        assertThat(json.readTree(body).propertyNames()).containsExactlyInAnyOrder("template","deviceAlias","collectionStartedAt","collectionFinishedAt","device","alarm","readings");
        assertThat(json.readTree(body).path("readings").get(0).path("value").isNumber()).isTrue();
        assertThat(prepared.input().toString()).doesNotContain("23.5","private_property");
        assertThat(prepared.input().readings().get(0).toString()).doesNotContain("23.5");
    }
    @Test void unknownStatusesOrDuplicatePropertiesRejectWholeEvidence() {
        var s=snapshot();var unknown=new DeviceEvidenceSnapshot(1,project,device,model,now,now,
                new DeviceEvidenceSnapshot.Device("private-bad-status",null,now),List.of(),s.alarmSummary());
        assertThatThrownBy(()->projector().prepare(unknown,Template.STATUS_SUMMARY))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("invalid trusted device status");
        var property=property("temperature",json.readTree("1"));
        assertThatThrownBy(()->projector().prepare(snapshot(property,property),Template.STATUS_SUMMARY)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void configurationFingerprintIsOrderIndependentAndChangesWithMapping() {
        var first=binding("temperature",Semantic.TEMPERATURE,Unit.CELSIUS);var second=binding("humidity",Semantic.RELATIVE_HUMIDITY,Unit.PERCENT);
        var a=new OutboundPropertyPolicy(List.of(first,second));var b=new OutboundPropertyPolicy(List.of(second,first));
        assertThat(a.fingerprint()).isEqualTo(b.fingerprint()).matches("[0-9a-f]{64}");
        assertThat(a.fingerprint()).isNotEqualTo(new OutboundPropertyPolicy(List.of(binding("temperature",Semantic.TEMPERATURE,Unit.KELVIN),second)).fingerprint());
        assertThat(a.toString()).doesNotContain(project.toString(),"temperature");
    }
    @Test void duplicateOrIncompatiblePolicyIsRejected() {
        var binding=binding("temperature",Semantic.TEMPERATURE,Unit.CELSIUS);
        assertThatThrownBy(()->new OutboundPropertyPolicy(List.of(binding,binding))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->binding("temperature",Semantic.TEMPERATURE,Unit.WATT)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->binding("private text",Semantic.TEMPERATURE,Unit.CELSIUS)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void policyAndPreparedCollectionsCannotBeMutated() {
        var source=new ArrayList<Binding>();source.add(binding("temperature",Semantic.TEMPERATURE,Unit.CELSIUS));
        var p=new OutboundEvidenceProjector(new OutboundPropertyPolicy(source));source.clear();
        var prepared=p.prepare(snapshot(property("temperature",json.readTree("1"))),Template.STATUS_SUMMARY);
        assertThat(prepared.input().readings()).hasSize(1);
        assertThatThrownBy(()->prepared.input().readings().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(()->prepared.propertyPositions().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(()->prepared.omissions().clear()).isInstanceOf(UnsupportedOperationException.class);
    }
}
