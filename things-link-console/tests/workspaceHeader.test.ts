import { beforeEach, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { nextTick, reactive } from 'vue'
import Header from '@/components/business/ConsoleWorkspaceHeader.vue'
const mocks = vi.hoisted(() => ({ user: {} as any, push: vi.fn() }))
vi.mock('@/store/modules/user', () => ({ useUserStore: () => mocks.user }))
vi.mock('vue-router', () => ({ useRouter: () => ({ push: mocks.push }) }))
beforeEach(() => {
  vi.clearAllMocks()
  mocks.user = reactive({ info: { buttons: ['rule:read'] } })
})
it('当前权限过滤关联入口，权限变化立即移除入口，并通过Router保留离开守卫', async () => {
  const page = mount(Header, {
    props: {
      title: '规则',
      description: '管理与记录',
      links: [
        { label: '执行记录', path: '/rule/executions', permission: 'rule:read' },
        { label: '配置规则', path: '/rule/messages', permission: 'rule:manage' },
        { label: '项目', path: '/project/list' }
      ]
    },
    global: { stubs: { ElButton: { template: '<button><slot /></button>' } } }
  })
  expect(page.get('h1').text()).toBe('规则')
  expect(page.text()).not.toContain('配置规则')
  await page.findAll('button')[0]!.trigger('click')
  expect(mocks.push).toHaveBeenCalledWith('/rule/executions')
  mocks.user.info.buttons = []
  await nextTick()
  expect(page.text()).not.toContain('执行记录')
  expect(page.text()).toContain('项目')
})
