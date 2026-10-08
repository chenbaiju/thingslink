import { beforeEach, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import type { Router } from 'vue-router'
vi.mock('@/router', () => ({ router: { push: vi.fn() } }))
vi.mock('@/hooks/core/useCommon', () => ({
  useCommon: () => ({ homePath: { value: '/dashboard/workbench' } })
}))
import { useWorktabStore } from '@/store/modules/worktab'
const router = {
  hasRoute: () => true,
  getRoutes: () => [{ name: 'Overview' }, { name: 'Devices' }],
  resolve: () => ({ matched: [1] })
} as unknown as Router
beforeEach(() => setActivePinia(createPinia()))
it('migrates the legacy system overview pin once and preserves other tabs and titles', () => {
  const store = useWorktabStore()
  store.openTab({
    name: 'Overview',
    path: '/dashboard/overview',
    title: '项目概况',
    fixedTab: true
  } as any)
  store.openTab({ name: 'Devices', path: '/device/list', title: '我的设备', fixedTab: true } as any)
  store.validateWorktabs(router)
  expect(store.opened.find((tab) => tab.name === 'Overview')?.fixedTab).toBe(false)
  expect(store.opened.find((tab) => tab.name === 'Devices')?.fixedTab).toBe(true)
  expect(store.opened.find((tab) => tab.name === 'Devices')?.title).toBe('我的设备')
  store.toggleFixedTab('/dashboard/overview')
  store.validateWorktabs(router)
  expect(store.opened.find((tab) => tab.name === 'Overview')?.fixedTab).toBe(true)
})
it('does not migrate the legacy tab before a workbench route is authorized', () => {
  const store = useWorktabStore()
  store.openTab({
    name: 'Overview',
    path: '/dashboard/overview',
    title: '项目概况',
    fixedTab: true
  } as any)
  store.validateWorktabs({ ...router, hasRoute: () => false } as unknown as Router)
  expect(store.opened[0].fixedTab).toBe(true)
  expect(store.workbenchTabsMigrated).toBe(false)
})
