import type { components } from '@/types/api/schema'

export type AnalysisCall = components['schemas']['AssistantAnalysisCallView']
export type AnalysisRun = components['schemas']['AssistantAnalysisRunView']
type AnalysisResult = components['schemas']['AssistantAnalysisResult']
type AnalysisUsage = components['schemas']['AssistantAnalysisUsage']
type AnalysisFinding = components['schemas']['AssistantAnalysisFinding']

const exact = (value: unknown, names: string): value is Record<string, unknown> =>
  !!value &&
  typeof value === 'object' &&
  !Array.isArray(value) &&
  Object.keys(value).sort().join(',') === names
const text = (value: unknown): value is string => typeof value === 'string' && value.length > 0
const instant = (value: unknown): value is string =>
  typeof value === 'string' &&
  /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?Z$/.test(value) &&
  Number.isFinite(Date.parse(value))
const invalid = () => new Error('INVALID_ANALYSIS_RUN')

/** 只返回七字段元数据的副本；成功状态本身不能恢复旧正文。 */
export function decodeAnalysisCall(value: unknown): AnalysisCall {
  try {
    if (
      !exact(value, 'createdAt,deadline,dispatchedAt,expiresAt,finishedAt,id,status') ||
      typeof value.id !== 'string' ||
      !/^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(value.id) ||
      !['RESERVED', 'DISPATCHED', 'SUCCEEDED', 'FAILED', 'UNKNOWN'].includes(
        value.status as string
      ) ||
      !instant(value.createdAt) ||
      !instant(value.deadline) ||
      !instant(value.expiresAt) ||
      (value.dispatchedAt !== null && !instant(value.dispatchedAt)) ||
      (value.finishedAt !== null && !instant(value.finishedAt)) ||
      Date.parse(value.createdAt) > Date.parse(value.deadline) ||
      Date.parse(value.deadline) > Date.parse(value.expiresAt)
    )
      throw invalid()
    return { ...(value as unknown as AnalysisCall) }
  } catch {
    throw new Error('INVALID_ANALYSIS_CALL')
  }
}

function usage(value: unknown): AnalysisUsage {
  if (
    !exact(value, 'cacheHitTokens,cacheMissTokens,completionTokens,promptTokens,totalTokens') ||
    Object.values(value).some((count) => !Number.isSafeInteger(count) || (count as number) < 0)
  )
    throw invalid()
  const counts = value as unknown as AnalysisUsage
  if (
    counts.promptTokens < 1 ||
    counts.promptTokens > 4096 ||
    counts.completionTokens > 1024 ||
    counts.totalTokens !== counts.promptTokens + counts.completionTokens ||
    counts.promptTokens !== counts.cacheHitTokens + counts.cacheMissTokens
  )
    throw invalid()
  return { ...counts }
}

function finding(value: unknown): AnalysisFinding {
  if (
    !exact(value, 'evidenceIds,kind,statement') ||
    !['FACT', 'HYPOTHESIS', 'RECOMMENDATION'].includes(value.kind as string) ||
    !text(value.statement) ||
    !Array.isArray(value.evidenceIds) ||
    value.evidenceIds.length === 0 ||
    value.evidenceIds.some(
      (id) => typeof id !== 'string' || !/^e-(?:device|alarm|property-(?:[1-9]|10))$/.test(id)
    )
  )
    throw invalid()
  return {
    kind: value.kind as AnalysisFinding['kind'],
    statement: value.statement,
    evidenceIds: [...value.evidenceIds]
  }
}

function result(value: unknown): AnalysisResult {
  if (
    !exact(value, 'findings,limitations,model,promptVersion,summary,usage') ||
    value.model !== 'deepseek-flash' ||
    value.promptVersion !== 'thingslink-agent-single-analysis-v1' ||
    !text(value.summary) ||
    !Array.isArray(value.findings) ||
    !Array.isArray(value.limitations) ||
    !value.limitations.every(text)
  )
    throw invalid()
  const content = {
    summary: value.summary,
    findings: value.findings.map(finding),
    limitations: [...value.limitations]
  }
  if (new TextEncoder().encode(JSON.stringify(content)).byteLength > 16 * 1024) throw invalid()
  return {
    model: value.model,
    promptVersion: value.promptVersion,
    ...content,
    usage: usage(value.usage)
  }
}

/** 纯结构边界：不联网、不保存、不执行文本，不以解码成功授予准入或语义质量。 */
export function decodeAnalysisRun(value: unknown): AnalysisRun {
  try {
    if (
      !exact(value, 'call,category,result') ||
      !['UNAVAILABLE', 'REPLAY', 'TRANSPORT_UNKNOWN', 'UNQUALIFIED', 'SUCCEEDED'].includes(
        value.category as string
      )
    )
      throw invalid()
    if (value.category === 'UNAVAILABLE') {
      if (value.call !== null || value.result !== null) throw invalid()
      return { call: null, category: 'UNAVAILABLE', result: null }
    }
    const call = decodeAnalysisCall(value.call)
    if (value.category === 'SUCCEEDED') {
      if (call.status !== 'SUCCEEDED') throw invalid()
      return { call, category: 'SUCCEEDED', result: result(value.result) }
    }
    if (value.result !== null) throw invalid()
    return { call, category: value.category as AnalysisRun['category'], result: null }
  } catch {
    // 原网络对象、正文和解析异常都不进入错误消息或原因链。
    throw invalid()
  }
}
