import request from '@/utils/http'
import type { components } from '@/types/api/schema'
export type ProjectExportResponse = components['schemas']['ProjectExportResponse']
export type ProjectExportDownloadUrlResponse =
  components['schemas']['ProjectExportDownloadUrlResponse']
/** 只读找回当前删除代次本人最新任务，204兼容空正文。 */
export async function fetchLatestProjectExport(projectId: string) {
  const result = await request.get<ProjectExportResponse | undefined>({
    url: `/api/v1/projects/${projectId}/exports/latest`
  })
  return result || undefined
}
/** 明确点击后申请；返回已有非终态或新建任务。 */
export function requestProjectExport(projectId: string) {
  return request.post<ProjectExportResponse>({ url: `/api/v1/projects/${projectId}/exports` })
}
/** 轮询仅读取固定任务，不重复申请。 */
export function fetchProjectExport(projectId: string, exportId: string) {
  return request.get<ProjectExportResponse>({
    url: `/api/v1/projects/${projectId}/exports/${exportId}`
  })
}
/** 每次下载重新确权，短时URL只用于本次导航。 */
export function downloadProjectExport(projectId: string, exportId: string) {
  return request.post<ProjectExportDownloadUrlResponse>({
    url: `/api/v1/projects/${projectId}/exports/${exportId}/download-url`
  })
}
