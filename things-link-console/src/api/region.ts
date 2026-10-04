import request from '@/utils/http'
import type { components } from '@/types/api/schema'

/**
 * 项目区域目录接口。
 *
 * 区域由后端配置/代码维护，前端只消费展示名、分组和是否允许创建。
 * 这样后续平台运营后台上线前，不会再出现页面和接口各有一套白名单。
 */

/** 项目区域（后端契约） */
export type ProjectRegionResponse = components['schemas']['ProjectRegionResponse']

/** 取项目区域目录 */
export function fetchProjectRegions() {
  return request.get<ProjectRegionResponse[]>({
    url: '/api/v1/project-regions'
  })
}
