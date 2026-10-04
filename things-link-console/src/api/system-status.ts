import request from '@/utils/http'
import type { components } from '@/types/api/schema'

/** 系统状态快照；类型由后端 OpenAPI 契约生成，不在前端复制字段。 */
export type SystemStatusResponse = components['schemas']['SystemStatusResponse']

/** 运行依赖状态；响应不包含连接地址或异常详情。 */
export type DependencyStatusResponse = components['schemas']['DependencyStatusResponse']

/** 读取当前应用节点已注册健康探针的实时脱敏状态。 */
export function fetchSystemStatus() {
  return request.get<SystemStatusResponse>({
    url: '/api/v1/system/status'
  })
}
