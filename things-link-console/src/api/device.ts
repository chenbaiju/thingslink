import request from '@/utils/http'
import type { components } from '@/types/api/schema'

/** 设备响应。 */
export type DeviceResponse = components['schemas']['DeviceResponse']
/** 创建设备请求。 */
export type CreateDeviceRequest = components['schemas']['CreateDeviceRequest']
/** 修改设备请求。 */
export type UpdateDeviceRequest = components['schemas']['UpdateDeviceRequest']
/** 设备类型响应；类型由 OpenAPI 生成，禁止在前端复制字段定义。 */
export type DeviceTypeResponse = components['schemas']['DeviceTypeResponse']
/** 创建设备类型请求。 */
export type CreateDeviceTypeRequest = components['schemas']['CreateDeviceTypeRequest']
/** 修改设备类型请求。 */
export type UpdateDeviceTypeRequest = components['schemas']['UpdateDeviceTypeRequest']
/** 属性定义响应。 */
export type DevicePropertyDefinitionResponse =
  components['schemas']['DevicePropertyDefinitionResponse']
/** 创建或修改属性定义请求。 */
export type SaveDevicePropertyDefinitionRequest =
  components['schemas']['SaveDevicePropertyDefinitionRequest']
/** 事件定义响应。 */
export type DeviceEventDefinitionResponse = components['schemas']['DeviceEventDefinitionResponse']
/** 创建或修改事件定义请求。 */
export type SaveDeviceEventDefinitionRequest =
  components['schemas']['SaveDeviceEventDefinitionRequest']
/** 命令定义响应。 */
export type DeviceCommandDefinitionResponse =
  components['schemas']['DeviceCommandDefinitionResponse']
/** 创建或修改命令定义请求。 */
export type SaveDeviceCommandDefinitionRequest =
  components['schemas']['SaveDeviceCommandDefinitionRequest']
/** 设备命令提交请求。 */
export type SubmitDeviceCommandRequest = components['schemas']['SubmitDeviceCommandRequest']
/** 设备命令执行快照。 */
export type DeviceCommandResponse = components['schemas']['DeviceCommandResponse']

/** @param projectId 当前项目 ID @param body 创建参数 */
export function fetchCreateDevice(projectId: string, body: CreateDeviceRequest) {
  return request.post<DeviceResponse>({
    url: `/api/v1/projects/${projectId}/devices`,
    params: body
  })
}

/** 按 ID 读取一个设备；用于列表事实只携带 deviceId 时按需补齐显示名称。 */
export function fetchDeviceDetail(projectId: string, deviceId: string) {
  return request.get<DeviceResponse>({ url: `/api/v1/projects/${projectId}/devices/${deviceId}` })
}

/** @param projectId 项目 ID @param id 设备 ID @param body 修改参数 */
export function fetchUpdateDevice(projectId: string, id: string, body: UpdateDeviceRequest) {
  return request.put<DeviceResponse>({
    url: `/api/v1/projects/${projectId}/devices/${id}`,
    params: body
  })
}

/** @param projectId 项目 ID @param id 设备 ID */
export function fetchDeleteDevice(projectId: string, id: string) {
  return request.del<void>({ url: `/api/v1/projects/${projectId}/devices/${id}` })
}

/** 凭据响应（不含明文）。 */
export type DeviceCredentialResponse = components['schemas']['DeviceCredentialResponse']
/** 凭据创建响应（唯一一次携带明文密钥）。 */
export type CredentialCreatedResponse = components['schemas']['CredentialCreatedResponse']

/** 读取设备凭据列表。 */
export function fetchDeviceCredentials(projectId: string, deviceId: string) {
  return request.get<DeviceCredentialResponse[]>({
    url: `/api/v1/projects/${projectId}/devices/${deviceId}/credentials`
  })
}

/** 生成新凭据，响应包含仅此一次的明文密钥。 */
export function fetchGenerateCredential(projectId: string, deviceId: string) {
  return request.post<CredentialCreatedResponse>({
    url: `/api/v1/projects/${projectId}/devices/${deviceId}/credentials`
  })
}

