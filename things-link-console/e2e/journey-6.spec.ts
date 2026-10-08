import { expect, test, type Page } from '@playwright/test'
import { simulatorRuntime } from './mqtt-runtime'
import {
  enterProject,
  openDeviceList,
  openDeviceCredentials,
  openDeviceDetails,
  login,
  OWNER_EMAIL,
  OWNER_PASSWORD
} from './helpers'
import { observeCreatedHistoryDevice, verifyReportedHistory } from './history-sync'

/** 在控制台创建指定分类的设备类型，并按需补齐温度属性与 reboot 命令后发布。 */
async function createAndPublishType(
  page: Page,
  name: string,
  typeKey: string,
  kindLabel: '网关' | '网关子设备',
  withModel: boolean
) {
  await page.goto('/#/device/types')
  await page.getByRole('button', { name: '创建设备类型' }).click()
  const dialog = page.getByRole('dialog', { name: '创建设备类型' })
  await dialog.locator('input').first().fill(name)
  await dialog.getByPlaceholder('例如 temperature_sensor').fill(typeKey)
  await dialog
    .locator('.el-form-item')
    .filter({ hasText: '设备分类' })
    .locator('.el-select')
    .click()
  await page.getByRole('option', { name: kindLabel, exact: true }).click()
  await dialog.getByRole('button', { name: '确定' }).click()
  await expect(page.getByText(name, { exact: true }).first()).toBeVisible({ timeout: 15_000 })
  const row = page
    .locator('tr')
    .filter({ has: page.getByText(name, { exact: true }) })
    .first()

  if (withModel) {
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
  }

  await row.getByRole('button', { name: '发布' }).click()
  await page.getByRole('button', { name: '确定发布' }).click()
  await expect(row.getByText('已发布')).toBeVisible({ timeout: 15_000 })
}

/** 创建一台绑定指定类型的设备，并返回其稳定表格行。 */
async function createDevice(page: Page, name: string, deviceKey: string, typeName: string) {
  await openDeviceList(page)
  await page.getByRole('button', { name: '创建设备', exact: true }).click()
  const dialog = page.getByRole('dialog', { name: '创建设备' })
  await dialog.locator('input').first().fill(name)
  await dialog.getByPlaceholder('例如 sensor_01').fill(deviceKey)
  await dialog.locator('.el-select').first().click()
  await page.getByRole('option', { name: typeName, exact: true }).click()
  await dialog.getByRole('button', { name: '确定' }).click()
  const row = page
    .locator('tr')
    .filter({ has: page.getByText(deviceKey, { exact: true }) })
    .first()
  await expect(row).toBeVisible({ timeout: 15_000 })
  return row
}

/** 旅程失败或完成后都停止模拟器，避免污染后续共享栈用例。 */
test.afterEach(async ({ request }) => {
  await request.post(`${simulatorRuntime().baseURL}/simulations/stop`).catch(() => undefined)
})

/**
 * D-058 旅程 6（@simulator）：网关/子设备绑定 → 配置驱动批量上报 → 显式在线 → 子设备命令 → 网关掉线级联。
 */
