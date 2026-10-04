import { expect, test, type Page, type Request } from '@playwright/test'
import { login, enterProject, OWNER_EMAIL, OWNER_PASSWORD } from './helpers'

test.use({ trace: 'off', video: 'off', screenshot: 'off' })
test('动态文本：纯标签同步、默认恢复与静态动态完整重绑', async ({ page }) => {
  test.setTimeout(120_000)
  await page.setViewportSize({ width: 1920, height: 1080 })
  await login(page, OWNER_EMAIL, OWNER_PASSWORD)
  await enterProject(page, 'E2E项目')
  await page.goto('/#/dashboard/designer')
  await page.getByTestId('dashboard-create').click()
  await page.getByTestId('dashboard-name').fill(`动态文本验收${Date.now()}`)
  await page.getByTestId('dashboard-create-confirm').click()
  await expect(page).toHaveURL(/dashboardId=/)
  const unsafe = '<script>window.__dashboardTextE2E=1</script>'
  await page.getByRole('button', { name: '新建文本变量', exact: true }).click()
  await page.getByLabel('文本变量标题', { exact: true }).fill('展示状态')
  await page.getByLabel('第1项值', { exact: true }).fill('script')
  await page.getByLabel('第1项标签', { exact: true }).fill(unsafe)
  await page.getByRole('button', { name: '添加文本选项', exact: true }).click()
  await page.getByLabel('第2项值', { exact: true }).fill('normal')
  await page.getByLabel('第2项标签', { exact: true }).fill('正常运行')
  await page.getByLabel('文本默认选项', { exact: true }).selectOption('')
  await page.getByTestId('text-variable-save').click()
  await saved(page)
  await page.getByLabel('文本模式', { exact: true }).selectOption('DYNAMIC')
  const key = await page
    .getByLabel('文本绑定变量', { exact: true })
    .locator('option')
    .filter({ hasText: '展示状态' })
    .getAttribute('value')
  if (!key) throw new Error('文本变量未进入草稿')
  await page.getByLabel('文本绑定变量', { exact: true }).selectOption(key)
  await page.getByLabel('文本对齐', { exact: true }).selectOption('CENTER')
  await page.getByLabel('文本大小', { exact: true }).selectOption('LARGE')
  await page.getByLabel('文本色调', { exact: true }).selectOption('PRIMARY')
  for (let n = 0; n < 2; n++) {
    await page.getByTestId('text-component-add').click()
    await saved(page)
  }
  await expect(page.locator('[data-kind="TEXT"]')).toHaveCount(2)
  const id = await page.locator('[data-kind="TEXT"]').first().getAttribute('data-component-id')
  if (!id) throw new Error('缺少文本组件身份')
  await page.getByRole('button', { name: '编辑文本变量 展示状态', exact: true }).click()
  await expect(page.getByTestId('text-variable-delete')).toBeDisabled()
  // 草稿渲染会挂载发布/分享面板；其GET链晚于Schema，不归本地TEXT选择触发。
  const initialRequests: string[] = []
  const observeInitial = (request: Request) => {
    const entry = apiRequest(request)
    if (entry) initialRequests.push(entry)
  }
  page.on('request', observeInitial)
  await page.reload()
  await managementLoaded(page)
  page.off('request', observeInitial)
  await test.info().attach('text-preview-initial-api-requests', {
    body: JSON.stringify(initialRequests, null, 2),
    contentType: 'application/json'
  })
  const preview = page.getByRole('region', { name: '动态文本预览' })
  const choice = page.getByLabel('预览文本 展示状态', { exact: true })
  const rows = preview.locator('[data-preview-text]')
  await expect(choice).toHaveValue('')
  await expect(rows).toHaveCount(2)
  await expect(rows.first()).not.toContainText(unsafe)
  // 真实保存和加载完成后，单独观察本地运行选择，不允许产生任何API请求。
  const requests: string[] = []
  const observe = (request: Request) => {
    const entry = apiRequest(request)
    if (entry) requests.push(entry)
  }
  page.on('request', observe)
  await choice.selectOption('script')
  for (let n = 0; n < 2; n++) await expect(rows.nth(n)).toContainText(unsafe)
  await expect(preview.locator('script')).toHaveCount(0)
  expect(
    await page.evaluate(() => (window as unknown as Record<string, unknown>).__dashboardTextE2E)
  ).toBeUndefined()
  await choice.selectOption('normal')
  for (let n = 0; n < 2; n++) await expect(rows.nth(n)).toContainText('正常运行')
  expect(requests, '本地TEXT选择不得产生任何API请求（method + pathname）').toHaveLength(0)
  page.off('request', observe)
  await page.reload()
  await expect(choice).toHaveValue('')
  await page.getByRole('button', { name: '编辑文本变量 展示状态', exact: true }).click()
  await page.getByLabel('文本默认选项', { exact: true }).selectOption('normal')
  await page.getByTestId('text-variable-save').click()
  await saved(page)
  await page.reload()
  await expect(choice).toHaveValue('normal')
  for (let n = 0; n < 2; n++) await expect(rows.nth(n)).toContainText('正常运行')
  await page.getByTestId(`designer-component-${id}`).click()
  await page.getByLabel('文本模式', { exact: true }).selectOption('STATIC')
  await page.getByLabel('静态文本内容', { exact: true }).fill('静态 <b>正文</b>')
  await page.getByTestId('text-component-rebind').click()
  await saved(page)
  await page.reload()
  await expect(page.getByTestId(`designer-component-${id}`)).toContainText('静态 <b>正文</b>')
  const staticText = await persisted(page, id)
  expect(staticText.bindings).toEqual({})
  expect(staticText.props).toMatchObject({
    content: '静态 <b>正文</b>',
    align: 'CENTER',
    size: 'LARGE',
    tone: 'PRIMARY'
  })
  await page.getByTestId(`designer-component-${id}`).click()
  await page.getByLabel('文本模式', { exact: true }).selectOption('DYNAMIC')
  await page.getByLabel('文本绑定变量', { exact: true }).selectOption(key)
  await page.getByTestId('text-component-rebind').click()
  await saved(page)
  await page.reload()
  const dynamicText = await persisted(page, id)
  expect(dynamicText.bindings).toEqual({ text: { source: 'ENUM_TEXT', variableKey: key } })
  expect(Object.hasOwn(dynamicText.props, 'content')).toBe(false)
  await expect(choice).toHaveValue('normal')
  await expect(preview.locator(`[data-preview-text="${id}"]`)).toContainText('正常运行')
  await page.setViewportSize({ width: 375, height: 812 })
  await choice.selectOption('script')
  for (let n = 0; n < 2; n++) await expect(rows.nth(n)).toContainText(unsafe)
  expect(
    await page.evaluate(() => (window as unknown as Record<string, unknown>).__dashboardTextE2E)
  ).toBeUndefined()
  const dimensions = await preview.evaluate((element) => ({
    scroll: element.scrollWidth,
    client: element.clientWidth
  }))
  expect(dimensions.scroll).toBeLessThanOrEqual(dimensions.client + 1)
  await preview.screenshot({ path: 'test-results/dashboard-text.png' })
})
/** 仅保存方法与路径；请求头、查询参数和正文可能含凭据，不进入诊断附件。 */
function apiRequest(request: Request): string | undefined {
  const pathname = new URL(request.url()).pathname
  return pathname.startsWith('/api/v1/') ? `${request.method()} ${pathname}` : undefined
}
/** 新看板尚未发布/分享：等待两条实际管理加载链消费响应，不用networkidle或固定sleep。 */
async function managementLoaded(page: Page) {
  await saved(page)
  // publication.open顺序读取catalog→versions；catalog且!loading的空历史标志仅在链结束出现。
  await expect(
    page
      .getByRole('region', { name: '看板发布与历史恢复' })
      .getByText('暂无已发布历史版本。', { exact: true })
  ).toBeVisible()
  const sharing = page.getByRole('region', { name: '匿名只读分享管理' })
  // sharing.open先等configuration/list均终态，再启动自己的catalog→versions链。
  await expect(sharing.getByText('当前页没有分享记录。', { exact: true })).toBeVisible()
  await expect(sharing.getByText('暂无可选择的已发布版本。', { exact: true })).toBeVisible()
}
async function saved(page: Page) {
  await expect(page.getByTestId('designer-save-state')).toHaveAttribute('data-status', 'saved')
}
/** 使用真实HTTP读回已持久化草稿，不能只观察本地编辑状态。 */
async function persisted(page: Page, componentId: string) {
  return page.evaluate(async (id) => {
    const path = '/src/api/dashboard.ts',
      userPath = '/src/store/modules/user.ts'
    const api = await import(path),
      { useUserStore } = await import(userPath)
    const dashboardId = new URLSearchParams(location.hash.split('?')[1]).get('dashboardId')
    if (!dashboardId) throw new Error('缺少看板身份')
    const draft = await api.fetchDashboardDraft(useUserStore().info.currentProjectId, dashboardId)
    const schema = draft.content as {
      pages: {
        components: {
          id: string
          props: Record<string, unknown>
          bindings: Record<string, unknown>
        }[]
      }[]
    }
    const component = schema.pages
      .flatMap((page) => page.components)
      .find((component) => component.id === id)
    if (!component) throw new Error('已保存草稿缺少文本组件')
    return { props: component.props, bindings: component.bindings }
  }, componentId)
}
