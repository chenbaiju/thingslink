import { mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { validateDashboardSchemaV1 } from '@things-link/client-contracts/dashboard/v1'
import { textPreview } from '@/features/dashboard/text-preview'
import DesignerTextPreview from '@/views/dashboard/designer/components/DesignerTextPreview.vue'
function schema(defaultValue?: string, required = true) {
  return validateDashboardSchemaV1(
    new TextEncoder().encode(
      JSON.stringify({
        schemaVersion: 'tc.dashboard/v1',
        presentation: { mode: 'RESPONSIVE_GRID' },
        models: [],
        variables: [
          {
            key: 'label',
            type: 'TEXT_ENUM',
            title: '环境',
            required,
            options: [
              { value: 'safe', label: '生产环境' },
              { value: 'script', label: '<script>window.compromised=1</script>' }
            ],
            ...(defaultValue ? { defaultValue } : {})
          }
        ],
        pages: [
          {
            id: 'main',
            title: '主页',
            components: ['one', 'two'].map((id, index) => ({
              id,
              kind: 'TEXT',
              componentVersion: '1.0.0',
              layout: { x: 0, y: index * 4, w: 12, h: 4 },
              props: { align: 'CENTER', size: 'LARGE', tone: 'PRIMARY' },
              bindings: { text: { source: 'ENUM_TEXT', variableKey: 'label' } }
            }))
          }
        ]
      })
    )
  ).schema
}
afterEach(() => {
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})
describe('枚举文本本地投影', () => {
  it('无默认不自动取首项，区分必选与可选', () => {
    expect(textPreview(schema(), 'main').entries.map((item) => item.text)).toEqual([
      '请选择文本选项',
      '请选择文本选项'
    ])
    expect(textPreview(schema(undefined, false), 'main').entries[0]?.text).toBe('未选择文本选项')
    expect(textPreview(schema('safe'), 'main', { label: null }).entries[0]?.state).toBe(
      'UNSELECTED'
    )
  })
  it('非法运行值拒绝而非回落默认；删页不保留旧业务展示', () => {
    expect(textPreview(schema('safe'), 'main', { label: 'unknown' }).entries[0]).toMatchObject({
      state: 'INVALID',
      text: '文本选项已失效，请重新选择'
    })
    expect(textPreview(schema('safe'), 'deleted').entries).toEqual([])
  })
  it('同变量双TEXT同步，script保持文本，选择无HTTP且不改默认', async () => {
    const request = vi.fn()
    vi.stubGlobal('fetch', request)
    const original = schema()
    const wrapper = mount(DesignerTextPreview, {
      props: { schema: original, pageId: 'main', projectId: 'project', available: true }
    })
    await wrapper.get('select').setValue('script')
    expect(wrapper.findAll('[data-preview-text]').map((node) => node.text())).toEqual([
      '<script>window.compromised=1</script>',
      '<script>window.compromised=1</script>'
    ])
    expect(wrapper.find('script').exists()).toBe(false)
    expect(request).not.toHaveBeenCalled()
    expect(original.variables[0]).not.toHaveProperty('defaultValue')
    expect(wrapper.get('[data-preview-text]').classes()).toEqual(
      expect.arrayContaining(['align-center', 'size-large', 'tone-primary'])
    )
    wrapper.unmount()
  })
  it('换Schema、页面和权限代次清临时选择', async () => {
    const wrapper = mount(DesignerTextPreview, {
      props: { schema: schema(), pageId: 'main', projectId: 'project', available: true }
    })
    await wrapper.get('select').setValue('script')
    await wrapper.setProps({ schema: schema('safe') })
    expect(wrapper.get('select').element.value).toBe('safe')
    await wrapper.get('select').setValue('script')
    await wrapper.setProps({ available: false })
    expect(wrapper.find('[data-preview-text]').exists()).toBe(false)
    await wrapper.setProps({ available: true })
    expect(wrapper.get('select').element.value).toBe('safe')
    await wrapper.get('select').setValue('script')
    await wrapper.setProps({ projectId: 'other-project' })
    expect(wrapper.get('select').element.value).toBe('safe')
    await wrapper.setProps({ pageId: 'deleted' })
    expect(wrapper.find('[data-preview-text]').exists()).toBe(false)
    wrapper.unmount()
  })
  it('隐藏立即撤文本及临时选择，恢复仅用持久默认', async () => {
    let hidden = false
    vi.spyOn(document, 'hidden', 'get').mockImplementation(() => hidden)
    const wrapper = mount(DesignerTextPreview, {
      props: { schema: schema(), pageId: 'main', projectId: 'project', available: true }
    })
    await wrapper.get('select').setValue('script')
    hidden = true
    document.dispatchEvent(new Event('visibilitychange'))
    await wrapper.vm.$nextTick()
    expect(wrapper.find('[data-preview-text]').exists()).toBe(false)
    hidden = false
    document.dispatchEvent(new Event('visibilitychange'))
    await wrapper.vm.$nextTick()
    expect(wrapper.get('select').element.value).toBe('')
    expect(wrapper.findAll('[data-preview-text]').map((node) => node.text())).toEqual([
      '请选择文本选项',
      '请选择文本选项'
    ])
    wrapper.unmount()
  })
})
