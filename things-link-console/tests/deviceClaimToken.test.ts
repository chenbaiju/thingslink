import { beforeEach, afterEach, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { reactive } from 'vue'
import Panel from '@/views/device/components/DeviceClaimToken.vue'
import { issueDeviceClaimToken } from '@/api/end-users'
import { fetchProjects } from '@/api/project'
const state = vi.hoisted(() => ({ user: {} as any, confirm: vi.fn() }))
vi.mock('@/store/modules/user', () => ({ useUserStore: () => state.user }))
vi.mock('element-plus', () => ({ ElMessageBox: { confirm: state.confirm } }))
vi.mock('@/api/project', () => ({ fetchProjects: vi.fn() }))
vi.mock('@/api/end-users', () => ({ issueDeviceClaimToken: vi.fn() }))
let panel: VueWrapper
function render(primaryKnown = false) {
  panel = mount(Panel, {
    props: { projectId: 'p', deviceId: 'd', primaryKnown },
    global: {
      stubs: {
        ElButton: {
          props: ['disabled'],
          template: '<button :disabled="disabled"><slot/></button>'
        },
        ElAlert: { props: ['title'], template: '<p>{{title}}<slot /></p>' },
        ElInput: {
          props: ['modelValue', 'type', 'readonly'],
          template: '<input :value="modelValue" :type="type" :readonly="readonly" />'
        }
      }
    }
  })
}
const issue = () => panel.findAll('button').find((b) => b.text() === '签发认领令牌')!
const secret = () => panel.find('[aria-label="一次性认领令牌"]')
beforeEach(() => {
  vi.resetAllMocks()
  state.user = reactive({ info: { userId: 'u', tenantId: 't', buttons: ['enduser:manage'] } })
  state.confirm.mockResolvedValue('confirm')
  vi.mocked(fetchProjects).mockResolvedValue([{ id: 'p', status: 'ACTIVE' }])
  vi.mocked(issueDeviceClaimToken).mockResolvedValue({
    token: 'synthetic-secret',
    expiresAt: new Date(Date.now() + 60_000).toISOString()
  })
})
afterEach(() => {
  panel?.unmount()
  vi.useRealTimers()
})
it('does not offer issuer capabilities to readonly members', async () => {
  state.user.info.buttons = []
  render()
  await flushPromises()
  expect(panel.find('button').exists()).toBe(false)
  expect(fetchProjects).not.toHaveBeenCalled()
})
it.each(['ARCHIVED', 'unknown'])('keeps %s project issuance disabled', async (status) => {
  vi.mocked(fetchProjects).mockResolvedValue(status === 'unknown' ? [] : [{ id: 'p', status }])
  render()
  await flushPromises()
  expect(issue().attributes('disabled')).toBeDefined()
  await issue().trigger('click')
  expect(issueDeviceClaimToken).not.toHaveBeenCalled()
})
it('known PRIMARY blocks issuance and never relies on the current page to authorize', async () => {
  render(true)
  await flushPromises()
  expect(issue().attributes('disabled')).toBeDefined()
  expect(panel.text()).toContain('已有主控')
  expect(issueDeviceClaimToken).not.toHaveBeenCalled()
})
it('cancellation sends no request', async () => {
  state.confirm.mockRejectedValue('cancel')
  render()
  await flushPromises()
  await issue().trigger('click')
  await flushPromises()
  expect(issueDeviceClaimToken).not.toHaveBeenCalled()
})
it('issues only after confirmation and closes the one-time display without storing or revoking it', async () => {
  const storage = vi.spyOn(Storage.prototype, 'setItem')
  render()
  await flushPromises()
  expect(issueDeviceClaimToken).not.toHaveBeenCalled()
  await issue().trigger('click')
  await flushPromises()
  expect(issueDeviceClaimToken).toHaveBeenCalledExactlyOnceWith('p', 'd')
  expect((secret().element as HTMLInputElement).value).toBe('synthetic-secret')
  expect(secret().attributes('type')).toBe('password')
  expect(storage).not.toHaveBeenCalled()
  await panel
    .findAll('button')
    .find((b) => b.text() === '关闭令牌展示')!
    .trigger('click')
  expect(secret().exists()).toBe(false)
  expect(issueDeviceClaimToken).toHaveBeenCalledTimes(1)
})
it('blocks repeated signing while confirmation is pending', async () => {
  let done!: (value: string) => void
  state.confirm.mockReturnValue(
    new Promise((resolve) => {
      done = resolve
    })
  )
  render()
  await flushPromises()
  await issue().trigger('click')
  await issue().trigger('click')
  expect(state.confirm).toHaveBeenCalledTimes(1)
  done('confirm')
  await flushPromises()
  expect(issueDeviceClaimToken).toHaveBeenCalledTimes(1)
})
it('stale signing responses cannot expose an old device capability', async () => {
  let done!: (value: any) => void
  vi.mocked(issueDeviceClaimToken).mockReturnValue(
    new Promise((resolve) => {
      done = resolve
    })
  )
  render()
  await flushPromises()
  await issue().trigger('click')
  await flushPromises()
  await panel.setProps({ deviceId: 'q' })
  done({ token: 'old-secret', expiresAt: new Date(Date.now() + 60_000).toISOString() })
  await flushPromises()
  expect(secret().exists()).toBe(false)
})
it('unknown signing never automatically issues another token', async () => {
  vi.mocked(issueDeviceClaimToken).mockRejectedValue(new Error('lost'))
  render()
  await flushPromises()
  await issue().trigger('click')
  await flushPromises()
  expect(panel.text()).toContain('不会自动补签')
  expect(secret().exists()).toBe(false)
  expect(issueDeviceClaimToken).toHaveBeenCalledTimes(1)
})
it('automatically removes a secret at its returned expiry', async () => {
  vi.useFakeTimers()
  render()
  await flushPromises()
  await issue().trigger('click')
  await flushPromises()
  expect(secret().exists()).toBe(true)
  await vi.advanceTimersByTimeAsync(60_001)
  expect(secret().exists()).toBe(false)
})
it('expired or malformed capabilities are never displayed', async () => {
  vi.mocked(issueDeviceClaimToken).mockResolvedValue({
    token: 'expired',
    expiresAt: new Date(Date.now() - 1).toISOString()
  })
  render()
  await flushPromises()
  await issue().trigger('click')
  await flushPromises()
  expect(secret().exists()).toBe(false)
  expect(panel.text()).toContain('响应无效')
})
