import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { reactive, nextTick } from 'vue'
import Panel from '@/views/device/components/DevicePersonalRecords.vue'
import {
  savePersonalRecord,
  listPersonalRecords,
  readPersonalRecord,
  deletePersonalRecord
} from '@/api/assistant-records'
const state = vi.hoisted(() => ({ user: {} as any }))
vi.mock('@/store/modules/user', () => ({ useUserStore: () => state.user }))
vi.mock('@/api/assistant-records', () => ({
  savePersonalRecord: vi.fn(),
  listPersonalRecords: vi.fn(),
  readPersonalRecord: vi.fn(),
  deletePersonalRecord: vi.fn(),
  generatePersonalFactReport: vi.fn()
}))
const row = {
  id: 'r',
  deviceId: 'd',
  modelVersionId: 'm',
  createdAt: '2026-10-04T00:00:00Z',
  expiresAt: '2026-11-03T00:00:00Z',
  contentSha256: 'a'.repeat(64)
}
const detail = {
  record: row,
  snapshot: {
    schemaVersion: 1,
    deviceId: 'd',
    modelVersionId: 'm',
    collectionStartedAt: row.createdAt,
    collectionFinishedAt: row.createdAt,
    device: { status: 'ONLINE' },
    alarmSummary: { state: 'NORMAL' },
    properties: [
      {
        key: 'n',
        value: 0,
        valueOmitted: false,
        availability: 'PRESENT',
        occurredAt: row.createdAt
      },
      {
        key: 'b',
        value: false,
        valueOmitted: false,
        availability: 'PRESENT',
        occurredAt: row.createdAt
      },
      {
        key: 't',
        value: null,
        valueOmitted: true,
        availability: 'PRESENT',
        occurredAt: row.createdAt
      }
    ]
  }
}
let panel: VueWrapper
const vm = () => (panel.vm as any).$?.setupState as any
function page() {
  panel = mount(Panel, {
    props: { projectId: 'p', deviceId: 'd', modelVersionId: 'm', propertyKeys: ['n'] },
    global: {
      stubs: {
        ElButton: { template: '<button><slot /></button>' },
        ElAlert: { props: ['title'], template: '<p>{{ title }}</p>' }
      }
    }
  })
}
beforeEach(() => {
  vi.resetAllMocks()
  state.user = reactive({
    isLogin: true,
    info: { userId: 'u', tenantId: 't', currentProjectId: 'p', roles: ['VIEWER'] }
  })
  vi.mocked(savePersonalRecord).mockResolvedValue(row)
  vi.mocked(listPersonalRecords).mockResolvedValue([row])
  vi.mocked(readPersonalRecord).mockResolvedValue(detail as any)
})
afterEach(() => panel?.unmount())
it.each(['OWNER', 'ADMIN', 'OPERATOR', 'VIEWER'])(
  '%s only acts manually and saves selectors rather than displayed facts',
  async (role) => {
    state.user.info.roles = [role]
    page()
    expect(listPersonalRecords).not.toHaveBeenCalled()
    expect(savePersonalRecord).not.toHaveBeenCalled()
    await vm().save()
    expect(savePersonalRecord).toHaveBeenCalledWith('p', 'd', 'm', ['n'], expect.any(AbortSignal))
    expect(vm().rows).toEqual([row])
    expect(panel.text()).toContain('历史事实不代表设备当前状态')
  }
)
it('shows safe zero false and omitted text from an explicit historical read', async () => {
  page()
  await vm().refresh()
  await vm().open('r')
  expect(panel.text()).toContain('n：0')
  expect(panel.text()).toContain('b：false')
  expect(panel.text()).toContain('值已省略')
  expect(readPersonalRecord).toHaveBeenCalledOnce()
})
it('suppresses old identity results after project change and clears old rows', async () => {
  page()
  await vm().refresh()
  let resolve: (x: any) => void = () => {}
  vi.mocked(readPersonalRecord).mockImplementation(() => new Promise((r) => (resolve = r)))
  const pending = vm().open('r')
  state.user.info.currentProjectId = 'other'
  await nextTick()
  resolve(detail)
  await pending
  expect(vm().detail).toBeUndefined()
  expect(vm().rows).toEqual([])
})
it('discards late saves from another account and aborts the old signal', async () => {
  page()
  let resolve: (x: any) => void = () => {}
  vi.mocked(savePersonalRecord).mockImplementation(() => new Promise((r) => (resolve = r)))
  const pending = vm().save()
  const signal = vi.mocked(savePersonalRecord).mock.calls[0][4]
  state.user.info.userId = 'other'
  await nextTick()
  expect(signal.aborted).toBe(true)
  resolve(row)
  await pending
  expect(vm().rows).toEqual([])
  expect(vm().notice).toBe('')
})
it('does not automatically repeat ambiguous saves and prevents overlapping operations', async () => {
  page()
  let reject: (x: any) => void = () => {}
  vi.mocked(savePersonalRecord).mockImplementation(() => new Promise((_r, j) => (reject = j)))
  const pending = vm().save()
  await vm().save()
  await vm().refresh()
  expect(savePersonalRecord).toHaveBeenCalledOnce()
  expect(listPersonalRecords).not.toHaveBeenCalled()
  reject(new Error('unknown outcome'))
  await pending
  await flushPromises()
  expect(panel.text()).toContain('不会自动重发')
  expect(savePersonalRecord).toHaveBeenCalledOnce()
})
it('deletes only a selected own row and drops its detail', async () => {
  page()
  await vm().refresh()
  await vm().open('r')
  await vm().remove('r')
  expect(deletePersonalRecord).toHaveBeenCalledWith('p', 'r', expect.any(AbortSignal))
  expect(vm().rows).toEqual([])
  expect(vm().detail).toBeUndefined()
  await vm().remove('unlisted')
  expect(deletePersonalRecord).toHaveBeenCalledOnce()
})
it('rejects a detail bound to another device and does not show it', async () => {
  page()
  await vm().refresh()
  vi.mocked(readPersonalRecord).mockResolvedValue({
    ...detail,
    snapshot: { ...detail.snapshot, deviceId: 'foreign' }
  } as any)
  await vm().open('r')
  expect(vm().detail).toBeUndefined()
  expect(panel.text()).toContain('历史事实不可读')
})
it('only lists this device and invalid selectors disable saving', async () => {
  page()
  vi.mocked(listPersonalRecords).mockResolvedValue([
    row,
    { ...row, id: 'other', deviceId: 'other' }
  ])
  await vm().refresh()
  expect(vm().rows).toEqual([row])
  await panel.setProps({ propertyKeys: ['n', 'n'] })
  await vm().save()
  expect(savePersonalRecord).not.toHaveBeenCalled()
})
