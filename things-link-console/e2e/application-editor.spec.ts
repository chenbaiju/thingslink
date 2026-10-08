import { expect, test, type Page } from '@playwright/test'
import {
  ensureViewerMember,
  login,
  enterProject,
  resetSession,
  OWNER_EMAIL,
  OWNER_PASSWORD,
  MEMBER_EMAIL,
  MEMBER_PASSWORD
} from './helpers'

/** S12-4m：真实菜单/HTTP/PG和生产看板发布；不mock草稿或用SQL插版本。 */
test('应用组合：创建、固定版本排序入口、保存重开、CAS冲突与只读隔离', async ({ page }) => {
  test.setTimeout(300_000)
  page.setDefaultTimeout(20_000)
  await page.setViewportSize({ width: 1600, height: 1000 })
  await login(page, OWNER_EMAIL, OWNER_PASSWORD)
  await enterProject(page, 'E2E项目')
  const first = await dashboard(page, '组合看板一')
  const second = await dashboard(page, '组合看板二')
  await page.goto('/#/dashboard/applications')
  const catalog = page.getByRole('region', { name: '应用目录' })
  await expect(catalog.getByRole('heading', { name: '应用目录', exact: true })).toBeVisible()
  await page.getByLabel('管理名称', { exact: true }).fill('组合验收应用')
  await page.getByRole('button', { name: '创建应用', exact: true }).click()
  const draft = page.getByRole('region', { name: '应用草稿' })
  await expect(draft).toHaveAttribute('data-application-id', /^[a-f0-9-]{36}$/)
  const id = (await draft.getAttribute('data-application-id'))!
  const application = catalog
    .getByRole('listitem')
    .filter({ has: page.getByTestId(`application-open-${id}`) })
  await expect(page.getByLabel('公开展示名')).toHaveValue('组合验收应用')
  await page.getByLabel('公开展示名').fill('公开组合应用')
  await page.getByRole('button', { name: '读取看板目录', exact: true }).click()
  for (const [value, label] of [
    [first, '导航一'],
    [second, '导航二']
  ] as const) {
    await page.getByLabel('看板', { exact: true }).selectOption(value.dashboardId)
    await expect(page.getByLabel('不可变版本')).toContainText('版本 1')
    await page.getByLabel('不可变版本').selectOption(value.versionId)
    await page.getByLabel('新导航标题').fill(label)
    await page.getByRole('button', { name: '添加或替换引用' }).click()
  }
  await page.getByRole('button', { name: '上移 2', exact: true }).click()
  await expect(page.getByLabel('导航标题 1', { exact: true })).toHaveValue('导航二')
  await page.getByLabel('入口看板').selectOption(second.dashboardId)
  await save(page)
  await page.reload()
  await page.getByRole('button', { name: '读取应用目录', exact: true }).click()
  await application.getByRole('button', { name: '编辑', exact: true }).click()
  await expect(page.getByLabel('公开展示名')).toHaveValue('公开组合应用')
  await expect(page.getByLabel('导航标题 1', { exact: true })).toHaveValue('导航二')
  await expect(page.getByLabel('入口看板')).toHaveValue(second.dashboardId)
  await expect(draft).toContainText(first.versionId)
  // 第二个真实HTTP客户端保存，制造真实CAS冲突。
  const concurrent = await page.evaluate(async (applicationId) => {
    const module = '/src/store/modules/user.ts'
    const { useUserStore } = await import(module)
    const user = useUserStore()
    const url = `/api/v1/projects/${user.info.currentProjectId}/applications/${applicationId}/draft`
    const headers = { Authorization: `Bearer ${user.accessToken}` }
    const response = await fetch(url, { headers })
    if (!response.ok) throw Error('竞争读取失败')
    const value = await response.json()
    value.content.displayName = '另一客户端已保存'
    const result = await fetch(url, {
      method: 'PUT',
      headers: {
        ...headers,
        'Content-Type': 'application/json',
        'Idempotency-Key': crypto.randomUUID()
      },
      body: JSON.stringify({ expectedRevision: value.revision, content: value.content })
    })
    return result.status
  }, id)
  expect(concurrent).toBe(200)
  await page.getByLabel('公开展示名').fill('冲突时本地保留')
  const rejected = page.waitForResponse(
    (r) => r.request().method() === 'PUT' && r.url().endsWith(`/applications/${id}/draft`)
  )
  await page.getByRole('button', { name: '保存应用草稿' }).click()
  expect((await rejected).status()).toBe(409)
  await expect(page.getByLabel('公开展示名')).toHaveValue('冲突时本地保留')
  await expect(page.getByRole('button', { name: '保存应用草稿' })).toBeDisabled()
  await page.getByRole('button', { name: '读取远端比较' }).click()
  await expect(page.locator('pre').first()).toContainText('另一客户端已保存')
  await page.getByRole('button', { name: '丢弃本地并重载远端' }).click()
  await page.getByRole('dialog').getByRole('button', { name: '确定' }).click()
  await expect(page.getByLabel('公开展示名')).toHaveValue('另一客户端已保存')
  await expect(page.getByRole('dialog')).toBeHidden()
  await catalog.getByRole('heading', { name: '应用目录', exact: true }).scrollIntoViewIfNeeded()
  await page.screenshot({ path: test.info().outputPath('application-editor.png'), fullPage: true })
  // 真实VIEWER写入边界，不能仅证明按钮隐藏。
  // 这里只准备只读角色；同一矩阵可已存在成员，不重复制造待接受邀请。
  const projectId = await page.evaluate(async () => {
    const path = '/src/store/modules/user.ts'
    const { useUserStore } = await import(path)
    return useUserStore().info.currentProjectId as string
  })
  await ensureViewerMember(page, projectId, MEMBER_EMAIL)
  await resetSession(page)
  await login(page, MEMBER_EMAIL, MEMBER_PASSWORD)
  await enterProject(page, 'E2E项目')
  await page.goto('/#/dashboard/applications')
  await page.getByRole('button', { name: '读取应用目录', exact: true }).click()
  await application.getByRole('button', { name: '查看', exact: true }).click()
  await expect(page.getByLabel('公开展示名')).toBeDisabled()
  await expect(page.getByRole('button', { name: '保存应用草稿' })).toHaveCount(0)
  const forbidden = await page.evaluate(async (applicationId) => {
    const module = '/src/store/modules/user.ts'
    const { useUserStore } = await import(module)
    const user = useUserStore()
    const headers = {
      Authorization: `Bearer ${user.accessToken}`,
      'Content-Type': 'application/json',
      'Idempotency-Key': crypto.randomUUID()
    }
    const url = `/api/v1/projects/${user.info.currentProjectId}/applications/${applicationId}/draft`
    const value = await (await fetch(url, { headers })).json()
    return (
      await fetch(url, {
        method: 'PUT',
        headers,
        body: JSON.stringify({ expectedRevision: value.revision, content: value.content })
      })
    ).status
  }, id)
  expect(forbidden).toBe(403)
})
async function save(page: Page) {
  const response = page.waitForResponse(
    (r) =>
      r.request().method() === 'PUT' &&
      /\/applications\/[^/]+\/draft$/.test(new URL(r.url()).pathname)
  )
  await page.getByRole('button', { name: '保存应用草稿' }).click()
  expect((await response).status()).toBe(200)
  await expect(page.getByRole('button', { name: '保存应用草稿' })).toBeDisabled()
}
async function dashboard(page: Page, name: string) {
  await page.goto('/#/dashboard/designer')
  await page.getByTestId('dashboard-create').click()
  await page.getByTestId('dashboard-name').fill(name)
  await page.getByTestId('dashboard-create-confirm').click()
  await expect(page).toHaveURL(/dashboardId=/)
  const dashboardId = new URLSearchParams(new URL(page.url()).hash.split('?')[1]).get(
    'dashboardId'
  )!
  await page.getByTestId('designer-add-text').click()
  await page.getByTestId('designer-text-content').fill(name)
  await expect(page.getByTestId('designer-save-state')).toHaveAttribute('data-status', 'saved')
  await page.getByTestId('publication-publish').click()
  await page.getByRole('dialog').getByRole('button', { name: '发布', exact: true }).click()
  await expect(page.getByTestId('publication-status')).toHaveAttribute(
    'data-version-id',
    /^[a-f0-9-]{36}$/
  )
  return {
    dashboardId,
    versionId: (await page.getByTestId('publication-status').getAttribute('data-version-id'))!
  }
}