/** 作废凭据。 */
export function fetchRevokeCredential(projectId: string, deviceId: string, id: string) {
  return request.del<void>({
    url: `/api/v1/projects/${projectId}/devices/${deviceId}/credentials/${id}`
  })
}

/** 连接记录响应。 */
export type DeviceConnectionResponse = components['schemas']['DeviceConnectionResponse']

/** 读取设备连接记录。 */
export function fetchDeviceConnections(projectId: string, deviceId: string) {
  return request.get<DeviceConnectionResponse[]>({
    url: `/api/v1/projects/${projectId}/devices/${deviceId}/connections`
  })
}

/** 设备影子响应。 */
export type DeviceShadowResponse = components['schemas']['DeviceShadowResponse']
/** 批量设备当前值响应。 */
export type BatchCurrentValuesResponse = components['schemas']['BatchCurrentValuesResponse']
/** 批量设备当前值请求。 */
export type BatchCurrentValuesRequest = components['schemas']['BatchCurrentValuesRequest']

/** 读取设备影子。 */
export function fetchDeviceShadow(projectId: string, deviceId: string) {
  return request.get<DeviceShadowResponse>({
    url: `/api/v1/projects/${projectId}/devices/${deviceId}/shadow`
  })
}

/** 看板与详情页批量读取多设备、多属性当前值。 */
export function fetchBatchCurrentValues(projectId: string, body: BatchCurrentValuesRequest) {
  return request.post<BatchCurrentValuesResponse>({
    url: `/api/v1/projects/${projectId}/devices/current-values/query`,
    params: body
  })
}

/** 更新 desired 期望状态。 */
export function fetchUpdateDesired(
  projectId: string,
  deviceId: string,
  body: { desired: string; version: number }
) {
  return request.put<DeviceShadowResponse>({
    url: `/api/v1/projects/${projectId}/devices/${deviceId}/shadow/desired`,
    params: body
  })
}

/** 提交设备命令；网络重试必须复用同一个幂等键。 */
export function fetchSubmitDeviceCommand(
  projectId: string,
  deviceId: string,
  body: SubmitDeviceCommandRequest,
  idempotencyKey: string
) {
  return request.post<DeviceCommandResponse>({
    url: `/api/v1/projects/${projectId}/devices/${deviceId}/commands`,
    params: body,
    headers: { 'Idempotency-Key': idempotencyKey }
  })
}

/** 读取命令的最新执行结果。 */
export function fetchDeviceCommand(projectId: string, deviceId: string, commandId: string) {
  return request.get<DeviceCommandResponse>({
    url: `/api/v1/projects/${projectId}/devices/${deviceId}/commands/${commandId}`
  })
}

/** 消息日志响应。 */
export type MessageLogResponse = components['schemas']['MessageLogResponse']
/** 消息日志游标页；必须使用生成契约，不能把分页响应误写为数组。 */
export type MessageLogPage = components['schemas']['CursorPageMessageLogResponse']
/** 高级设备筛选游标页。 */
export type DeviceSearchPage = components['schemas']['CursorPageDeviceResponse']
/** 设备类型游标页。 */
export type DeviceTypePage = components['schemas']['CursorPageDeviceTypeResponse']
/** S4 属性历史聚合响应，包含服务端实际采用的粒度。 */
export type PropertyHistoryResponse = components['schemas']['PropertyHistoryResponse']

/**
 * 高级设备筛选固定参数。
 *
 * 后端只接受这一组白名单字段；数组会被序列化成重复的 query key，不能改成自由 DSL。
 */
export interface DeviceSearchQuery {
  keyword?: string
  deviceTypeIds?: string[]
  statuses?: Array<'INACTIVE' | 'ONLINE' | 'OFFLINE'>
  groupId?: string
  tagKey?: string
  tagValue?: string
  cursor?: string
  limit?: number
}

/**
 * 项目级消息日志筛选参数。
 * V1 运行时协议只有 MQTT，筛选控件已删除（D-056），后端查询契约保留 protocol 参数供后续范围版本使用。
 */
