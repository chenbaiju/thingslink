/**
 * OTA类型基线历史页的纯分页状态逻辑。
 *
 * 与固件/设备作业历史集合共用一套键集合并合同（`mergeOtaHistoryPage`）：游标只从服务端
 * 原样透传，控制台不解析也不自造；`hasMore`为假时立刻清空游标。这里只做类型别名，
 * 不复制第二份实现，避免三个历史集合的游标语义各自漂移。
 *
 * 基线的版本与摘要都是服务端事实：控制台只展示，不推断「哪一版应该生效」。
 *
 * @module features/ota/baseline-model
 */

import {
  emptyOtaHistory,
  mergeOtaHistoryPage,
  type OtaHistoryPage,
  type OtaHistoryState
} from './firmware-model'

/** 类型基线历史一页的读取结果形状；与固件历史集合共用同一游标合同。 */
export type OtaBaselineVersionPage<T> = OtaHistoryPage<T>

/** 类型基线历史的本地分页状态；`cursor`只在`hasMore`为真时有意义。 */
export type OtaBaselineVersionHistoryState<T> = OtaHistoryState<T>

/** 空类型基线历史状态。 */
export const emptyOtaBaselineVersionHistory = <T>(): OtaBaselineVersionHistoryState<T> =>
  emptyOtaHistory<T>()

/**
 * 合并一页类型基线历史：续页追加在已有事实之后，首页替换。
 *
 * 切换设备类型时必须走替换（`append`为false），否则会把上一类型的版本混进当前历史。
 *
 * @param current 当前已加载状态
 * @param page 服务端返回的一页
 * @param append 是否为续页（true 时追加，false 时替换）
 */
export function mergeOtaBaselineVersionPage<T>(
  current: OtaBaselineVersionHistoryState<T>,
  page: OtaBaselineVersionPage<T>,
  append: boolean
): OtaBaselineVersionHistoryState<T> {
  return mergeOtaHistoryPage(current, page, append)
}
