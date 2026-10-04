import type { LineDataItem } from '@/types/component/chart'

/**
 * 折线图序列形状判别与空序列判定（A6-06 的图表侧 null 安全）。
 *
 * 关键陷阱：JavaScript 中 `typeof null === 'object'`，若只按 `typeof data[0] === 'object'`
 * 判断多序列，则 `[null, 1]` 这类「首个历史点为缺失值」的单序列会被误判为多序列，
 * 并在读取 `item.data` 时崩溃。因此多序列判定必须显式排除 `null`。
 *
 * @module components/core/charts/art-line-chart/series-shape
 */

/** 单序列（含 null 断点）或多序列联合类型。 */
export type SeriesData = (number | null)[] | LineDataItem[]

/** 多序列判定：首元素为非 null 对象且含 name 字段。 */
export const isMultiSeries = (data: SeriesData): data is LineDataItem[] =>
  Array.isArray(data) &&
  data.length > 0 &&
  typeof data[0] === 'object' &&
  data[0] !== null &&
  'name' in data[0]

/**
 * 空序列判定：空数组或全部为 null 视为空；只要存在数值（包括 0）就是有数据。
 *
 * 真实读数 0 与缺值必须区分（与 {@code toSeriesValue} 保留 0 的口径一致）：
 * `[0]`、`[0, null]`、`[0, 0]` 都算有数据，只有 `[]` 或 `[null]` 才算空。
 */
export const isSeriesEmpty = (data: SeriesData): boolean => {
  if (!Array.isArray(data) || data.length === 0) return true
  if (isMultiSeries(data)) {
    return data.every((item) => !item.data?.length)
  }
  return data.every((value) => value === null)
}
