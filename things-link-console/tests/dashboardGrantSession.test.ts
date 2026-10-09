import { mount, flushPromises } from '@vue/test-utils'
import { beforeEach, afterEach, describe, expect, it, vi } from 'vitest'
const mocks = vi.hoisted(() => ({
  users: vi.fn(),
  detail: vi.fn(),
  write: vi.fn(),
  confirm: vi.fn()
}))
vi.mock('element-plus', () => ({ ElMessageBox: { confirm: mocks.confirm } }))
vi.mock('../src/store/modules/user', () => ({
  useUserStore: () => ({ info: { userId: 'manager' } })
}))
vi.mock('../src/utils/http/identity-scope', () => ({ currentIdentityEpoch: () => 1 }))
vi.mock('../src/api/dashboard-grants', () => ({
  fetchGrantUsers: mocks.users,
  fetchDashboardGrant: mocks.detail,
  writeDashboardGrantIntent: mocks.write
}))
import DesignerGrants from '../src/views/dashboard/designer/components/DesignerGrants.vue'
const projectId = '11111111-1111-4111-8111-111111111111',
  dashboardId = '22222222-2222-4222-8222-222222222222',
  appUserId = '33333333-3333-4333-8333-333333333333',
  otherId = '44444444-4444-4444-8444-444444444444',
  time = '2026-09-08T00:00:00Z'
