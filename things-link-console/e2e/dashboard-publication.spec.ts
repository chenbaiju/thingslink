import { expect, test, type Page } from '@playwright/test'
import { login, enterProject, OWNER_EMAIL, OWNER_PASSWORD } from './helpers'

/** 发布旅程使用Runner登记的真实宿主；所有版本由生产发布接口创建，不插发布数据。 */
test('看板发布：精确历史、回滚保留草稿、真实冲突与撤回', async ({ page }) => {
  test.setTimeout(180_000)
  await page.setViewportSize({ width: 1920, height: 1080 })
  await login(page, OWNER_EMAIL, OWNER_PASSWORD)
  await enterProject(page, 'E2E项目')
  await page.goto('/#/dashboard/designer')
  await page.getByTestId('dashboard-create').click()
  await page.getByTestId('dashboard-name').fill('发布恢复验收')
  await page.getByTestId('dashboard-create-confirm').click()
  await expect(page).toHaveURL(/dashboardId=/)
  await page.getByTestId('designer-add-text').click()
  await page.getByTestId('designer-text-content').fill('发布第一版')
  await saved(page)
  const first = await publish(page)
  await expect(page.getByTestId('publication-status')).toHaveAttribute('data-version-id', first.id)

  await page.getByTestId('designer-text-content').fill('保留在草稿中的第二版')
  await saved(page)
  const second = await publish(page)
  expect(second.id).not.toBe(first.id)
  const draftRevision = await page.locator('.revision').textContent()
  await expect(page.getByTestId('publication-status')).toHaveAttribute('data-version-id', second.id)
  await page.getByRole('button', { name: '查看版本 1', exact: true }).click()
  const detail = page.getByRole('region', { name: '不可变版本详情' })
  await expect(detail).toContainText('该版本只读')
  await detail.getByText('查看版本内容', { exact: true }).click()
  await expect(detail).toContainText('发布第一版')
  await page.getByRole('button', { name: '回滚到版本 1', exact: true }).click()
  await page.getByRole('dialog').getByRole('button', { name: '回滚', exact: true }).click()
  await expect(page.getByTestId('publication-status')).toHaveAttribute('data-version-id', first.id)
  await expect(page.getByTestId('designer-text-content')).toHaveValue('保留在草稿中的第二版')
  await expect(page.locator('.revision')).toHaveText(draftRevision!)

  // 另一客户端真实撤回，令本页publicationRevision过期；不用mock伪造CAS错误。
  const concurrent = await page.evaluate(async () => {
    const path = '/src/store/modules/user.ts'
    const { useUserStore } = await import(path)
    const user = useUserStore()
    const dashboardId = new URLSearchParams(location.hash.split('?')[1]).get('dashboardId')
    const url = `/api/v1/projects/${user.info.currentProjectId}/dashboards/${dashboardId}`
    const headers = { Authorization: `Bearer ${user.accessToken}` }
    const response = await fetch(url, { headers, cache: 'no-store' })
    if (!response.ok) throw new Error('竞争夹具目录读取失败')
    const catalog = await response.json()
    const withdrawn = await fetch(`${url}/withdraw`, {
      method: 'POST',
      headers: {
        ...headers,
        'Content-Type': 'application/json',
        'Idempotency-Key': crypto.randomUUID()
      },
      body: JSON.stringify({ expectedPublicationRevision: catalog.publicationRevision })
    })
    return withdrawn.status
  })
  expect(concurrent).toBe(204)
  const rejected = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      new URL(response.url()).pathname.endsWith('/versions')
  )
  await page.getByTestId('publication-publish').click()
  await page.getByRole('dialog').getByRole('button', { name: '发布', exact: true }).click()
  const conflict = await rejected
  expect(conflict.status()).toBe(409)
  await expect(page.getByRole('alert').filter({ hasText: '看板发布状态已变化' })).toBeVisible()
  await page.getByTestId('publication-refresh').click()
  await expect(page.getByTestId('publication-status')).toHaveAttribute('data-version-id', '')
  await expect(page.locator('[data-version-number]')).toHaveCount(2)
  await page.getByRole('button', { name: '回滚到版本 2', exact: true }).click()
  await page.getByRole('dialog').getByRole('button', { name: '回滚', exact: true }).click()
  await expect(page.getByTestId('publication-status')).toHaveAttribute('data-version-id', second.id)
  await page.getByTestId('publication-withdraw').click()
  await page.getByRole('dialog').getByRole('button', { name: '撤回', exact: true }).click()
  await expect(page.getByTestId('publication-status')).toHaveAttribute('data-version-id', '')
  await expect(page.locator('[data-version-number]')).toHaveCount(2)
  await expect(page.getByTestId('designer-text-content')).toHaveValue('保留在草稿中的第二版')
  await page.screenshot({ path: 'test-results/dashboard-publication.png', fullPage: true })
})
async function saved(page: Page) {
  await expect(page.getByTestId('designer-save-state')).toHaveAttribute('data-status', 'saved')
}
async function publish(page: Page): Promise<{ id: string }> {
  const before = await page.getByTestId('publication-status').getAttribute('data-version-id')
  const response = page.waitForResponse(
    (value) =>
      value.request().method() === 'POST' && new URL(value.url()).pathname.endsWith('/versions')
  )
  await page.getByTestId('publication-publish').click()
  await page.getByRole('dialog').getByRole('button', { name: '发布', exact: true }).click()
  const published = await response
  expect(published.status()).toBe(201)
  // 单发传输已消费并关闭流；从界面的权威重读确认结果，不二次读取CDP正文。
  const current = page.getByTestId('publication-status')
  await expect(current).toHaveAttribute('data-version-id', /^[a-f0-9-]{36}$/)
  await expect(current).not.toHaveAttribute('data-version-id', before ?? '')
  const id = await current.getAttribute('data-version-id')
  if (!id) throw new Error('发布后缺少当前精确版本')
  return { id }
}
