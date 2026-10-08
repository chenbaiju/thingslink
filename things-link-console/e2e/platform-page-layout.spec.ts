import { expect, test } from '@playwright/test'
import { enterProject, login, OWNER_EMAIL, OWNER_PASSWORD } from './helpers'

// 检查真实菜单注册和项目上下文中的布局区别；不注入路由或模拟接口。
test('平台页面在进入项目前后保留菜单，并使用对应的 titlebar', async ({ page }) => {
  await page.setViewportSize({ width: 2173, height: 1220 })
  await login(page, OWNER_EMAIL, OWNER_PASSWORD)
  await page.evaluate(async () => {
    const authPath = '/src/api/auth.ts'
    const storePath = '/src/store/modules/user.ts'
    const session = await (await import(authPath)).fetchSwitchProject(null)
    ;(await import(storePath)).useUserStore().setToken(session.accessToken)
  })
  await page.reload()
  const destinations = [
    ['/project/list', '项目列表', '我创建的项目'],
    ['/project/create', '添加项目', '添加项目'],
    ['/project/recycle-bin', '项目回收站', '项目回收站'],
    ['/plan-catalog', '套餐与权益', '套餐与权益'],
    ['/system-status', '系统状态', '系统状态']
  ]
  for (const entered of [false, true]) {
    if (entered) await enterProject(page, 'E2E项目')
    for (const [route, label, title] of destinations) {
      await page.goto(`/#${route}`)
      await expect(page.getByRole('heading', { name: title, exact: true })).toBeVisible()
      const sidebar = page.locator('#app-sidebar')
      const parent = ['套餐与权益', '系统状态'].includes(label) ? '账号与平台工具' : '项目与资源'
      const item = sidebar.getByRole('menuitem', { name: label, exact: true })
      if (!(await item.isVisible()))
        await sidebar.getByRole('menuitem', { name: parent, exact: true }).click()
      await expect(item).toBeVisible()
      const bar = page.locator('#app-header .console-titlebar')
      if (entered) await expect(bar).not.toHaveClass(/project-titlebar/)
      else await expect(bar).toHaveClass(/project-titlebar/)
      const style = await bar.evaluate((element) => ({
        background: getComputedStyle(element).backgroundColor,
        shadow: getComputedStyle(element).boxShadow
      }))
      expect(style.background).toBe('rgb(255, 255, 255)')
      expect(style.shadow).not.toBe('none')
      await expect(sidebar.getByRole('menuitem', { name: '403', exact: true })).toHaveCount(0)
      await expect(page.getByText('操作指南', { exact: true })).toHaveCount(0)
      if (route === '/system-status') {
        const summary = page.locator('.system-status__summary')
        await expect(summary).toContainText('观测时间')
        expect((await summary.boundingBox())!.height).toBeLessThan(200)
        await expect(page.getByRole('heading', { name: '运行依赖', exact: true })).toHaveCSS(
          'font-size',
          '28px'
        )
      }
      if (route === '/project/list') {
        const sections = page.locator('.project-list__section')
        await expect(sections).toHaveCount(2)
        const heights = await sections.evaluateAll((elements) =>
          elements.map((element) => element.getBoundingClientRect().height)
        )
        expect(Math.abs(heights[0] - heights[1])).toBeLessThan(2)
        await expect(page.getByRole('heading', { name: '我创建的项目', exact: true })).toHaveCSS(
          'font-weight',
          '400'
        )
      }
      if (route === '/project/recycle-bin')
        await expect(page.getByRole('button', { name: '刷新回收站', exact: true })).toHaveCount(0)
    }
  }
})
