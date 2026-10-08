import request from '@/utils/http'
import type { components } from '@/types/api/schema'

/** 告警规则响应；字段及枚举只由 OpenAPI 生成物约束。 */
export type AlarmRuleResponse = components['schemas']['AlarmRuleResponse']
/** 告警规则写入请求；不允许前端扩展为自由脚本或表达式。 */
export type SaveAlarmRuleRequest = components['schemas']['SaveAlarmRuleRequest']
/** 告警规则游标页。 */
export type AlarmRulePage = components['schemas']['CursorPageAlarmRuleResponse']
/** 告警实例响应；条件状态与确认状态保持两个字段。 */
export type AlarmInstanceResponse = components['schemas']['AlarmInstanceResponse']
/** 告警实例游标页。 */
export type AlarmInstancePage = components['schemas']['CursorPageAlarmInstanceResponse']
/** 告警不可变事件响应。 */
export type AlarmEventResponse = components['schemas']['AlarmEventResponse']
/** 告警事件游标页。 */
export type AlarmEventPage = components['schemas']['CursorPageAlarmEventResponse']
/** ACK 与人工清除共用的 CAS 请求。 */
export type AlarmStateMutationRequest = components['schemas']['AlarmStateMutationRequest']
/** 通知组响应。 */
export type AlarmNotificationGroupResponse = components['schemas']['AlarmNotificationGroupResponse']
/** 通知组写入请求。 */
export type SaveAlarmNotificationGroupRequest =
  components['schemas']['SaveAlarmNotificationGroupRequest']
/** 通知组游标页。 */
export type AlarmNotificationGroupPage =
  components['schemas']['CursorPageAlarmNotificationGroupResponse']
/** 通知组收件人响应；target 由后端脱敏。 */
export type AlarmNotificationRecipientResponse =
  components['schemas']['AlarmNotificationRecipientResponse']
/** 收件人写入请求；更新时 target 必须显式提交完整新值。 */
export type SaveAlarmNotificationRecipientRequest =
  components['schemas']['SaveAlarmNotificationRecipientRequest']
/** 固定变量通知模板响应。 */
export type AlarmNotificationTemplateResponse =
  components['schemas']['AlarmNotificationTemplateResponse']
/** 通知模板写入请求。 */
export type SaveAlarmNotificationTemplateRequest =
  components['schemas']['SaveAlarmNotificationTemplateRequest']
/** 通知模板游标页。 */
export type AlarmNotificationTemplatePage =
  components['schemas']['CursorPageAlarmNotificationTemplateResponse']
/** 告警规则到通知组与模板的路由绑定响应。 */
export type AlarmNotificationBindingResponse =
  components['schemas']['AlarmNotificationBindingResponse']
/** 通知路由绑定写入请求。 */
export type SaveAlarmNotificationBindingRequest =
  components['schemas']['SaveAlarmNotificationBindingRequest']

/** 分页读取项目告警规则。 */
export function fetchAlarmRules(projectId: string, cursor?: string, limit = 50) {
  return request.get<AlarmRulePage>({
    url: `/api/v1/projects/${projectId}/alarm-rules`,
    params: { cursor, limit }
  })
}

/** 创建固定白名单数值阈值规则。 */
export function fetchCreateAlarmRule(projectId: string, body: SaveAlarmRuleRequest) {
  return request.post<AlarmRuleResponse>({
    url: `/api/v1/projects/${projectId}/alarm-rules`,
    params: body
  })
}

/** 使用版本号 CAS 修改规则。 */
export function fetchUpdateAlarmRule(
  projectId: string,
  ruleId: string,
  body: SaveAlarmRuleRequest
) {
  return request.put<AlarmRuleResponse>({
    url: `/api/v1/projects/${projectId}/alarm-rules/${ruleId}`,
    params: body
  })
}

/** 软删除规则；既有实例和事件仍保留。 */
export function fetchDeleteAlarmRule(projectId: string, ruleId: string, version: number) {
  return request.del<void>({
    url: `/api/v1/projects/${projectId}/alarm-rules/${ruleId}`,
    params: { version }
  })
}

