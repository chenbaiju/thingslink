import request from '@/utils/http'
import type { components } from '@/types/api/schema'
export type KnowledgeSource = components['schemas']['AssistantKnowledgeSource']
export type KnowledgeDetail = components['schemas']['AssistantKnowledgeDetail']
export type KnowledgeSearch = components['schemas']['AssistantKnowledgeSearchResult']
const base = (project: string) =>
  `/api/v1/projects/${encodeURIComponent(project)}/assistant/knowledge` as const
const source = (project: string, key: string) =>
  `${base(project)}/sources/${encodeURIComponent(key)}` as const
export const listKnowledge = (project: string, signal: AbortSignal) =>
  request.get<KnowledgeSource[]>({
    url: `${base(project)}/sources`,
    signal,
    showErrorMessage: false
  })
export const readKnowledge = (project: string, key: string, signal: AbortSignal) =>
  request.get<KnowledgeDetail>({ url: source(project, key), signal, showErrorMessage: false })
/** 每次批准只提交封闭正文与当前版本，不携带身份或模型凭据。 */
export const publishKnowledge = (
  project: string,
  key: string,
  expected: string | null,
  content: string,
  signal: AbortSignal
) =>
  request.put<KnowledgeSource>({
    url: source(project, key),
    data: { expectedCurrentVersionId: expected, content, approvedForProjectMembers: true },
    signal,
    showErrorMessage: false
  })
export const deleteKnowledge = (
  project: string,
  key: string,
  expected: string,
  signal: AbortSignal
) =>
  request.del<void>({
    url: source(project, key),
    params: { expectedCurrentVersionId: expected },
    signal,
    showErrorMessage: false
  })
export const searchKnowledge = (project: string, keywords: string[], signal: AbortSignal) =>
  request.post<KnowledgeSearch>({
    url: `${base(project)}/search`,
    data: { keywords: [...keywords] },
    signal,
    showErrorMessage: false
  })
