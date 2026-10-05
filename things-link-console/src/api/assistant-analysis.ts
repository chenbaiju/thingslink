import request from '@/utils/http'
import type { components } from '@/types/api/schema'
import {
  assertAnalysisKeyUsable,
  parseIntent,
  type AnalysisIntent
} from '@/features/agent/analysis-intent'
import { decodeAnalysisCall, decodeAnalysisRun } from '@/features/agent/analysis-result'

export type AnalysisAvailability = components['schemas']['AssistantAnalysisAvailability']
export type AnalysisTemplate = components['schemas']['AssistantAnalysisRequest']['template']
export type AnalysisCall = components['schemas']['AssistantAnalysisCallView']

/** 只读恢复仅接受封闭元数据，不能从任意返回正文推断模型分析成功或零消费。 */
export async function readAnalysisCallByKey(
  project: string,
  key: string,
  signal: AbortSignal
): Promise<AnalysisCall> {
  assertAnalysisKeyUsable(key)
  const value = await request.get<AnalysisCall>({
    url: `/api/v1/projects/${encodeURIComponent(project)}/assistant/analysis-runs/by-key`,
    headers: { 'Idempotency-Key': key },
    signal,
    showErrorMessage: false
  })
  return decodeAnalysisCall(value)
}

/** 受权状态仅提示本次可发起；固定原因和布尔值必须配对，未知响应拒绝。 */
export async function readAnalysisAvailability(project: string, signal: AbortSignal) {
  const result = await request.get<AnalysisAvailability>({
    url: `/api/v1/projects/${encodeURIComponent(project)}/assistant/analysis-runs/status`,
    signal,
    showErrorMessage: false
  })
  signal.throwIfAborted()
  if (
    !result ||
    Object.keys(result).sort().join(',') !== 'businessAvailable,reason' ||
    !(
      (result.businessAvailable === true && result.reason === 'REVIEWED_CONFIGURATION_AVAILABLE') ||
      (result.businessAvailable === false &&
        [
          'INTERNAL_TRANSPORT_DISABLED',
          'MODEL_ADMISSION_PENDING',
          'PROJECT_MODEL_CONFIGURATION_DISABLED'
        ].includes(result.reason))
    )
  )
    throw new Error('INVALID_ANALYSIS_AVAILABILITY')
  return { businessAvailable: result.businessAvailable, reason: result.reason }
}

/** 仅由先保存原意图的手动流程调用；服务端重新确权，客户端包装不授予发送资格。 */
export async function postAnalysisRun(intent: AnalysisIntent, signal: AbortSignal) {
  signal.throwIfAborted()
  const original = parseIntent(intent)
  assertAnalysisKeyUsable(original.key)
  const value = await request.post<unknown>({
    url: `/api/v1/projects/${encodeURIComponent(original.projectId)}/assistant/analysis-runs`,
    headers: { 'Idempotency-Key': original.key },
    data: original.request,
    signal,
    timeout: 60_000,
    showErrorMessage: false
  })
  signal.throwIfAborted()
  return decodeAnalysisRun(value)
}
