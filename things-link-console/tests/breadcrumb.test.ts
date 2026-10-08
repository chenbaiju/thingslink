import { mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'

const { route } = vi.hoisted(() => ({
  route: {
    path: '',
    meta: {},
    matched: [] as { path: string; name: string; meta: { title: string } }[]
  }
}))
vi.mock('vue-router', () => ({
  useRoute: () => route,
  useRouter: () => ({ getRoutes: () => [], push: vi.fn() })
}))
vi.mock('@/store/modules/menu', () => ({ useMenuStore: () => ({ navigationMenu: [] }) }))
vi.mock('@/utils/router', () => ({ formatMenuTitle: (title: string) => title }))
import Breadcrumb from '@/components/core/layouts/art-breadcrumb/index.vue'
const item = (path: string, title: string) => ({ path, name: path, meta: { title } })

describe('D-002概要面包屑', () => {
  it('同义概要只保留当前页', () => {
    route.matched = [item('/dashboard', '概要'), item('/dashboard/overview', '概要')]
    const wrapper = mount(Breadcrumb)
    expect(wrapper.findAll('li')).toHaveLength(1)
    expect(wrapper.text()).toBe('概要')
  })
  it('普通二级及同名三级不折叠', () => {
    route.matched = [item('/device', '设备'), item('/device/messages', '消息日志')]
    expect(mount(Breadcrumb).findAll('li')).toHaveLength(2)
    route.matched.push(item('/device/messages/detail', '消息日志'))
    expect(mount(Breadcrumb).findAll('li')).toHaveLength(3)
  })
  it('概要目录的其他页面或不同译文保留层级', () => {
    route.matched = [item('/dashboard', '概要'), item('/dashboard/designer', '看板设计器')]
    expect(mount(Breadcrumb).findAll('li')).toHaveLength(2)
    route.matched = [item('/dashboard', '工作空间'), item('/dashboard/overview', '概要')]
    expect(mount(Breadcrumb).findAll('li')).toHaveLength(2)
  })
})
