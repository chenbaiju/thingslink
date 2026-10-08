import { expect, test } from '@playwright/test'
import { login, enterProject, OWNER_EMAIL, OWNER_PASSWORD } from './helpers'

test('用户看板授权目录：空显示名账号、复用授予撤销与停用历史', async ({ page }) => {
  test.skip(!process.env.E2E_END_USER_PROJECT_NAME, '需要独立当前候选及管理项目')
  test.setTimeout(180_000)
  await login(page, OWNER_EMAIL, OWNER_PASSWORD)
  await enterProject(page, process.env.E2E_END_USER_PROJECT_NAME!)
  await page.goto('/#/dashboard/designer')
  const name = `用户目录看板_${Date.now()}`
  await page.getByTestId('dashboard-create').click()
  await page.getByTestId('dashboard-name').fill(name)
  await page.getByTestId('dashboard-create-confirm').click()
  await expect(page).toHaveURL(/dashboardId=/)
  const dashboardId = new URLSearchParams(new URL(page.url()).hash.split('?')[1]).get(
    'dashboardId'
  )!
  const fixture = await page.evaluate(async () => {
    const apiPath = '/src/api/end-users.ts',
      storePath = '/src/store/modules/user.ts'
    const api = await import(apiPath),
      projectId = (await import(storePath)).useUserStore().info.currentProjectId!
    const username = `directory_grant_${Date.now()}`
    const account = await api.provisionEndUser(projectId, {
      username,
      password: 'directory-fixture-123'
    })
    if (account.displayName !== null || account.role !== null) throw new Error('预置可空合同不一致')
    await api.assignEndUserRole(projectId, account.id!, 'OBSERVER')
    return { username, appUserId: account.id!, projectId }
  })
  await page.goto('/#/project/end-users')
  await page.getByRole('textbox', { name: '精确用户名', exact: true }).fill(fixture.username)
  await page.getByRole('button', { name: '查找账号', exact: true }).click()
  const directory = page.locator('.end-user-dashboard-grants')
  await expect(directory).toContainText('当前页暂无授权记录')
  await directory
    .locator('li')
    .filter({ hasText: name })
    .getByRole('button', { name: '选择此看板', exact: true })
    .click()
  await directory.getByTestId('grants-open').click()
  await expect(page.getByTestId('grants-dialog')).toContainText(
    `目标用户：${fixture.username}（${fixture.username}）`
  )
  await expect(page.getByTestId('grants-refresh-users')).toHaveCount(0)
  await page.getByTestId('grants-grant').click()
  await page
    .getByRole('dialog', { name: '确认授予读取权限', exact: true })
    .getByRole('button', { name: '授予读取权限', exact: true })
    .click()
  await expect(page.getByTestId('grants-status')).toHaveAttribute('data-revision', '1')
  await page.getByTestId('grants-dialog').getByRole('button', { name: '关闭', exact: true }).click()
  const row = directory.locator(`li[data-dashboard-id="${dashboardId}"]`)
  await expect(row).toContainText('READ / ACTIVE · 修订 1')
  await row.getByRole('button', { name: '查看此看板授权', exact: true }).click()
  await directory.getByTestId('grants-open').click()
  await page.getByTestId('grants-revoke').click()
  await page
    .getByRole('dialog', { name: '确认撤销读取权限', exact: true })
    .getByRole('button', { name: '撤销读取权限', exact: true })
    .click()
  await expect(page.getByTestId('grants-status')).toHaveAttribute('data-status', 'REVOKED')
  await page.getByTestId('grants-dialog').getByRole('button', { name: '关闭', exact: true }).click()
  await expect(row).toContainText('READ / REVOKED · 修订 2')
  await page.getByRole('button', { name: '停用项目角色', exact: true }).click()
  await page.getByRole('dialog').getByRole('button', { name: '确定', exact: true }).click()
  await expect(page.getByRole('button', { name: '恢复项目角色', exact: true })).toBeVisible()
  await directory.getByTestId('grants-open').click()
  await expect(page.getByTestId('grants-status')).toHaveAttribute('data-revision', '2')
  await expect(page.getByTestId('grants-grant')).toBeDisabled()
  await expect(page.getByTestId('grants-revoke')).toBeDisabled()
  const facts = await page.evaluate(async ({ projectId, appUserId }) => {
    const path = '/src/api/dashboard-grants.ts'
    return (await import(path)).fetchUserDashboardGrants(projectId, appUserId)
  }, fixture)
  expect(facts.items).toContainEqual(
    expect.objectContaining({ dashboardId, status: 'REVOKED', revision: '2' })
  )
})