export interface ProjectMessageQuery {
  deviceId?: string
  direction?: 'UP' | 'DOWN'
  from?: string
  to?: string
  traceId?: string
  /** 消息类型（如 PROPERTY_REPORT／COMMAND／COMMAND_REPLY）；空表示不限。 */
  messageType?: string
  cursor?: string
  limit?: number
}

/** 查询单个数值属性的历史曲线。 */
export function fetchPropertyHistory(
  projectId: string,
  deviceId: string,
  params: {
    propertyKey: string
    from: string
    to: string
    granularity?: 'RAW' | 'ONE_MINUTE' | 'ONE_HOUR' | 'ONE_DAY'
    aggregation?: 'AVG' | 'MIN' | 'MAX' | 'SUM' | 'COUNT'
  }
) {
  return request.get<PropertyHistoryResponse>({
    url: `/api/v1/projects/${projectId}/devices/${deviceId}/telemetry/property/history`,
    params
  })
}

/** 读取设备消息日志；cursor 为空时读取第一页。 */
export function fetchDeviceMessages(
  projectId: string,
  deviceId: string,
  cursor?: string,
  limit = 20
) {
  return request.get<MessageLogPage>({
    url: `/api/v1/projects/${projectId}/devices/${deviceId}/messages`,
    params: { cursor, limit }
  })
}

/**
 * 按固定白名单组合筛选项目设备。
 *
 * Spring 的 {@code List} 参数要求重复 key（而不是 {@code deviceTypeIds[]}），因此在
 * 调用点显式指定 Axios 序列化方式；否则多选类型和状态会在浏览器侧看似已选择、服务端却收不到。
 */
export function fetchSearchDevices(projectId: string, params: DeviceSearchQuery) {
  return request.get<DeviceSearchPage>({
    url: `/api/v1/projects/${projectId}/devices/search`,
    params,
    paramsSerializer: { indexes: null }
  })
}

/** 查询项目内消息日志摘要；原始报文不属于该 API 的数据边界。 */
export function fetchProjectMessages(projectId: string, params: ProjectMessageQuery) {
  return request.get<MessageLogPage>({
    url: `/api/v1/projects/${projectId}/messages`,
    params
  })
}

/** 消息详情响应；字段由 OpenAPI 生成，不在前端复制定义。 */
export type MessageLogDetailResponse = components['schemas']['MessageLogDetailResponse']

/** 接入连接诊断响应。 */
export type DeviceAccessDiagnosticsResponse =
  components['schemas']['DeviceAccessDiagnosticsResponse']

/**
 * 读取一条消息详情。
 *
 * @param projectId 项目 ID
 * @param deviceId 设备 ID
 * @param logId 日志 ID
 * @param format 摘要格式：JSON（默认）或 HEX
 */
export function fetchMessageLogDetail(
  projectId: string,
  deviceId: string,
  logId: string,
  format: 'JSON' | 'HEX' = 'JSON'
) {
  return request.get<MessageLogDetailResponse>({
    url: `/api/v1/projects/${projectId}/devices/${deviceId}/messages/${logId}`,
    params: { format }
  })
}

/**
 * 读取一台设备的接入连接诊断（协议、配置版本、会话代次、在线状态与断开原因）。
 *
 * @param projectId 项目 ID
 * @param deviceId 设备 ID
 */
export function fetchDeviceAccessDiagnostics(projectId: string, deviceId: string) {
  return request.get<DeviceAccessDiagnosticsResponse>({
    url: `/api/v1/projects/${projectId}/devices/${deviceId}/access-diagnostics`
  })
}

/** 按创建时间与 UUID 稳定倒序读取一页设备类型。 */
export function fetchDeviceTypePage(projectId: string, cursor?: string, limit = 50) {
  return request.get<DeviceTypePage>({
    url: `/api/v1/projects/${projectId}/device-types/search`,
    params: { cursor, limit }
  })
}

/** @param projectId 当前项目 ID @param body 创建参数 */
export function fetchCreateDeviceType(projectId: string, body: CreateDeviceTypeRequest) {
  return request.post<DeviceTypeResponse>({
    url: `/api/v1/projects/${projectId}/device-types`,
    params: body
  })
}

