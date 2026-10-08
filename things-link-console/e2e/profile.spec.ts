import { expect, test, type Page } from '@playwright/test'
import {
  enterProject,
  login,
  resetSession,
  MEMBER_EMAIL,
  MEMBER_PASSWORD,
  OWNER_EMAIL,
  OWNER_PASSWORD
} from './helpers'

async function openProfile(page: Page) {
  await page.getByAltText('avatar', { exact: true }).first().hover()
  await page.locator('.user-menu-popover:visible').getByText('个人中心', { exact: true }).click()
  await expect(page).toHaveURL(/#\/system\/user-center$/)
  await expect(page.locator('.user-center-page__details')).toBeVisible()
}

async function verifyProfile(page: Page, email: string, role: string | null) {
  // 使用真实 /me 与菜单接口，不从 store 的缓存断言其自身。
  const data = await page.evaluate(async () => {
    const path = '/src/utils/http/index.ts'
    const { default: request } = await import(path)
    const me = await request.get({ url: '/api/v1/auth/me' })
    const menus = await request.get({ url: '/api/v1/system/menus' })
    type Node = { name?: string; path?: string; component?: string; children?: Node[] }
    const flatten = (nodes: Node[]): Node[] =>
      nodes.flatMap((n) => [n, ...flatten(n.children ?? [])])
    const profile = flatten(menus).find((node) => node.name === 'UserCenter')
    return { me, profile }
  })
  expect(data.me.email).toBe(email)
  expect(data.me.projectRole ?? null).toBe(role)
  expect(data.profile).toMatchObject({
    name: 'UserCenter',
    path: '/system/user-center',
    component: '/system/user-center'
  })
  const values = page.locator('.profile-details__item dd')
  await expect(values.nth(0)).toHaveText(data.me.accountId)
  await expect(values.nth(1)).toHaveText(email)
  await expect(values.nth(2)).toHaveText(data.me.tenantId)
  if (data.me.currentProjectId) await expect(values.nth(3)).toHaveText(data.me.currentProjectId)
  else await expect(values.nth(3)).toHaveText('未选择项目')
  if (role) await expect(values.nth(4).locator('.el-tag')).toHaveText([role])
  else {
    await expect(values.nth(4).locator('.el-tag')).toHaveCount(0)
    await expect(values.nth(4)).toHaveText('暂无角色信息')
  }
  await expect(page.locator('.user-center-page__notice')).toContainText(
    '以下为当前登录账号的资料。'
  )
  const titleStyle = await page.locator('.workspace-header h1').evaluate((element) => ({
    size: getComputedStyle(element).fontSize,
    weight: getComputedStyle(element).fontWeight
  }))
  expect(titleStyle).toEqual({ size: '28px', weight: '400' })
  if (data.me.currentProjectId) {
    await expect(page.locator('#app-header .console-titlebar')).not.toHaveClass(/project-titlebar/)
    await expect(page.getByRole('button', { name: '项目列表', exact: true })).toHaveCount(0)
  } else {
    await expect(page.locator('#app-header .console-titlebar')).toHaveClass(/project-titlebar/)
    await expect(page.getByRole('button', { name: '项目列表', exact: true })).toBeVisible()
  }
  await expect(page.getByRole('button', { name: '下一页', exact: true })).toHaveCount(0)
  await expect(page.getByRole('button', { name: '回到第一页', exact: true })).toHaveCount(0)
  await expect(page.locator('.profile-identity__text h4')).toHaveText(data.me.displayName || '—')
  await expect(
    page.locator('.user-center-page__details input, .user-center-page__details button')
  ).toHaveCount(0)
  await expect(page.locator('.profile-identity__avatar')).toBeVisible()
  return data.me.accountId as string
}

test('个人中心：无项目账号经真实菜单和头像进入只读资料，换账号不保留旧身份', async ({ page }) => {
  await login(page, OWNER_EMAIL, OWNER_PASSWORD)
  await page.evaluate(async () => {
    const authPath = '/src/api/auth.ts'
    const storePath = '/src/store/modules/user.ts'
    const { fetchSwitchProject } = await import(authPath)
    const { useUserStore } = await import(storePath)
    const session = await fetchSwitchProject(null)
    useUserStore().setToken(session.accessToken)
  })
  await page.reload()
  await openProfile(page)
  const ownerId = await verifyProfile(page, OWNER_EMAIL, null)
  await resetSession(page)
  await login(page, MEMBER_EMAIL, MEMBER_PASSWORD)
  await page.evaluate(async () => {
    const authPath = '/src/api/auth.ts'
    const storePath = '/src/store/modules/user.ts'
    const { fetchSwitchProject } = await import(authPath)
    const { useUserStore } = await import(storePath)
    const session = await fetchSwitchProject(null)
    useUserStore().setToken(session.accessToken)
  })
  await page.reload()
  await openProfile(page)
  const memberId = await verifyProfile(page, MEMBER_EMAIL, null)
  expect(memberId).not.toBe(ownerId)
  await expect(page.locator('.user-center-page__details')).not.toContainText(ownerId)
  await expect(page.locator('.user-center-page__profile')).not.toContainText(OWNER_EMAIL)
})

test('个人中心：OWNER、ADMIN、OPERATOR、VIEWER 均可进入且仅显示当前账号角色', async ({
  page,
  browser
}) => {
  await login(page, OWNER_EMAIL, OWNER_PASSWORD)
  const name = `个人中心角色-${Date.now()}`
  const fixture = await page.evaluate(
    async ({ name, email }) => {
      const path = '/src/api/project.ts'
      const api = await import(path)
      const project = await api.fetchCreateProject({ name, region: 'sh-1' })
      // 此处只准备角色夹具；兼容直接添加与新增邀请是不同合同。
      const member = await api.fetchInviteMember(project.id, { email, role: 'ADMIN' })
      return { projectId: project.id as string, accountId: member.accountId as string }
    },
    { name, email: MEMBER_EMAIL }
  )
  // API 夹具不会触发项目页刷新；登录可能已落在同一路由，先重载真实目录。
  await page.reload()
  await enterProject(page, name)
  await openProfile(page)
  await verifyProfile(page, OWNER_EMAIL, 'OWNER')
  const context = await browser.newContext({ baseURL: new URL(page.url()).origin })
  const member = await context.newPage()
  try {
    await login(member, MEMBER_EMAIL, MEMBER_PASSWORD)
    await enterProject(member, name)
    for (const role of ['ADMIN', 'OPERATOR', 'VIEWER']) {
      if (role !== 'ADMIN') {
        await page.evaluate(
          async ({ projectId, accountId, role }) => {
            const path = '/src/api/project.ts'
            const { fetchUpdateMemberRole } = await import(path)
            await fetchUpdateMemberRole(projectId, accountId, role)
          },
          { ...fixture, role }
        )
        await member.reload()
      }
      await openProfile(member)
      await verifyProfile(member, MEMBER_EMAIL, role)
    }
    // 只回收本场成功创建的项目；失败夹具保留至隔离栈清理，不修改共享账号。
    await page.evaluate(async ({ projectId, accountId }) => {
      const path = '/src/api/project.ts'
      const api = await import(path)
      await api.fetchRemoveMember(projectId, accountId)
      await api.fetchDeleteProject(projectId)
    }, fixture)
  } finally {
    await context.close()
  }
})
