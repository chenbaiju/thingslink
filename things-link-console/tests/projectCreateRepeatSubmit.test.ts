import { createPinia, setActivePinia } from 'pinia'
import { defineComponent, h, ref } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'

// ElMessage 在生产由 unplugin-auto-import 的 ElementPlusResolver 自动导入；
// 测试环境不启用该 resolver（避免其注入的 element-plus 样式副作用在 jsdom 下被当真实模块），
// 因此 ElMessage 退化为全局引用，这里用 stubGlobal 兜底。
vi.stubGlobal('ElMessage', { success: vi.fn(), error: vi.fn(), warning: vi.fn(), info: vi.fn() })

// 真实写入页面的防重复提交回归（G1-C2b）：
// 把「校验期」而非「API 在途」作为竞态窗口——旧实现里 submitting 在 await validate() 之后才置位，
// 校验未决期间的快速双击会启动两次校验并最终调用两次 API。本测试用可控未决 Promise 钉住该窗口。
// 选择生产实际使用的 ProjectList 而非 ArtForm，覆盖真实创建项目的提交路径。

const { fetchCreateProject, validateSpy } = vi.hoisted(() => ({
  fetchCreateProject: vi.fn(),
  validateSpy: vi.fn()
}))

vi.mock('vue-i18n', () => ({
  useI18n: () => ({ t: (key: string) => key }),
  // @/locales 模块在 import 时调用 createI18n，需要一并提供，否则组件 import 链会报缺少导出
  createI18n: () => ({ global: { t: (key: string) => key } })
}))
vi.mock('@element-plus/icons-vue', () => ({
  Delete: defineComponent(() => () => h('span')),
  Edit: defineComponent(() => () => h('span')),
  Plus: defineComponent(() => () => h('span'))
}))
vi.mock('@/api/project', () => ({
  fetchProjects: vi.fn().mockResolvedValue([]),
  fetchCreateProject,
  fetchUpdateProject: vi.fn(),
  fetchDeleteProject: vi.fn()
}))
vi.mock('@/api/auth', () => ({
  fetchSwitchProject: vi.fn()
}))
vi.mock('@/hooks/project/useProjectSwitch', () => ({
  useProjectSwitch: () => ({ switchingId: ref(''), switchProject: vi.fn() })
}))
vi.mock('@/hooks/project/useProjectRegionCatalog', () => ({
  useProjectRegionCatalog: () => ({
    defaultProjectRegion: ref('sh-1'),
    loading: ref(false),
    loadProjectRegions: vi.fn().mockResolvedValue(undefined),
    regionGroups: ref([]),
    regionName: () => '',
    regions: ref([])
  })
}))

// validate 的可控未决 Promise：resolveValidate 在测试里手动触发，用于钉住「校验期」竞态窗口。
let resolveValidate: ((valid: boolean) => void) | null = null

// 行为化桩：ElForm 暴露可控的 validate，ElButton 按 loading/disabled 禁用并转发 click，
// ElDialog 只在其 modelValue 为 true 时渲染内容（尊重 v-model 的开合）。
const ElFormStub = defineComponent({
  setup(_, { slots, expose }) {
    expose({
      validate: () => {
        validateSpy()
        return new Promise<boolean>((resolve) => {
          resolveValidate = resolve
        })
      },
      resetFields: () => {}
    })
    return () => h('form', slots.default?.())
  }
})
const ElButtonStub = defineComponent({
  props: { loading: Boolean, disabled: Boolean },
  emits: ['click'],
  setup(props, { slots, emit }) {
    return () =>
      h(
        'button',
        { disabled: props.loading || props.disabled, onClick: () => emit('click') },
        slots.default?.()
      )
  }
})
const ElDialogStub = defineComponent({
  props: { modelValue: Boolean },
  setup(props, { slots }) {
    return () =>
      props.modelValue
        ? h('div', { class: 'el-dialog' }, [slots.default?.(), slots.footer?.()])
        : h('div')
  }
})
const passthrough = (tag: string) =>
  defineComponent({
    setup(_, { slots }) {
      return () => h(tag, slots.default?.())
    }
  })

// 列表表格与本测试无关（且其作用域插槽需要 row 等 slot props），渲染为空即可，
// 避免 passthrough 桩触发 `{ row }` 解构 undefined。
const empty = defineComponent(() => () => h('span'))

const stubs = {
  ElForm: ElFormStub,
  ElButton: ElButtonStub,
  ElDialog: ElDialogStub,
  ElFormItem: passthrough('div'),
  ElInput: passthrough('input'),
  ElSelect: passthrough('div'),
  ElOption: passthrough('span'),
  ElOptionGroup: passthrough('span'),
  ElTable: empty,
  ElTableColumn: empty,
  ElEmpty: empty,
  ElIcon: empty,
  ElTag: empty
}

describe('项目创建防重复提交（G1-C2b 真实写入页）', () => {
  beforeEach(async () => {
    setActivePinia(createPinia())
    fetchCreateProject.mockReset()
    fetchCreateProject.mockResolvedValue({} as never)
    validateSpy.mockReset()
    resolveValidate = null
  })
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  const mountPage = async () => {
    const { useUserStore } = await import('@/store/modules/user')
    const userStore = useUserStore()
    userStore.setUserInfo({ ...userStore.info, currentProjectId: 'proj-1' } as never)
    const { default: ProjectList } = await import('@/views/project/list/index.vue')
    return mount(ProjectList, {
      global: { stubs, mocks: { $t: (key: string) => key } }
    })
  }

  it('校验未决期间连续点击两次：校验与 API 各只调用一次，按钮进入禁用', async () => {
    const wrapper = await mountPage()
    // 打开创建项目对话框
    const createButton = wrapper.findAll('button').find((b) => b.text().includes('project.create'))
    expect(createButton).toBeDefined()
    await createButton!.trigger('click')
    await flushPromises()

    const submitButton = wrapper.findAll('button').find((b) => b.text().includes('project.confirm'))
    expect(submitButton).toBeDefined()

    // 第一次点击：submitting 在 await validate 前同步置位，validate 进入未决
    await submitButton!.trigger('click')
    // 第二次点击：重入守卫在 validate 未决期间就应拦截
    await submitButton!.trigger('click')

    // 校验只启动一次；校验未决时 API 尚未调用、按钮处于禁用/加载态
    expect(validateSpy).toHaveBeenCalledTimes(1)
    expect(fetchCreateProject).not.toHaveBeenCalled()
    expect(submitButton!.attributes('disabled')).toBeDefined()

    // 解析校验后，API 只调用一次
    expect(resolveValidate).not.toBeNull()
    resolveValidate!(true)
    await flushPromises()
    expect(fetchCreateProject).toHaveBeenCalledTimes(1)
  })
})