/** 分页读取当前项目的活动与历史告警实例。 */
export function fetchAlarmInstances(projectId: string, cursor?: string, limit = 50) {
  return request.get<AlarmInstancePage>({
    url: `/api/v1/projects/${projectId}/alarms`,
    params: { cursor, limit }
  })
}

/** 读取单个告警实例的当前摘要；不以列表缓存代替详情。 */
export function fetchAlarmInstance(projectId: string, instanceId: string) {
  return request.get<AlarmInstanceResponse>({
    url: `/api/v1/projects/${projectId}/alarms/${instanceId}`
  })
}

/** 分页读取单个实例的不可变事件时间线。 */
export function fetchAlarmEvents(
  projectId: string,
  instanceId: string,
  cursor?: string,
  limit = 50
) {
  return request.get<AlarmEventPage>({
    url: `/api/v1/projects/${projectId}/alarms/${instanceId}/events`,
    params: { cursor, limit }
  })
}

/** 确认告警但不改变条件状态。 */
export function fetchAcknowledgeAlarm(
  projectId: string,
  instanceId: string,
  body: AlarmStateMutationRequest
) {
  return request.post<AlarmInstanceResponse>({
    url: `/api/v1/projects/${projectId}/alarms/${instanceId}/ack`,
    params: body
  })
}

/** 人工清除活动告警但不自动确认。 */
export function fetchClearAlarm(
  projectId: string,
  instanceId: string,
  body: AlarmStateMutationRequest
) {
  return request.post<AlarmInstanceResponse>({
    url: `/api/v1/projects/${projectId}/alarms/${instanceId}/clear`,
    params: body
  })
}

/** 分页读取当前项目的通知组。 */
export function fetchAlarmNotificationGroups(projectId: string, cursor?: string, limit = 20) {
  return request.get<AlarmNotificationGroupPage>({
    url: `/api/v1/projects/${projectId}/alarm-notification-groups`,
    params: { cursor, limit }
  })
}

/** 创建通知组。 */
export function fetchCreateAlarmNotificationGroup(
  projectId: string,
  body: SaveAlarmNotificationGroupRequest
) {
  return request.post<AlarmNotificationGroupResponse>({
    url: `/api/v1/projects/${projectId}/alarm-notification-groups`,
    params: body
  })
}

/** 使用版本号 CAS 修改通知组。 */
export function fetchUpdateAlarmNotificationGroup(
  projectId: string,
  groupId: string,
  body: SaveAlarmNotificationGroupRequest
) {
  return request.put<AlarmNotificationGroupResponse>({
    url: `/api/v1/projects/${projectId}/alarm-notification-groups/${groupId}`,
    params: body
  })
}

/** 删除通知组。 */
export function fetchDeleteAlarmNotificationGroup(
  projectId: string,
  groupId: string,
  version: number
) {
  return request.del<void>({
    url: `/api/v1/projects/${projectId}/alarm-notification-groups/${groupId}`,
    params: { version }
  })
}

/** 读取一个通知组的脱敏收件人列表。 */
export function fetchAlarmNotificationRecipients(projectId: string, groupId: string) {
  return request.get<AlarmNotificationRecipientResponse[]>({
    url: `/api/v1/projects/${projectId}/alarm-notification-groups/${groupId}/recipients`
  })
}

/** 向通知组添加收件人。 */
export function fetchCreateAlarmNotificationRecipient(
  projectId: string,
  groupId: string,
  body: SaveAlarmNotificationRecipientRequest
) {
  return request.post<AlarmNotificationRecipientResponse>({
    url: `/api/v1/projects/${projectId}/alarm-notification-groups/${groupId}/recipients`,
    params: body
  })
}