/** @param projectId 当前项目 ID @param id 设备类型 ID @param body 修改参数 */
export function fetchUpdateDeviceType(
  projectId: string,
  id: string,
  body: UpdateDeviceTypeRequest
) {
  return request.put<DeviceTypeResponse>({
    url: `/api/v1/projects/${projectId}/device-types/${id}`,
    params: body
  })
}

/** @param projectId 当前项目 ID @param id 设备类型 ID */
export function fetchDeleteDeviceType(projectId: string, id: string) {
  return request.del<void>({ url: `/api/v1/projects/${projectId}/device-types/${id}` })
}

/** @param projectId 当前项目 ID @param id 设备类型 ID */
export function fetchPublishDeviceType(projectId: string, id: string) {
  return request.post<DeviceTypeResponse>({
    url: `/api/v1/projects/${projectId}/device-types/${id}/publish`
  })
}

/** 读取设备类型的全部属性定义。 */
export function fetchDevicePropertyDefinitions(projectId: string, deviceTypeId: string) {
  return request.get<DevicePropertyDefinitionResponse[]>({
    url: `/api/v1/projects/${projectId}/device-types/${deviceTypeId}/properties`
  })
}

/** 创建设备类型属性定义。 */
export function fetchCreateDevicePropertyDefinition(
  projectId: string,
  deviceTypeId: string,
  body: SaveDevicePropertyDefinitionRequest
) {
  return request.post<DevicePropertyDefinitionResponse>({
    url: `/api/v1/projects/${projectId}/device-types/${deviceTypeId}/properties`,
    params: body
  })
}

/** 修改设备类型属性定义。 */
export function fetchUpdateDevicePropertyDefinition(
  projectId: string,
  deviceTypeId: string,
  id: string,
  body: SaveDevicePropertyDefinitionRequest
) {
  return request.put<DevicePropertyDefinitionResponse>({
    url: `/api/v1/projects/${projectId}/device-types/${deviceTypeId}/properties/${id}`,
    params: body
  })
}

/** 软删除设备类型属性定义。 */
export function fetchDeleteDevicePropertyDefinition(
  projectId: string,
  deviceTypeId: string,
  id: string
) {
  return request.del<void>({
    url: `/api/v1/projects/${projectId}/device-types/${deviceTypeId}/properties/${id}`
  })
}

/** 读取设备类型的全部事件定义。 */
export function fetchDeviceEventDefinitions(projectId: string, deviceTypeId: string) {
  return request.get<DeviceEventDefinitionResponse[]>({
    url: `/api/v1/projects/${projectId}/device-types/${deviceTypeId}/events`
  })
}

/** 创建设备类型事件定义。 */
export function fetchCreateDeviceEventDefinition(
  projectId: string,
  deviceTypeId: string,
  body: SaveDeviceEventDefinitionRequest
) {
  return request.post<DeviceEventDefinitionResponse>({
    url: `/api/v1/projects/${projectId}/device-types/${deviceTypeId}/events`,
    params: body
  })
}

/** 修改设备类型事件定义。 */
export function fetchUpdateDeviceEventDefinition(
  projectId: string,
  deviceTypeId: string,
  id: string,
  body: SaveDeviceEventDefinitionRequest
) {
  return request.put<DeviceEventDefinitionResponse>({
    url: `/api/v1/projects/${projectId}/device-types/${deviceTypeId}/events/${id}`,
    params: body
  })
}

/** 软删除设备类型事件定义。 */
export function fetchDeleteDeviceEventDefinition(
  projectId: string,
  deviceTypeId: string,
  id: string
) {
  return request.del<void>({
    url: `/api/v1/projects/${projectId}/device-types/${deviceTypeId}/events/${id}`
  })
}

/** 读取设备类型的全部命令定义。 */
export function fetchDeviceCommandDefinitions(projectId: string, deviceTypeId: string) {
  return request.get<DeviceCommandDefinitionResponse[]>({
    url: `/api/v1/projects/${projectId}/device-types/${deviceTypeId}/commands`
  })
}

