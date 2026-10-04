import request from '@/utils/http'
import type { components } from '@/types/api/schema'

export type ProjectInvitation = components['schemas']['ProjectInvitationView']
type InvitationPage = components['schemas']['CursorPageProjectInvitationView']
type InvitationProof = components['schemas']['ProjectInvitationProofRequest']
type InvitationRegistration = components['schemas']['ProjectInvitationRegistrationRequest']

export const fetchProjectInvitations = (projectId: string, cursor?: string, signal?: AbortSignal) =>
  request.get<InvitationPage>({
    url: `/api/v1/projects/${projectId}/invitations`,
    params: { cursor, limit: 20 },
    signal
  })
export const fetchMyProjectInvitations = (cursor?: string, signal?: AbortSignal) =>
  request.get<InvitationPage>({
    url: '/api/v1/project-invitations',
    params: { cursor, limit: 20 },
    signal
  })
export const createProjectInvitation = (
  projectId: string,
  body: components['schemas']['InviteMemberRequest']
) =>
  request.post<ProjectInvitation>({
    url: `/api/v1/projects/${projectId}/invitations`,
    params: body
  })
export const resendProjectInvitation = (projectId: string, invitationId: string) =>
  request.post<ProjectInvitation>({
    url: `/api/v1/projects/${projectId}/invitations/${invitationId}/resend`
  })
export const revokeProjectInvitation = (projectId: string, invitationId: string) =>
  request.del<void>({ url: `/api/v1/projects/${projectId}/invitations/${invitationId}` })
export const acceptProjectInvitation = (invitationId: string, code: string) =>
  request.post<void>({
    url: `/api/v1/project-invitations/${invitationId}/accept`,
    params: { code }
  })
export const previewProjectInvitation = (body: InvitationProof, signal?: AbortSignal) =>
  request.post<ProjectInvitation>({
    url: '/api/v1/auth/project-invitation/preview',
    params: body,
    signal
  })
export const registerWithProjectInvitation = (body: InvitationRegistration) =>
  request.post<void>({ url: '/api/v1/auth/project-invitation/register', params: body })
