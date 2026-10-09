import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { reactive } from 'vue'
import Panel from '@/views/project/end-users/EndUserDashboardGrants.vue'
import { fetchUserDashboardGrants } from '@/api/dashboard-grants'
import { fetchDashboards } from '@/api/dashboard'
const state = vi.hoisted(() => ({ user: {} as any }))
vi.mock('@/store/modules/user', () => ({ useUserStore: () => state.user }))
vi.mock('@/api/dashboard-grants', () => ({ fetchUserDashboardGrants: vi.fn() }))
vi.mock('@/api/dashboard', () => ({ fetchDashboards: vi.fn() }))
const projectId = '11111111-1111-4111-8111-111111111111',
  dashboardId = '22222222-2222-4222-8222-222222222222',
  appUserId = '33333333-3333-4333-8333-333333333333',
  otherId = '44444444-4444-4444-8444-444444444444',
  time = '2026-10-01T00:00:00Z'
const account = {
  id: appUserId,
  username: 'alice',
  displayName: null,
  status: 'ACTIVE',
  role: 'OBSERVER',
  roleStatus: 'ACTIVE',
  assignedAt: time
}
const fact = {
  appUserId,
  dashboardId,
  permission: 'READ' as const,
  status: 'ACTIVE' as const,
  revision: '9007199254740993',
  createdAt: time,
  updatedAt: time,
  revokedAt: null
}
let panel: VueWrapper
function render(allowWrite = true) {
  panel = mount(Panel, {
    props: { projectId, account, allowWrite },
    global: {
      stubs: {
        ElDivider: { template: '<div role="separator"><slot /></div>' },
        ElButton: {
          props: ['disabled'],
          template: '<button :disabled="disabled"><slot/></button>'
        },
        ElAlert: { props: ['title'], template: '<div>{{ title }}<slot /></div>' },
        DesignerGrants: {
          props: ['dashboardId', 'fixedUser', 'writable'],
          template:
            '<div data-testid="shared-grant" :data-user="fixedUser.id" :data-write="writable">{{dashboardId}}</div>'
        }
      }
    }
  })
}
function button(name: string) {
  return panel.findAll('button').find((b) => b.text() === name)!
}
beforeEach(() => {
  vi.resetAllMocks()
  state.user = reactive({ info: { userId: 'u', tenantId: 't', buttons: ['enduser:manage'] } })
  vi.mocked(fetchUserDashboardGrants).mockResolvedValue({
    items: [fact],
    hasMore: false,
    nextCursor: null
  })
  vi.mocked(fetchDashboards).mockResolvedValue({
    items: [{ id: dashboardId, managementName: '可选看板' }],
    hasMore: false,
    nextCursor: null
  })
})
afterEach(() => panel?.unmount())
it('展示授权事实和无精度丢失的修订，固定用户复用单看板组件', async () => {
  render()
  await flushPromises()
  expect(panel.text()).toContain('READ / ACTIVE · 修订 9007199254740993')
  await button('查看此看板授权').trigger('click')
  expect(panel.get('[data-testid="shared-grant"]').attributes('data-user')).toBe(appUserId)
  expect(panel.get('[data-testid="shared-grant"]').text()).toBe(dashboardId)
})
it('OPERATOR/VIEWER没有授权目录读取权限或入口', async () => {
  state.user.info.buttons = []
  render()
  await flushPromises()
  expect(fetchUserDashboardGrants).not.toHaveBeenCalled()
  expect(fetchDashboards).not.toHaveBeenCalled()
  expect(panel.text()).toBe('')
})
it('ARCHIVED与停用历史仍读，复用组件仅允许读取', async () => {
  vi.mocked(fetchUserDashboardGrants).mockResolvedValue({
    items: [{ ...fact, status: 'REVOKED', revokedAt: time }],
    hasMore: false
  })
  render(false)
  await flushPromises()
  await button('查看此看板授权').trigger('click')
  expect(panel.text()).toContain('REVOKED')
  expect(panel.get('[data-testid="shared-grant"]').attributes('data-write')).toBe('false')
})
it('空目录与读取失败不同，错误响应不展示成无授权', async () => {
  vi.mocked(fetchUserDashboardGrants).mockResolvedValue({ items: [], hasMore: false })
  render()
  await flushPromises()
  expect(panel.text()).toContain('当前页暂无授权记录')
  vi.mocked(fetchUserDashboardGrants).mockRejectedValueOnce(new Error('failed'))
  await button('刷新授权目录').trigger('click')
  await flushPromises()
  expect(panel.text()).toContain('授权目录读取失败')
  expect(panel.text()).not.toContain('当前页暂无授权记录')
})
it.each([
  { ...fact, appUserId: otherId },
  { ...fact, permission: 'WRITE' },
  { ...fact, revision: 2 },
  { ...fact, dashboardId: 'invalid' },
  { ...fact, tenantId: 'private' }
])('错误身份或合同字段拒绝展示：%j', async (row) => {
  vi.mocked(fetchUserDashboardGrants).mockResolvedValue({ items: [row as any], hasMore: false })
  render()
  await flushPromises()
  expect(panel.text()).toContain('授权目录读取失败')
  expect(panel.text()).not.toContain('修订')
})
it('20项游标前后页且刷新回第一页，不重复循环游标', async () => {
  vi.mocked(fetchUserDashboardGrants).mockResolvedValueOnce({
    items: [fact],
    hasMore: true,
    nextCursor: 'opaque'
  })
  render()
  await flushPromises()
  vi.mocked(fetchUserDashboardGrants).mockResolvedValueOnce({
    items: [{ ...fact, dashboardId: otherId }],
    hasMore: false
  })
  await button('下一页授权').trigger('click')
  await flushPromises()
  expect(fetchUserDashboardGrants).toHaveBeenLastCalledWith(projectId, appUserId, 'opaque')
  await button('上一页授权').trigger('click')
  await flushPromises()
  expect(fetchUserDashboardGrants).toHaveBeenLastCalledWith(projectId, appUserId, undefined)
  await button('刷新授权目录').trigger('click')
  await flushPromises()
  expect(panel.text()).toContain('第1页')
})
it('换用户清除旧记录、目标及旧延迟响应', async () => {
  render()
  await flushPromises()
  await button('选择此看板').trigger('click')
  let done!: (page: any) => void
  vi.mocked(fetchUserDashboardGrants).mockReturnValueOnce(
    new Promise((resolve) => {
      done = resolve
    })
  )
  await button('刷新授权目录').trigger('click')
  vi.mocked(fetchUserDashboardGrants).mockResolvedValueOnce({ items: [], hasMore: false })
  await panel.setProps({ account: { ...account, id: otherId, username: 'bob' } })
  await flushPromises()
  done({ items: [fact], hasMore: false })
  await flushPromises()
  expect(panel.find('[data-testid="shared-grant"]').exists()).toBe(false)
  expect(panel.text()).not.toContain('修订')
  expect(panel.text()).toContain('bob的看板')
})
it('权限收回清除旧目录和目标并停止读取', async () => {
  render()
  await flushPromises()
  await button('查看此看板授权').trigger('click')
  state.user.info.buttons = []
  await flushPromises()
  expect(panel.text()).toBe('')
  expect(fetchUserDashboardGrants).toHaveBeenCalledTimes(1)
})
it('可选看板失败保留明确错误，重复循环游标拒绝', async () => {
  vi.mocked(fetchDashboards).mockResolvedValueOnce({
    items: [{ id: dashboardId, managementName: '看板' }],
    hasMore: true,
    nextCursor: 'opaque'
  })
  render()
  await flushPromises()
  vi.mocked(fetchDashboards).mockResolvedValueOnce({
    items: [{ id: dashboardId, managementName: '看板' }],
    hasMore: true,
    nextCursor: 'opaque'
  })
  await button('下一页看板').trigger('click')
  await flushPromises()
  expect(panel.text()).toContain('可选看板读取失败')
  expect(panel.text()).not.toContain('看板（')
})

it('同一用户角色变化更新固定用户而不销毁恢复意图所在组件', async () => {
  render()
  await flushPromises()
  await button('查看此看板授权').trigger('click')
  await panel.setProps({ account: { ...account, roleStatus: 'DISABLED' } })
  await flushPromises()
  expect(panel.get('[data-testid="shared-grant"]').attributes('data-user')).toBe(appUserId)
  expect(fetchUserDashboardGrants).toHaveBeenCalledTimes(1)
})