/** 创建设备类型命令定义。 */
export function fetchCreateDeviceCommandDefinition(
  projectId: string,
  deviceTypeId: string,
  body: SaveDeviceCommandDefinitionRequest
) {
  return request.post<DeviceCommandDefinitionResponse>({
    url: `/api/v1/projects/${projectId}/device-types/${deviceTypeId}/commands`,
    params: body
  })
}

/** 修改设备类型命令定义。 */
export function fetchUpdateDeviceCommandDefinition(
  projectId: string,
  deviceTypeId: string,
  id: string,
  body: SaveDeviceCommandDefinitionRequest
) {
  return request.put<DeviceCommandDefinitionResponse>({
    url: `/api/v1/projects/${projectId}/device-types/${deviceTypeId}/commands/${id}`,
    params: body
  })
}

/** 软删除设备类型命令定义。 */
export function fetchDeleteDeviceCommandDefinition(
  projectId: string,
  deviceTypeId: string,
  id: string
) {
  return request.del<void>({
    url: `/api/v1/projects/${projectId}/device-types/${deviceTypeId}/commands/${id}`
  })
}

/** 接入管理响应，版本字段沿生成合同保留字符串。 */
export type DeviceAccessConfigurationResponse =
  components['schemas']['DeviceAccessConfigurationResponse']
export type ChangeDeviceAccessConfigurationRequest =
  components['schemas']['ChangeDeviceAccessConfigurationRequest']

/** 读取当前配置和管理资格；不能用页面角色猜测协议能力。 */
export function fetchDeviceAccessConfiguration(projectId: string, deviceId: string) {
  return request.get<DeviceAccessConfigurationResponse>({
    url: `/api/v1/projects/${projectId}/devices/${deviceId}/access-config`,
    showErrorMessage: false
  })
}

/** 使用原字符串版本作CAS；响应未知时由页面重读，不自动重放。 */
export function fetchChangeDeviceAccessConfiguration(
  projectId: string,
  deviceId: string,
  body: ChangeDeviceAccessConfigurationRequest
) {
  return request.put<DeviceAccessConfigurationResponse>({
    url: `/api/v1/projects/${projectId}/devices/${deviceId}/access-config`,
    params: body,
    showErrorMessage: false
  })
}

/** PS-026a当前点；接口和可空值由OpenAPI生成。 */
export type DeviceLocationPointResponse = components['schemas']['DeviceLocationPointResponse']
export type UpdateDeviceLocationPointRequest =
  components['schemas']['UpdateDeviceLocationPointRequest']
export function fetchDeviceLocationPoint(projectId: string, deviceId: string) {
  return request.get<DeviceLocationPointResponse>({
    url: `/api/v1/projects/${projectId}/devices/${deviceId}/location-point`
  })
}
export function fetchUpdateDeviceLocationPoint(
  projectId: string,
  deviceId: string,
  body: UpdateDeviceLocationPointRequest
) {
  return request.put<DeviceLocationPointResponse>({
    url: `/api/v1/projects/${projectId}/devices/${deviceId}/location-point`,
    params: body
  })
}

/** 单类型公开事实，不读取产品秘密或摘要。 */
export function fetchDeviceTypeDetail(projectId: string, typeId: string) {
  return request.get<DeviceTypeResponse>({
    url: `/api/v1/projects/${encodeURIComponent(projectId)}/device-types/${encodeURIComponent(typeId)}`,
    showErrorMessage: false
  })
}
export type ProductCredentialCreatedResponse =
  components['schemas']['ProductCredentialCreatedResponse']
/** 非幂等生成或轮换，HTTP层对该精确路径禁止自动重发。 */
export function generateProductCredential(projectId: string, typeId: string) {
  return request.post<ProductCredentialCreatedResponse>({
    url: `/api/v1/projects/${encodeURIComponent(projectId)}/device-types/${encodeURIComponent(typeId)}/product-credential`,
    showErrorMessage: false
  })
}
