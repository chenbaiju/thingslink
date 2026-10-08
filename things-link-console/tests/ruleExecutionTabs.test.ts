import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, shallowMount } from '@vue/test-utils'
import { reactive } from 'vue'
import Page from '@/views/rule/executions/index.vue'
import * as api from '@/api/rule-execution'

const mocks = vi.hoisted(() => ({
  user: { info: { currentProjectId: '' } },
  route: { query: {} as Record<string, string> }
}))
vi.mock('vue-router', async () => ({
  ...(await vi.importActual<typeof import('vue-router')>('vue-router')),
  useRoute: () => mocks.route
}))
vi.mock('@/store/modules/user', () => ({ useUserStore: () => mocks.user }))
vi.mock('@/api/rule-execution', () => ({
  fetchSceneExecutions: vi.fn(),
  fetchSceneExecutionSceneOptions: vi.fn(),
  fetchRuleExecutionRuleOptions: vi.fn(),
  fetchRuleExecutions: vi.fn(),
  fetchRuleExecutionAttempts: vi.fn(),
  fetchSceneExecutionDetail: vi.fn()
}))

function mountPage() {
  return shallowMount(Page, {
    global: {
      renderStubDefaultSlot: true,
      stubs: {
        ElCard: { template: '<section><slot /></section>' },
        ElTabs: {
          name: 'ElTabs',
          props: ['modelValue'],
          emits: ['update:modelValue'],
          template: '<div><slot /></div>'
        },
        ElTabPane: true,
        ElTable: { name: 'ElTable', props: ['data'], template: '<div />' },
        ElTableColumn: true,
        ElDrawer: true,
        ElTimeline: true,
        ElEmpty: true,
        AutomationExecutions: true
      },
      directives: { loading: () => {} }
    }
  })
}

beforeEach(() => {
  vi.resetAllMocks()
  mocks.route = reactive({ query: {} })
  mocks.user = reactive({ info: { currentProjectId: 'owned-project' } })
  vi.mocked(api.fetchSceneExecutions).mockResolvedValue({ items: [], hasMore: false })
  vi.mocked(api.fetchSceneExecutionSceneOptions).mockResolvedValue([])
  vi.mocked(api.fetchRuleExecutionRuleOptions).mockResolvedValue([])
  vi.mocked(api.fetchRuleExecutions).mockResolvedValue({
    items: [{ ruleId: 'owned-rule', ruleName: '已执行规则', status: 'SUCCESS', attemptCount: 1 }],
    hasMore: false
  })
})

describe('执行记录切换页签的读取合同', () => {
  it('首次进入上行规则页签自动读取真实接口合同的数据，无需额外点击查询', async () => {
    const page = mountPage()
    try {
      await flushPromises()
      expect(api.fetchSceneExecutions).toHaveBeenCalledTimes(1)
      expect(api.fetchRuleExecutions).not.toHaveBeenCalled()
      page.findComponent({ name: 'ElTabs' }).vm.$emit('update:modelValue', 'rule')
      await flushPromises()
      expect(api.fetchRuleExecutions).toHaveBeenCalledExactlyOnceWith(
        'owned-project',
        expect.objectContaining({ ruleId: undefined, status: undefined, cursor: undefined })
      )
      expect(page.findComponent({ name: 'ElTable' }).props('data')).toEqual([
        expect.objectContaining({ ruleName: '已执行规则', status: 'SUCCESS', attemptCount: 1 })
      ])
    } finally {
      page.unmount()
    }
  })

  it('返回场景页签刷新场景；再次进入规则页签刷新规则，不重复读取另一个列表', async () => {
    const page = mountPage()
    try {
      await flushPromises()
      for (const tab of ['rule', 'scene', 'rule']) {
        page.findComponent({ name: 'ElTabs' }).vm.$emit('update:modelValue', tab)
        await flushPromises()
      }
      expect(api.fetchSceneExecutions).toHaveBeenCalledTimes(2)
      expect(api.fetchRuleExecutions).toHaveBeenCalledTimes(2)
      expect(api.fetchRuleExecutionRuleOptions).toHaveBeenCalledTimes(1)
    } finally {
      page.unmount()
    }
  })

  it('未选择项目时切换页签不发起无范围查询', async () => {
    mocks.user.info.currentProjectId = ''
    const page = mountPage()
    try {
      await flushPromises()
      page.findComponent({ name: 'ElTabs' }).vm.$emit('update:modelValue', 'rule')
      await flushPromises()
      expect(api.fetchSceneExecutions).not.toHaveBeenCalled()
      expect(api.fetchRuleExecutions).not.toHaveBeenCalled()
      expect(api.fetchRuleExecutionRuleOptions).not.toHaveBeenCalled()
    } finally {
      page.unmount()
    }
  })
})

it.each(['rule', 'automation', 'scene'])(
  '关联入口定位 %s 执行记录，不混读其他来源',
  async (source) => {
    mocks.route.query.source = source
    const page = mountPage()
    await flushPromises()
    expect((page.vm as any).$.setupState.activeTab).toBe(source)
    expect(api.fetchSceneExecutions).toHaveBeenCalledTimes(source === 'scene' ? 1 : 0)
    expect(api.fetchRuleExecutions).toHaveBeenCalledTimes(source === 'rule' ? 1 : 0)
    page.unmount()
  }
)
