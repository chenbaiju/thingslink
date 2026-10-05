package com.things.link.assistant.application;

import com.things.link.assistant.domain.AnalysisCall;
import java.time.Instant;
import java.util.UUID;

/** 可返回的调用状态；不是历史分析结果，也不是重复出站授权。 */
@io.swagger.v3.oas.annotations.media.Schema(name="AssistantAnalysisCallView",requiredProperties={"id","status","createdAt","deadline","expiresAt","dispatchedAt","finishedAt"},
        additionalProperties=io.swagger.v3.oas.annotations.media.Schema.AdditionalPropertiesValue.FALSE)
public record AnalysisCallView(UUID id, AnalysisCall.Status status, Instant createdAt,
        Instant deadline, Instant expiresAt,
        @io.swagger.v3.oas.annotations.media.Schema(types={"string","null"},format="date-time") Instant dispatchedAt,
        @io.swagger.v3.oas.annotations.media.Schema(types={"string","null"},format="date-time") Instant finishedAt) {
    static AnalysisCallView of(AnalysisCall c) {
        return new AnalysisCallView(c.id(), c.status(), c.createdAt(), c.deadline(),
                c.expiresAt(), c.dispatchedAt(), c.finishedAt());
    }
    public record Reservation(AnalysisCallView call, boolean fresh) {}
}
