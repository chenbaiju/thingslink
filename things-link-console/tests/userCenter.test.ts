import { afterEach, describe, expect, it, vi } from 'vitest'
import { shallowMount } from '@vue/test-utils'
import { reactive, toRefs, nextTick } from 'vue'

const user = reactive({ getUserInfo: {} as Record<string, unknown> })
vi.mock('@/store/modules/user', () => ({ useUserStore: () => user }))
vi.mock('pinia', async (importOriginal) => ({
  ...(await importOriginal<typeof import('pinia')>()),
  storeToRefs: (store: object) => toRefs(store)
}))
import UserCenter from '@/views/system/user-center/index.vue'
import defaultAvatar from '@imgs/user/avatar.webp'

let wrapper: ReturnType<typeof shallowMount> | undefined
afterEach(() => wrapper?.unmount())
const mountPage = () =>
  shallowMount(UserCenter, {
    global: {
      mocks: { $t: (key: string) => key },
      stubs: {
        ElCard: { template: '<section><slot name="header"/><slot/></section>' },
        ElTag: { template: '<span><slot/></span>' },
        ElAlert: true
      }
    }
  })

describe('个人中心当前会话资料', () => {
  it('只读呈现当前账号并响应切换，不能残留上个账号资料', async () => {
    user.getUserInfo = {
      userName: '测试用户',
      email: 'viewer@example.test',
      userId: 'account-1',
      tenantId: 'tenant-1',
      currentProjectId: 'project-1',
      roles: ['VIEWER'],
      avatar: '/test-avatar.png'
    }
    wrapper = mountPage()
    for (const value of [
      '测试用户',
      'viewer@example.test',
      'account-1',
      'tenant-1',
      'project-1',
      'VIEWER'
    ]) {
      expect(wrapper.text()).toContain(value)
    }
    expect(wrapper.get('img').attributes('src')).toBe('/test-avatar.png')
    expect(wrapper.find('input, form, button').exists()).toBe(false)
    user.getUserInfo = { userName: '新用户', userId: 'account-2' }
    await nextTick()
    expect(wrapper.text()).toContain('account-2')
    expect(wrapper.text()).not.toContain('viewer@example.test')
    expect(wrapper.text()).not.toContain('project-1')
    expect(wrapper.get('img').attributes('src')).toBe(defaultAvatar)
  })

  it('无项目、角色及缺失字段保持明确空态，不捏造身份', () => {
    user.getUserInfo = { userName: '   ' }
    wrapper = mountPage()
    expect(wrapper.text()).toContain('userCenter.noProject')
    expect(wrapper.text()).toContain('userCenter.noRoles')
    expect(wrapper.text()).toContain('—')
    expect(wrapper.text()).not.toMatch(/undefined|null|OWNER|ADMIN/)
  })
})
