import request from '@/utils/http'
import type { components } from '@/types/api/schema'
export type Subscription = components['schemas']['WebhookSubscriptionView']
export type Create = components['schemas']['WebhookCreate']
export type Delivery = components['schemas']['DeliveryView']
export type Detail = components['schemas']['DeliveryDetail']
export type Event = components['schemas']['EventView']
export type Action = 'pause' | 'resume' | 'revoke' | 'rotate'
const base = (project: string) => `/api/v1/projects/${project}/webhooks` as const
export const list = (project: string, cursor?: string) =>
  request.get<components['schemas']['CursorPageWebhookSubscriptionView']>({
    url: base(project),
    params: { cursor, limit: 20 }
  })
export const save = (project: string, data: Create, target?: Subscription) => {
  const headers = { 'Idempotency-Key': data.operationId }
  return target
    ? request.put<components['schemas']['WebhookIssued']>({
        url: `${base(project)}/${target.id}`,
        params: { ...data, expectedRevision: target.revision },
        headers
      })
    : request.post<components['schemas']['WebhookIssued']>({
        url: base(project),
        params: data,
        headers
      })
}
export const change = (
  project: string,
  target: Subscription,
  action: Action,
  operationId: string
) =>
  request.post<components['schemas']['WebhookIssued']>({
    url: `${base(project)}/${target.id}/${action}`,
    params: { operationId, expectedRevision: target.revision },
    headers: { 'Idempotency-Key': operationId }
  })
export const operation = (project: string, operationId: string) =>
  request.get<components['schemas']['WebhookOperationView']>({
    url: `${base(project)}/operations/${operationId}`
  })
export const deliveries = (project: string, status?: string, cursor?: string) =>
  request.get<components['schemas']['CursorPageDeliveryView']>({
    url: `${base(project)}/deliveries`,
    params: { status, cursor, limit: 20 }
  })
export const detail = (project: string, id: string) =>
  request.get<Detail>({ url: `${base(project)}/deliveries/${id}` })
export const events = (project: string, eventType: string, result?: string, cursor?: string) =>
  request.get<components['schemas']['CursorPageEventView']>({
    url: `${base(project)}/events`,
    params: { eventType, result, cursor, limit: 20 }
  })
export const recover = (project: string, delivery: Delivery, operationId: string) =>
  request.post<components['schemas']['WebhookRecoveryView']>({
    url: `${base(project)}/deliveries/${delivery.id}/recover`,
    params: { operationId, expectedRound: delivery.round },
    headers: { 'Idempotency-Key': operationId }
  })
export const recoveryOperation = (project: string, operationId: string) =>
  request.get<components['schemas']['WebhookRecoveryView']>({
    url: `${base(project)}/recovery-operations/${operationId}`
  })
