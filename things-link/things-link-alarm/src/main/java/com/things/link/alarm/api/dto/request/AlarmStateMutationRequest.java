package com.things.link.alarm.api.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** ACK 或人工清除必须带出读取时 version，避免操作员覆盖另一人的并发维护。 */
public record AlarmStateMutationRequest(@NotNull @Min(0) Integer version) { }
