import request from '@/utils/http'

export interface ReviewDetail {
  requestId: string
  deploymentId: string
  claimedTenantId: string
  publicKeySha256: string
  requestSha256: string
  revisionId: string
  revisionSha256: string
  attestationCount: number
}

export interface ReviewInput {
  deploymentId: string
  tenantId: string
  publicKeySha256: string
  requestSha256: string
  organizationReference: string
  evidenceSha256: string
  tier: string
  revisionId: string
  revisionSha256: string
}

export interface ReviewProgress {
  requestId: string
  attestationCount: number
  readyForIssuanceReview: boolean
}

const endpoint = (requestId: string) =>
  `/api/v1/operations/self-hosted/enrollment-requests/${encodeURIComponent(requestId)}/review` as const

export const fetchReviewDetail = (requestId: string) =>
  request.get<ReviewDetail>({ url: endpoint(requestId) })

export const submitReview = (requestId: string, input: ReviewInput) =>
  request.post<ReviewProgress>({ url: endpoint(requestId), data: input })
