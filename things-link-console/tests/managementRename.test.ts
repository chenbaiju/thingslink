import { mount, flushPromises } from '@vue/test-utils'
import { beforeEach, afterEach, describe, expect, it, vi } from 'vitest'
import { memoryStorage } from './commercialStorageFixture'
const auth = vi.hoisted(() => ({ epoch: 1, user: { accessToken: 'token' } }))
vi.mock('@/store/modules/user', () => ({ useUserStore: () => auth.user }))
vi.mock('@/utils/http/identity-scope', () => ({ currentIdentityEpoch: () => auth.epoch }))
import { renameManagementResource, validManagementName } from '@/api/management-rename'
import ManagementRenameDialog from '@/components/business/ManagementRenameDialog.vue'
import {
  recordRecentResource,
  renameRecentResource,
  readRecentResources
} from '@/utils/workbench-recent'
const fetcher = vi.fn()
const target = { id: 'id', managementName: '目录原名' }
const result = { id: 'id', managementName: '新名称', draftRevision: '3', publicationRevision: '2' }
const response = () => ({ ok: true, status: 200, json: async () => result })
function mountDialog() {
  return mount(ManagementRenameDialog, {
    props: {
      target,
      projectId: 'project',
      kind: 'applications',
      identity: 'user:1',
      allowed: true
    },
    global: {
      stubs: {
        ElDialog: {
          props: ['modelValue'],
          template: '<section v-if="modelValue"><slot /><slot name="footer" /></section>'
        },
        ElInput: {
          props: ['modelValue', 'disabled'],
          emits: ['update:modelValue'],
          template:
            '<input :value="modelValue" :disabled="disabled" @input="$emit(\'update:modelValue\', $event.target.value)" />'
        },
        ElButton: {
          props: ['disabled'],
          template: '<button :disabled="disabled"><slot /></button>'
        },
        ElAlert: {
          props: ['title', 'type'],
          template: '<p role="alert" :data-type="type">{{title}}<slot /></p>'
        }
      }
    }
  })
}
beforeEach(() => {
  auth.epoch = 1
  auth.user.accessToken = 'token'
  fetcher.mockReset()
  vi.stubEnv('VITE_API_URL', '')
  vi.stubGlobal('fetch', fetcher)
  vi.stubGlobal('localStorage', memoryStorage())
})
afterEach(() => {
  vi.unstubAllGlobals()
  vi.unstubAllEnvs()
})
describe('管理名称独立合同', () => {
  it('counts codepoints without trimming or interpreting markup', () => {
    expect(validManagementName('😀'.repeat(80))).toBe(true)
    expect(validManagementName('😀'.repeat(81))).toBe(false)
    expect(validManagementName('  名称 <b>  ')).toBe(true)
    for (const value of ['', ' \u00a0\u3000', 'a\n', 'a\u0085'])
      expect(validManagementName(value)).toBe(false)
  })
  it.each(['applications', 'dashboards'] as const)(
    'PATCH %s only contains managementName, never draft content/revisions',
    async (kind) => {
      fetcher.mockResolvedValue(response())
      expect(await renameManagementResource(kind, 'p', 'id', '新名称')).toEqual(result)
      expect(fetcher).toHaveBeenCalledTimes(1)
      const [url, config] = fetcher.mock.calls[0]
      expect(url).toContain(`/projects/p/${kind}/id`)
      expect(config.method).toBe('PATCH')
      expect(JSON.parse(config.body)).toEqual({ managementName: '新名称' })
    }
  )
  it.each([401, 403, 503])('never refreshes credentials or replays status %s', async (status) => {
    fetcher.mockResolvedValue({ ok: false, status })
    await expect(renameManagementResource('applications', 'p', 'id', '新名称')).rejects.toThrow()
    expect(fetcher).toHaveBeenCalledTimes(1)
  })
  it('rejects response from previous identity', async () => {
    fetcher.mockImplementation(async () => {
      auth.epoch++
      return response()
    })
    await expect(renameManagementResource('applications', 'p', 'id', '新名称')).rejects.toThrow(
      '身份'
    )
  })
  it('preserves input after failed request and sends only once', async () => {
    fetcher.mockRejectedValue(new TypeError('network'))
    const wrapper = mountDialog()
    await wrapper.find('input').setValue('新名称')
    await wrapper.findAll('button')[1].trigger('click')
    await flushPromises()
    expect(wrapper.find('input').element.value).toBe('新名称')
    expect(wrapper.find('[role="alert"][data-type="error"]').text()).toContain('未知')
    expect(fetcher).toHaveBeenCalledTimes(1)
    expect(wrapper.emitted('renamed')).toBeUndefined()
    wrapper.unmount()
  })
  it('hides action without permission and ignores an in-flight response after context change', async () => {
    let resolve!: (value: unknown) => void
    fetcher.mockReturnValue(
      new Promise((done) => {
        resolve = done
      })
    )
    const wrapper = mountDialog()
    await wrapper.find('input').setValue('新名称')
    await wrapper.findAll('button')[1].trigger('click')
    await wrapper.setProps({ identity: 'user:2', allowed: false })
    resolve(response())
    await flushPromises()
    expect(wrapper.find('button').exists()).toBe(false)
    expect(wrapper.emitted('renamed')).toBeUndefined()
    wrapper.unmount()
  })
  it('renames only an existing recent reference without fabricating visit or refreshing its timestamp', () => {
    const scope = { userId: 'user', tenantId: 'tenant', projectId: 'project' }
    renameRecentResource(scope, 'application', 'id', '新名称')
    expect(readRecentResources(scope)).toEqual([])
    recordRecentResource(scope, { kind: 'application', id: 'id', label: '旧名称' })
    const original = readRecentResources(scope)[0]
    renameRecentResource(scope, 'application', 'id', '新名称')
    expect(readRecentResources(scope)[0]).toEqual({ ...original, label: '新名称' })
    recordRecentResource(scope, { kind: 'application', id: 'id', label: '草稿展示名称' }, true)
    expect(readRecentResources(scope)[0].label).toBe('新名称')
    expect(readRecentResources({ ...scope, projectId: 'other' })).toEqual([])
  })
})
