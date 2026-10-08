import { expect, test, type Page } from '@playwright/test'
import {
  acceptProjectInvitationFromInbox,
  cleanupCreatedProject,
  enterProject,
  login,
  resetSession,
  OWNER_EMAIL,
  OWNER_PASSWORD,
  MEMBER_EMAIL,
  MEMBER_PASSWORD
} from './helpers'

let createdProjectName = ''
test.afterEach(async ({ browser, baseURL }) => {
  if (createdProjectName) await cleanupCreatedProject(browser, baseURL!, createdProjectName)
  createdProjectName = ''
})

/** S12-4a：所有草稿均经过真实Console授权HTTP；第二编辑页制造真实CAS竞争，不mock保存结果。 */
test('看板静态编辑：创建、拖拽调整、自动保存重开以及冲突保留本地', async ({ page, context }) => {
  test.setTimeout(240_000)
  await page.setViewportSize({ width: 1920, height: 1080 })
  const projectName = `设计器验收-${Date.now()}`
  createdProjectName = projectName
  await login(page, OWNER_EMAIL, OWNER_PASSWORD)
  await page.goto('/#/project/list')
  await page.getByRole('button', { name: '创建项目' }).click()
  await expect(page).toHaveURL(/#\/project\/create$/)
  await page.getByPlaceholder('请输入项目名称').fill(projectName)
  await page.getByRole('button', { name: '创建项目', exact: true }).click()
  await expect(page).toHaveURL(/#\/project\/list$/)
  await expect(page.getByText(projectName).first()).toBeVisible()
  await enterProject(page, projectName)
  // 后端菜单真实下发并注册；不临时注入前端路由绕过菜单资格。
  await page.goto('/#/dashboard/designer')
  await page.getByTestId('dashboard-create').click()
  await page.getByTestId('dashboard-name').fill('真实静态画布')
  const createdResponse = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      /\/api\/v1\/projects\/[^/]+\/dashboards$/.test(new URL(response.url()).pathname)
  )
  await page.getByTestId('dashboard-create-confirm').click()
  const created = await createdResponse
  expect(created.status()).toBe(201)
  const dashboard = await created.json()
  expect(dashboard.id).toMatch(/^[a-f0-9-]{36}$/)
  await expect(page).toHaveURL(new RegExp(`dashboardId=${dashboard.id}`))
  const editorUrl = page.url()

  await page.getByTestId('designer-add-text').click()
  await page.getByTestId('designer-text-content').fill('真实保存的中文文本')
  await saved(page)
  const text = page
    .locator('[data-testid^="designer-component-"]')
    .filter({ hasText: '真实保存的中文文本' })
    .first()
  const textId = await text.getAttribute('data-testid')
  expect(textId).toBeTruthy()
  const beforeDragY = await page.getByTestId('designer-y').inputValue()
  const box = await text.boundingBox()
  if (!box) throw new Error('文字画布没有可拖拽边界')
  // 真正指针拖拽，不只直接修改模型坐标冒充画布交互。
  await page.mouse.move(box.x + 12, box.y + 12)
  await page.mouse.down()
  await page.mouse.move(box.x + 64, box.y + 60, { steps: 8 })
  await page.mouse.up()
  await expect(page.getByTestId('designer-y')).not.toHaveValue(beforeDragY)
  await saved(page)
  await page.getByTestId('designer-w').fill('10')
  await page.getByTestId('designer-w').blur()
  await saved(page)
  await page.getByTestId('designer-add-image').click()
  await page.getByTestId('designer-y').fill('20')
  await page.getByTestId('designer-y').blur()
  await page.getByTestId('designer-x').fill('12')
  await page.getByTestId('designer-w').fill('8')
  await page.getByTestId('designer-h').fill('4')
  await page.getByTestId('designer-h').blur()
  await saved(page)
  await expect(page.locator('[data-testid^="designer-component-"]')).toHaveCount(2)
  await expect(page.locator('[data-testid^="designer-component-"] img')).toHaveCount(1)
  await expect
    .poll(() =>
      page
        .locator('[data-testid^="designer-component-"] img')
        .evaluate((image) => (image as HTMLImageElement).naturalWidth)
    )
    .toBeGreaterThan(0)

  await page.reload()
  await expect(page.getByTestId(textId!)).toContainText('真实保存的中文文本')
  await expect(page.locator('[data-testid^="designer-component-"]')).toHaveCount(2)
  await page.getByTestId(textId!).click()
  await expect(page.getByTestId('designer-w')).toHaveValue('10')

  // 撤销/重做恢复完整规范草稿；分页与两种布局必须能保存后重新载入。
  await page.getByTestId('designer-text-content').fill('撤销重做后的文字')
  await page.getByRole('button', { name: '撤销', exact: true }).click()
  await expect(page.getByTestId(textId!)).toContainText('真实保存的中文文本')
  await page.getByRole('button', { name: '重做', exact: true }).click()
  await expect(page.getByTestId(textId!)).toContainText('撤销重做后的文字')
  await saved(page)
  await page.getByRole('button', { name: '添加页面', exact: true }).click()
  await page.getByRole('button', { name: '页面 2', exact: true }).click()
  await expect(page.locator('[data-testid^="designer-component-"]')).toHaveCount(0)
  await page.getByLabel('页面标题', { exact: true }).fill('临时第二页')
  await page.getByLabel('页面标题', { exact: true }).blur()
  await saved(page)
  await page.reload()
  await expect(page.getByRole('button', { name: '临时第二页', exact: true })).toBeVisible()
  await page.getByRole('button', { name: '临时第二页', exact: true }).click()
  await page.getByRole('button', { name: '删除当前页', exact: true }).click()
  await saved(page)
  await page.getByLabel('画布模式', { exact: true }).selectOption('FIXED_SCREEN')
  await saved(page)
  await page.reload()
  await expect(page.getByLabel('画布模式', { exact: true })).toHaveValue('FIXED_SCREEN')
  await expect(page.getByTestId(textId!)).toContainText('撤销重做后的文字')
  await page.getByLabel('画布模式', { exact: true }).selectOption('RESPONSIVE_GRID')
  await saved(page)
  await page.getByTestId(textId!).click()

  // 两个普通浏览器编辑者读取同一revision，先完成B保存，再让A沿旧revision触发真实409。
  const other = await context.newPage()
  try {
    await other.goto(editorUrl)
    await expect(other.getByTestId(textId!)).toBeVisible()
    await other.getByTestId(textId!).click()
    await other.getByTestId('designer-text-content').fill('另一编辑页已保存')
    await saved(other)
    const conflictResponse = page.waitForResponse(
      (response) =>
        response.request().method() === 'PUT' &&
        new URL(response.url()).pathname.endsWith(`/dashboards/${dashboard.id}/draft`)
    )
    await page.getByTestId('designer-text-content').fill('本地冲突内容必须保留')
    expect((await conflictResponse).status()).toBe(409)
    await expect(page.getByTestId('designer-save-state')).toHaveAttribute('data-status', 'conflict')
    await expect(page.getByTestId('designer-text-content')).toHaveValue('本地冲突内容必须保留')
    await expect(page.getByTestId(textId!)).toContainText('本地冲突内容必须保留')
    await page.getByTestId('designer-reload-remote').click()
    await expect(page.getByTestId(textId!)).toContainText('另一编辑页已保存')
    await expect(page.getByTestId(textId!)).not.toContainText('本地冲突内容必须保留')
    await saved(page)
    await page.reload()
    await expect(page.getByTestId(textId!)).toContainText('另一编辑页已保存')
  } finally {
    await other.close()
  }

  await page.screenshot({ path: test.info().outputPath('dashboard-designer.png'), fullPage: true })

  // 服务端真实授予VIEWER；换账号后的只读页面不能借OWNER曾打开的编辑状态写入。
  await page.goto('/#/project/members')
  await page.getByRole('button', { name: '邀请成员' }).click()
  await page.getByPlaceholder('接收邀请的邮箱').fill(MEMBER_EMAIL)
  await page.locator('.el-dialog:visible .el-select').first().click()
  await page.getByText('VIEWER — 只读').last().click()
  await page.getByRole('button', { name: '确定' }).click()
  await expect(page.getByText(MEMBER_EMAIL).first()).toBeVisible()
  await resetSession(page)
  await login(page, MEMBER_EMAIL, MEMBER_PASSWORD)
  await acceptProjectInvitationFromInbox(page, projectName)
  await enterProject(page, projectName)
  await page.goto('/#/dashboard/designer')
  await expect(page.getByTestId('dashboard-create')).toHaveCount(0)
  await page.getByTestId(`list-edit-${dashboard.id}`).click()
  await expect(page.getByTestId(textId!)).toContainText('另一编辑页已保存')
  await expect(page.getByTestId('designer-add-text')).toBeDisabled()
  await expect(page.getByTestId('designer-add-image')).toBeDisabled()
  await page.getByTestId(textId!).click()
  await expect(page.getByTestId('designer-text-content')).toBeDisabled()
  await expect(page.getByTestId('designer-w')).toBeDisabled()
})

/** 保存完成必须观察真实编辑状态，不能用固定sleep估算网络或自动保存节流结束。 */
async function saved(page: Page) {
  await expect(page.getByTestId('designer-save-state')).toHaveAttribute('data-status', 'saved')
}
