import { expect, test, type APIRequestContext, type Page } from '@playwright/test'
import {
  enterProject,
  openDeviceList,
  openDeviceCredentials,
  login,
  OWNER_EMAIL,
  OWNER_PASSWORD
} from './helpers'

interface CreatedDevice {
  id: string
  projectId: string
  row: ReturnType<Page['locator']>
}

interface EndUser {
  id: string
}

interface AppSession {
  accessToken: string
}

interface DeviceBinding {
  deviceId: string
  relationRole: string
  status: string
}

interface TokenResponse {
  token: string
}

interface RelationResponse {
  deviceId: string
  relationRole: string
}

interface CommandResponse {
  commandId: string
  status: string
}

interface ApiError {
  code: number
}

/** 浏览器 Response 与 APIResponse 都满足的最小读取合同。 */
interface JsonResponse {
  ok(): boolean
  status(): number
  text(): Promise<string>
  json(): Promise<unknown>
}

/** 为旅程创建带 temperature 属性、reboot 命令的直连设备类型并发布。 */
async function createAndPublishType(page: Page, name: string, typeKey: string) {
  await page.goto('/#/device/types')
  await page.getByRole('button', { name: '创建设备类型' }).click()
  const typeDialog = page.getByRole('dialog', { name: '创建设备类型' })
  await typeDialog.locator('input').first().fill(name)
  await typeDialog.getByPlaceholder('例如 temperature_sensor').fill(typeKey)
  await typeDialog.getByRole('button', { name: '确定' }).click()
  const row = page
    .locator('tr')
    .filter({ has: page.getByText(name, { exact: true }) })
    .first()
  await expect(row).toBeVisible({ timeout: 15_000 })

  // 真实模拟器固定上报一个属性；先声明 temperature，避免未建模遥测干扰在线与命令链路取证。
  await row.getByRole('button', { name: '属性' }).click()
  await page
    .getByRole('dialog', { name: /属性定义/ })
    .getByRole('button', { name: '添加属性' })
    .click()
  const propertyDialog = page.getByRole('dialog', { name: '添加属性' })
  await propertyDialog.locator('input').first().fill('温度')
  await propertyDialog.getByPlaceholder('例如 temperature').fill('temperature')
  await propertyDialog.getByRole('button', { name: '确定' }).click()
  await expect(propertyDialog).toBeHidden({ timeout: 10_000 })
  await page
    .getByRole('dialog', { name: /属性定义/ })
    .getByLabel('关闭此对话框')
    .click()

  await row.getByRole('button', { name: '命令' }).click()
  await page
    .getByRole('dialog', { name: /命令定义/ })
    .getByRole('button', { name: '添加命令' })
    .click()
  const commandDialog = page.getByRole('dialog', { name: '添加命令' })
  await commandDialog.locator('input').first().fill('重启')
  await commandDialog.getByPlaceholder('例如 reboot').fill('reboot')
  await commandDialog.getByRole('button', { name: '确定' }).click()
  await expect(commandDialog).toBeHidden({ timeout: 10_000 })
  await page
    .getByRole('dialog', { name: /命令定义/ })
    .getByLabel('关闭此对话框')
    .click()

  await row.getByRole('button', { name: '发布' }).click()
  await page.getByRole('button', { name: '确定发布' }).click()
  await expect(row.getByText('已发布')).toBeVisible({ timeout: 15_000 })
}

