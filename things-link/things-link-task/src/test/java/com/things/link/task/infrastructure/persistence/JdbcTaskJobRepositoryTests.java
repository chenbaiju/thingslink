package com.things.link.task.infrastructure.persistence;

import com.things.link.task.domain.TaskJob;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 任务定义与唯一调度定义的 JDBC 双写结果检查。 */
class JdbcTaskJobRepositoryTests {

    /** 调度定义缺失时不能把任务行更新伪装成完整成功。 */
    @Test
    void updateRejectsMissingScheduleAfterJobCas() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1, 0);
        JdbcTaskJobRepository repository = new JdbcTaskJobRepository(jdbc);
        Instant now = Instant.parse("2026-09-04T12:00:00Z");
        TaskJob job = new TaskJob(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "任务", null,
                TaskJob.Status.ACTIVE, 2L, TaskJob.ScheduleType.CRON, null, "0 * * * * *",
                "Asia/Shanghai", now.plusSeconds(60), TaskJob.TargetType.ALL_DEVICES, null,
                "reboot", "{}", UUID.randomUUID(), now.minusSeconds(60), now);

        assertThatThrownBy(() -> repository.update(job, 1L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("任务定义更新必须同步更新唯一调度定义");
    }
}
