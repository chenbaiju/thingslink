import { expect, test, type Page } from '@playwright/test'
import { login, enterProject, OWNER_EMAIL, OWNER_PASSWORD } from './helpers'

// App会话夹具含刷新凭据；网络trace、视频和失败截图不保留这些响应。
test.use({ trace: 'off', video: 'off', screenshot: 'off' })
interface RuntimeProbe {
  current: number
  schema: number
}
interface GrantFixture {
  probe(): Promise<RuntimeProbe>
  compete(): Promise<number>
  suspend(): Promise<number>
  close(): Promise<number>
}
declare global {
  interface Window {
    grantFixture?: GrantFixture
  }
}

test('用户READ授权：真实App可见性、CAS竞争、原键恢复与停用历史', async ({ page }) => {
  test.setTimeout(180_000)
  await page.setViewportSize({ width: 1920, height: 1080 })
  await login(page, OWNER_EMAIL, OWNER_PASSWORD)
  await enterProject(page, process.env.E2E_END_USER_PROJECT_NAME || 'E2E项目')
  await page.goto('/#/dashboard/designer')
  await page.getByTestId('dashboard-create').click()
  await page.getByTestId('dashboard-name').fill('用户读取授权验收')
  await page.getByTestId('dashboard-create-confirm').click()
  await expect(page).toHaveURL(/dashboardId=/)
  await page.getByTestId('designer-add-text').click()
  await page.getByTestId('designer-text-content').fill('需显式用户READ授权')
  await expect(page.getByTestId('designer-save-state')).toHaveAttribute('data-status', 'saved')
  await page.getByTestId('publication-publish').click()
  await page.getByRole('dialog').getByRole('button', { name: '发布', exact: true }).click()
  await expect(page.getByTestId('publication-status')).toHaveAttribute(
    'data-version-id',
    /^[a-f0-9-]{36}$/
  )
  const versionId = await page.getByTestId('publication-status').getAttribute('data-version-id')
  if (!versionId) throw new Error('未取得真实发布版本')
  const username = `grant_user_${Date.now()}`
  await setup(page, versionId, username)
  try {
    expect(await probe(page)).toEqual({ current: 404, schema: 404 })
    await page.getByTestId('grants-open').click()
    await page.getByRole('button', { name: `选择用户 ${username}`, exact: true }).click()
    await expect(page.getByTestId('grants-grant')).toBeEnabled()
    await change(page, 'grant')
    await state(page, 'ACTIVE', '1')
    expect(await probe(page)).toEqual({ current: 200, schema: 200 })

    // 真实另一客户端先撤销，当前UI保留revision1；旧操作必须409而非覆盖。
    expect(await page.evaluate(() => window.grantFixture!.compete())).toBe(200)
    const conflict = page.waitForResponse(
      (response) =>
        response.request().method() === 'PUT' && response.url().includes('/dashboard-grants/')
    )
    await change(page, 'revoke')
    expect((await conflict).status()).toBe(409)
    await expect(page.getByTestId('grants-grant')).toBeDisabled()
    await page
      .getByTestId('grants-dialog')
      .getByRole('button', { name: '关闭', exact: true })
      .click()
    await page.getByTestId('grants-open').click()
    await page.getByRole('button', { name: `选择用户 ${username}`, exact: true }).click()
    await state(page, 'REVOKED', '2')
    expect(await probe(page)).toEqual({ current: 404, schema: 404 })
    await change(page, 'grant')
    await state(page, 'ACTIVE', '3')
    expect(await probe(page)).toEqual({ current: 200, schema: 200 })

    let dropped = false,
      originalKey = '',
      originalBody = '',
      sameIntent = false
    await page.route(/\/dashboard-grants\/[^/]+$/, async (route) => {
      if (route.request().method() !== 'PUT') return route.continue()
      const key = route.request().headers()['idempotency-key'] ?? '',
        body = route.request().postData() ?? ''
      if (dropped) {
        sameIntent = !!key && key === originalKey && body === originalBody
        return route.continue()
      }
      originalKey = key
      originalBody = body
      dropped = true
      const response = await route.fetch(),
        status = response.status()
      await response.dispose()
      await route.abort('connectionreset')
      if (status !== 200) throw new Error('未知授权夹具未取得真实成功')
    })
    await change(page, 'revoke')
    await expect(page.getByTestId('grants-retry')).toBeVisible()
    // 弹窗关闭清旧视图，仍保留原意图，重开后恢复而不制造第二次写。
    await page
      .getByTestId('grants-dialog')
      .getByRole('button', { name: '关闭', exact: true })
      .click()
    await page.getByTestId('grants-open').click()
    const replay = page.waitForResponse(
      (response) =>
        response.request().method() === 'PUT' && response.url().includes('/dashboard-grants/')
    )
    await page.getByTestId('grants-retry').click()
    expect((await replay).status()).toBe(409)
    await state(page, 'REVOKED', '4')
    expect(sameIntent).toBe(true)
    originalKey = ''
    originalBody = ''
    await page.unroute(/\/dashboard-grants\/[^/]+$/)
    expect(await probe(page)).toEqual({ current: 404, schema: 404 })
    await page.getByRole('button', { name: `选择用户 ${username}`, exact: true }).click()
    await change(page, 'grant')
    await state(page, 'ACTIVE', '5')
    expect(await page.evaluate(() => window.grantFixture!.suspend())).toBe(204)
    await page.getByTestId('grants-refresh-users').click()
    await page.getByRole('button', { name: `选择用户 ${username}`, exact: true }).click()
    await state(page, 'ACTIVE', '5')
    await expect(page.getByTestId('grants-grant')).toBeDisabled()
    await expect(page.getByTestId('grants-revoke')).toBeDisabled()
    await expect(page.getByTestId('grants-dialog')).toContainText('非有效状态，仅查看历史')
    await page.screenshot({ path: 'test-results/dashboard-grants.png', fullPage: true })
  } finally {
    const logout = await page.evaluate(async () => {
      const result = await window.grantFixture?.close()
      delete window.grantFixture
      return result
    })
    expect(logout).toBe(204)
  }
})
async function change(page: Page, action: 'grant' | 'revoke') {
  await page.getByTestId(`grants-${action}`).click()
  await page
    .getByRole('dialog')
    .filter({ hasText: action === 'grant' ? '确认授予' : '确认撤销' })
    .getByRole('button', {
      name: action === 'grant' ? '授予读取权限' : '撤销读取权限',
      exact: true
    })
    .click()
}
async function state(page: Page, status: string, revision: string) {
  await expect(page.getByTestId('grants-status')).toHaveAttribute('data-status', status)
  await expect(page.getByTestId('grants-status')).toHaveAttribute('data-revision', revision)
}
async function probe(page: Page) {
  return page.evaluate(() => window.grantFixture!.probe())
}
async function setup(page: Page, dashboardVersionId: string, username: string) {
  const projectKey = process.env.E2E_PROJECT_KEY
  if (!projectKey) throw new Error('缺少隔离项目标识')
  await page.evaluate(
    async (input) => {
      const path = '/src/store/modules/user.ts'
      const { useUserStore } = await import(path),
        user = useUserStore()
      const projectId = user.info.currentProjectId
      const dashboardId = new URLSearchParams(location.hash.split('?')[1]).get('dashboardId')
      const base = `/api/v1/projects/${projectId}`
      const management = (url: string, method = 'GET', body?: unknown) =>
        fetch(url, {
          method,
          credentials: 'omit',
          cache: 'no-store',
          redirect: 'error',
          headers: {
            Authorization: `Bearer ${user.accessToken}`,
            ...(body === undefined
              ? {}
              : { 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID() })
          },
          body: body === undefined ? undefined : JSON.stringify(body)
        })
      const password = 'Grant-Test-12345!'
      const provision = await management(`${base}/end-users`, 'POST', {
        username: input.username,
        password,
        displayName: 'READ授权验收用户'
      })
      if (provision.status !== 200) throw new Error('终端用户预置失败')
      const target = await provision.json()
      const assigned = await management(`${base}/end-users/${target.id}/role`, 'POST', {
        role: 'OBSERVER'
      })
      if (assigned.status !== 204) throw new Error('隔离用户角色分配失败')
      const created = await management(`${base}/applications`, 'POST', {
        managementName: '授权验收应用',
        content: {
          formatVersion: 'tc.application/v1',
          displayName: '授权验收应用',
          hostCompatibility: { minInclusive: '1.0.0', maxExclusive: '1.0.1' },
          dashboardRefs: [
            { dashboardId, dashboardVersionId: input.dashboardVersionId, title: '用户读取看板' }
          ],
          entryDashboardId: dashboardId
        }
      })
      if (created.status !== 201) throw new Error('应用管理夹具创建失败')
      const application = await created.json()
      const published = await management(
        `${base}/applications/${application.id}/versions`,
        'POST',
        { expectedDraftRevision: '0', expectedPublicationRevision: '0' }
      )
      if (published.status !== 201) throw new Error('应用生产发布资格未通过')
      const version = await published.json()
      const signedIn = await fetch('/api/v1/app/auth/login', {
        method: 'POST',
        credentials: 'omit',
        cache: 'no-store',
        redirect: 'error',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ projectKey: input.projectKey, username: input.username, password })
      })
      if (signedIn.status !== 200) throw new Error('App独立会话夹具登录失败')
      const session = await signedIn.json()
      const grantPath = `${base}/end-users/${target.id}/dashboard-grants/${dashboardId}`
      const appBase = `/api/v1/app/applications/${application.appKey}`
      const schemaPath = `${appBase}/versions/${version.id}/dashboards/${input.dashboardVersionId}/schema?expectedPublicationRevision=1`
      window.grantFixture = {
        async probe() {
          const options: RequestInit = {
            credentials: 'omit',
            cache: 'no-store',
            redirect: 'error',
            headers: { Authorization: `Bearer ${session.accessToken}` }
          }
          const current = await fetch(`${appBase}/current`, options),
            schema = await fetch(schemaPath, options)
          if (current.ok) {
            const value = await current.json()
            if (
              !Array.isArray(value.dashboards) ||
              !value.dashboards.some(
                (item: { dashboardId?: string }) => item.dashboardId === dashboardId
              )
            )
              throw new Error('App当前入口未返回目标授权看板')
          }
          if (schema.ok) {
            const value = await schema.json()
            if (
              value.dashboardId !== dashboardId ||
              !JSON.stringify(value.schema).includes('需显式用户READ授权')
            )
              throw new Error('App未读取到目标精确看板Schema')
          }
          return { current: current.status, schema: schema.status }
        },
        async compete() {
          const response = await management(grantPath)
          if (response.status !== 200) throw new Error('竞争前授权事实读取失败')
          const grant = await response.json()
          return (
            await management(grantPath, 'PUT', {
              expectedRevision: grant.revision,
              status: 'REVOKED'
            })
          ).status
        },
        async suspend() {
          return (await management(`${base}/end-users/${target.id}/suspend`, 'POST')).status
        },
        async close() {
          return (
            await fetch('/api/v1/app/auth/logout', {
              method: 'POST',
              credentials: 'omit',
              cache: 'no-store',
              headers: { 'Content-Type': 'application/json' },
              body: JSON.stringify({ refreshToken: session.refreshToken })
            })
          ).status
        }
      }
    },
    { dashboardVersionId, username, projectKey }
  )
}