test(
  '旅程6：网关/子设备绑定→上报→在线级联→子设备命令',
  { tag: '@simulator' },
  async ({ page, request }) => {
    test.setTimeout(240_000)
    const projectKey = process.env.E2E_PROJECT_KEY
    expect(
      projectKey,
      'E2E_PROJECT_KEY 未注入：请用 run-e2e-tests.sh --with-simulator 运行'
    ).toBeTruthy()
    await request.post(`${simulatorRuntime().baseURL}/simulations/stop`)

    const suffix = Date.now()
    const gatewayTypeName = `E2E网关类型-${suffix}`
    const subTypeName = `E2E子设备类型-${suffix}`
    const gatewayName = `E2E网关-${suffix}`
    const subName = `E2E子设备-${suffix}`
    const gatewayKey = `e2e_gateway_${suffix}`
    const subDeviceKey = `e2e_sub_${suffix}`

    await login(page, OWNER_EMAIL, OWNER_PASSWORD)
    await enterProject(page, 'E2E项目')
    await createAndPublishType(page, gatewayTypeName, `e2e_gateway_type_${suffix}`, '网关', false)
    await createAndPublishType(page, subTypeName, `e2e_sub_type_${suffix}`, '网关子设备', true)
    let gatewayRow = await createDevice(page, gatewayName, gatewayKey, gatewayTypeName)
    const historyIdentity = observeCreatedHistoryDevice(page, subDeviceKey)
    await createDevice(page, subName, subDeviceKey, subTypeName)
    const targetHistoryDevice = await historyIdentity

    // 绑定必须走真实浏览器控制面；拓扑表同时出现网关和子设备才算绑定事实已被重新读取。
    await page.goto('/#/device/topology')
    await expect(page.locator('.topology__table')).toBeVisible({ timeout: 15_000 })
    await page.getByRole('button', { name: '绑定子设备' }).click()
    const bindDialog = page.getByRole('dialog', { name: '绑定子设备' })
    const selects = bindDialog.locator('.el-select')
    await selects.nth(0).click()
    await page.getByRole('option', { name: gatewayName, exact: true }).click()
    await selects.nth(1).click()
    await page.getByRole('option', { name: subName, exact: true }).click()
    await bindDialog.getByRole('button', { name: '确定' }).click()
    await expectToastVisible(page, '绑定成功')
    const topologyTable = page.locator('.topology__table')
    await expect(topologyTable.getByText(gatewayName, { exact: true })).toBeVisible()
    await expect(topologyTable.getByText(subName, { exact: true })).toBeVisible()

    // 只有网关拥有 MQTT 凭据；子设备命令和上报都复用该已认证连接。
    await page.goto('/#/device/list')
    gatewayRow = page
      .locator('tr')
      .filter({ has: page.getByText(gatewayKey, { exact: true }) })
      .first()
    const credDialog = await openDeviceCredentials(page, gatewayRow)
    await credDialog.getByRole('button', { name: '生成新密钥' }).click()
    const secretInput = credDialog.locator('.cred-secret input')
    await expect(secretInput).toBeVisible({ timeout: 15_000 })
    const accessToken = (await secretInput.inputValue()).trim()
    expect(accessToken.length).toBeGreaterThan(10)
    await credDialog.getByText('我已复制并安全保存该密钥').click()
    await credDialog.getByLabel('关闭此对话框').click()

    const startResponse = await request.post(`${simulatorRuntime().baseURL}/simulations/start`, {
      data: {
        brokerUri: simulatorRuntime().brokerUri,
        projectKey,
        devices: [{ deviceKey: gatewayKey, accessToken, gateway: true }],
        intervalSeconds: 2,
        autoReplyCommands: true,
        runId: `e2e-journey-6-${suffix}`,
        shardId: 'shard-000',
        propertiesPerReport: 1
      }
    })
    expect(startResponse.ok(), await startResponse.text()).toBeTruthy()

    // 发布点位会经 Outbox/Kafka/EMQX 下发真实配置；模拟网关据此显式 login 并开始 batch/report。
    await page.goto('/#/device/modbus-points')
    await expect(page.locator('.modbus-points')).toBeVisible({
      timeout: 15_000
    })
    await page.getByRole('button', { name: '添加点位' }).click()
    const pointDialog = page.getByRole('dialog', { name: '添加点位' })
    await pointDialog.locator('.el-select').nth(0).click()
    await page.getByRole('option', { name: subName, exact: true }).click()
    await pointDialog.locator('.el-select').nth(1).click()
    await page.getByRole('option', { name: /温度.*NUMBER/ }).click()
    await pointDialog.getByRole('button', { name: '确定' }).click()
    await expect(page.getByText('temperature', { exact: true })).toBeVisible({ timeout: 15_000 })
    await page.getByRole('button', { name: /发布（1 个草稿）/ }).click()
    await page.getByRole('button', { name: '确定' }).click()
    await expect(page.getByText('已发布', { exact: true }).first()).toBeVisible({ timeout: 20_000 })

    // 子设备只有收到显式 sub/login 才能在线；batch/report 还必须进入历史/影子，而非只更新拓扑标签。
    await page.goto('/#/device/list')
    let subRow = page
      .locator('tr')
      .filter({ has: page.getByText(subDeviceKey, { exact: true }) })
      .first()
    await expect(async () => {
      await page.reload()
      subRow = page
        .locator('tr')
        .filter({ has: page.getByText(subDeviceKey, { exact: true }) })
        .first()
      await expect(subRow.getByText('在线')).toBeVisible({ timeout: 3_000 })
    }).toPass({ timeout: 45_000 })
    await verifyReportedHistory(
      page,
      targetHistoryDevice,
      () => openDeviceDetails(page, subRow),
      30_000
    )

    // 子设备命令真实路由到 gatewayKey 的连接；网关在同一连接回复后，命令必须进入成功终态。
    await page.locator('.device-detail').getByRole('tab', { name: '命令', exact: true }).click()
    const commandPanel = page.locator('.command-panel')
    await commandPanel.getByRole('button', { name: '下发命令' }).click()
    await expect(page.getByText('命令已受理').first()).toBeVisible({ timeout: 10_000 })
    await expect(async () => {
      await commandPanel.getByRole('button', { name: '刷新状态' }).click()
      await expect(
        commandPanel.locator('.command-result').getByText('执行成功', { exact: true })
      ).toBeVisible({ timeout: 3_000 })
    }).toPass({ timeout: 30_000 })

    // 不发送 sub/logout，直接断开网关，证明 Broker disconnect 会把网关及其子设备级联为 OFFLINE。
    expect((await request.post(`${simulatorRuntime().baseURL}/simulations/stop`)).ok()).toBeTruthy()
    await page.locator('.device-detail').getByRole('button', { name: '返回', exact: true }).click()
    await expect(async () => {
      await page.reload()
      gatewayRow = page
        .locator('tr')
        .filter({ has: page.getByText(gatewayKey, { exact: true }) })
        .first()
      subRow = page
        .locator('tr')
        .filter({ has: page.getByText(subDeviceKey, { exact: true }) })
        .first()
      await expect(gatewayRow.getByText('离线')).toBeVisible({ timeout: 3_000 })
      await expect(subRow.getByText('离线')).toBeVisible({ timeout: 3_000 })
    }).toPass({ timeout: 45_000 })
  }
)

/** 等待 Element Plus 成功消息，避免消息动画结束前后续导航吞掉失败。 */
async function expectToastVisible(page: Page, text: string) {
  await expect(page.locator('.el-message').filter({ hasText: text }).first()).toBeVisible()
}
