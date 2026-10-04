import { expect, test } from '@playwright/test'
import { login, OWNER_EMAIL, OWNER_PASSWORD } from './helpers'

/**
 * 双标签共享旋转型 refresh Cookie：两个页面同时恢复会话时，请求必须串行，不能同时提交旧 Cookie
 * 触发后端复用检测并作废整族。核心认证 API 与 Cookie 轮换全部走真实后端。
 */
test('异常矩阵：401 同标签单飞且双标签刷新串行', async ({ page, context }) => {
  await login(page, OWNER_EMAIL, OWNER_PASSWORD)

  // 新标签不共享 Pinia 内存 access token，但共享 HttpOnly refresh Cookie 与同源持久状态；
  // 首次打开会走一次真实刷新，先等它稳定，再开始计数本轮并发刷新。
  const secondPage = await context.newPage()
  const initialRefresh = secondPage.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      new URL(response.url()).pathname === '/api/v1/auth/refresh',
    { timeout: 30_000 }
  )
  await secondPage.goto('/#/dashboard/overview')
  expect((await initialRefresh).status()).toBe(200)
  await secondPage.waitForURL((url) => !url.hash.includes('/auth/login'), { timeout: 30_000 })

  let refreshRequests = 0
  const refreshStatuses: number[] = []
  const isRefresh = (url: string, method: string) =>
    method === 'POST' && new URL(url).pathname === '/api/v1/auth/refresh'
  context.on('request', (request) => {
    if (!isRefresh(request.url(), request.method())) return
    refreshRequests++
  })
  context.on('response', (response) => {
    if (isRefresh(response.url(), response.request().method()))
      refreshStatuses.push(response.status())
  })

  // 整页刷新会清掉两个标签各自的内存 access token；二者随后同时用共享 Cookie 恢复会话。
  await Promise.all([page.reload(), secondPage.reload()])
  await expect.poll(() => refreshRequests, { timeout: 30_000 }).toBe(2)
  await expect.poll(() => refreshStatuses.length, { timeout: 30_000 }).toBe(2)
  expect(refreshStatuses).toEqual([200, 200])
  await expect(page).not.toHaveURL(/\/auth\/login/)
  await expect(secondPage).not.toHaveURL(/\/auth\/login/)
  await secondPage.close()
})
