import { expect, it, vi } from 'vitest'
import { flushPromises, shallowMount } from '@vue/test-utils'
import Page from '@/views/rule/components/AutomationExecutions.vue'
import ConsoleFilterBar from '@/components/ConsoleFilterBar.vue'
import { listAutomationExecutions } from '@/api/automation-management'

vi.mock('@/store/modules/user', () => ({
  useUserStore: () => ({
    isLogin: true,
    info: { currentProjectId: 'project', userId: 'user', buttons: ['rule:read'] }
  })
}))
vi.mock('@/api/automation-management', () => ({
  listAutomationExecutions: vi.fn(),
  getAutomationExecution: vi.fn()
}))

it('重置清空筛选及游标，重新查询全部记录，迟到的旧筛选结果不能覆盖重置结果', async () => {
  let finishOldQuery!: (value: { items: { id: string }[] }) => void
  vi.mocked(listAutomationExecutions)
    .mockResolvedValueOnce({ items: [{ id: 'initial' }], nextCursor: 'old-cursor' })
    .mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          finishOldQuery = resolve
        })
    )
    .mockResolvedValueOnce({ items: [{ id: 'reset-result' }] })
  const page = shallowMount(Page, {
    global: {
      renderStubDefaultSlot: true,
      stubs: {
        ElCard: { template: '<section><slot /></section>' },
        ElTable: true,
        ElTableColumn: true,
        ElDialog: true
      },
      directives: { loading: () => {} }
    }
  })
  try {
    await flushPromises()
    const state = (page.vm as any).$.setupState
    state.automationId = 'filtered-automation'
    state.status = 'FAILED'
    state.range = [new Date('2026-10-01T00:00:00Z'), new Date('2026-10-02T00:00:00Z')]
    page.findComponent(ConsoleFilterBar).vm.$emit('search')
    await flushPromises()
    expect(listAutomationExecutions).toHaveBeenLastCalledWith('project', {
      automationId: 'filtered-automation',
      status: 'FAILED',
      from: '2026-10-01T00:00:00.000Z',
      to: '2026-10-02T00:00:00.000Z',
      cursor: undefined
    })
    page.findComponent(ConsoleFilterBar).vm.$emit('reset')
    await flushPromises()
    expect(listAutomationExecutions).toHaveBeenLastCalledWith('project', {
      automationId: undefined,
      status: undefined,
      from: undefined,
      to: undefined,
      cursor: undefined
    })
    expect(state.automationId).toBe('')
    expect(state.status).toBe('')
    expect(state.range).toBeUndefined()
    expect(state.next).toBeUndefined()
    finishOldQuery({ items: [{ id: 'stale-filter-result' }] })
    await flushPromises()
    expect(state.items).toEqual([{ id: 'reset-result' }])
  } finally {
    page.unmount()
  }
})
