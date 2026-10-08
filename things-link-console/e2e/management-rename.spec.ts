import { expect, test, type Page } from '@playwright/test'
import { enterProject, login, OWNER_EMAIL, OWNER_PASSWORD } from './helpers'

test.use({ trace: 'off', video: 'off', screenshot: 'off' })
async function snapshot(page: Page, kind: 'applications' | 'dashboards', id: string) {
  return page.evaluate(
    async ({ kind, id }) => {
      const userPath = '/src/store/modules/user.ts'
      const { useUserStore } = await import(userPath)
      const user = useUserStore()
      const base = `/api/v1/projects/${user.info.currentProjectId}/${kind}/${id}`
      const headers = { Authorization: `Bearer ${user.accessToken}` }
      const [catalog, draft] = await Promise.all([
        fetch(base, { headers }),
        fetch(`${base}/draft`, { headers })
      ])
      if (!catalog.ok || !draft.ok) throw new Error('无法读取真实管理合同')
      return { catalog: await catalog.json(), draft: await draft.json() }
    },
    { kind, id }
  )
}
async function rename(page: Page, name: string) {
  const dialog = page.getByRole('dialog', { name: '重命名管理名称', exact: true })
  await dialog.getByLabel('新的管理名称').fill(name)
  const [response] = await Promise.all([
    page.waitForResponse(
      (response) =>
        response.request().method() === 'PATCH' &&
        /\/(applications|dashboards)\/[^/]+$/.test(new URL(response.url()).pathname)
    ),
    dialog.getByRole('button', { name: '确认重命名', exact: true }).click()
  ])
  expect(response.status()).toBe(200)
  expect(response.request().postDataJSON()).toEqual({ managementName: name })
  await expect(dialog).toBeHidden()
}
test('目录重命名保持应用未保存草稿、远端修订与发布状态，并更新已有最近访问名称', async ({
  page
}) => {
  await login(page, OWNER_EMAIL, OWNER_PASSWORD)
  await enterProject(page, 'E2E项目')
  await page.goto('/#/dashboard/applications')
  const original = `管理名称验收-${Date.now()}`
  await page.getByLabel('管理名称', { exact: true }).fill(original)
  const [created] = await Promise.all([
    page.waitForResponse(
      (response) =>
        response.request().method() === 'POST' &&
        /\/applications$/.test(new URL(response.url()).pathname)
    ),
    page.getByTestId('application-create').click()
  ])
  expect(created.status()).toBe(201)
  const { id } = await created.json()
  await expect(page.getByLabel('公开展示名', { exact: true })).toHaveValue(original)
  await page.getByTestId('application-directory-refresh').click()
  await page.getByTestId(`application-open-${id}`).click()
  const before = await snapshot(page, 'applications', id)
  await page.getByLabel('公开展示名', { exact: true }).fill('仍未保存的公开展示名')
  await page.getByTestId(`application-rename-${id}`).click()
  const renamed = `${original}-已更名`
  await rename(page, renamed)
  await expect(page.getByTestId('application-management-name')).toContainText(renamed)
  await expect(page.getByLabel('公开展示名', { exact: true })).toHaveValue('仍未保存的公开展示名')
  await expect(page.getByRole('region', { name: '应用草稿', exact: true })).toContainText('未保存')
  const after = await snapshot(page, 'applications', id)
  expect(after.draft).toEqual(before.draft)
  expect(after.catalog.managementName).toBe(renamed)
  expect(after.catalog.currentVersionId).toBe(before.catalog.currentVersionId)
  expect(after.catalog.publicationRevision).toBe(before.catalog.publicationRevision)
  await page
    .getByRole('navigation', { name: '应用管理关联入口' })
    .getByRole('button', { name: '看板开发', exact: true })
    .click()
  await page
    .getByRole('dialog', { name: '离开应用编辑' })
    .getByRole('button', { name: '确定', exact: true })
    .click()
  await expect(page).toHaveURL(/\/dashboard\/designer$/)
  await page.goto('/#/dashboard/workbench')
  await expect(page).toHaveURL(/\/dashboard\/workbench$/)
  await expect(
    page
      .getByRole('region', { name: '本机最近访问' })
      .getByRole('button', { name: new RegExp(renamed) })
  ).toBeVisible()
})

test('看板目录重命名仅更新管理名称，草稿内容和发布修订保持不变', async ({ page }) => {
  await login(page, OWNER_EMAIL, OWNER_PASSWORD)
  await enterProject(page, 'E2E项目')
  await page.goto('/#/dashboard/designer')
  await page.getByTestId('dashboard-create').click()
  const original = `看板名称验收-${Date.now()}`
  await page.getByTestId('dashboard-name').fill(original)
  await page.getByTestId('dashboard-create-confirm').click()
  await expect(page).toHaveURL(/dashboardId=/)
  const id = new URLSearchParams(new URL(page.url()).hash.split('?')[1]).get('dashboardId')!
  const before = await snapshot(page, 'dashboards', id)
  await page.getByTestId('designer-back').click()
  await page.getByTestId(`dashboard-rename-${id}`).click()
  const renamed = `${original}-已更名`
  await rename(page, renamed)
  const after = await snapshot(page, 'dashboards', id)
  expect(after.draft).toEqual(before.draft)
  expect(after.catalog.managementName).toBe(renamed)
  expect(after.catalog.currentVersionId).toBe(before.catalog.currentVersionId)
  expect(after.catalog.publicationRevision).toBe(before.catalog.publicationRevision)
  await page.getByTestId(`list-edit-${id}`).click()
  await expect(page.getByTestId('dashboard-management-name')).toContainText(renamed)
})
