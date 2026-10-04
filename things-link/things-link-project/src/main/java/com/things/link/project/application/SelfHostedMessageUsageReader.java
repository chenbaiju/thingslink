package com.things.link.project.application;

import java.time.LocalDate;
import java.util.UUID;

/** 自部署逻辑消息用量端口；由拥有上/下行事实的模块实现。 */
public interface SelfHostedMessageUsageReader {
    long acceptedMessages(UUID billingTenantId, LocalDate utcDay, Direction direction);

    enum Direction { UP, DOWN }
}
