import request from '@/utils/http'
import type { components } from '@/types/api/schema'
import type { ApplicationContent } from '@/features/application/editor-model'
export type ApplicationCatalog = components['schemas']['ApplicationCatalogResponse']
const base = (projectId: string) =>
  `/api/v1/projects/${encodeURIComponent(projectId)}/applications` as const
export function fetchApplications(projectId: string, cursor?: string) {
  return request.get<components['schemas']['CursorPageApplicationCatalogResponse']>({
    url: base(projectId),
    params: { limit: 20, ...(cursor ? { cursor } : {}) },
    showErrorMessage: false
  })
}
export function fetchApplicationDraft(projectId: string, id: string) {
  return request.get<components['schemas']['ApplicationDraftResponse']>({
    url: `${base(projectId)}/${encodeURIComponent(id)}/draft`,
    showErrorMessage: false
  })
}
export function createApplication(
  projectId: string,
  managementName: string,
  content: ApplicationContent,
  key: string
) {
  return request.post<components['schemas']['ApplicationCreationResponse']>({
    url: base(projectId),
    params: { managementName, content },
    headers: { 'Idempotency-Key': key },
    showErrorMessage: false
  })
}
export function saveApplicationDraft(
  projectId: string,
  id: string,
  expectedRevision: string,
  content: ApplicationContent,
  key: string
) {
  return request.put<components['schemas']['ApplicationDraftResponse']>({
    url: `${base(projectId)}/${encodeURIComponent(id)}/draft`,
    params: { expectedRevision, content },
    headers: { 'Idempotency-Key': key },
    showErrorMessage: false
  })
}
