import request from '@/utils/http'
import type { components } from '@/types/api/schema'

/** 管理契约全部由OpenAPI生成；列表与版本不会手写另一份业务模型。 */
export type ManagedAutomation = components['schemas']['AutomationView']
export type AutomationVersion = components['schemas']['AutomationVersionView']
export type AutomationWrite = components['schemas']['ReviseAutomationRequest']
export type AutomationPage = components['schemas']['RuleManagementPageAutomationView']
export type AutomationVersionPage = components['schemas']['RuleManagementPageAutomationVersionView']
export { ruleCatalog } from './rule-management'

const base = (project: string) => `/api/v1/projects/${project}/automations` as const
export const listAutomations = (
  project: string,
  params: { name?: string; status?: string; cursor?: string; limit?: number }
) => request.get<AutomationPage>({ url: base(project), params })
export const getAutomation = (project: string, id: string) =>
  request.get<ManagedAutomation>({ url: `${base(project)}/${id}` })
export const automationHistory = (project: string, id: string, cursor?: string) =>
  request.get<AutomationVersionPage>({
    url: `${base(project)}/${id}/version-history`,
    params: { cursor }
  })
export const saveAutomation = (project: string, id: string, params: AutomationWrite) =>
  id
    ? request.put<ManagedAutomation>({ url: `${base(project)}/${id}`, params })
    : request.post<ManagedAutomation>({ url: base(project), params: createBody(params) })
export const activateAutomation = (
  project: string,
  id: string,
  version: string,
  expectedVersion: number
) =>
  request.post<ManagedAutomation>({
    url: `${base(project)}/${id}/versions/${version}/activate`,
    params: { expectedVersion },
    data: {}
  })
export const pauseAutomation = (project: string, id: string, expectedVersion: number) =>
  request.post<ManagedAutomation>({
    url: `${base(project)}/${id}/pause`,
    params: { expectedVersion },
    data: {}
  })
export const deleteAutomation = (project: string, id: string, expectedVersion: number) =>
  request.del<void>({ url: `${base(project)}/${id}`, params: { expectedVersion } })

/** 创建体不发送更新专用CAS字段，后端拒绝额外字段。 */
function createBody(params: AutomationWrite) {
  const { expectedVersion: _version, ...body } = params
  return body
}

export type AutomationExecution = components['schemas']['AutomationExecutionView']
export type AutomationExecutionDetail = components['schemas']['AutomationExecutionDetailView']
export type AutomationExecutionPage =
  components['schemas']['RuleManagementPageAutomationExecutionView']
export const listAutomationExecutions = (
  project: string,
  params: { automationId?: string; status?: string; from?: string; to?: string; cursor?: string }
) =>
  request.get<AutomationExecutionPage>({
    url: `/api/v1/projects/${project}/automation-executions`,
    params
  })
export const getAutomationExecution = (project: string, id: string) =>
  request.get<AutomationExecutionDetail>({
    url: `/api/v1/projects/${project}/automation-executions/${id}`
  })
