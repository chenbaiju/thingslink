import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { shallowMount, flushPromises } from '@vue/test-utils'
import { nextTick, reactive, ref, toRefs } from 'vue'

const mocks = vi.hoisted(() => ({
  refresh: vi.fn(),
  count: vi.fn(),
  dispose: vi.fn(),
  projects: vi.fn()
}))
const user = reactive({
  info: { userId: 'account-one', currentProjectId: 'project-one' },
  isLogin: false,
  accessToken: '',
  language: 'zh'
})
vi.mock('@/api/project', () => ({ fetchProjects: mocks.projects }))
vi.mock('@/store/modules/user', () => ({ useUserStore: () => user }))
vi.mock('@/store/modules/setting', () => ({
  useSettingStore: () =>
    reactive({
      menuOpen: false,
      systemThemeColor: '',
      showSettingGuide: false,
      menuType: '',
      isDark: false,
      tabStyle: ''
    })
}))
vi.mock('@/store/modules/menu', () => ({ useMenuStore: () => reactive({ menuList: [] }) }))
vi.mock('pinia', () => ({ storeToRefs: (store: object) => toRefs(store) }))
vi.mock('vue-i18n', () => ({ useI18n: () => ({ locale: ref('zh') }) }))
vi.mock('vue-router', () => ({ useRouter: () => ({ push: vi.fn() }) }))
vi.mock('@vueuse/core', () => ({
  useWindowSize: () => ({ width: ref(1000) }),
  useFullscreen: () => ({ isFullscreen: ref(false), toggle: vi.fn() })
}))
vi.mock('@/hooks/core/useCommon', () => ({
  useCommon: () => ({ homePath: ref('/'), refresh: vi.fn() })
}))
vi.mock('@/hooks/core/useHeaderBar', () => ({
  useHeaderBar: () =>
    Object.fromEntries(
      [
        'shouldShowMenuButton',
        'shouldShowRefreshButton',
        'shouldShowFastEnter',
        'shouldShowBreadcrumb',
        'shouldShowGlobalSearch',
        'shouldShowFullscreen',
        'shouldShowNotification',
        'shouldShowChat',
        'shouldShowLanguage',
        'shouldShowSettings',
        'shouldShowThemeToggle',
        'fastEnterMinWidth'
      ].map((key) => [key, ref(key === 'shouldShowNotification')])
    )
}))
vi.mock('@/config', () => ({ default: { systemInfo: { name: 'ThingsLink' } } }))
vi.mock('@/locales', () => ({ languageOptions: [] }))
vi.mock('@/utils/sys', () => ({ mittBus: { emit: vi.fn() } }))
vi.mock('@/utils/ui/animation', () => ({ themeAnimation: vi.fn() }))
vi.mock('@/composables/useAlarmInbox', () => ({
  useAlarmInbox: () => ({
    unreadCount: ref(null),
    countError: ref(''),
    refresh: mocks.refresh,
    refreshCount: mocks.count,
    dispose: mocks.dispose
  })
}))

import ArtHeaderBar from '@/components/core/layouts/art-header-bar/index.vue'

const stubs = [
  'ArtLogo',
  'ArtSvgIcon',
  'ArtIconButton',
  'ArtBreadcrumb',
  'ArtFastEnter',
  'ArtUserMenu',
  'ArtProjectSwitcher',
  'ArtWorkTab',
  'ArtNotification',
  'ElPopover',
  'ArtHorizontalMenu',
  'ArtMixedMenu',
  'ElDropdownItem',
  'ElDropdownMenu',
  'ElDropdown'
]
let wrapper: ReturnType<typeof shallowMount> | undefined
function mountHeader() {
  wrapper = shallowMount(ArtHeaderBar, {
    global: {
      stubs: {
        ...Object.fromEntries(stubs.map((name) => [name, true])),
        ArtNotification: {
          name: 'ArtNotification',
          props: ['value', 'inbox', 'available', 'writable', 'projectStatus'],
          template: '<div />'
        }
      },
      mocks: { $t: (key: string) => key }
    }
  })
  return wrapper
}

