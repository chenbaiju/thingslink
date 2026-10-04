package com.things.link.telemetry.infrastructure.persistence;

import com.things.link.project.application.SelfHostedMessageUsageReader;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.UUID;

/** 遥测领域只向平台应用端口提供已绑定租户的 UTC 日逻辑消息总数。 */
@Repository
public class JdbcSelfHostedMessageUsageReader implements SelfHostedMessageUsageReader {
    private final JdbcTemplate jdbc;

    public JdbcSelfHostedMessageUsageReader(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public long acceptedMessages(UUID billingTenantId, LocalDate utcDay, Direction direction) {
        Long used = jdbc.queryForObject("SELECT public.shc_local_message_usage(?,?,?)", Long.class,
                billingTenantId, utcDay, direction.name());
        if (used == null) throw new IllegalStateException("消息权威用量为空");
        return used;
    }
}
