import { expect, test } from '@playwright/test'
import { login, enterProject, OWNER_EMAIL, OWNER_PASSWORD } from './helpers'

test('终端用户预置后刷新找回，显式分配、修改、停用和恢复项目角色', async ({ page }) => {
  test.skip(!process.env.E2E_END_USER_PROJECT_NAME, '需要独立已配置套餐的终端用户管理项目')
  await login(page, OWNER_EMAIL, OWNER_PASSWORD)
  await enterProject(page, process.env.E2E_END_USER_PROJECT_NAME!)
  await page.getByRole('menuitem', { name: '应用与看板', exact: true }).click()
  await expect(page.getByRole('menuitem', { name: '终端用户', exact: true })).toBeVisible()
  await page.getByRole('menuitem', { name: '终端用户', exact: true }).click()
  const username = `console-user-${Date.now()}`
  const initialPassword = 'console-enduser-123'
  const urls: string[] = [],
    messages: string[] = []
  page.on('request', (request) => urls.push(request.url()))
  page.on('console', (message) => messages.push(message.text()))
  await page.getByLabel('精确用户名', { exact: true }).fill(username)
  await page.getByLabel('显示名称', { exact: true }).fill('控制台终端用户验收')
  await page.getByLabel('初始口令', { exact: true }).fill(initialPassword)
  await expect(page.getByRole('button', { name: '预置账号', exact: true })).toBeEnabled()
  await page.getByRole('button', { name: '预置账号', exact: true }).click()
  await expect(page).toHaveURL(/project\/end-users/)
  await expect(page.locator('.end-user-selection')).toContainText('尚未分配')
  await expect(page.getByLabel('初始口令', { exact: true })).toHaveValue('')
  await page.reload()
  await expect(page.locator('.end-user-selection')).toHaveCount(0)
  await page.getByLabel('精确用户名', { exact: true }).fill(username)
  await page.getByRole('button', { name: '查找账号', exact: true }).click()
  const selected = page.locator('.end-user-selection')
  await expect(selected).toContainText(username)
  await expect(selected).toContainText('尚未分配')
  const confirm = async () =>
    page.getByRole('dialog').getByRole('button', { name: '确定', exact: true }).click()
  await selected.getByRole('button', { name: '分配项目角色', exact: true }).click()
  await confirm()
  await expect(selected).toContainText('项目角色状态：ACTIVE')
  await selected.locator('.el-select').click()
  await page.getByRole('option', { name: 'MAINTAINER', exact: true }).click()
  await selected.getByRole('button', { name: '修改项目角色', exact: true }).click()
  await confirm()
  await expect(selected).toContainText('本项目角色：MAINTAINER')
  await selected.getByRole('button', { name: '停用项目角色', exact: true }).click()
  await expect(page.getByRole('dialog')).toContainText('恢复角色不会重新打开')
  await confirm()
  await expect(selected).toContainText('项目角色状态：DISABLED')
  await selected.getByRole('button', { name: '恢复项目角色', exact: true }).click()
  await confirm()
  await expect(selected).toContainText('项目角色状态：ACTIVE')
  await expect(page.locator('tr').filter({ hasText: username })).toContainText('MAINTAINER')
  const stored = await page.evaluate(() => ({
    local: { ...localStorage },
    session: { ...sessionStorage },
    location: location.href
  }))
  expect(JSON.stringify(stored)).not.toContain(initialPassword)
  expect(urls.join('\n')).not.toContain(initialPassword)
  expect(messages.join('\n')).not.toContain(initialPassword)
})