/** 修改收件人；脱敏 target 不得作为原值回传，调用方必须显式提交完整目标。 */
export function fetchUpdateAlarmNotificationRecipient(
  projectId: string,
  recipientId: string,
  body: SaveAlarmNotificationRecipientRequest
) {
  return request.put<AlarmNotificationRecipientResponse>({
    url: `/api/v1/projects/${projectId}/alarm-notification-recipients/${recipientId}`,
    params: body
  })
}

/** 删除收件人。 */
export function fetchDeleteAlarmNotificationRecipient(
  projectId: string,
  recipientId: string,
  version: number
) {
  return request.del<void>({
    url: `/api/v1/projects/${projectId}/alarm-notification-recipients/${recipientId}`,
    params: { version }
  })
}

/** 分页读取当前项目的通知模板。 */
export function fetchAlarmNotificationTemplates(projectId: string, cursor?: string, limit = 20) {
  return request.get<AlarmNotificationTemplatePage>({
    url: `/api/v1/projects/${projectId}/alarm-notification-templates`,
    params: { cursor, limit }
  })
}

/** 创建固定变量通知模板。 */
export function fetchCreateAlarmNotificationTemplate(
  projectId: string,
  body: SaveAlarmNotificationTemplateRequest
) {
  return request.post<AlarmNotificationTemplateResponse>({
    url: `/api/v1/projects/${projectId}/alarm-notification-templates`,
    params: body
  })
}

/** 使用版本号 CAS 修改通知模板。 */
export function fetchUpdateAlarmNotificationTemplate(
  projectId: string,
  templateId: string,
  body: SaveAlarmNotificationTemplateRequest
) {
  return request.put<AlarmNotificationTemplateResponse>({
    url: `/api/v1/projects/${projectId}/alarm-notification-templates/${templateId}`,
    params: body
  })
}

/** 删除通知模板。 */
export function fetchDeleteAlarmNotificationTemplate(
  projectId: string,
  templateId: string,
  version: number
) {
  return request.del<void>({
    url: `/api/v1/projects/${projectId}/alarm-notification-templates/${templateId}`,
    params: { version }
  })
}

/** 读取一个告警规则的通知路由绑定。 */
export function fetchAlarmNotificationBindings(projectId: string, ruleId: string) {
  return request.get<AlarmNotificationBindingResponse[]>({
    url: `/api/v1/projects/${projectId}/alarm-rules/${ruleId}/notification-bindings`
  })
}

/** 为告警规则创建通知路由绑定。 */
export function fetchCreateAlarmNotificationBinding(
  projectId: string,
  ruleId: string,
  body: SaveAlarmNotificationBindingRequest
) {
  return request.post<AlarmNotificationBindingResponse>({
    url: `/api/v1/projects/${projectId}/alarm-rules/${ruleId}/notification-bindings`,
    params: body
  })
}

/** 使用版本号 CAS 修改告警通知路由。 */
export function fetchUpdateAlarmNotificationBinding(
  projectId: string,
  bindingId: string,
  body: SaveAlarmNotificationBindingRequest
) {
  return request.put<AlarmNotificationBindingResponse>({
    url: `/api/v1/projects/${projectId}/alarm-notification-bindings/${bindingId}`,
    params: body
  })
}

/** 删除告警通知路由。 */
export function fetchDeleteAlarmNotificationBinding(
  projectId: string,
  bindingId: string,
  version: number
) {
  return request.del<void>({
    url: `/api/v1/projects/${projectId}/alarm-notification-bindings/${bindingId}`,
    params: { version }
  })
}

/** 项目投递意图只读查询；状态不等于外部渠道实际送达。 */
export type AlarmNotificationDeliveryResponse =
  components['schemas']['AlarmNotificationDeliveryResponse']
export function fetchAlarmNotificationDeliveries(
  projectId: string,
  instanceId: string,
  cursor?: string,
  signal?: AbortSignal
) {
  return request.get<components['schemas']['CursorPageAlarmNotificationDeliveryResponse']>({
    url: `/api/v1/projects/${encodeURIComponent(projectId)}/alarm-notification-deliveries`,
    params: { instanceId, cursor, limit: 20 },
    signal,
    showErrorMessage: false
  })
}
