import { test, expect, type Page } from '@playwright/test'
import { login, enterProject } from './helpers'
import path from 'node:path'
test.use({ trace: 'off', video: 'off', screenshot: 'off' })
let emptyProjectId = ''

async function enterEmptyProject(page: Page) {
  const name = `首页引导-${Date.now()}`
  const project = await page.evaluate(async (name) => {
    const path = '/src/api/project.ts'
    return (await import(path)).fetchCreateProject({ name, region: 'sh-1' })
  }, name)
  emptyProjectId = project.id!
  await page.reload()
  await enterProject(page, name)
  return name
}

test.afterEach(async ({ page }) => {
  if (!emptyProjectId) return
  const id = emptyProjectId
  emptyProjectId = ''
  await enterProject(page, 'E2E项目')
  await page.evaluate(async (id) => {
    const path = '/src/api/project.ts'
    await (await import(path)).fetchDeleteProject(id)
  }, id)
})

test('开发首页真实项目摘要、入口与响应式布局保留项目概况', async ({ page }) => {
  const email = process.env.E2E_OWNER_EMAIL
  const password = process.env.E2E_OWNER_PASSWORD
  test.skip(!email || !password, '需要独占测试账号')
  await login(page, email!, password!)
  await enterProject(page, 'E2E项目')
  await page.goto('/#/dashboard/overview')
  await expect(page.locator('.overview-card--count')).toHaveCount(4)
  await page.screenshot({
    path: path.join(process.env.E2E_RUN_DIR ?? 'logs', 'overview-preserved.png'),
    fullPage: true
  })
  await page.goto('/#/dashboard/workbench')
  await expect(page.locator('.workbench-hero h1')).toHaveText('E2E项目')
  await expect(page.locator('.development-step')).toHaveCount(5)
  await expect(page.locator('.workbench-metrics strong').first()).not.toHaveText('—')
  await expect(page.locator('.workbench-metrics > div')).toHaveCount(4)
  await expect(page.locator('.workbench-traffic > section')).toHaveCount(3)
  await expect(page.locator('.workbench-main')).not.toContainText('统计生成于')
  const total = Number(
    (await page.locator('.workbench-metrics strong').first().innerText()).replaceAll(',', '')
  )
  if (total === 0) await expect(page.locator('.workbench-help')).toBeVisible()
  else {
    await expect(page.getByRole('region', { name: '我的资源', exact: true })).toBeVisible()
    await expect(page.locator('.workbench-help')).toHaveCount(0)
    await expect(page.getByLabel('设备类型数量', { exact: true })).not.toHaveText(/—|暂不可用/)
  }
  await page.setViewportSize({ width: 2173, height: 1220 })
  const cardTops = await page
    .locator('.workbench-traffic > section')
    .evaluateAll((elements) =>
      elements.map((element) => Math.round(element.getBoundingClientRect().top))
    )
  expect(new Set(cardTops).size).toBe(1)
  for (const [width, height] of [
    [1440, 900],
    [1280, 800],
    [390, 844]
  ]) {
    await page.setViewportSize({ width, height })
    await expect(page.locator('.developer-workbench')).toBeVisible()
    await page.mouse.move(0, height - 1)
    await page.locator('#app-main').evaluate((element) => {
      element.scrollTop = 0
    })
    if (width < 800) await expect(page.locator('.menu-left')).toHaveClass(/menu-left-close/)
    await page.evaluate(async () => {
      await Promise.all(
        document
          .getAnimations()
          .filter((animation) => animation.effect?.getTiming().iterations !== Infinity)
          .map((animation) => animation.finished.catch(() => {}))
      )
    })
    expect(
      await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1)
    ).toBe(true)
    await page.screenshot({
      path: path.join(process.env.E2E_RUN_DIR ?? 'logs', `workbench-${width}.png`),
      fullPage: true
    })
  }
  await page.setViewportSize({ width: 1440, height: 900 })
  await page.getByRole('button', { name: '定义物模型', exact: false }).first().click()
  await expect(page).toHaveURL(/\/device\/types/)
})

test('业务导航保留旧路由和终端用户入口', async ({ page }) => {
  test.skip(!process.env.E2E_OWNER_EMAIL || !process.env.E2E_OWNER_PASSWORD, '需要独占测试账号')
  await login(page, process.env.E2E_OWNER_EMAIL!, process.env.E2E_OWNER_PASSWORD!)
  await enterProject(page, 'E2E项目')
  await page.getByRole('menuitem', { name: '应用与看板', exact: true }).click()
  await page.getByRole('menuitem', { name: '终端用户', exact: true }).click()
  await expect(page).toHaveURL(/\/project\/end-users/)
  await expect(page.locator('[aria-label="breadcrumb"]')).toContainText('应用与看板')
  await page.goto('/#/device/types')
  await expect(page.locator('[aria-label="breadcrumb"]')).toContainText('设备开发')
  await page.reload()
  await expect(page.getByRole('heading', { name: '设备类型与物模型', exact: true })).toBeVisible()
  await expect(page).toHaveURL(/\/device\/types/)
  await page.goto('/#/dashboard/overview')
  await expect(page.locator('.overview-card--count')).toHaveCount(4)
})

test('开发首页深色、文字放大与键盘入口保持可用', async ({ page }) => {
  test.skip(!process.env.E2E_OWNER_EMAIL || !process.env.E2E_OWNER_PASSWORD, '需要独占测试账号')
  await login(page, process.env.E2E_OWNER_EMAIL!, process.env.E2E_OWNER_PASSWORD!)
  const name = await enterEmptyProject(page)
  await page.goto('/#/dashboard/workbench')
  await expect(page.locator('.workbench-hero h1')).toHaveText(name)
  await expect(page.locator('.workbench-help')).toBeVisible()
  await expect(page.getByRole('region', { name: '我的资源', exact: true })).toHaveCount(0)
  await page.setViewportSize({ width: 1440, height: 900 })
  await page.evaluate(async () => {
    const path = '/src/hooks/core/useTheme.ts'
    const { useTheme } = await import(path)
    useTheme().switchThemeStyles('dark')
  })
  await expect(page.locator('html')).toHaveClass(/dark/)
  await page.screenshot({
    path: path.join(process.env.E2E_RUN_DIR ?? 'logs', 'workbench-dark.png'),
    fullPage: true
  })
  await page.evaluate(() => {
    document.documentElement.style.zoom = '2'
  })
  await page.getByRole('button', { name: '查看接入步骤', exact: true }).focus()
  await expect(page.getByRole('button', { name: '查看接入步骤', exact: true })).toBeFocused()
  await page.keyboard.press('Enter')
  await expect(page.getByRole('dialog', { name: '设备接入步骤', exact: true })).toBeVisible()
  await page.keyboard.press('Escape')
  await expect(page.getByRole('dialog', { name: '设备接入步骤', exact: true })).toBeHidden()
  await page.locator('#app-main').evaluate((element) => {
    element.scrollTop = 0
  })
  expect(
    await page
      .locator('.development-steps')
      .evaluate((element) => getComputedStyle(element).gridTemplateColumns.split(' ').length)
  ).toBe(2)
  await page.screenshot({
    path: path.join(process.env.E2E_RUN_DIR ?? 'logs', 'workbench-css-zoom-200.png'),
    fullPage: true
  })
  await page.evaluate(() => {
    document.documentElement.style.zoom = ''
  })
})
