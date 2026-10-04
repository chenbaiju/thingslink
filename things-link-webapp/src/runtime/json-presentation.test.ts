import { describe, expect, it } from 'vitest'
import { parseDashboardRuntimeResponse } from '@things-link/client-contracts/dashboard/v1'
import { parseCompositeValue, serializeRuntimeValue } from './composite-value'
import { initiallyExpanded, runtimeChildren, runtimeContainer } from './json-presentation'
import { localTablePage } from './device-presentation'
function composite(source: string, kind: 'OBJECT' | 'LIST') {
  const root = parseDashboardRuntimeResponse(new TextEncoder().encode(`{"value":${source}}`))
  return parseCompositeValue(root.value, kind)
}

describe('复合值本地呈现边界', () => {
  it('根depth0默认展开只影响初始details，不隐藏默认深度外的访问入口', () => {
    expect([0, 1, 2].map(depth => initiallyExpanded(depth, 0))).toEqual([false, false, false])
    expect([0, 1, 2].map(depth => initiallyExpanded(depth, 1))).toEqual([true, false, false])
    expect([0, 1, 2].map(depth => initiallyExpanded(depth, 2))).toEqual([true, true, false])
    expect(initiallyExpanded(8, 99)).toBe(false)
  })
  it('用户kind/lexical对象不会冒充数字，危险字段保持普通文本', () => {
    const value = composite('{"kind":"NUMBER","lexical":"9007199254740993","__proto__":"<script>文本</script>"}', 'OBJECT')
    expect(runtimeContainer(value)).toBe(true)
    expect(runtimeChildren(value).map(entry => entry.label)).toEqual(['kind', 'lexical', '__proto__'])
    expect(serializeRuntimeValue(value)).toContain('"kind":"NUMBER"')
    expect(serializeRuntimeValue(value)).toContain('<script>文本</script>')
  })
  it('数值叶精确保留词法而不是展开实现wrapper字段', () => {
    const value = composite('[9007199254740993,0]', 'LIST')
    const number = runtimeChildren(value)[0].value
    expect(runtimeContainer(number)).toBe(false)
    expect(runtimeChildren(number)).toEqual([])
    expect(serializeRuntimeValue(number)).toBe('9007199254740993')
  })
  it('完整LIST本地分页所有元素可达，含空集合且不按内容过滤或切请求', () => {
    const value = composite('[{"a":0},{"b":false},{"c":""},{"a":1},{"d":[2,3]}]', 'LIST')
    const rows = runtimeChildren(value).map(entry => entry.value)
    expect([0, 1, 2].flatMap(index => localTablePage(rows, index, 2).rows).map(serializeRuntimeValue)).toEqual(['{"a":0}', '{"b":false}', '{"c":""}', '{"a":1}', '{"d":[2,3]}'])
    expect(localTablePage([], 3, 2)).toMatchObject({ count: 1, total: 0, rows: [], index: 0 })
  })
})