/** 通过真实控制台创建设备，并从创建响应取得稳定 ID 与项目 ID。 */
async function createDevice(page: Page, name: string, deviceKey: string, typeName: string) {
  await openDeviceList(page)
  await page.getByRole('button', { name: '创建设备', exact: true }).click()
  const dialog = page.getByRole('dialog', { name: '创建设备' })
  await dialog.locator('input').first().fill(name)
  await dialog.getByPlaceholder('例如 sensor_01').fill(deviceKey)
  await dialog.locator('.el-select').first().click()
  await page.getByRole('option', { name: typeName, exact: true }).click()

  const responsePromise = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      /\/api\/v1\/projects\/[^/]+\/devices$/.test(new URL(response.url()).pathname)
  )
  await dialog.getByRole('button', { name: '确定' }).click()
  const response = await responsePromise
  expect(response.ok(), `创建设备失败：HTTP ${response.status()}`).toBeTruthy()
  const body = (await response.json()) as { id: string }
  const pathParts = new URL(response.url()).pathname.split('/')
  const projectId = pathParts[pathParts.indexOf('projects') + 1]
  expect(body.id).toBeTruthy()
  expect(projectId).toBeTruthy()

  const row = page
    .locator('tr')
    .filter({ has: page.getByText(deviceKey, { exact: true }) })
    .first()
  await expect(row).toBeVisible({ timeout: 15_000 })
  return { id: body.id, projectId, row } satisfies CreatedDevice
}

/** 生成只显示一次的设备密钥。 */
async function generateDeviceAccessToken(device: CreatedDevice) {
  const dialog = await openDeviceCredentials(device.row.page(), device.row)
  await dialog.getByRole('button', { name: '生成新密钥' }).click()
  const secretInput = dialog.locator('.cred-secret input')
  await expect(secretInput).toBeVisible({ timeout: 15_000 })
  const accessToken = (await secretInput.inputValue()).trim()
  expect(accessToken.length).toBeGreaterThan(10)
  await dialog.getByText('我已复制并安全保存该密钥').click()
  await dialog.getByLabel('关闭此对话框').click()
  return accessToken
}

/** 要求响应成功后解析 JSON；失败正文只进入当前测试诊断，不写持久日志。 */
async function json<T>(response: JsonResponse, operation: string): Promise<T> {
  if (!response.ok()) {
    throw new Error(`${operation}失败：HTTP ${response.status()} ${await response.text()}`)
  }
  return (await response.json()) as T
}

/** 控制台 OWNER 预置终端用户并分配项目角色。 */
async function provisionEndUser(
  request: APIRequestContext,
  ownerToken: string,
  projectId: string,
  username: string,
  password: string
) {
  const headers = { Authorization: `Bearer ${ownerToken}` }
  const user = await json<EndUser>(
    await request.post(`/api/v1/projects/${projectId}/end-users`, {
      headers,
      data: { username, password, displayName: username }
    }),
    `预置终端用户 ${username}`
  )
  const roleResponse = await request.post(
    `/api/v1/projects/${projectId}/end-users/${user.id}/role`,
    { headers, data: { role: 'APP_ADMIN' } }
  )
  expect(roleResponse.status(), `分配 ${username} 项目角色失败`).toBe(204)
  return user
}

/** 以移动端契约登录，返回与控制台安全链隔离的 App 访问令牌。 */
async function appLogin(
  request: APIRequestContext,
  projectKey: string,
  username: string,
  password: string
) {
  return json<AppSession>(
    await request.post('/api/v1/app/auth/login', {
      data: { projectKey, username, password }
    }),
    `App 用户 ${username} 登录`
  )
}

/** 通过真实账号安全链取得 OWNER 项目令牌，避免整页导航后再读取已释放的浏览器响应体。 */
async function ownerProjectSession(request: APIRequestContext, projectId: string) {
  const accountSession = await json<AppSession>(
    await request.post('/api/v1/auth/login', {
      data: { email: OWNER_EMAIL, password: OWNER_PASSWORD }
    }),
    'OWNER API 登录'
  )
  return json<AppSession>(
    await request.post('/api/v1/auth/switch-project', {
      headers: { Authorization: `Bearer ${accountSession.accessToken}` },
      data: { projectId }
    }),
    '切换 OWNER 项目'
  )
}

/** 查询管理侧完整关系历史，避免只根据 App 响应推断唯一 PRIMARY。 */
async function bindings(
  request: APIRequestContext,
  ownerToken: string,
  projectId: string,
  userId: string
) {
  return json<DeviceBinding[]>(
    await request.get(`/api/v1/projects/${projectId}/end-users/${userId}/devices`, {
      headers: { Authorization: `Bearer ${ownerToken}` }
    }),
    '读取终端用户设备关系'
  )
}