const user = {
  id: appUserId,
  username: 'alice',
  displayName: '用户',
  status: 'ACTIVE',
  role: 'OBSERVER',
  roleStatus: 'ACTIVE',
  assignedAt: time
}
const fact = {
  appUserId,
  dashboardId,
  permission: 'READ',
  status: 'ACTIVE',
  revision: '1',
  createdAt: time,
  updatedAt: time,
  revokedAt: null
}
beforeEach(() => {
  vi.clearAllMocks()
  vi.spyOn(document, 'hidden', 'get').mockReturnValue(false)
  mocks.users.mockResolvedValue({ items: [user], hasMore: false, nextCursor: null })
  mocks.detail.mockResolvedValue(fact)
  mocks.write.mockResolvedValue(fact)
  mocks.confirm.mockResolvedValue(undefined)
})
afterEach(() => vi.restoreAllMocks())
async function fixture(canManage = true) {
  const wrapper = mount(DesignerGrants, {
    props: { projectId, dashboardId, available: true, canManage },
    global: {
      stubs: {
        ElDivider: { template: '<div><slot /></div>' },
        ElButton: {
          props: ['disabled'],
          template: '<button :disabled="disabled"><slot /></button>'
        },
        ElDialog: {
          props: ['modelValue'],
          template: '<div v-if="modelValue"><slot /><slot name="footer" /></div>'
        },
        ElAlert: { props: ['title'], template: '<p>{{title}}<slot /></p>' }
      }
    }
  })
  return wrapper
}
async function open(wrapper: Awaited<ReturnType<typeof fixture>>) {
  await wrapper.get('[data-testid="grants-open"]').trigger('click')
  await flushPromises()
}
async function select(wrapper: Awaited<ReturnType<typeof fixture>>) {
  await wrapper
    .findAll('button')
    .find((button) => button.text() === '选择用户 alice')!
    .trigger('click')
  await flushPromises()
}
describe('用户READ授权独立弹窗', () => {
  it('按钮打开才查询，不把60025显示为确定未授权；确认首次授予0并显示真实记录', async () => {
    const wrapper = await fixture()
    expect(mocks.users).not.toHaveBeenCalled()
    await open(wrapper)
    mocks.detail.mockRejectedValueOnce({ code: 60025, status: 404 })
    await select(wrapper)
    expect(wrapper.text()).toContain('可能尚无记录，也可能目标不可见')
    expect(wrapper.get('[data-testid="grants-revoke"]').attributes('disabled')).toBeDefined()
    await wrapper.get('[data-testid="grants-grant"]').trigger('click')
    await flushPromises()
    expect(mocks.confirm).toHaveBeenCalledWith(
      expect.any(String),
      '确认授予读取权限',
      expect.objectContaining({ confirmButtonText: '授予读取权限' })
    )
    expect(mocks.write.mock.calls[0]?.[0].body).toEqual({ expectedRevision: '0', status: 'ACTIVE' })
    expect(wrapper.get('[data-testid="grants-status"]').attributes('data-status')).toBe('ACTIVE')
    wrapper.unmount()
  })
  it('撤销先确认，状态非有效的用户只能读取历史', async () => {
    const wrapper = await fixture()
    await open(wrapper)
    await select(wrapper)
    const revoked = { ...fact, status: 'REVOKED', revision: '2', revokedAt: time }
    mocks.write.mockResolvedValueOnce(revoked)
    mocks.detail.mockResolvedValueOnce(revoked)
    await wrapper.get('[data-testid="grants-revoke"]').trigger('click')
    await flushPromises()
    expect(mocks.confirm).toHaveBeenCalledWith(
      expect.any(String),
      '确认撤销读取权限',
      expect.objectContaining({ confirmButtonText: '撤销读取权限' })
    )
    expect(wrapper.get('[data-testid="grants-status"]').attributes('data-revision')).toBe('2')
    mocks.users.mockResolvedValueOnce({
      items: [{ ...user, status: 'SUSPENDED' }],
      hasMore: false,
      nextCursor: null
    })
    await wrapper.get('[data-testid="grants-refresh-users"]').trigger('click')
    await flushPromises()
    expect(wrapper.get('[data-testid="grants-grant"]').attributes('disabled')).toBeDefined()
    expect(wrapper.get('[data-testid="grants-revoke"]').attributes('disabled')).toBeDefined()
    wrapper.unmount()
  })
  it('关闭重开保留未知意图，原用户不在目录页仍可按原ID恢复', async () => {
    const wrapper = await fixture()
    await open(wrapper)
    mocks.detail.mockRejectedValueOnce({ code: 60025, status: 404 })
    await select(wrapper)
    mocks.write.mockRejectedValueOnce({ outcomeUnknown: true })
    await wrapper.get('[data-testid="grants-grant"]').trigger('click')
    await flushPromises()
    const intent = mocks.write.mock.calls[0]![0]
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '关闭')!
      .trigger('click')
    mocks.users.mockResolvedValueOnce({
      items: [{ ...user, id: otherId, username: 'bob' }],
      hasMore: false,
      nextCursor: null
    })
    await open(wrapper)
    expect(wrapper.find('[data-testid="grants-retry"]').exists()).toBe(true)
    await wrapper.get('[data-testid="grants-recover"]').trigger('click')
    await flushPromises()
    expect(mocks.detail).toHaveBeenLastCalledWith(projectId, appUserId, dashboardId)
    mocks.write.mockRejectedValueOnce({ code: 10014, status: 409 })
    await wrapper.get('[data-testid="grants-retry"]').trigger('click')
    await flushPromises()
    expect(mocks.write.mock.calls[1]?.[0]).toEqual(intent)
    expect(wrapper.find('[data-testid="grants-retry"]').exists()).toBe(false)
    wrapper.unmount()
  })
  it('确认期间换看板不写新上下文，取消也不写', async () => {
    const wrapper = await fixture()
    await open(wrapper)
    await select(wrapper)
    mocks.confirm.mockRejectedValueOnce('cancel')
    await wrapper.get('[data-testid="grants-revoke"]').trigger('click')
    await flushPromises()
    expect(mocks.write).not.toHaveBeenCalled()
    let resolve!: () => void
    mocks.confirm.mockImplementationOnce(
      () =>
        new Promise<void>((done) => {
          resolve = done
        })
    )
    await wrapper.get('[data-testid="grants-revoke"]').trigger('click')
    await wrapper.setProps({ dashboardId: otherId })
    resolve()
    await flushPromises()
    expect(mocks.write).not.toHaveBeenCalled()
    wrapper.unmount()
  })
  it('无管理权限不显示入口，读取失权清历史不显示空目录成功', async () => {
    const readonly = await fixture(false)
    expect(readonly.find('[data-testid="grants-open"]').exists()).toBe(false)
    expect(mocks.users).not.toHaveBeenCalled()
    readonly.unmount()
    const wrapper = await fixture()
    await open(wrapper)
    await select(wrapper)
    mocks.users.mockRejectedValueOnce({ code: 30001 })
    await wrapper.get('[data-testid="grants-refresh-users"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-testid="grants-status"]').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('当前页没有项目用户')
    expect(wrapper.text()).toContain('权限已变化')
    wrapper.unmount()
  })
})

