import request from '@/utils/http'

export type EnrollmentChannel = 'ONLINE' | 'OFFLINE'

export interface PendingEnrollment {
  requestId: string
  deploymentId: string
  claimedTenantId: string
  firstChannel: EnrollmentChannel
  receivedAt: string
}

export interface PendingRegistration {
  requestId: string
  deploymentId: string
  tenantId: string
  firstChannel: EnrollmentChannel
  status: 'PENDING'
  receivedAt: string
}

const base = '/api/v1/operations/self-hosted/enrollment-requests' as const

/** 上传原始 V1 封套；来源是运营人员的声明，不是网络或客户身份的证明。 */
export const receiveEnrollment = (bytes: ArrayBuffer, channel: EnrollmentChannel) =>
  request.post<PendingRegistration>({
    url: base,
    data: bytes,
    headers: { 'Content-Type': 'application/octet-stream', 'X-Enrollment-Source': channel }
  })

export const fetchPendingEnrollments = (limit: number, cursor?: PendingEnrollment) => {
  const params: Record<string, string> = { limit: String(limit) }
  if (cursor) {
    params.beforeReceivedAt = cursor.receivedAt
    params.beforeRequestId = cursor.requestId
  }
  return request.get<PendingEnrollment[]>({ url: base, params })
}