/** 旅程失败或完成后都停止模拟器，避免污染后续共享栈用例。 */
test.afterEach(async ({ request }) => {
  await request.post('http://localhost:8090/simulations/stop').catch(() => undefined)
})

/**
 * D-058 旅程 7（@simulator）：终端用户登录 → CLAIM → MEMBER 共享 → PRIMARY 转移
 * → 真实设备控制 → 旧主控自解绑 → 数据面立即失权并保留 CLOSED 历史。
 */
test(
  '旅程7：登录→认领/共享/转移→控制→解绑立即失权',
  { tag: '@simulator' },
  async ({ page, request }) => {
    test.setTimeout(300_000)
    const projectKey = process.env.E2E_PROJECT_KEY
    expect(
      projectKey,
      'E2E_PROJECT_KEY 未注入：请用 run-e2e-tests.sh --with-simulator 运行'
    ).toBeTruthy()
    await request.post('http://localhost:8090/simulations/stop')

    const suffix = Date.now()
    const typeName = `E2E终端类型-${suffix}`
    const deviceName = `E2E终端设备-${suffix}`
    const deviceKey = `e2e_app_device_${suffix}`
    const aliceName = `alice_${suffix}`
    const bobName = `bob_${suffix}`
    const appPassword = 'App-e2e-pass-1234'

    await login(page, OWNER_EMAIL, OWNER_PASSWORD)
    await enterProject(page, 'E2E项目')
    await createAndPublishType(page, typeName, `e2e_app_type_${suffix}`)
    const device = await createDevice(page, deviceName, deviceKey, typeName)
    const deviceAccessToken = await generateDeviceAccessToken(device)
    const ownerSession = await ownerProjectSession(request, device.projectId)

    const simulatorResponse = await request.post('http://localhost:8090/simulations/start', {
      data: {
        brokerUri: 'tcp://localhost:1883',
        projectKey,
        devices: [{ deviceKey, accessToken: deviceAccessToken, gateway: false }],
        intervalSeconds: 2,
        autoReplyCommands: true,
        runId: `e2e-journey-7-${suffix}`,
        shardId: 'shard-000',
        propertiesPerReport: 1
      }
    })
    expect(simulatorResponse.ok(), await simulatorResponse.text()).toBeTruthy()
    // 等一个真实上报周期后只刷新一次；反复 reload 会持续打断动态路由恢复，制造“设备行不存在”的假阴性。
    await page.waitForTimeout(5_000)
    await page.reload()
    await expect(device.row.getByText('在线')).toBeVisible({ timeout: 45_000 })

    const alice = await provisionEndUser(
      request,
      ownerSession.accessToken,
      device.projectId,
      aliceName,
      appPassword
    )
    const bob = await provisionEndUser(
      request,
      ownerSession.accessToken,
      device.projectId,
      bobName,
      appPassword
    )
    const aliceSession = await appLogin(request, projectKey!, aliceName, appPassword)
    const bobSession = await appLogin(request, projectKey!, bobName, appPassword)
    const ownerHeaders = { Authorization: `Bearer ${ownerSession.accessToken}` }
    const aliceHeaders = { Authorization: `Bearer ${aliceSession.accessToken}` }
    const bobHeaders = { Authorization: `Bearer ${bobSession.accessToken}` }

    // CLAIM 明文只出现一次；消费后 Alice 成为唯一 PRIMARY。
    const claimToken = await json<TokenResponse>(
      await request.post(`/api/v1/projects/${device.projectId}/end-users/device-claim-tokens`, {
        headers: ownerHeaders,
        data: { deviceId: device.id }
      }),
      '签发 CLAIM 令牌'
    )
    const claimed = await json<RelationResponse>(
      await request.post('/api/v1/app/device-claims', {
        headers: aliceHeaders,
        data: { token: claimToken.token }
      }),
      '消费 CLAIM 令牌'
    )
    expect(claimed).toMatchObject({ deviceId: device.id, relationRole: 'PRIMARY' })

    // Alice 共享 MEMBER 给 Bob，随后再把 PRIMARY 原子转移给 Bob。
    const shareToken = await json<TokenResponse>(
      await request.post(`/api/v1/app/devices/${device.id}/share-tokens`, {
        headers: aliceHeaders,
        data: { targetRole: 'MEMBER' }
      }),
      '签发 SHARE 令牌'
    )
    const shared = await json<RelationResponse>(
      await request.post('/api/v1/app/device-shares', {
        headers: bobHeaders,
        data: { token: shareToken.token }
      }),
      '消费 SHARE 令牌'
    )
    expect(shared).toMatchObject({ deviceId: device.id, relationRole: 'MEMBER' })

    const transferToken = await json<TokenResponse>(
      await request.post(`/api/v1/app/devices/${device.id}/transfer-tokens`, {
        headers: aliceHeaders
      }),
      '签发 TRANSFER 令牌'
    )
    const transferred = await json<RelationResponse>(
      await request.post('/api/v1/app/device-transfers', {
        headers: bobHeaders,
        data: { token: transferToken.token }
      }),
      '消费 TRANSFER 令牌'
    )
    expect(transferred).toMatchObject({ deviceId: device.id, relationRole: 'PRIMARY' })
    expect(
      await bindings(request, ownerSession.accessToken, device.projectId, alice.id)
    ).toContainEqual(
      expect.objectContaining({ deviceId: device.id, relationRole: 'MEMBER', status: 'ACTIVE' })
    )
    expect(
      await bindings(request, ownerSession.accessToken, device.projectId, bob.id)
    ).toContainEqual(
      expect.objectContaining({ deviceId: device.id, relationRole: 'PRIMARY', status: 'ACTIVE' })
    )

    // 新 PRIMARY 通过真实 EMQX 下行与模拟器 ACK 完成控制，不把 HTTP 202 当作执行成功。
    const accepted = await json<CommandResponse>(
      await request.post(`/api/v1/app/devices/${device.id}/commands`, {
        headers: { ...bobHeaders, 'Idempotency-Key': `journey-7-${suffix}` },
        data: { commandKey: 'reboot', input: {} }
      }),
      'App 下发命令'
    )
    expect(accepted.status).toBe('ACCEPTED')
    await expect
      .poll(
        async () => {
          const status = await json<CommandResponse>(
            await request.get(`/api/v1/app/devices/${device.id}/commands/${accepted.commandId}`, {
              headers: bobHeaders
            }),
            '回读 App 命令状态'
          )
          return status.status
        },
        { timeout: 30_000 }
      )
      .toBe('SUCCEEDED')

    // 旧主控 Alice 此时为 MEMBER；自解绑后同一令牌必须立即丧失读/控权限，Bob 不受影响。
    const unbindResponse = await request.delete(`/api/v1/app/devices/${device.id}/binding`, {
      headers: aliceHeaders
    })
    expect(unbindResponse.status()).toBe(204)
    const deniedDetail = await request.get(`/api/v1/app/devices/${device.id}`, {
      headers: aliceHeaders
    })
    expect(deniedDetail.status()).toBe(404)
    expect(((await deniedDetail.json()) as ApiError).code).toBe(60010)
    const deniedCommand = await request.post(`/api/v1/app/devices/${device.id}/commands`, {
      headers: { ...aliceHeaders, 'Idempotency-Key': `journey-7-denied-${suffix}` },
      data: { commandKey: 'reboot', input: {} }
    })
    expect(deniedCommand.status()).toBe(404)
    expect(((await deniedCommand.json()) as ApiError).code).toBe(60010)
    expect(
      await json<{ id: string }>(
        await request.get(`/api/v1/app/devices/${device.id}`, { headers: bobHeaders }),
        '新 PRIMARY 回读设备'
      )
    ).toMatchObject({ id: device.id })
    expect(
      await bindings(request, ownerSession.accessToken, device.projectId, alice.id)
    ).toContainEqual(
      expect.objectContaining({ deviceId: device.id, relationRole: 'MEMBER', status: 'CLOSED' })
    )
  }
)
