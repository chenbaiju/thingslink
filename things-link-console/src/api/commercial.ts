import request from '@/utils/http'
import type { components } from '@/types/api/schema'

export type CommercialPreview = components['schemas']['Preview']
export type CommercialAdjustment = components['schemas']['Adjustment']
export type CommercialRequest = components['schemas']['CreateCommercialAdjustmentRequest']
const base = (tenantId: string) =>
  `/api/v1/operations/tenants/${encodeURIComponent(tenantId)}/adjustments` as const
export const fetchCommercialPreview = (tenantId: string) =>
  request.get<CommercialPreview>({ url: `${base(tenantId)}/context` })
export const createCommercialAdjustment = (tenantId: string, data: CommercialRequest) =>
  request.post<CommercialAdjustment>({ url: base(tenantId), data })
export const recoverCommercialAdjustment = (tenantId: string, key: string) =>
  request.get<CommercialAdjustment>({ url: `${base(tenantId)}/by-key/${encodeURIComponent(key)}` })
export const revokeCommercialAdjustment = (tenantId: string, id: string, reason: string) =>
  request.post<components['schemas']['RevokeResult']>({
    url: `${base(tenantId)}/${encodeURIComponent(id)}/revoke`,
    data: { reason }
  })
