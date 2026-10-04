/**
 * OTA信任域页的纯展示与只读分页状态逻辑。
 *
 * 与固件/作业/基线历史集合共用一套键集合并合同（`mergeOtaHistoryPage`）：游标只从服务端
 * 原样透传，控制台不解析也不自造；`hasMore`为假时立刻清空游标。
 *
 * 本模块只服务**只读**信任/密钥表：密钥状态是服务端安全事实，控制台只做标签映射与展示，
 * 不提供 rotate/retire/import 入口——那属于独立安全切片。未知状态原样透传而不是折叠成
 * 某个已知状态，避免把服务端新状态显示成"可用"。
 *
 * @module features/ota/trust-model
 */

import {
  emptyOtaHistory,
  mergeOtaHistoryPage,
  type OtaHistoryPage,
  type OtaHistoryState
} from './firmware-model'

/** 发布键状态中文标签；闭集取自 `tc-ota-trust-bundle/v1` 的四态。 */
export const OTA_TRUST_KEY_STATE_LABELS: Record<string, string> = {
  PREPARED: '待启用',
  ACTIVE: '使用中',
  VERIFY_ONLY: '仅验签',
  REVOKED: '已撤销'
}

/** 发布键状态 ElTag 类型。 */
export const OTA_TRUST_KEY_STATE_TAGS: Record<string, 'info' | 'success' | 'warning' | 'danger'> = {
  PREPARED: 'info',
  ACTIVE: 'success',
  VERIFY_ONLY: 'warning',
  REVOKED: 'danger'
}

/** 发布键状态 → 中文标签；未知状态原样透传，缺状态返回中性占位符。 */
export const otaTrustKeyStateLabel = (state?: string | null): string =>
  state ? (OTA_TRUST_KEY_STATE_LABELS[state] ?? state) : '—'

/** 发布键状态 → ElTag 类型；未知或缺省一律 info。 */
export const otaTrustKeyStateTag = (
  state?: string | null
): 'info' | 'success' | 'warning' | 'danger' =>
  state ? (OTA_TRUST_KEY_STATE_TAGS[state] ?? 'info') : 'info'

/**
 * 把有效期窗口格式化为只读展示文本。
 *
 * `notBefore`/`notAfter` 是UTC epoch秒、左闭右开；控制台只做格式化，不推断"当前是否有效"，
 * 因为那是服务端按精确时刻与受控配置判定的资格事实。
 *
 * @param notBefore 包含的有效起点epoch秒
 * @param notAfter 不包含的有效终点epoch秒
 */
export function otaTrustKeyValidity(notBefore?: number | null, notAfter?: number | null): string {
  if (
    notBefore === undefined ||
    notBefore === null ||
    notAfter === undefined ||
    notAfter === null
  ) {
    return '—'
  }
  return `${new Date(notBefore * 1000).toISOString()} → ${new Date(notAfter * 1000).toISOString()}（不含终点）`
}

/** 信任域集合一页的读取结果形状；与固件历史集合共用同一游标合同。 */
export type OtaTrustDomainPage<T> = OtaHistoryPage<T>

/** 信任域集合的本地分页状态；`cursor`只在`hasMore`为真时有意义。 */
export type OtaTrustDomainHistoryState<T> = OtaHistoryState<T>

/** 空信任域集合状态。 */
export const emptyOtaTrustDomainHistory = <T>(): OtaTrustDomainHistoryState<T> =>
  emptyOtaHistory<T>()

/**
 * 合并一页信任域事实：续页追加在已有事实之后，首页替换。
 *
 * 切换项目时必须走替换（`append`为false），否则会把上一个项目的域混进当前表。
 *
 * @param current 当前已加载状态
 * @param page 服务端返回的一页
 * @param append 是否为续页（true 时追加，false 时替换）
 */
export function mergeOtaTrustDomainPage<T>(
  current: OtaTrustDomainHistoryState<T>,
  page: OtaTrustDomainPage<T>,
  append: boolean
): OtaTrustDomainHistoryState<T> {
  return mergeOtaHistoryPage(current, page, append)
}

/** 信任密钥集合一页的读取结果形状；与固件历史集合共用同一游标合同。 */
export type OtaTrustKeyPage<T> = OtaHistoryPage<T>

/** 信任密钥集合的本地分页状态；`cursor`只在`hasMore`为真时有意义。 */
export type OtaTrustKeyHistoryState<T> = OtaHistoryState<T>

/** 空信任密钥集合状态。 */
export const emptyOtaTrustKeyHistory = <T>(): OtaTrustKeyHistoryState<T> => emptyOtaHistory<T>()

/**
 * 合并一页信任密钥事实：续页追加在已有事实之后，首页替换。
 *
 * 切换信任域或服务端包版本轮换后必须走替换（`append`为false）：服务端游标绑定包版本，
 * 轮换后旧游标会被拒（400/10001），继续追加会把两代键拼成一张表。
 *
 * @param current 当前已加载状态
 * @param page 服务端返回的一页
 * @param append 是否为续页（true 时追加，false 时替换）
 */
export function mergeOtaTrustKeyPage<T>(
  current: OtaTrustKeyHistoryState<T>,
  page: OtaTrustKeyPage<T>,
  append: boolean
): OtaTrustKeyHistoryState<T> {
  return mergeOtaHistoryPage(current, page, append)
}
