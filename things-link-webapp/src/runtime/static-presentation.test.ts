import { describe, expect, it } from 'vitest'
import type { DashboardSchemaV1, DashboardStaticTextComponent, DashboardResponsiveLayout } from '@things-link/client-contracts/dashboard/v1'
import { componentLayout, fixedFit, imageSource, supportsStaticDashboard } from './static-presentation'

const digest = 'a'.repeat(64)
const text: DashboardStaticTextComponent<DashboardResponsiveLayout> = {
  id: 'text', kind: 'TEXT', componentVersion: '1.0.0', layout: { x: 6, y: 2, w: 12, h: 3 },
  props: { content: '<script>字面文本</script>\n第二行', align: 'LEFT', size: 'MEDIUM', tone: 'REGULAR' }, bindings: {},
}
const schema: DashboardSchemaV1 = {
  schemaVersion: 'tc.dashboard/v1', presentation: { mode: 'RESPONSIVE_GRID', theme: 'LIGHT', columns: 24, rowHeight: 8, gap: 8 },
  models: [], variables: [], pages: [{ id: 'first', title: '第一页', components: [text] }],
}
const image = { id: 'image', kind: 'IMAGE' as const, componentVersion: '1.0.0' as const, layout: text.layout,
  props: { resourceId: 'logo', resourceDigest: digest, alt: '设备', fit: 'CONTAIN' as const }, bindings: {} }
const resources = [{ resourceId: 'logo', digest, src: `/app/assets/${digest}.png` }]

describe('静态呈现的合同边界', () => {
  it('768内容断点使用24列，小屏只保留逻辑最小高度而不按坐标排序', () => {
    expect(componentLayout(text, false, 768)).toEqual({ gridColumn: '7 / span 12', gridRow: '3 / span 3' })
    expect(componentLayout(text, false, 767)).toEqual({ minHeight: '40px' })
  })
  it('固定画布同时受宽高限制并保留原始矩形', () => {
    expect(fixedFit(960, 1080)).toBe(0.5)
    expect(fixedFit(1920, 270)).toBe(0.25)
    expect(fixedFit(0, 500)).toBe(0)
    expect(componentLayout(text, true, 400)).toEqual({ position: 'absolute', left: '6px', top: '2px', width: '12px', height: '3px' })
  })
  it('资源必须精确匹配注册id、摘要及摘要命名同源PNG', () => {
    expect(imageSource(image, resources)).toBe(resources[0].src)
    for (const src of ['https://example.com/image.png', '//example.com/image.png', '/app/assets/other.png', `${resources[0].src}?x=1`]) {
      expect(imageSource(image, [{ ...resources[0], src }])).toBeUndefined()
    }
    expect(imageSource(image, [{ ...resources[0], digest: 'b'.repeat(64) }])).toBeUndefined()
    expect(imageSource(image, [...resources, ...resources])).toBeUndefined()
  })
  it('整版扫描非当前页面，不对不支持版本或动态绑定提供部分画布', () => {
    expect(supportsStaticDashboard(schema, [])).toBe(true)
    expect(supportsStaticDashboard({ ...schema, pages: [{ ...schema.pages[0], components: [{ ...text, componentVersion: '1.0.1' }] }] }, [])).toBe(true)
    const unsupported = { ...text, componentVersion: '9.0.0' } as unknown as typeof text
    expect(supportsStaticDashboard({ ...schema, pages: [...schema.pages, { id: 'other', title: '其他页', components: [unsupported] }] }, [])).toBe(false)
    const dynamic = { ...text, props: { align: 'LEFT', size: 'MEDIUM', tone: 'REGULAR' }, bindings: { text: { source: 'ENUM_TEXT', variableKey: 'choice' } } } as unknown as typeof text
    expect(supportsStaticDashboard({ ...schema, pages: [{ ...schema.pages[0], components: [dynamic] }] }, [])).toBe(false)
  })
})
