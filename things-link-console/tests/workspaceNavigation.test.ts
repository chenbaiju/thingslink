import { expect, it } from 'vitest'
import { workspaceNavigation, activeWorkspace } from '@/utils/workspace-navigation'
import type { AppRouteRecord } from '@/types/router'
const leaf = (path: string, hidden = false): AppRouteRecord => ({
  path,
  name: path,
  component: path,
  meta: { title: path, isHide: hidden }
})
it('preserves authorized leaf identity and deep links exactly once without mutating routes', () => {
  const source = [
    leaf('/device/list'),
    leaf('/ota/jobs'),
    leaf('/dashboard/overview'),
    leaf('/dashboard/designer'),
    leaf('/project/end-users'),
    leaf('/project/list'),
    leaf('/new-domain/page'),
    leaf('/secret', true)
  ]
  const before = JSON.stringify(source),
    result = workspaceNavigation(source)
  expect(result.flatMap((item) => item.children)).toHaveLength(7)
  expect(
    result.find((item) => item.meta.title === '应用与看板')?.children?.map((item) => item.path)
  ).toEqual(['/dashboard/designer', '/project/end-users'])
  expect(activeWorkspace(result, '/ota/jobs')?.meta.title).toBe('设备开发')
  expect(
    result.flatMap((item) => item.children).find((item) => item?.path === '/device/list')
  ).toBe(source[0])
  expect(JSON.stringify(source)).toBe(before)
})
it('does not recreate missing permissions and keeps project-only navigation usable', () => {
  expect(workspaceNavigation([])).toEqual([])
  const result = workspaceNavigation([leaf('/project/list')])
  expect(result).toHaveLength(1)
  expect(result[0].meta.title).toBe('项目与资源')
  expect(activeWorkspace(result, '/ota/jobs')).toBeUndefined()
})
it('never resurrects descendants of hidden or permission-filtered parent directories', () => {
  const result = workspaceNavigation([
    { ...leaf('/hidden', true), children: [leaf('/device/list')] }
  ])
  expect(result).toEqual([])
})
