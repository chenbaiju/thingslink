import { postAnalysisRun } from '@/api/assistant-analysis'
import {
  type AnalysisScope,
  type AnalysisRequest,
  type analysisIntentStorage
} from './analysis-intent'

/** 单次人工意图：守卫通过后先保存再发送，任何结果均保留原意图供人工核对。 */
export async function submitNewAnalysisIntent(
  storage: ReturnType<typeof analysisIntentStorage>,
  scope: AnalysisScope,
  request: AnalysisRequest,
  signal: AbortSignal,
  assertReady: () => void
) {
  const check = () => {
    signal.throwIfAborted()
    assertReady()
  }
  check()
  const intent = await storage.create(scope, request, check)
  const binding = JSON.stringify(intent)
  const checkOriginal = () => {
    check()
    const loaded = storage.load(scope)
    if (loaded.kind !== 'pending' || JSON.stringify(loaded.intent) !== binding)
      throw new Error('原分析意图已变化，请先核对，不要重复提交。')
  }
  checkOriginal()
  const result = await postAnalysisRun(intent, signal)
  checkOriginal()
  return result
}
