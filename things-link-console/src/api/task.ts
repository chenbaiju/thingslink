import request from '@/utils/http'
import type { components } from '@/types/api/schema'

/** 任务定义响应；调度时刻始终为 RFC3339 UTC，cron 的解释时区由服务端随响应返回。 */
export type TaskJobResponse = components['schemas']['TaskJobResponse']
/** 创建或更新任务定义的请求体；字段约束以 OpenAPI 为唯一来源。 */
export type SaveTaskJobRequest = components['schemas']['SaveTaskJobRequest']
/** 一次不可变任务执行的汇总结果。 */
export type TaskExecutionResponse = components['schemas']['TaskExecutionResponse']

/** 读取当前项目的任务定义；目标设备枚举由服务端内部按 keyset 分批处理。 */
export function fetchTaskJobs(projectId: string) {
  return request.get<TaskJobResponse[]>({
    url: `/api/v1/projects/${projectId}/task-jobs`
  })
}

/** 创建一次性或周期任务。 */
export function fetchCreateTaskJob(projectId: string, body: SaveTaskJobRequest) {
  return request.post<TaskJobResponse>({
    url: `/api/v1/projects/${projectId}/task-jobs`,
    params: body
  })
}

/** 使用版本号 CAS 更新任务定义，防止旧编辑器覆盖新配置。 */
export function fetchUpdateTaskJob(projectId: string, jobId: string, body: SaveTaskJobRequest) {
  return request.put<TaskJobResponse>({
    url: `/api/v1/projects/${projectId}/task-jobs/${jobId}`,
    params: body
  })
}

/** 删除任务定义；既有执行记录仍由服务端保留。 */
export function fetchDeleteTaskJob(projectId: string, jobId: string, version: number) {
  return request.del<void>({
    url: `/api/v1/projects/${projectId}/task-jobs/${jobId}`,
    params: { expectedVersion: version }
  })
}

/** 启用任务，服务端重新计算并持久化下一次 UTC 触发时刻。 */
export function fetchEnableTaskJob(projectId: string, jobId: string) {
  return request.post<TaskJobResponse>({
    url: `/api/v1/projects/${projectId}/task-jobs/${jobId}/enable`
  })
}

/** 停用任务；不会取消已经创建的执行快照。 */
export function fetchDisableTaskJob(projectId: string, jobId: string) {
  return request.post<TaskJobResponse>({
    url: `/api/v1/projects/${projectId}/task-jobs/${jobId}/disable`
  })
}

/** 手工创建一次执行快照，返回记录后由调度器异步分批下发。 */
export function fetchRunTaskJob(projectId: string, jobId: string) {
  return request.post<TaskExecutionResponse>({
    url: `/api/v1/projects/${projectId}/task-jobs/${jobId}/runs`
  })
}

/** 读取某任务的不可变执行记录。 */
export function fetchTaskExecutions(projectId: string, jobId: string) {
  return request.get<TaskExecutionResponse[]>({
    url: `/api/v1/projects/${projectId}/task-jobs/${jobId}/executions`
  })
}
