import type { RuntimeNumber, DeviceSnapshot, CurrentDevice } from './device-data'

export { formatDecimal, formatScalar } from '@things-link/client-contracts/dashboard/v1'
export function isRuntimeNumber(value: unknown): value is RuntimeNumber {
  return typeof value === 'object' && value !== null && 'kind' in value && value.kind === 'NUMBER' && 'lexical' in value && typeof value.lexical === 'string'
}

export function stateLabel(state: string, required = true): string {
  const labels: Record<string, string> = {
    LOADING: '正在读取设备数据…', NOT_AVAILABLE: '设备不可用或已无访问权限', MODEL_MISMATCH: '设备模型已变化，请检查发布版本',
    NO_VALUE: '暂无采集值', SOURCE_VERSION_UNKNOWN: '数据来源版本未知', SOURCE_MODEL_MISMATCH: '数据来源模型不匹配',
    CONTRACT_MISMATCH: '数据合同不匹配', ERROR: '设备数据读取失败', INACTIVE: '未激活', ONLINE: '在线', OFFLINE: '离线',
  }
  return state === 'UNSELECTED' ? required ? '请选择设备' : '未选择设备' : labels[state] ?? '数据合同不匹配'
}

/** 多选变化是一次完整集合替换；超限拒绝而不是截断用户意图。 */
export function acceptsDeviceSelection(ids: readonly string[], maximum: number): boolean {
  return ids.length <= maximum && new Set(ids).size === ids.length && ids.every(id => id.length > 0)
}
/** current复验优先于较早的元信息；撤权行不能保留旧设备名。 */
export function deviceTableIdentity(snapshot: DeviceSnapshot | undefined, current: CurrentDevice | undefined): { state: string; name: string } {
  // 快照已明确不可用时loader不查询current；保留首个权威拒绝，不能误报合同缺字段。
  const state = !snapshot ? 'CONTRACT_MISMATCH' : snapshot.status !== 'AVAILABLE' ? snapshot.status
    : current?.status ?? 'CONTRACT_MISMATCH'
  return { state, name: state === 'AVAILABLE' && snapshot?.status === 'AVAILABLE' ? snapshot.name : stateLabel(state) }
}

export { gaugePosition, localTablePage } from "@things-link/client-contracts/dashboard/v1";
