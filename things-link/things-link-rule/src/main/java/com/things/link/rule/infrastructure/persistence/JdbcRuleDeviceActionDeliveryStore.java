package com.things.link.rule.infrastructure.persistence;

import com.things.link.rule.application.outbox.RuleDeviceActionDeliveryStore;
import com.things.link.shared.message.DeviceCommandDispatch;
import com.things.link.shared.message.DeviceCommandTerminalEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/** 通过受限函数保存设备动作投递并按命令终态幂等回写。 */
@Repository
public class JdbcRuleDeviceActionDeliveryStore implements RuleDeviceActionDeliveryStore {
    /** JDBC 入口。 */ private final JdbcTemplate jdbcTemplate;
    /** @param jdbcTemplate JDBC 入口 */
    public JdbcRuleDeviceActionDeliveryStore(JdbcTemplate jdbcTemplate) { this.jdbcTemplate = jdbcTemplate; }
    /** {@inheritDoc} */
    @Override public void record(UUID actionId, UUID tenantId, UUID projectId, UUID ruleId, UUID ruleVersionId,
                                 UUID messageId, UUID deviceId, DeviceCommandDispatch.OperationType operationType,
                                 UUID commandId, String status, String failureCode, String traceId, Instant createdAt) {
        jdbcTemplate.queryForObject("SELECT rule_device_action_delivery_record(?,?,?,?,?,?,?,?,?,?,?,?,?)",
                Boolean.class, actionId, tenantId, projectId, ruleId, ruleVersionId, messageId, deviceId,
                operationType.name(), commandId, status, failureCode, traceId, Timestamp.from(createdAt));
    }
    /** {@inheritDoc} */
    @Override public void recordScene(UUID actionId, UUID tenantId, UUID projectId, UUID sceneId, UUID sceneVersionId,
                                      UUID sceneExecutionId, UUID deviceId, DeviceCommandDispatch.OperationType operationType,
                                      UUID commandId, String status, String failureCode, String traceId, Instant createdAt) {
        jdbcTemplate.queryForObject(
                "SELECT rule_device_action_delivery_record_scene(?,?,?,?,?,?,?,?,?,?,?,?,?)",
                Boolean.class, actionId, tenantId, projectId, sceneId, sceneVersionId, sceneExecutionId, deviceId,
                operationType.name(), commandId, status, failureCode, traceId, Timestamp.from(createdAt));
    }
    /** {@inheritDoc} */
    @Override public void recordAutomation(UUID actionId, UUID tenantId, UUID projectId, UUID automationId,
            UUID automationVersionId, UUID automationExecutionId, UUID deviceId,
            DeviceCommandDispatch.OperationType operationType, UUID commandId, String status,
            String failureCode, String traceId, Instant createdAt) {
        if (!Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT rule_device_action_delivery_record_automation(?,?,?,?,?,?,?,?,?,?,?,?,?)", Boolean.class,
                actionId, tenantId, projectId, automationId, automationVersionId, automationExecutionId, deviceId,
                operationType.name(), commandId, status, failureCode, traceId, Timestamp.from(createdAt)))) {
            throw new IllegalStateException("自动化设备投递事实冲突");
        }
    }
    /** {@inheritDoc} */
    @Override public boolean complete(DeviceCommandTerminalEvent event) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT rule_device_action_delivery_complete(?,?,?,?,?,?,?)", Boolean.class,
                event.tenantId(), event.projectId(), event.commandId(), event.operationType().name(),
                event.status().name(), event.failureCode(), Timestamp.from(event.completedAt())));
    }
}
