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

test.use({ trace: 'off', video: 'off', screenshot: 'off' })

/** S12-4n：生产发布事实、指针CAS与真实提交后的响应丢失，不伪造业务响应。 */
test('应用发布：精确历史、回滚保留草稿、撤回、CAS及原键恢复', async ({ page }) => {
  test.setTimeout(240_000)
  page.setDefaultTimeout(20_000)
  await login(page, OWNER_EMAIL, OWNER_PASSWORD)
  await enterProject(page, 'E2E项目')
  await page.goto('/#/dashboard/designer')
  await page.getByTestId('dashboard-create').click()
  await page.getByTestId('dashboard-name').fill('应用发布引用')
  await page.getByTestId('dashboard-create-confirm').click()
  await expect(page).toHaveURL(/dashboardId=/)
  const dashboardId = new URLSearchParams(new URL(page.url()).hash.split('?')[1]).get(
    'dashboardId'
  )!
  await page.getByTestId('designer-add-text').click()
  await page.getByTestId('designer-text-content').fill('精确看板内容')
  await expect(page.getByTestId('designer-save-state')).toHaveAttribute('data-status', 'saved')
  await page.getByTestId('publication-publish').click()
  await confirm(page, '发布')
  await expect(page.getByTestId('publication-status')).toHaveAttribute(
    'data-version-id',
    /^[a-f0-9-]{36}$/
  )
  const dashboardVersionId = (await page
    .getByTestId('publication-status')
    .getAttribute('data-version-id'))!
  const applicationId = await page.evaluate(
    async (reference) => {
      const apiPath = '/src/api/application.ts',
        userPath = '/src/store/modules/user.ts'
      const api = await import(apiPath),
        { useUserStore } = await import(userPath)
      const created = await api.createApplication(
        useUserStore().info.currentProjectId,
        '发布验收应用',
        {
          formatVersion: 'tc.application/v1',
          displayName: '应用版本甲',
          hostCompatibility: { minInclusive: '1.0.0', maxExclusive: '2.0.0' },
          dashboardRefs: [{ ...reference, title: '固定导航' }],
          entryDashboardId: reference.dashboardId
        },
        crypto.randomUUID()
      )
      return created.id as string
    },
    { dashboardId, dashboardVersionId }
  )
  await page.goto('/#/dashboard/applications')
  const application = page
    .getByRole('region', { name: '应用目录' })
    .getByRole('listitem')
    .filter({ has: page.getByTestId(`application-open-${applicationId}`) })
  await page.getByRole('button', { name: '读取应用目录', exact: true }).click()
  await application.getByRole('button', { name: '编辑', exact: true }).click()
  const panel = page.getByRole('region', { name: '应用发布与历史恢复' })
  const status = page.getByTestId('application-publication-status')
  await expect(status).toHaveAttribute('data-version-id', '')
  await page.getByTestId('application-publication-publish').click()
  await confirm(page, '发布')
  await expect(status).toHaveAttribute('data-version-id', /^[a-f0-9-]{36}$/)
  const first = (await status.getAttribute('data-version-id'))!
  await page.getByLabel('公开展示名').fill('应用版本乙')
  await expect(page.getByTestId('application-publication-publish')).toBeDisabled()
  await page.getByRole('button', { name: '保存应用草稿' }).click()
  await expect(page.getByRole('button', { name: '保存应用草稿' })).toBeDisabled()
  await page.getByTestId('application-publication-publish').click()
  await confirm(page, '发布')
  await expect.poll(async () => status.getAttribute('data-version-id')).not.toBe(first)
  const second = (await status.getAttribute('data-version-id'))!
  await expect(panel.locator('[data-version-number]')).toHaveCount(2)
  await page.getByLabel('公开展示名').fill('未保存的本地内容')
  await panel.getByRole('button', { name: '回滚到版本 1', exact: true }).click()
  await confirm(page, '回滚')
  await expect(status).toHaveAttribute('data-version-id', first)
  await expect(page.getByLabel('公开展示名')).toHaveValue('未保存的本地内容')
  await panel.getByRole('button', { name: '查看版本 1', exact: true }).click()
  await expect(panel.getByRole('region', { name: '不可变版本详情' })).toContainText('应用版本甲')
  await expect(panel.getByRole('region', { name: '不可变版本详情' })).toContainText(
    dashboardVersionId
  )
  await page.getByTestId('application-publication-withdraw').click()
  await confirm(page, '撤回')
  await expect(status).toHaveAttribute('data-version-id', '')
  await panel.getByRole('button', { name: '回滚到版本 2', exact: true }).click()
  await confirm(page, '回滚')
  await expect(status).toHaveAttribute('data-version-id', second)
  await expect(panel.locator('[data-version-number]')).toHaveCount(2)
  // 第二客户端改动发布revision，旧页面撤回必须真实409，不能自动重试新修订。
  expect(
    await page.evaluate(async (id) => {
      const path = '/src/store/modules/user.ts',
        { useUserStore } = await import(path),
        user = useUserStore()
      const url = `/api/v1/projects/${user.info.currentProjectId}/applications/${id}`
      const headers = { Authorization: `Bearer ${user.accessToken}` }
      const catalog = await (await fetch(url, { headers })).json()
      return (
        await fetch(`${url}/withdraw`, {
          method: 'POST',
          headers: {
            ...headers,
            'Content-Type': 'application/json',
            'Idempotency-Key': crypto.randomUUID()
          },
          body: JSON.stringify({ expectedPublicationRevision: catalog.publicationRevision })
        })
      ).status
    }, applicationId)
  ).toBe(204)
  let conflictCode: number | undefined
  await page.route(
    `**/applications/${applicationId}/withdraw`,
    async (route) => {
      const response = await route.fetch({ maxRedirects: 0 })
      conflictCode = (await response.json()).code
      await route.fulfill({ response })
    },
    { times: 1 }
  )
  const conflict = page.waitForResponse(
    (r) =>
      r.request().method() === 'POST' && r.url().endsWith(`/applications/${applicationId}/withdraw`)
  )
  await page.getByTestId('application-publication-withdraw').click()
  await confirm(page, '撤回')
  const conflictResponse = await conflict
  expect(conflictResponse.status()).toBe(409)
  expect(conflictCode).toBe(60044)
  await expect(panel).toContainText('本次操作被明确拒绝')
  await page.getByTestId('application-publication-refresh').click()
  await expect(status).toHaveAttribute('data-version-id', '')
  await panel.getByRole('button', { name: '回滚到版本 1', exact: true }).click()
  await confirm(page, '回滚')
  await expect(status).toHaveAttribute('data-version-id', first)
  const keys: string[] = []
  const withdrawal = `**/applications/${applicationId}/withdraw`
  page.on('request', (r) => {
    if (r.method() === 'POST' && r.url().endsWith(`/applications/${applicationId}/withdraw`))
      keys.push(r.headers()['idempotency-key']!)
  })
  await page.route(withdrawal, async (route) => {
    const committed = await route.fetch({ maxRedirects: 0 })
    expect(committed.status()).toBe(204)
    await route.abort('failed')
  })
  await page.getByTestId('application-publication-withdraw').click()
  await confirm(page, '撤回')
  await expect(panel).toContainText('上次操作结果尚未确认')
  await expect(page.getByTestId('application-publication-withdraw')).toBeDisabled()
  await page.getByTestId('application-publication-refresh').click()
  await expect(status).toHaveAttribute('data-version-id', '')
  await expect(panel).toContainText('原操作结果仍未知')
  await page.unroute(withdrawal)
  await panel.getByRole('button', { name: '重试原操作', exact: true }).click()
  await expect(panel).toContainText('不是首次操作回执')
  expect(keys).toHaveLength(2)
  expect(keys[0]).toBeTruthy()
  expect(keys[1]).toBe(keys[0])
  await expect(page.getByLabel('公开展示名')).toHaveValue('未保存的本地内容')
  await page.screenshot({
    path: test.info().outputPath('application-publication.png'),
    fullPage: true
  })
  // 保留4m的显式丢弃边界，再用真实VIEWER验证读写隔离。
  await page.getByRole('button', { name: '丢弃本地并重载远端' }).click()
  await confirm(page, '确定')
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
  await expect(panel.locator('[data-version-number]')).toHaveCount(2)
  await expect(page.getByTestId('application-publication-publish')).toHaveCount(0)
  const forbidden = await page.evaluate(async (id) => {
    const path = '/src/store/modules/user.ts',
      { useUserStore } = await import(path),
      user = useUserStore()
    return (
      await fetch(`/api/v1/projects/${user.info.currentProjectId}/applications/${id}/versions`, {
        method: 'POST',
        headers: {
          Authorization: `Bearer ${user.accessToken}`,
          'Content-Type': 'application/json',
          'Idempotency-Key': crypto.randomUUID()
        },
        body: JSON.stringify({ expectedDraftRevision: '1', expectedPublicationRevision: '7' })
      })
    ).status
  }, applicationId)
  expect(forbidden).toBe(403)
})
async function confirm(page: Page, label: string) {
  await page.getByRole('dialog').getByRole('button', { name: label, exact: true }).click()
  await expect(page.getByRole('dialog')).toBeHidden()
}
