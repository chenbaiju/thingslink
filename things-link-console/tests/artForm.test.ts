import { describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import ArtForm from '@/components/core/forms/art-form/index.vue'

// art-form 依赖 @vueuse 的 useWindowSize 与 vue-i18n，测试里以桩替换。
vi.mock('@vueuse/core', () => ({
  useWindowSize: () => ({ width: 1200, height: 800 })
}))
vi.mock('vue-i18n', () => ({
  useI18n: () => ({ t: (key: string) => key })
}))

const stubs = {
  ElForm: { template: '<form><slot /></form>' },
  ElRow: { template: '<div><slot /></div>' },
  ElCol: { template: '<div><slot /></div>' },
  ElFormItem: { template: '<div><slot /></div>' },
  ElButton: { template: '<button :disabled="disabled"><slot /></button>', props: ['disabled'] }
}

/** G1-C2b 组件态：表单提交按钮的禁用（防重复提交）与提交事件。 */
describe('表单提交按钮禁用与提交（G1-C2b）', () => {
  const mountForm = (disabledSubmit: boolean) =>
    mount(ArtForm, {
      props: { modelValue: {}, items: [], showSubmit: true, showReset: false, disabledSubmit },
      global: { stubs, directives: { ripple: {} } }
    })

  it('未禁用时点击提交触发 submit 事件一次', async () => {
    const wrapper = mountForm(false)
    const button = wrapper.find('button')
    expect(button.attributes('disabled')).toBeUndefined()
    await button.trigger('click')
    expect(wrapper.emitted('submit')).toHaveLength(1)
  })

  it('disabledSubmit=true 时按钮带 disabled，阻断重复提交', async () => {
    const wrapper = mountForm(true)
    const button = wrapper.find('button')
    expect(button.attributes('disabled')).toBeDefined()
  })
})
