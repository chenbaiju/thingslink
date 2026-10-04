import type { HistoryPoint } from './history-response.js'
export type { HistoryPoint } from './history-response.js'
/** 已具备完整粒度的展示事实；网络失败结果不能直接充作可绘制序列。 */
export interface HistoryPresentationResult {
  readonly requestedGranularity: string; readonly actualGranularity: string; readonly aggregation: string;
  readonly points: readonly HistoryPoint[];
}
/** 展示序列仅持有说明和事实，不引用宿主组件、API或会话。 */
export interface HistorySeriesView { readonly id: string; readonly label: string; readonly state: string; readonly history?: HistoryPresentationResult }
/** 按完整来源版本及实际桶宽断线，不跨版本合并同刻点，也不推测RAW缺值。 */
export function historySegments(points: readonly HistoryPoint[], granularity: string): readonly (readonly HistoryPoint[])[] {
  const bucket: Record<string, number> = { ONE_MINUTE: 60000, ONE_HOUR: 3600000, ONE_DAY: 86400000 }
  const segments: HistoryPoint[][] = []
  for (const point of points) {
    const previousSegment = segments[segments.length - 1]
    const previous = previousSegment?.[previousSegment.length - 1]
    if (!previous || previous.thingModelVersionId !== point.thingModelVersionId || previous.modelVersion !== point.modelVersion
      || bucket[granularity] && Date.parse(point.ts) - Date.parse(previous.ts) > bucket[granularity]!) segments.push([point])
    else previousSegment!.push(point)
  }
  return segments
}
/** 真实点不按timestamp去重；缩放先归一避免Double两端相减溢出。 */
export function historyGeometry(points: readonly HistoryPoint[]): readonly { x: number; y: number; point: HistoryPoint }[] {
  if (!points.length) return []
  const times = points.map(point => Date.parse(point.ts))
  const values = points.map(point => Number(point.value.lexical))
  const lowTime = Math.min(...times); const highTime = Math.max(...times)
  const magnitude = Math.max(...values.map(Math.abs), 1)
  const normalized = values.map(value => value / magnitude)
  const low = Math.min(...normalized); const high = Math.max(...normalized)
  return points.map((point, index) => ({ point,
    x: highTime === lowTime ? 320 : 24 + (times[index]! - lowTime) / (highTime - lowTime) * 592,
    y: high === low ? 110 : 196 - (normalized[index]! - low) / (high - low) * 172,
  }))
}
/** 保留既有中文局部状态，不把空数据或非数值业务状态伪装为网络失败。 */
export function interactionStateLabel(state: string): string {
  return ({ UNSELECTED: '请完成变量选择', LOADING: '正在读取…', '30058': '历史包含非数值数据，无法绘制完整序列',
    '10001': '配置或查询预算不符合要求', EMPTY: '当前查询没有数据', ERROR: '数据读取失败',
    NOT_AVAILABLE: '设备不可用或已无访问权限', MODEL_MISMATCH: '设备模型已变化，请检查发布版本',
  } as Record<string, string>)[state] ?? '数据合同不匹配'
}

/** 范围文字直接取真实点的词法；几何近似不反向替换这些事实。 */
export function historyRange(points: readonly HistoryPoint[]): { from: string; to: string; minimum: string; maximum: string } | null {
  if (!points.length) return null
  const compare = (left: string, right: string): number => {
    const parts = (raw: string) => {
      const [coefficient, exponent = '0'] = raw.replace(/^-/, '').toLowerCase().split('e')
      const [integer, fraction = ''] = coefficient!.split('.')
      const digits = (integer! + fraction).replace(/^0+/, '')
      return { sign: digits ? raw.startsWith('-') ? -1 : 1 : 0, digits, order: digits.length + Number(exponent) - fraction.length }
    }
    const a = parts(left); const b = parts(right)
    if (a.sign !== b.sign) return a.sign < b.sign ? -1 : 1
    if (!a.sign) return 0
    if (a.order !== b.order) return (a.order < b.order ? -1 : 1) * a.sign
    const length = Math.max(a.digits.length, b.digits.length)
    const aa = a.digits.padEnd(length, '0'); const bb = b.digits.padEnd(length, '0')
    return aa === bb ? 0 : (aa < bb ? -1 : 1) * a.sign
  }
  let minimum = points[0]!.value.lexical; let maximum = minimum
  for (const point of points) {
    if (compare(point.value.lexical, minimum) < 0) minimum = point.value.lexical
    if (compare(point.value.lexical, maximum) > 0) maximum = point.value.lexical
  }
  return { from: points[0]!.ts, to: points[points.length - 1]!.ts, minimum, maximum }
}
