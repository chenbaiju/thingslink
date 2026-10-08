import { createPinia, setActivePinia } from 'pinia'
import { defineComponent, h, ref } from 'vue'
import { ElTable, ElTableColumn } from 'element-plus'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { fetchDeleteProject, fetchProjects } from '@/api/project'
import { fetchProjectQuota } from '@/api/quota'
import { fetchSwitchProject } from '@/api/auth'
import { useUserStore } from '@/store/modules/user'
import ProjectList from '@/views/project/list/index.vue'
import ProjectCreate from '@/views/project/create/index.vue'

// ElMessage 在生产由 unplugin-auto-import 的 ElementPlusResolver 自动导入；
// 测试环境不启用该 resolver（避免其注入的 element-plus 样式副作用在 jsdom 下被当真实模块），
// 因此 ElMessage 退化为全局引用，这里用 stubGlobal 兜底。
vi.stubGlobal('ElMessage', { success: vi.fn(), error: vi.fn(), warning: vi.fn(), info: vi.fn() })

// 真实写入页面的防重复提交回归（G1-C2b）：
// 把「校验期」而非「API 在途」作为竞态窗口——旧实现里 submitting 在 await validate() 之后才置位，
// 校验未决期间的快速双击会启动两次校验并最终调用两次 API。本测试用可控未决 Promise 钉住该窗口。
// 选择生产实际使用的 ProjectCreate，覆盖创建页的真实提交路径；ProjectList 验证入口与行操作。

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
vi.mock('@/api/plan', () => ({ fetchPlanCatalog: vi.fn().mockResolvedValue([]) }))
vi.mock('@/api/quota', () => ({
  fetchProjectQuota: vi.fn()
}))
vi.mock('@/hooks/project/useProjectSwitch', () => ({
  useProjectSwitch: () => ({ switchingId: ref(''), switchProject: vi.fn() })
}))
vi.mock('@/hooks/project/useProjectRegionCatalog', () => ({
  useProjectRegionCatalog: () => ({
    defaultProjectRegion: ref('sh-1'),
    loading: ref(false),
    loadProjectRegions: vi.fn().mockResolvedValue(undefined),
    regionGroups: ref([
      {
        areaCode: 'sh',
        areaName: '上海',
        regions: [{ code: 'sh-1', name: '上海1区', enabled: true, projectCreationEnabled: true }]
      }
    ]),
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
const ElInputStub = defineComponent({
  props: { modelValue: String, type: String },
  emits: ['update:modelValue'],
  setup(props, { emit }) {
    return () =>
      h(props.type === 'textarea' ? 'textarea' : 'input', {
        value: props.modelValue,
        onInput: (event: Event) =>
          emit('update:modelValue', (event.target as HTMLInputElement).value)
      })
  }
})
const passthrough = (tag: string) =>
  defineComponent({
    setup(_, { slots }) {
      return () => h(tag, slots.default?.())
    }
  })

// 无关展示组件保持轻量桩，表格使用真实组件验证项目行及作用域插槽。
const empty = defineComponent(() => () => h('span'))

const stubs = {
  ConsoleTableAction: true,
  ElForm: ElFormStub,
  ElButton: ElButtonStub,
  ElDialog: ElDialogStub,
  ElFormItem: passthrough('div'),
  ElInput: ElInputStub,
  ElSelect: passthrough('div'),
  ElOption: passthrough('span'),
  ElOptionGroup: passthrough('span'),
  ElTable,
  ElTableColumn,
  ElEmpty: empty,
  ElIcon: empty,
  ElTag: empty
}

describe('项目创建防重复提交（G1-C2b 真实写入页）', () => {
  beforeEach(async () => {
    vi.stubGlobal('ElMessage', {
      success: vi.fn(),
      error: vi.fn(),
      warning: vi.fn(),
      info: vi.fn()
    })
    setActivePinia(createPinia())
    fetchCreateProject.mockReset()
    fetchCreateProject.mockResolvedValue({} as never)
    vi.mocked(fetchProjects).mockResolvedValue([])
    vi.mocked(fetchProjectQuota).mockReset()
    validateSpy.mockReset()
    resolveValidate = null
  })
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  const mountPage = async (create = false) => {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/project/list', component: { template: '<div />' } },
        { path: '/project/recycle-bin', component: { template: '<div />' } },
        { path: '/project/create', component: { template: '<div />' } }
      ]
    })
    await router.push(create ? '/project/create' : '/project/list')
    await router.isReady()
    const userStore = useUserStore()
    userStore.setUserInfo({ ...userStore.info, currentProjectId: 'proj-1' } as never)
    return mount(create ? ProjectCreate : ProjectList, {
      global: {
        plugins: [router],
        stubs,
        directives: { loading: () => {} },
        mocks: { $t: (key: string) => key }
      }
    })
  }

  it('创建按钮跳转添加项目页面，不打开创建弹窗', async () => {
    const wrapper = await mountPage()
    await flushPromises()
    await wrapper
      .findAll('button')
      .find((b) => b.text().includes('project.create'))!
      .trigger('click')
    await flushPromises()
    expect(wrapper.vm.$route.path).toBe('/project/create')
    expect(wrapper.find('.el-dialog').exists()).toBe(false)
    wrapper.unmount()
  })

  it('回收站按钮跳转独立页面，不在项目列表展开回收站', async () => {
    const wrapper = await mountPage()
    await flushPromises()
    const button = wrapper.findAll('button').find((item) => item.text() === '项目回收站')!
    await button.trigger('click')
    await flushPromises()
    expect(wrapper.vm.$route.path).toBe('/project/recycle-bin')
    expect(wrapper.find('.project-recycle-bin').exists()).toBe(false)
    wrapper.unmount()
  })

  it('列表展示各项目返回的真实订阅，不查当前项目配额且不把缺失版本当免费版', async () => {
    vi.mocked(fetchProjects).mockResolvedValue([
      {
        id: 'proj-1',
        name: '当前项目',
        description: '设备接入与规则调试',
        myRole: 'OWNER',
        region: 'sh-1',
        subscribedPlan: { name: '免费版', code: 'FREE', revision: 'free-r1', revisionNo: 1 }
      },
      {
        id: 'proj-2',
        name: '未进入项目',
        myRole: 'OWNER',
        region: 'bj-2',
        subscribedPlan: { name: '标准版', code: 'STANDARD', revision: 'standard-r2', revisionNo: 2 }
      },
      { id: 'proj-3', name: '外部协作项目', myRole: 'VIEWER', region: 'sh-1' }
    ])
    const wrapper = await mountPage()
    await flushPromises()
    expect(fetchProjectQuota).not.toHaveBeenCalled()
    expect(
      wrapper
        .findAll('.el-table__row')
        .find((row) => row.text().includes('当前项目'))!
        .text()
    ).toContain('免费版')
    const ownedRows = wrapper.findAll('.project-list__section')[0].findAll('.el-table__row')
    expect(ownedRows[0].findAll('.cell')[1].text()).toBe('设备接入与规则调试')
    expect(ownedRows[1].findAll('.cell')[1].text()).toBe('—')
    const descriptionColumns = wrapper
      .findAllComponents(ElTableColumn)
      .filter((column) => column.props('label') === '项目描述')
    expect(descriptionColumns).toHaveLength(2)
    expect(descriptionColumns.every((column) => column.props('showOverflowTooltip'))).toBe(true)
    const unselected = wrapper
      .findAll('.el-table__row')
      .find((row) => row.text().includes('未进入项目'))!
    expect(unselected.text()).toContain('标准版')
    expect(unselected.find('.project-list__plan').attributes('title')).toContain('standard-r2 · v2')
    const external = wrapper
      .findAll('.el-table__row')
      .find((row) => row.text().includes('外部协作项目'))!
    expect(external.text()).toContain('订阅版本暂不可用')
    expect(external.text()).not.toContain('免费版')
    expect(external.text()).not.toContain('标准版')
    expect(external.findAll('.cell')[1].text()).toBe('—')
    await wrapper.vm.$router.replace({ query: { region: 'bj-2' } })
    expect(wrapper.findAll('.el-table__row')).toHaveLength(1)
    expect(wrapper.find('.el-table__row').text()).toContain('未进入项目')
    wrapper.unmount()
  })

  it('点击项目行或名称均可进入，编辑和删除操作不会触发进入', async () => {
    const project = { id: 'proj-2', name: '目标项目', myRole: 'OWNER', region: 'sh-1' }
    vi.mocked(fetchProjects).mockResolvedValue([project])
    vi.stubGlobal('ElMessageBox', { confirm: vi.fn().mockRejectedValue('cancel') })
    const wrapper = await mountPage()
    await flushPromises()
    const state = (wrapper.vm as any).$?.setupState
    const card = wrapper.find('.el-table__row')
    await card.findAll('.cell')[1].trigger('click')
    expect(state.switchProject).toHaveBeenCalledExactlyOnceWith(project)
    state.switchProject.mockClear()
    await card.find('.project-list__entry').trigger('click')
    expect(state.switchProject).toHaveBeenCalledExactlyOnceWith(project)
    state.switchProject.mockClear()
    await card.find('console-table-action-stub[label="project.edit"]').trigger('click')
    expect(state.editVisible).toBe(true)
    expect(state.switchProject).not.toHaveBeenCalled()
    await card.find('console-table-action-stub[label="project.delete"]').trigger('click')
    await flushPromises()
    expect(ElMessageBox.confirm).toHaveBeenCalled()
    expect(state.switchProject).not.toHaveBeenCalled()
    expect(card.text()).not.toContain('创建于')
    wrapper.unmount()
  })

  it('加入的项目也支持整行进入，当前项目与切换过程中不会重复进入', async () => {
    const project = { id: 'joined', name: '团队项目', myRole: 'VIEWER', region: 'sh-1' }
    vi.mocked(fetchProjects).mockResolvedValue([project])
    const wrapper = await mountPage()
    await flushPromises()
    const state = (wrapper.vm as any).$.setupState
    await wrapper.find('.el-table__row').findAll('.cell')[1].trigger('click')
    expect(state.switchProject).toHaveBeenCalledExactlyOnceWith(project)
    state.switchProject.mockClear()
    state.switchingId = 'switching'
    await wrapper.find('.el-table__row').findAll('.cell')[1].trigger('click')
    expect(state.switchProject).not.toHaveBeenCalled()
    state.switchingId = ''
    useUserStore().setUserInfo({ ...useUserStore().info, currentProjectId: 'joined' } as never)
    await wrapper.find('.el-table__row').findAll('.cell')[1].trigger('click')
    expect(state.switchProject).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('删除当前项目前切出作用域，避免用已失效的旧代次令牌切项目', async () => {
    vi.stubGlobal('ElMessage', { success: vi.fn() })
    vi.stubGlobal('ElMessageBox', { confirm: vi.fn().mockResolvedValue('confirm') })
    vi.mocked(fetchSwitchProject).mockResolvedValue({ accessToken: 'projectless-token' } as never)
    vi.mocked(fetchDeleteProject).mockResolvedValue(undefined)
    const wrapper = await mountPage()
    vi.mocked(fetchDeleteProject).mockImplementation(async () => {
      expect(useUserStore().info.currentProjectId).toBe('')
    })
    const state = (wrapper.vm as any).$?.setupState
    await state.confirmDelete({ id: 'proj-1', name: '当前项目', myRole: 'OWNER' })
    expect(fetchSwitchProject).toHaveBeenCalledWith(null)
    expect(vi.mocked(fetchSwitchProject).mock.invocationCallOrder[0]).toBeLessThan(
      vi.mocked(fetchDeleteProject).mock.invocationCallOrder[0]!
    )
    wrapper.unmount()
  })

  it('校验未决期间连续点击两次：校验与 API 各只调用一次，按钮进入禁用', async () => {
    const wrapper = await mountPage(true)
    await flushPromises()
    ;(wrapper.vm as any).$.setupState.form.name = '新项目'
    const submitButton = wrapper.findAll('button').find((b) => b.text() === '创建项目')
    expect(submitButton).toBeDefined()

    // 第一次点击：submitting 在 await validate 前同步置位，validate 进入未决
    await wrapper.find('form').trigger('submit')
    // 第二次点击：重入守卫在 validate 未决期间就应拦截
    await wrapper.find('form').trigger('submit')

    // 校验只启动一次；校验未决时 API 尚未调用、按钮处于禁用/加载态
    expect(validateSpy).toHaveBeenCalledTimes(1)
    expect(fetchCreateProject).not.toHaveBeenCalled()
    expect(submitButton!.attributes('disabled')).toBeDefined()

    // 解析校验后，API 只调用一次
    expect(resolveValidate).not.toBeNull()
    resolveValidate!(true)
    await flushPromises()
    expect(fetchCreateProject).toHaveBeenCalledExactlyOnceWith({
      name: '新项目',
      region: 'sh-1',
      description: undefined
    })
    expect(wrapper.vm.$route.path).toBe('/project/list')
    wrapper.unmount()
  })

  it('创建页可输入可选多行描述，限制 1000 字并将描述提交给接口', async () => {
    const wrapper = await mountPage(true)
    await flushPromises()
    const description = wrapper.find('textarea')
    expect(description.attributes('maxlength')).toBe('1000')
    expect(description.attributes('rows')).toBe('4')
    await wrapper.find('input').setValue('描述项目')
    await description.setValue('  设备接入试验\n告警验证  ')
    await wrapper.find('form').trigger('submit')
    resolveValidate!(true)
    await flushPromises()
    expect(fetchCreateProject).toHaveBeenCalledExactlyOnceWith({
      name: '描述项目',
      region: 'sh-1',
      description: '设备接入试验\n告警验证'
    })
    expect(wrapper.vm.$route.path).toBe('/project/list')
    wrapper.unmount()
  })
})