beforeEach(() => {
  vi.useFakeTimers()
  vi.clearAllMocks()
  mocks.projects.mockResolvedValue([{ id: 'project-one', status: 'ACTIVE' }])
  user.isLogin = false
  user.accessToken = ''
  user.info = { userId: 'account-one', currentProjectId: 'project-one' }
  Object.defineProperty(document, 'visibilityState', { configurable: true, value: 'visible' })
})
afterEach(() => {
  wrapper?.unmount()
  wrapper = undefined
  vi.useRealTimers()
})

describe('告警通知顶栏的认证与前台轮询', () => {
  it('持久化info不足以发请求，登录且有内存令牌后才拉取', async () => {
    const header = mountHeader()
    expect(mocks.count).not.toHaveBeenCalled()
    await header.get('[data-testid="alarm-inbox-toggle"]').trigger('click')
    expect(mocks.refresh).not.toHaveBeenCalled()
    user.isLogin = true
    await nextTick()
    expect(mocks.count).not.toHaveBeenCalled()
    user.accessToken = 'authenticated-token'
    await nextTick()
    expect(mocks.count).toHaveBeenCalledTimes(1)
    await header.get('[data-testid="alarm-inbox-toggle"]').trigger('click')
    expect(mocks.refresh).toHaveBeenCalledTimes(1)
    expect(mocks.count).toHaveBeenCalledTimes(2)
  })

  it('同账号项目令牌续期不关闭面板，真正切换身份才关闭', async () => {
    user.isLogin = true
    user.accessToken = 'first-token'
    const header = mountHeader()
    const toggle = header.get('[data-testid="alarm-inbox-toggle"]')
    await toggle.trigger('click')
    expect(toggle.attributes('aria-expanded')).toBe('true')
    const previousCount = mocks.count.mock.calls.length
    user.accessToken = 'refreshed-token'
    await nextTick()
    expect(toggle.attributes('aria-expanded')).toBe('true')
    expect(mocks.count).toHaveBeenCalledTimes(previousCount)
    user.info.currentProjectId = 'project-two'
    await nextTick()
    expect(toggle.attributes('aria-expanded')).toBe('false')
  })

  it('状态加载时只读，真实归档禁写，旧项目状态响应不能恢复新项目写入', async () => {
    user.isLogin = true
    user.accessToken = 'token'
    let resolveOld: (value: unknown) => void = () => {}
    mocks.projects.mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          resolveOld = resolve
        })
    )
    const header = mountHeader()
    const notification = () => header.findComponent({ name: 'ArtNotification' })
    expect(notification().props('writable')).toBe(false)
    mocks.projects.mockResolvedValueOnce([{ id: 'project-two', status: 'ARCHIVED' }])
    user.info.currentProjectId = 'project-two'
    await nextTick()
    await flushPromises()
    expect(notification().props('projectStatus')).toBe('ARCHIVED')
    expect(notification().props('writable')).toBe(false)
    resolveOld([{ id: 'project-one', status: 'ACTIVE' }])
    await flushPromises()
    expect(notification().props('projectStatus')).toBe('ARCHIVED')
    expect(notification().props('writable')).toBe(false)
  })

  it('隐藏期间不轮询，重新可见立即刷新，退出和卸载停止请求', async () => {
    user.isLogin = true
    user.accessToken = 'authenticated-token'
    const header = mountHeader()
    expect(mocks.count).toHaveBeenCalledTimes(1)
    Object.defineProperty(document, 'visibilityState', { configurable: true, value: 'hidden' })
    await vi.advanceTimersByTimeAsync(120_000)
    expect(mocks.count).toHaveBeenCalledTimes(1)
    Object.defineProperty(document, 'visibilityState', { configurable: true, value: 'visible' })
    document.dispatchEvent(new Event('visibilitychange'))
    expect(mocks.count).toHaveBeenCalledTimes(2)
    await vi.advanceTimersByTimeAsync(60_000)
    expect(mocks.count).toHaveBeenCalledTimes(3)
    user.isLogin = false
    user.accessToken = ''
    await nextTick()
    await vi.advanceTimersByTimeAsync(60_000)
    expect(mocks.count).toHaveBeenCalledTimes(3)
    header.unmount()
    wrapper = undefined
    expect(mocks.dispose).toHaveBeenCalledTimes(1)
    document.dispatchEvent(new Event('visibilitychange'))
    await vi.advanceTimersByTimeAsync(60_000)
    expect(mocks.count).toHaveBeenCalledTimes(3)
  })
})
