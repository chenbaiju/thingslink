import { expect, test } from '@playwright/test'
import { readFile } from 'node:fs/promises'
import { createHash } from 'node:crypto'
import { resolve } from 'node:path'
import { login, enterProject, OWNER_EMAIL, OWNER_PASSWORD } from './helpers'

// 一次性秘密仅留在测试和浏览器内存，失败也不得捕获请求响应或输入像素。
test.use({ trace: 'off', video: 'off', screenshot: 'off' })
// 关闭页面早于Playwright自动失败上下文收集，避免密码输入落入ARIA文本快照。
test.afterEach(async ({ page }) => {
  await page.close()
})
test('产品凭据真实生成、关闭清除及轮换后动态注册', async ({ page }) => {
  test.skip(!process.env.E2E_PRODUCT_RUNTIME, '需要显式专用栈及冻结候选')
  const consoleMessages: string[] = []
  page.on('console', (event) => consoleMessages.push(event.text()))
  const runtime = JSON.parse(await readFile(process.env.E2E_PRODUCT_RUNTIME!, 'utf8'))
  if (
    !/^tc_console_/.test(runtime.database) ||
    runtime.postgresPort !== 5547 ||
    runtime.backendPort !== 8088 ||
    runtime.redisDatabase !== 14
  )
    throw new Error('拒绝非专用栈')
  const hash = createHash('sha256')
    .update(await readFile(runtime.frozenJar))
    .digest('hex')
  if (hash !== runtime.jarSha) throw new Error('冻结JAR已漂移')
  await login(page, OWNER_EMAIL, OWNER_PASSWORD)
  await enterProject(page, process.env.E2E_END_USER_PROJECT_NAME!)
  const fixture = await page.evaluate(async () => {
    const apiPath = '/src/api/device.ts',
      userPath = '/src/store/modules/user.ts'
    const api = await import(apiPath),
      user = (await import(userPath)).useUserStore()
    const projectId = user.info.currentProjectId!,
      suffix = Date.now(),
      name = `product_${suffix}`
    const type = await api.fetchCreateDeviceType(projectId, {
      typeKey: name,
      name,
      deviceKind: 'DIRECT',
      payloadProtocol: 'STANDARD',
      networkType: 'WIFI'
    })
    await api.fetchPublishDeviceType(projectId, type.id!)
    return { projectId, id: type.id!, name, suffix }
  })
  let productPosts = 0
  page.on('request', (request) => {
    if (
      request.method() === 'POST' &&
      request.url().endsWith(`/device-types/${fixture.id}/product-credential`)
    )
      productPosts++
  })
  await page.goto('/#/device/types')
  const row = page.getByRole('row').filter({ hasText: fixture.name })
  await expect(row).toBeVisible()
  await row.getByRole('button', { name: '产品凭据', exact: true }).click()
  const dialog = page.getByRole('dialog', { name: '一型一密产品凭据', exact: true })
  await expect(dialog).toContainText('尚未生成')
  await dialog.getByRole('button', { name: '生成产品凭据', exact: true }).click()
  await page.getByRole('button', { name: '确认生成', exact: true }).click()
  const input = dialog.getByLabel('产品注册秘密（仅本次可见）', { exact: true })
  await expect(input).toBeVisible()
  // 不使用带实际值诊断的toHaveValue；把秘密转存到易失内存后仅返回布尔事实。
  let oldSecret = await input.inputValue()
  expect(/^[0-9a-f]{64}$/.test(oldSecret)).toBe(true)
  // 显式隔离运行的失败探针：验证页面关闭后诊断不再包含秘密文本快照。
  if (process.env.E2E_PRODUCT_CAPTURE_PROBE === '1') throw new Error('受控失败捕获探针')
  const publicKey = await page.evaluate(async (f) => {
    const apiPath = '/src/api/device.ts',
      api = await import(apiPath)
    return (await api.fetchDeviceTypeDetail(f.projectId, f.id)).productKey!
  }, fixture)
  const register = async (secret: string, key: string) =>
    page.evaluate(
      async ({ secret, key, publicKey, projectKey }) => {
        const response = await fetch('/api/v1/emqx/register', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            projectKey,
            productKey: publicKey,
            productSecret: secret,
            deviceKey: key
          })
        })
        const result = await response.json()
        // 设备连接凭据不返回到Playwright诊断。
        return {
          status: response.status,
          code: result.code ?? null,
          hasToken: typeof result.accessToken === 'string'
        }
      },
      { secret, key, publicKey, projectKey: runtime.projectKey }
    )
  const first = await register(oldSecret, `product_first_${fixture.suffix}`)
  expect(first).toEqual({ status: 201, code: null, hasToken: true })
  expect(
    await page.evaluate(
      (secret) =>
        [...Object.values(localStorage), ...Object.values(sessionStorage)].some((v) =>
          v.includes(secret)
        ),
      oldSecret
    )
  ).toBe(false)
  await dialog.getByRole('button', { name: '关闭', exact: true }).click()
  await expect(dialog).not.toBeVisible()
  await row.getByRole('button', { name: '产品凭据', exact: true }).click()
  await expect(dialog).toBeVisible()
  await expect(dialog.locator('[data-testid="product-secret"]')).toHaveCount(0)
  await expect(dialog.getByRole('button', { name: '轮换产品注册秘密', exact: true })).toBeEnabled()
  await dialog.getByRole('button', { name: '轮换产品注册秘密', exact: true }).click()
  await page.getByRole('button', { name: '确认轮换', exact: true }).click()
  await expect(input).toBeVisible()
  let newSecret = await input.inputValue()
  expect(/^[0-9a-f]{64}$/.test(newSecret) && newSecret !== oldSecret).toBe(true)
  const currentKey = await page.evaluate(async (f) => {
    const apiPath = '/src/api/device.ts',
      api = await import(apiPath)
    return (await api.fetchDeviceTypeDetail(f.projectId, f.id)).productKey!
  }, fixture)
  expect(currentKey).toBe(publicKey)
  expect(await register(oldSecret, `product_old_${fixture.suffix}`)).toEqual({
    status: 403,
    code: 30026,
    hasToken: false
  })
  expect(await register(newSecret, `product_new_${fixture.suffix}`)).toEqual({
    status: 201,
    code: null,
    hasToken: true
  })
  expect(
    await page.evaluate(
      (secret) =>
        [...Object.values(localStorage), ...Object.values(sessionStorage)].some((v) =>
          v.includes(secret)
        ),
      newSecret
    )
  ).toBe(false)
  await dialog.getByRole('button', { name: '关闭', exact: true }).click()
  await expect(dialog).not.toBeVisible()
  expect(
    consoleMessages.some((message) => message.includes(oldSecret) || message.includes(newSecret))
  ).toBe(false)
  expect(page.url().includes(oldSecret) || page.url().includes(newSecret)).toBe(false)
  expect(productPosts).toBe(2)
  const serviceDirectory = resolve('logs/015-a-service')
  for (const level of ['info', 'warn', 'error', 'debug']) {
    const log = await readFile(resolve(serviceDirectory, level, `${level}.log`), 'utf8')
    expect(log.includes(oldSecret) || log.includes(newSecret)).toBe(false)
  }
  consoleMessages.length = 0
  oldSecret = ''
  newSecret = ''
})
