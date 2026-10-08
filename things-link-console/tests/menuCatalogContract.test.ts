import { readFileSync } from 'node:fs'
import { describe, expect, it, vi } from 'vitest'
import type { AppRouteRecord } from '@/types/router'
import { consoleDomainRegistry } from '@/domains/registry'

const { user } = vi.hoisted(() => ({
  user: { info: { roles: [] as string[], buttons: [] as string[] } }
}))
vi.mock('@/store/modules/user', () => ({ useUserStore: () => user }))
vi.mock('@/hooks/core/useAppMode', () => ({
  useAppMode: () => ({ isFrontendMode: { value: true } })
}))
vi.mock('@/api/system-manage', () => ({ fetchGetMenuList: vi.fn() }))
vi.mock('@/utils', () => ({ formatMenuTitle: (title: string) => title }))
import { MenuProcessor } from '@/router/core/MenuProcessor'
import { workspaceNavigation } from '@/utils/workspace-navigation'

interface Row {
  name: string
  parent: string
  path: string
  component: string
  title: string
  hidden: boolean
  fullPage: boolean
  fixedTab: boolean
  authCodes: string[]
}
const baseline = JSON.parse(
  readFileSync('../docs/delivery/verification/menu-catalog-baseline.json', 'utf8')
) as {
  nodes: Row[]
  cases: {
    role: string
    commercial: boolean
    reviewer: boolean
    permissions: string[]
    names: string[]
  }[]
}
function rows(routes: readonly AppRouteRecord[], parent = '', base = ''): Row[] {
  return routes
    .flatMap((route) => {
      const path = route.path.startsWith('/') ? route.path : `${base}/${route.path}`
      return [
        {
          name: String(route.name),
          parent,
          path,
          component: String(route.component),
          title: String(route.meta.title),
          hidden: route.meta.isHide === true,
          fullPage: route.meta.isFullPage === true,
          fixedTab: route.meta.fixedTab === true,
          authCodes: [...new Set((route.meta.authList ?? []).map((point) => point.authMark))].sort()
        },
        ...rows(route.children ?? [], String(route.name), path)
      ]
    })
    .sort((a, b) => a.name.localeCompare(b.name, 'en'))
}
function assertCatalog(actual: Row[]) {
  expect(new Set(actual.map((row) => row.name)).size).toBe(actual.length)
  const withoutButtons = ({ authCodes: _ignored, ...identity }: Row) => identity
  expect(actual.map(withoutButtons)).toEqual(
    [...baseline.nodes].sort((a, b) => a.name.localeCompare(b.name, 'en')).map(withoutButtons)
  )
  for (const row of actual) {
    const expected = baseline.nodes.find((item) => item.name === row.name)!
    for (const permission of row.authCodes)
      expect(expected.authCodes, row.name).toContain(permission)
  }
}

describe('D-004后端目录与真实前端兜底合同', () => {
  it('全部业务菜单具有相同稳定身份、层级和显示合同', () =>
    assertCatalog(rows(consoleDomainRegistry.routes)))
  for (const scenario of baseline.cases) {
    it(`${scenario.role}平台运营=${scenario.commercial}独立审核=${scenario.reviewer}的实际处理结果与后端一致`, async () => {
      user.info.roles = scenario.role === 'NONE' ? [] : [scenario.role]
      user.info.buttons = scenario.permissions
      const menus = await new MenuProcessor().getMenuList()
      const visibleLeaves = (routes: readonly AppRouteRecord[]): AppRouteRecord[] =>
        routes.flatMap((route) =>
          route.meta.isHide
            ? []
            : route.children?.length
              ? visibleLeaves(route.children)
              : route.component && route.component !== '/index/index'
                ? [route]
                : []
        )
      const before = visibleLeaves(menus)
      const projected = workspaceNavigation(menus).flatMap((group) => group.children ?? [])
      expect(projected.map((leaf) => leaf.name).sort()).toEqual(
        before.map((leaf) => leaf.name).sort()
      )
      expect(new Set(projected).size).toBe(projected.length)
      for (const leaf of projected) expect(before).toContain(leaf)
      expect(
        rows(menus)
          .map((item) => item.name)
          .sort()
      ).toEqual([...scenario.names].sort())
    })
  }
  it('异常路由保留但不显示在业务导航中', () => {
    const catalog = rows(consoleDomainRegistry.routes)
    for (const name of ['Exception', 'Exception403', 'Exception404', 'Exception500']) {
      expect(catalog.find((route) => route.name === name)?.hidden).toBe(true)
    }
    const visible = workspaceNavigation(consoleDomainRegistry.routes)
      .flatMap((group) => group.children ?? [])
      .map((route) => route.name)
    expect(visible).not.toContain('Exception403')
    expect(visible).not.toContain('Exception404')
    expect(visible).not.toContain('Exception500')
  })
  it('删菜单或篡改路径不可被共同子集比对掩盖', () => {
    const good = rows(consoleDomainRegistry.routes)
    expect(() => assertCatalog(good.slice(1))).toThrow()
    const broken = structuredClone(good)
    broken[0].path = '/wrong-path'
    expect(() => assertCatalog(broken)).toThrow()
  })
})
