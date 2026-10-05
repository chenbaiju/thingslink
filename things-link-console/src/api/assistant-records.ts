import request from '@/utils/http'
import type { components } from '@/types/api/schema'

export type PersonalRecord = components['schemas']['PersonalEvidenceRecordView']
export type PersonalRecordDetail = components['schemas']['PersonalEvidenceRecordDetail']
export type PersonalFactReport = components['schemas']['PersonalFactReport']
export type PersonalFactCollection = components['schemas']['PersonalFactCollectionReport']
const base = (project: string) =>
  `/api/v1/projects/${encodeURIComponent(project)}/assistant/evidence-records` as const
/** 仅提交选择器，服务端重新取证；不上传设备证据或用户身份。 */
export const savePersonalRecord = (
  project: string,
  device: string,
  model: string,
  keys: string[],
  signal: AbortSignal
) =>
  request.post<PersonalRecord>({
    url: base(project),
    data: { deviceId: device, expectedModelVersionId: model, propertyKeys: [...keys] },
    signal,
    showErrorMessage: false
  })
export const listPersonalRecords = (project: string, signal: AbortSignal) =>
  request.get<PersonalRecord[]>({ url: base(project), signal, showErrorMessage: false })
export const readPersonalRecord = (project: string, id: string, signal: AbortSignal) =>
  request.get<PersonalRecordDetail>({
    url: `${base(project)}/${encodeURIComponent(id)}`,
    signal,
    showErrorMessage: false
  })
export const deletePersonalRecord = (project: string, id: string, signal: AbortSignal) =>
  request.del<void>({
    url: `${base(project)}/${encodeURIComponent(id)}`,
    signal,
    showErrorMessage: false
  })
/** 手动生成或下载前重新确权；无正文、模板、用户身份和持久写入。 */
export const generatePersonalFactReport = (project: string, id: string, signal: AbortSignal) =>
  request.get<PersonalFactReport>({
    url: `${base(project)}/${encodeURIComponent(id)}/fact-report`,
    signal,
    showErrorMessage: false
  })

/** 只提交所选个人来源；生成及下载均重新确权，不携带正文、模板或设备数据。 */
export const generatePersonalFactCollection = (
  project: string,
  recordIds: string[],
  signal: AbortSignal
) =>
  request.post<PersonalFactCollection>({
    url: `/api/v1/projects/${encodeURIComponent(project)}/assistant/fact-reports/collection`,
    data: { recordIds: [...recordIds] },
    signal,
    showErrorMessage: false
  })
