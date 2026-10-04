import { isRuntimeNumber, type RuntimeValue } from "./composite-value.js"

/** 仅遍历已验证复合快照自己的字段，字段名只作为文本，不解释为访问路径。 */
export function runtimeChildren(value: RuntimeValue): readonly { label: string; value: RuntimeValue }[] {
  if (typeof value !== 'object' || isRuntimeNumber(value)) return []
  return Array.isArray(value)
    ? value.map((entry, index) => ({ label: String(index), value: entry }))
    : Object.entries(value).map(([label, entry]) => ({ label, value: entry }))
}
export function runtimeContainer(value: RuntimeValue): boolean {
  return typeof value === 'object' && !isRuntimeNumber(value)
}
/** 根depth=0；默认展开深度只控制本地details，不影响请求或原始值。 */
export function initiallyExpanded(depth: number, initialExpandDepth: number): boolean {
  return depth < initialExpandDepth && depth < 8
}
