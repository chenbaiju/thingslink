import request from '@/utils/http'
import type { components } from '@/types/api/schema'

/** 管理契约全部由OpenAPI生成；列表与版本不会手写另一份业务模型。 */
export type ManagedScene = components['schemas']['RuleSceneView']
export type SceneVersion = components['schemas']['RuleSceneVersionView']
export type SceneWrite = components['schemas']['ReviseSceneRequest']
export type SceneExecution = components['schemas']['RuleSceneExecutionView']
export type SceneInput = components['schemas']['ExecuteSceneRequest']
export type ScenePage = components['schemas']['RuleManagementPageRuleSceneView']
export type SceneVersionPage = components['schemas']['RuleManagementPageRuleSceneVersionView']
export { ruleCatalog } from './rule-management'

const base = (project: string) => `/api/v1/projects/${project}/scenes` as const
export const listScenes = (
  project: string,
  params: { name?: string; status?: string; cursor?: string; limit?: number }
) => request.get<ScenePage>({ url: base(project), params })
export const getScene = (project: string, id: string) =>
  request.get<ManagedScene>({ url: `${base(project)}/${id}` })
export const sceneHistory = (project: string, id: string, cursor?: string) =>
  request.get<SceneVersionPage>({
    url: `${base(project)}/${id}/version-history`,
    params: { cursor }
  })
export const saveScene = (project: string, id: string, params: SceneWrite) =>
  id
    ? request.put<ManagedScene>({ url: `${base(project)}/${id}`, params })
    : request.post<ManagedScene>({ url: base(project), params })
export const activateScene = (
  project: string,
  id: string,
  version: string,
  expectedVersion: number
) =>
  request.post<ManagedScene>({
    url: `${base(project)}/${id}/versions/${version}/activate`,
    params: { expectedVersion },
    data: {}
  })
export const pauseScene = (project: string, id: string, expectedVersion: number) =>
  request.post<ManagedScene>({
    url: `${base(project)}/${id}/pause`,
    params: { expectedVersion },
    data: {}
  })
export const deleteScene = (project: string, id: string, expectedVersion: number) =>
  request.del<void>({ url: `${base(project)}/${id}`, params: { expectedVersion } })
export const executeScene = (project: string, id: string, key: string, params: SceneInput) =>
  request.post<SceneExecution>({
    url: `${base(project)}/${id}/executions`,
    headers: { 'Idempotency-Key': key },
    params
  })
