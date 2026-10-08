import { expect, test } from '@playwright/test'
import { enterProject, login } from './helpers'

test.use({ trace: 'off', video: 'off', screenshot: 'off' })

test('应用草稿经工作区入口离开保留取消确认，最近访问重新读取真实远端', async ({ page }) => {
  test.skip(!process.env.E2E_OWNER_EMAIL || !process.env.E2E_OWNER_PASSWORD, '需要独占测试账号')
  await login(page, process.env.E2E_OWNER_EMAIL!, process.env.E2E_OWNER_PASSWORD!)
  await enterProject(page, 'E2E项目')
  await page.goto('/#/dashboard/applications')
  const name = `工作区草稿验收-${Date.now()}`
  await page.getByLabel('管理名称', { exact: true }).fill(name)
  const [created] = await Promise.all([
    page.waitForResponse(
      (response) =>
        response.request().method() === 'POST' &&
        /\/api\/v1\/projects\/[^/]+\/applications$/.test(new URL(response.url()).pathname)
    ),
    page.getByTestId('application-create').click()
  ])
  expect(created.status()).toBe(201)
  const { id } = (await created.json()) as { id: string }
  expect(id).toMatch(/^[0-9a-f-]{36}$/i)
  const projectId = new URL(created.url()).pathname.split('/')[4]
  const draft = page.getByRole('region', { name: '应用草稿', exact: true })
  await expect(draft).toHaveAttribute('data-application-id', id)
  await expect(page.getByLabel('公开展示名', { exact: true })).toHaveValue(name)

  const unexpectedWrites: string[] = []
  page.on('request', (request) => {
    if (
      request.url().includes(`/applications/${id}`) &&
      ['POST', 'PUT', 'PATCH', 'DELETE'].includes(request.method())
    )
      unexpectedWrites.push(request.method())
  })
  const unsaved = `${name}-尚未保存`
  await page.getByLabel('公开展示名', { exact: true }).fill(unsaved)
  await expect(draft).toContainText('未保存')
  const dashboardLink = page
    .getByRole('navigation', { name: '应用管理关联入口', exact: true })
    .getByRole('button', { name: '看板开发', exact: true })
  await dashboardLink.click()
  const confirmation = page.getByRole('dialog', { name: '离开应用编辑', exact: true })
  await expect(confirmation).toBeVisible()
  await confirmation.getByRole('button', { name: '取消', exact: true }).click()
  await expect(confirmation).toBeHidden()
  await expect(page).toHaveURL(/\/dashboard\/applications$/)
  await expect(page.getByLabel('公开展示名', { exact: true })).toHaveValue(unsaved)
  await expect(draft).toContainText('未保存')

  await dashboardLink.click()
  await confirmation.getByRole('button', { name: '确定', exact: true }).click()
  await expect(page).toHaveURL(/\/dashboard\/designer$/)
  await expect(page.getByRole('heading', { name: '看板开发', exact: true })).toBeVisible()

  const applicationPath = `/#/dashboard/applications?resourceId=${id}&contextProjectId=${projectId}`
  const [reloaded] = await Promise.all([
    page.waitForResponse(
      (response) =>
        response.request().method() === 'GET' &&
        new URL(response.url()).pathname.endsWith(`/applications/${id}/draft`)
    ),
    page.goto(applicationPath)
  ])
  expect(reloaded.status()).toBe(200)
  await expect(draft).toHaveAttribute('data-application-id', id)
  await expect(page.getByLabel('公开展示名', { exact: true })).toHaveValue(name)
  await expect(draft).toContainText('已读取/保存')
  await page.goto('/#/dashboard/workbench')
  const recent = page.getByRole('region', { name: '本机最近访问', exact: true })
  await expect(recent.getByRole('button', { name: new RegExp(name) })).toBeVisible()
  const [reopened] = await Promise.all([
    page.waitForResponse(
      (response) =>
        response.request().method() === 'GET' &&
        new URL(response.url()).pathname.endsWith(`/applications/${id}/draft`)
    ),
    recent.getByRole('button', { name: new RegExp(name) }).click()
  ])
  expect(reopened.status()).toBe(200)
  await expect(draft).toHaveAttribute('data-application-id', id)
  await expect(page.getByLabel('公开展示名', { exact: true })).toHaveValue(name)
  expect(unexpectedWrites).toEqual([])
})
