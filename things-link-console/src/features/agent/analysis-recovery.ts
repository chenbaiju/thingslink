import { readAnalysisCallByKey } from '@/api/assistant-analysis'
import {
  assertAnalysisKeyUsable,
  type AnalysisIntent,
  type AnalysisScope,
  type analysisIntentStorage
} from './analysis-intent'

/** 必须由手动动作调用；不修改存储、不提交分析，任何失败都保留原意图。 */
export async function recoverAnalysisIntent(
  storage: ReturnType<typeof analysisIntentStorage>,
  scope: AnalysisScope,
  signal: AbortSignal,
  assertCurrent: () => void,
  expected?: AnalysisIntent
) {
  assertCurrent()
  signal.throwIfAborted()
  const loaded = storage.load(scope)
  if (loaded.kind !== 'pending') throw new Error('当前范围没有可恢复的原分析意图，请勿重新提交。')
  const intent = loaded.intent
  const binding = JSON.stringify(intent)
  if (expected && JSON.stringify(expected) !== binding)
    throw new Error('原分析意图已变化，请重新核对。')
  assertAnalysisKeyUsable(intent.key)
  const value = await readAnalysisCallByKey(intent.projectId, intent.key, signal)
  assertCurrent()
  signal.throwIfAborted()
  const current = storage.load(scope)
  if (current.kind !== 'pending' || JSON.stringify(current.intent) !== binding)
    throw new Error('原分析意图已变化，已丢弃迟到状态，请重新核对。')
  return value
}

/** 仅决定本地人工处置时机；不证明远端停止、零费用或授予新的调用资格。 */
export function mayForgetAnalysisIntent(
  intent: AnalysisIntent,
  call: Awaited<ReturnType<typeof readAnalysisCallByKey>> | undefined,
  now = Date.now()
): boolean {
  if (
    !Number.isFinite(now) ||
    !intent ||
    typeof intent.key !== 'string' ||
    !/^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(intent.key)
  )
    return false
  const created = Number.parseInt(intent.key.replaceAll('-', '').slice(0, 12), 16)
  if (Number.isFinite(created) && now >= created + 86_400_000 + 60_000) return true
  if (!call || typeof call.finishedAt !== 'string' || !Number.isFinite(Date.parse(call.finishedAt)))
    return false
  if (call.status === 'SUCCEEDED' || call.status === 'FAILED') return true
  return call.status === 'UNKNOWN' && now >= Date.parse(call.deadline)
}
