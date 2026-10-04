package com.things.link.alarm.domain;

import com.things.link.shared.page.CursorPage;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 告警域规则持久化端口；只能由 alarm 模块的 infrastructure 实现。 */
public interface AlarmRuleRepository {
    /** @param rule 新规则 @return 是否插入成功 */ boolean create(AlarmRule rule);
    /** @param projectId 项目 @param ruleId 规则 @return 有效规则 */ Optional<AlarmRule> findById(UUID projectId, UUID ruleId);
    /** @param projectId 项目 @param cursor 游标 @param limit 数量 @return 稳定倒序规则页 */ CursorPage<AlarmRule> page(UUID projectId, String cursor, int limit);
    /** @param rule 带期望 version 的新值 @return CAS 是否成功 */ boolean update(AlarmRule rule);
    /** @param projectId 项目 @param ruleId 规则 @param version 期望版本 @return CAS 软删是否成功 */ boolean softDelete(UUID projectId, UUID ruleId, int version);
    /** 仅供可信上行评估读取，不附带 HTTP 授权。 */ List<AlarmRule> findEnabledByProperty(UUID projectId, UUID deviceId, String propertyKey);
}
