import { expect, test, type Page } from '@playwright/test'
import { AlarmInboxFixture } from './alarm-inbox-fixture'
import {
  enterProject,
  login,
  OWNER_EMAIL,
  OWNER_PASSWORD,
  MEMBER_EMAIL,
  MEMBER_PASSWORD
} from './helpers'

/** 真实通知面板；不拦截或伪造核心API响应。 */
async function openInbox(page: Page, count: number) {
  await expect(page.getByTestId('alarm-inbox-count')).toHaveText(String(count))
  await page.getByTestId('alarm-inbox-toggle').click()
  const panel = page.getByTestId('alarm-inbox-panel')
  await expect(panel).toBeVisible()
  await expect(panel).toContainText('最近30天')
  return panel
}

/** ADR0093/S12-P0-6b2：真实双账号、跨项目个人已读，不把阅读当作共享告警ACK。 */
test(
  '告警通知：双账号独立已读、分页、项目隔离与真实退出',
  { tag: '@alarm-inbox' },
  async ({ page, browser }, testInfo) => {
    test.setTimeout(180_000)
    const fixture = new AlarmInboxFixture()
    let memberContext: Awaited<ReturnType<typeof browser.newContext>> | undefined
    try {
      fixture.create()
      const before = fixture.snapshot()
      await login(page, OWNER_EMAIL, OWNER_PASSWORD)
      await enterProject(page, fixture.owner.name)
      let panel = await openInbox(page, 22)
      await expect(panel.getByTestId('alarm-inbox-item')).toHaveCount(20)
      await panel.getByRole('button', { name: '下一页', exact: true }).click()
      await expect(panel.getByTestId('alarm-inbox-item')).toHaveCount(2)
      await expect(panel).toContainText('第 2 页')
      await panel.getByRole('button', { name: '上一页', exact: true }).click()
      await expect(panel.getByTestId('alarm-inbox-item')).toHaveCount(20)
      const firstId = await panel
        .getByTestId('alarm-inbox-item')
        .first()
        .getAttribute('data-event-id')
      expect(firstId).toBeTruthy()
      await panel
        .getByTestId('alarm-inbox-item')
        .first()
        .getByRole('button', { name: '标记已读', exact: true })
        .click()
      await expect(page.getByTestId('alarm-inbox-count')).toHaveText('21')
      await expect(
        panel
          .locator(`[data-event-id="${firstId}"]`)
          .getByRole('button', { name: '标记已读', exact: true })
      ).toHaveCount(0)
      await panel.getByRole('button', { name: '标记当前页已读', exact: true }).click()
      await expect(page.getByTestId('alarm-inbox-count')).toHaveText('2')
      await panel.getByRole('button', { name: '下一页', exact: true }).click()
      await expect(panel.getByTestId('alarm-inbox-item')).toHaveCount(2)
      await panel.getByRole('button', { name: '标记当前页已读', exact: true }).click()
      await expect(page.getByTestId('alarm-inbox-count')).toHaveText('0')
      await expect(panel.getByTestId('alarm-inbox-item')).toHaveCount(20)
      await expect(
        panel.getByRole('button', { name: '标记当前页已读', exact: true })
      ).toBeDisabled()
      await panel.screenshot({ path: testInfo.outputPath('alarm-inbox-owner-read.png') })

      // 第二个真实会话是跨租户VIEWER，同一批事件仍全部未读。
      memberContext = await browser.newContext({ baseURL: testInfo.project.use.baseURL })
      const memberPage = await memberContext.newPage()
      await login(memberPage, MEMBER_EMAIL, MEMBER_PASSWORD)
      await enterProject(memberPage, fixture.owner.name)
      const memberPanel = await openInbox(memberPage, 22)
      await expect(memberPanel.getByTestId('alarm-inbox-item')).toHaveCount(20)
      await memberPanel
        .getByTestId('alarm-inbox-item')
        .first()
        .getByRole('button', { name: '标记已读', exact: true })
        .click()
      await expect(memberPage.getByTestId('alarm-inbox-count')).toHaveText('21')
      await memberContext.close()
      memberContext = undefined

      // OWNER以VIEWER切入另一账号项目，只看到那个项目的1条事实。
      await panel.getByRole('button', { name: '关闭告警通知' }).click()
      await enterProject(page, fixture.member.name)
      panel = await openInbox(page, 1)
      await expect(panel.getByTestId('alarm-inbox-item')).toHaveCount(1)
      await panel.getByRole('button', { name: '关闭告警通知' }).click()
      await enterProject(page, fixture.owner.name)
      panel = await openInbox(page, 0)
      await expect(panel.getByTestId('alarm-inbox-item')).toHaveCount(20)
      await expect(panel.getByRole('button', { name: '标记已读', exact: true })).toHaveCount(0)
      expect(fixture.snapshot()).toBe(before)

      // 归档后使用既有项目状态API显示只读；不尝试改变共享安全过滤链。
      fixture.archiveOwner()
      // 归档读取必须真实通过计量过滤链，不能只凭旧列表或错误提示宣称只读成功。
      const readPaths = [
        '/api/v1/projects',
        `/api/v1/projects/${fixture.owner.projectId}/alarm-notifications`,
        `/api/v1/projects/${fixture.owner.projectId}/alarm-notifications/unread-count`
      ]
      const readonlyResponses = readPaths.map((path) =>
        page.waitForResponse(
          (response) =>
            response.request().method() === 'GET' && new URL(response.url()).pathname === path
        )
      )
      await panel.getByRole('button', { name: '刷新通知', exact: true }).click()
      const [projectsResponse, listResponse, countResponse] = await Promise.all(readonlyResponses)
      for (const response of [projectsResponse, listResponse, countResponse]) {
        expect(response.status()).toBe(200)
      }
      expect((await listResponse.json()).items).toHaveLength(20)
      expect((await countResponse.json()).unreadCount).toBe(0)
      expect(fixture.snapshot()).toBe(before)
      await expect(panel).toContainText('项目已归档，通知只读')
      await expect(
        panel.getByRole('button', { name: '标记当前页已读', exact: true })
      ).toBeDisabled()

      // 产品真实退出：操作头像菜单及确认，观察会话撤销HTTP，不用clearCookies冒充退出。
      await panel.getByRole('button', { name: '关闭告警通知' }).click()
      await page.getByAltText('avatar', { exact: true }).hover()
      await page.locator('.user-menu-popover:visible .log-out').click()
      const logout = page.waitForResponse(
        (response) =>
          response.url().endsWith('/api/v1/auth/logout') && response.request().method() === 'POST'
      )
      await page
        .locator('.login-out-dialog')
        .getByRole('button', { name: '确定', exact: true })
        .click()
      expect((await logout).status()).toBeLessThan(300)
      await page.waitForURL((url) => url.hash.includes('/auth/login'))
      await expect(page.getByTestId('alarm-inbox-count')).toHaveCount(0)
      await expect(page.getByTestId('alarm-inbox-panel')).toHaveCount(0)
      expect(fixture.snapshot()).toBe(before)
    } finally {
      await memberContext?.close()
      fixture.dispose()
    }
  }
)
