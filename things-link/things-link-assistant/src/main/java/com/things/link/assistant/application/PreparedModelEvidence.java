package com.things.link.assistant.application;

import com.fasterxml.jackson.annotation.JsonValue;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 内部投影回执；供应商请求仅消费 input，不发送配置版本、位置映射或缺项回执。 */
public record PreparedModelEvidence(Input input, String policyFingerprint,
        Map<String, Integer> propertyPositions, List<Omission> omissions) {
    public PreparedModelEvidence {
        propertyPositions = Map.copyOf(propertyPositions); omissions = List.copyOf(omissions);
    }
    public enum Template { STATUS_SUMMARY, ALARM_EXPLANATION }
    public enum DeviceStatus { INACTIVE, ONLINE, OFFLINE }
    public enum AlarmState { ACTIVE, NORMAL }
    public enum OmissionReason { UNCONFIGURED, UNAVAILABLE, SOURCE_MISMATCH, MISSING_TIME, INVALID_VALUE }
    public record Omission(int propertyPosition, OmissionReason reason) {}
    public record Input(Template template, String deviceAlias, Instant collectionStartedAt,
            Instant collectionFinishedAt, Device device, Alarm alarm, List<Reading> readings) {
        public Input { readings = List.copyOf(readings); }
        public Set<String> evidenceIds() {
            var ids = new java.util.HashSet<String>();
            ids.add(device.evidenceId()); ids.add(alarm.evidenceId());
            readings.forEach(reading -> ids.add(reading.evidenceId()));
            return Set.copyOf(ids);
        }
        @Override public String toString() { return "ModelEvidenceInput[template=" + template + ",readings=" + readings.size() + "]"; }
    }
    public record Device(String evidenceId, DeviceStatus status, Instant lastOnlineAt, Instant readAt) {}
    public record Alarm(String evidenceId, AlarmState state, Instant observedAt) {}
    public record Reading(String evidenceId, OutboundPropertyPolicy.Semantic semantic,
            OutboundPropertyPolicy.Unit unit, Scalar value, Instant occurredAt, Instant readAt) {
        @Override public String toString() { return "ModelReading[REDACTED]"; }
    }
    public sealed interface Scalar permits NumberValue, BooleanValue {}
    public record NumberValue(BigDecimal value) implements Scalar {
        public NumberValue { if (value == null) throw new IllegalArgumentException("missing number"); }
        @JsonValue @Override public BigDecimal value() { return value; }
        @Override public String toString() { return "NumberValue[REDACTED]"; }
    }
    public record BooleanValue(boolean value) implements Scalar {
        @JsonValue @Override public boolean value() { return value; }
    }
}
