import request from '@/utils/http'
import type { components } from '@/types/api/schema'

/** 管理契约全部由OpenAPI生成；列表与版本不会手写另一份业务模型。 */
export type MessageRule = components['schemas']['MessageRuleView']
export type MessageVersion = components['schemas']['MessageRuleVersionView']
export type RuleWrite = components['schemas']['MessageRuleWriteRequest']
export type RuleAction = components['schemas']['RuleActionRequest']
export type RuleCatalog = components['schemas']['Catalog']
export type RuleDescriptor = components['schemas']['Descriptor']
export type RulePage = components['schemas']['RuleManagementPageMessageRuleView']
export type VersionPage = components['schemas']['RuleManagementPageMessageRuleVersionView']
export type DebugResult = components['schemas']['DebugResponse']

const base = (project: string) => `/api/v1/projects/${project}/message-rules` as const
export const ruleCatalog = (project: string) =>
  request.get<RuleCatalog>({ url: `/api/v1/projects/${project}/rule-node-types` })
export const listRules = (
  project: string,
  params: { name?: string; status?: string; cursor?: string; limit?: number }
) => request.get<RulePage>({ url: base(project), params })
export const getRule = (project: string, id: string) =>
  request.get<MessageRule>({ url: `${base(project)}/${id}` })
export const ruleHistory = (project: string, id: string, cursor?: string) =>
  request.get<VersionPage>({ url: `${base(project)}/${id}/version-history`, params: { cursor } })
export const saveRule = (project: string, id: string, params: RuleWrite) =>
  id
    ? request.put<MessageRule>({ url: `${base(project)}/${id}`, params })
    : request.post<MessageRule>({ url: base(project), params })
export const activateRule = (
  project: string,
  id: string,
  version: string,
  expectedVersion: number
) =>
  request.post<MessageRule>({
    url: `${base(project)}/${id}/versions/${version}/activate`,
    params: { expectedVersion },
    data: {}
  })
export const pauseRule = (project: string, id: string, expectedVersion: number) =>
  request.post<MessageRule>({
    url: `${base(project)}/${id}/pause`,
    params: { expectedVersion },
    data: {}
  })
export const deleteRule = (project: string, id: string, expectedVersion: number) =>
  request.del<void>({ url: `${base(project)}/${id}`, params: { expectedVersion } })
export const debugRule = (project: string, id: string, version: string, inputJson: string) =>
  request.post<DebugResult>({
    url: `${base(project)}/${id}/versions/${version}/debug`,
    params: { inputJson }
  })
