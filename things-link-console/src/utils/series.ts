/**
 * 历史序列工具。
 *
 * @module utils/series
 */

/**
 * 把历史序列点的值规整为图表可用值：缺值保持 `null`（图表断点），
 * 不得用 0 补齐——否则「无数据」与「读数 0」在曲线上无法区分（A6-06）。
 *
 * @param value 后端历史点值，可能缺省
 * @returns 数值或 null
 */
export const toSeriesValue = (value?: number | null): number | null => value ?? null
