import request from '@/utils/http'
import type { components } from '@/types/api/schema'

export type ModelConfiguration = components['schemas']['ModelConfigurationView']
type CredentialInput = components['schemas']['ModelCredentialInput']
type EnableInput = components['schemas']['EnableRequest']
const base = (project: string) =>
  `/api/v1/projects/${project}/assistant/model-configurations/deepseek-chat` as const
const quiet = { showErrorMessage: false }
export const readModelConfiguration = (project: string, signal?: AbortSignal) =>
  request.get<ModelConfiguration>({ url: base(project), signal, ...quiet })
export const replaceModelCredential = (
  project: string,
  data: CredentialInput,
  signal?: AbortSignal
) => request.put<ModelConfiguration>({ url: base(project), params: data, signal, ...quiet })
export const setModelEnabled = (project: string, data: EnableInput, signal?: AbortSignal) =>
  request.patch<ModelConfiguration>({ url: base(project), params: data, signal, ...quiet })
export const removeModelCredential = (
  project: string,
  expectedRevision: string,
  signal?: AbortSignal
) =>
  request.del<ModelConfiguration>({
    url: base(project),
    params: { expectedRevision },
    signal,
    ...quiet
  })
