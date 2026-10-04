import request from '@/utils/http'
import type { components } from '@/types/api/schema'
export type DashboardCatalog = components['schemas']['DashboardCatalogResponse']
export type DashboardCatalogPage = components['schemas']['CursorPageDashboardCatalogResponse']
export type DashboardDraft = components['schemas']['DashboardDraftResponse']
export type DashboardCreation = components['schemas']['DashboardCreationResponse']
export function fetchDashboards(projectId: string, cursor?: string) {
  return request.get<DashboardCatalogPage>({
    url: `/api/v1/projects/${projectId}/dashboards`,
    params: { limit: 20, ...(cursor ? { cursor } : {}) },
    showErrorMessage: false
  })
}
export function fetchDashboardDraft(projectId: string, id: string) {
  return request.get<DashboardDraft>({
    url: `/api/v1/projects/${projectId}/dashboards/${id}/draft`,
    showErrorMessage: false
  })
}
export function createDashboard(
  projectId: string,
  managementName: string,
  content: unknown,
  key: string
) {
  return request.post<DashboardCreation>({
    url: `/api/v1/projects/${projectId}/dashboards`,
    params: { managementName, content },
    headers: { 'Idempotency-Key': key },
    showErrorMessage: false
  })
}
export function saveDashboardDraft(
  projectId: string,
  id: string,
  expectedRevision: string,
  content: unknown
) {
  return request.put<DashboardDraft>({
    url: `/api/v1/projects/${projectId}/dashboards/${id}/draft`,
    params: { expectedRevision, content },
    showErrorMessage: false
  })
}
