package com.things.link.alarm.api.dto.request;

import java.util.List;
import java.util.UUID;

/**
 * ADR0093：只接受明确展示的事件身份；原始数组上限和空元素在服务权限核对后统一校验。
 * @param eventIds 原始数组1至100个非空身份，不允许用时间水位代替
 */
public record AlarmInboxReadRequest(List<UUID> eventIds) {
}