it('暂时失去管理权限隐藏弹窗但保留原未知意图，恢复后不换键', async () => {
  const wrapper = await fixture()
  await open(wrapper)
  mocks.detail.mockRejectedValueOnce({ code: 60025, status: 404 })
  await select(wrapper)
  mocks.write.mockRejectedValueOnce({ outcomeUnknown: true })
  await wrapper.get('[data-testid="grants-grant"]').trigger('click')
  await flushPromises()
  const intent = mocks.write.mock.calls[0]![0]
  await wrapper.setProps({ canManage: false })
  await flushPromises()
  expect(wrapper.find('[data-testid="grants-dialog"]').exists()).toBe(false)
  mocks.write.mockRejectedValueOnce({ code: 10014, status: 409 })
  await wrapper.setProps({ canManage: true })
  await flushPromises()
  await wrapper.get('[data-testid="grants-retry"]').trigger('click')
  await flushPromises()
  expect(mocks.write.mock.calls[1]?.[0]).toEqual(intent)
  wrapper.unmount()
})

it('固定用户只查询该用户看板，合法空显示名回退用户名，不扫描项目用户目录', async () => {
  const wrapper = await fixture()
  await wrapper.setProps({ fixedUser: { ...user, displayName: null } })
  await open(wrapper)
  expect(mocks.users).not.toHaveBeenCalled()
  expect(mocks.detail).toHaveBeenCalledExactlyOnceWith(projectId, appUserId, dashboardId)
  expect(wrapper.find('[data-testid="grants-refresh-users"]').exists()).toBe(false)
  expect(wrapper.text()).toContain('目标用户：alice（alice）')
  wrapper.unmount()
})
it('归档固定用户可读历史但禁止写入与未知原操作重试', async () => {
  const wrapper = await fixture()
  await wrapper.setProps({ fixedUser: user })
  await open(wrapper)
  mocks.write.mockRejectedValueOnce({ outcomeUnknown: true })
  await wrapper.get('[data-testid="grants-revoke"]').trigger('click')
  await flushPromises()
  await wrapper.setProps({ writable: false })
  expect(wrapper.get('[data-testid="grants-retry"]').attributes('disabled')).toBeDefined()
  await wrapper.get('[data-testid="grants-retry"]').trigger('click')
  expect(mocks.write).toHaveBeenCalledTimes(1)
  await wrapper.get('[data-testid="grants-recover"]').trigger('click')
  await flushPromises()
  expect(mocks.detail).toHaveBeenCalledTimes(2)
  wrapper.unmount()
})
it('确认等待期间项目变成只读不得完成旧写入', async () => {
  const wrapper = await fixture()
  await wrapper.setProps({ fixedUser: user })
  await open(wrapper)
  let resolve!: () => void
  mocks.confirm.mockReturnValueOnce(
    new Promise<void>((done) => {
      resolve = done
    })
  )
  await wrapper.get('[data-testid="grants-revoke"]').trigger('click')
  await wrapper.setProps({ writable: false })
  resolve()
  await flushPromises()
  expect(mocks.write).not.toHaveBeenCalled()
  expect(wrapper.get('[data-testid="grants-status"]').attributes('data-status')).toBe('ACTIVE')
  wrapper.unmount()
})
it('固定用户变化立即清除旧用户记录并查询新用户', async () => {
  const wrapper = await fixture()
  await wrapper.setProps({ fixedUser: user })
  await open(wrapper)
  mocks.detail.mockResolvedValueOnce({ ...fact, appUserId: otherId })
  await wrapper.setProps({ fixedUser: { ...user, id: otherId, username: 'bob' } })
  await flushPromises()
  expect(mocks.detail).toHaveBeenLastCalledWith(projectId, otherId, dashboardId)
  expect(wrapper.text()).toContain('目标用户：用户（bob）')
  expect(wrapper.text()).not.toContain('（alice）')
  wrapper.unmount()
})

it('同一固定用户停用后保留未知原意图但阻止重试，仍可读取原事实', async () => {
  const wrapper = await fixture()
  await wrapper.setProps({ fixedUser: user })
  await open(wrapper)
  mocks.write.mockRejectedValueOnce({ outcomeUnknown: true })
  await wrapper.get('[data-testid="grants-revoke"]').trigger('click')
  await flushPromises()
  await wrapper.setProps({ fixedUser: { ...user, roleStatus: 'DISABLED' } })
  await flushPromises()
  expect(wrapper.get('[data-testid="grants-retry"]').attributes('disabled')).toBeDefined()
  expect(wrapper.text()).toContain('原操作结果未知')
  await wrapper.get('[data-testid="grants-recover"]').trigger('click')
  await flushPromises()
  expect(mocks.detail).toHaveBeenLastCalledWith(projectId, appUserId, dashboardId)
  expect(mocks.write).toHaveBeenCalledTimes(1)
  wrapper.unmount()
})
