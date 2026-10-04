import { expect, test, type Page, type BrowserContext } from '@playwright/test'
import { login, enterProject, OWNER_EMAIL, OWNER_PASSWORD } from './helpers'

// 一次性凭据不能进入Playwright网络trace、视频、失败截图或断言实参。
test.use({ trace: 'off', video: 'off', screenshot: 'off' })
test('匿名分享管理：一次性复制、真实匿名读取、撤销与未知签发恢复', async ({
  page,
  browser,
  context
}) => {
  test.setTimeout(180_000)
  await page.setViewportSize({ width: 1920, height: 1080 })
  await login(page, OWNER_EMAIL, OWNER_PASSWORD)
  await enterProject(page, 'E2E项目')
  await page.goto('/#/dashboard/designer')
  await page.getByTestId('dashboard-create').click()
  await page.getByTestId('dashboard-name').fill('匿名分享管理验收')
  await page.getByTestId('dashboard-create-confirm').click()
  await expect(page).toHaveURL(/dashboardId=/)
  await page.getByTestId('designer-add-text').click()
  await page.getByTestId('designer-text-content').fill('完整版本只读分享验收')
  await expect(page.getByTestId('designer-save-state')).toHaveAttribute('data-status', 'saved')
  const published = page.waitForResponse(
    (value) =>
      value.request().method() === 'POST' && new URL(value.url()).pathname.endsWith('/versions')
  )
  await page.getByTestId('publication-publish').click()
  await page.getByRole('dialog').getByRole('button', { name: '发布', exact: true }).click()
  expect((await published).status()).toBe(201)
  await expect(page.getByTestId('publication-status')).toHaveAttribute(
    'data-version-id',
    /^[a-f0-9-]{36}$/
  )
  await page.getByTestId('sharing-refresh').click()
  await page.getByRole('button', { name: '选择分享版本 1', exact: true }).click()
  await expect(page.getByTestId('sharing-create')).toBeEnabled()
  await create(page)
  await expect(page.getByTestId('sharing-copy')).toBeEnabled()
  const shareId = await page.getByTestId('sharing-created').getAttribute('data-share-id')
  if (!shareId) throw new Error('缺少安全分享身份')
  await context.grantPermissions(['clipboard-read', 'clipboard-write'], {
    origin: new URL(page.url()).origin
  })
  let link = ''
  let anonymous: BrowserContext | undefined
  try {
    await page.getByTestId('sharing-copy').click()
    await expect(page.getByText('完整分享链接已复制。', { exact: true })).toBeVisible()
    link = await page.evaluate(() => navigator.clipboard.readText())
    if (
      !/^http:\/\/localhost:\d+\/app\/share\/[a-f0-9-]{36}#token=sh_[A-Za-z0-9_-]{43}$/.test(link)
    )
      throw new Error('复制链接不符合合同')
    // DOM和持久存储只返回安全布尔值；断言输出永远不含凭据。
    const secretSafe = await page.evaluate(
      (value) =>
        !document.documentElement.outerHTML.includes(value.split('#token=')[1]!) &&
        !JSON.stringify({ ...localStorage, ...sessionStorage }).includes(
          value.split('#token=')[1]!
        ),
      link
    )
    expect(secretSafe).toBe(true)
    anonymous = await browser.newContext()
    const viewer = await anonymous.newPage()
    await openSecret(viewer, link)
    await expect(viewer.getByTestId('dashboard-canvas')).toContainText('完整版本只读分享验收')
    expect(await viewer.evaluate(() => location.hash === '')).toBe(true)
    await page.bringToFront()
    await page.getByTestId('sharing-refresh').click()
    const row = page.getByTestId(`sharing-row-${shareId}`)
    await expect(row).toHaveAttribute('data-status', 'ACTIVE')
    await row.getByRole('button', { name: '撤销分享', exact: true }).click()
    await page.getByRole('dialog').getByRole('button', { name: '撤销分享', exact: true }).click()
    await expect(row).toHaveAttribute('data-status', 'REVOKED')
    await openSecret(viewer, link)
    await expect(viewer.getByTestId('share-status')).toHaveAttribute(
      'data-status',
      'RETRY_REQUIRED'
    )
    await expect(viewer.getByTestId('dashboard-canvas')).toHaveCount(0)
  } finally {
    link = ''
    await anonymous?.close()
    // 剪贴板是用户明确复制的输出；测试结束清自己的夹具链接。
    await page.bringToFront()
    await page.evaluate(() => navigator.clipboard.writeText(''))
  }
  await page.getByTestId('sharing-refresh').click()
  await page.getByRole('button', { name: '选择分享版本 1', exact: true }).click()
  // 真实服务完成201后仅丢弃传输；禁止伪造签发成功JSON或直接插数据库。
  let dropped = false
  let initialKey = '',
    initialBody = '',
    replayMatched = false
  await page.route(/\/dashboards\/[^/]+\/shares$/, async (route) => {
    if (route.request().method() !== 'POST') return route.continue()
    const key = route.request().headers()['idempotency-key'] ?? ''
    const body = route.request().postData() ?? ''
    if (dropped) {
      replayMatched = !!key && key === initialKey && body === initialBody
      return route.continue()
    }
    initialKey = key
    initialBody = body
    dropped = true
    const response = await route.fetch()
    const status = response.status()
    await response.dispose()
    await route.abort('connectionreset')
    if (status !== 201) throw new Error('未知结果夹具未取得真实首次签发')
  })
  await create(page)
  await expect(page.getByTestId('sharing-retry')).toBeVisible()
  await page.getByTestId('sharing-retry').click()
  await expect(page.getByTestId('sharing-unrecoverable')).toBeVisible()
  expect(replayMatched).toBe(true)
  initialKey = ''
  initialBody = ''
  await expect(page.getByTestId('sharing-copy')).toHaveCount(0)
  await page
    .getByTestId('sharing-unrecoverable')
    .getByRole('button', { name: '撤销无法恢复的分享', exact: true })
    .click()
  await page.getByRole('dialog').getByRole('button', { name: '撤销分享', exact: true }).click()
  await expect(page.getByTestId('sharing-unrecoverable')).toHaveCount(0)
  await expect(page.getByRole('dialog')).toHaveCount(0)
  // 仅在凭据已清且撤销结束后保存无凭据界面；失败自动产物始终关闭。
  await page.screenshot({ path: 'test-results/dashboard-share-management.png', fullPage: true })
})
async function create(page: Page) {
  await page.getByTestId('sharing-create').click()
  await page.getByRole('dialog').getByRole('button', { name: '创建分享', exact: true }).click()
}
async function openSecret(page: Page, link: string) {
  // 每次重新启动匿名入口，避免同文档fragment导航不重新捕获凭据。
  await page.goto('about:blank')
  // 避免goto的失败调用日志保存带token URL；导航后仅检查固定测试标识。
  await page
    .evaluate((value) => {
      location.assign(value)
    }, link)
    .catch(() => undefined)
}
