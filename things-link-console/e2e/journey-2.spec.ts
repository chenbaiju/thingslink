import { expect, test } from '@playwright/test'
import {
  enterProject,
  openDeviceList,
  openDeviceCredentials,
  expectToast,
  login,
  OWNER_EMAIL,
  OWNER_PASSWORD
} from './helpers'

/**
 * 旅程 2：创建设备类型 → 发布物模型 → 创建设备 → 一次性查看凭据，刷新后不再展示明文。
 *
 * 真实浏览器 + 真实后端。设备密钥只出现在「生成新密钥」后的一次性警示框里（S3 的一机一密语义），
 * 关闭/刷新后设备凭据列表只返回元数据，不再出现明文。
 */
test('旅程2：设备类型→发布→设备→一次性凭据，刷新后明文不再展示', async ({ page }) => {
  const suffix = Date.now()
  const typeKey = `e2e_type_${suffix}`
  const typeName = `E2E类型-${suffix}`
  const deviceKey = `e2e_dev_${suffix}`

  await login(page, OWNER_EMAIL, OWNER_PASSWORD)
  // backend 权限模式下，设备菜单只在进入项目后注册；进入预置账号的项目
  await enterProject(page, 'E2E项目')

  // 1. 创建设备类型（草稿）。弹窗字段顺序：名称 → 标识符 → 分类/协议/通信方式（默认值即可）
  await page.goto('/#/device/types')
  await page.getByRole('button', { name: '创建设备类型' }).click()
  const typeDialog = page.locator('.el-dialog:visible')
  await typeDialog.locator('input').first().fill(typeName)
  await typeDialog.getByPlaceholder('例如 temperature_sensor').fill(typeKey)
  await page.getByRole('button', { name: '确定' }).click()
  await expect(page.getByText(typeName).first()).toBeVisible({ timeout: 15_000 })

  // 2. 发布（ElMessageBox 的确认按钮文案是「确定发布」）
  const typeRow = page.locator('tr', { hasText: typeName }).first()
  await typeRow.getByRole('button', { name: '发布' }).click()
  await page.getByRole('button', { name: '确定发布' }).click()
  await expect(typeRow.getByText('已发布')).toBeVisible({ timeout: 15_000 })

  // 3. 创建设备。hash 路由切换后必须先等设备列表的交互区真正渲染完成；
  //    「创建设备」用 exact：getByRole 默认子串匹配会命中上一页残留的「创建设备类型」。
  await openDeviceList(page)
  await page.getByRole('button', { name: '创建设备', exact: true }).click()
  // 弹窗用对话框标题定位（标题即「创建设备」），不依赖全局 .el-dialog:visible
  const deviceDialog = page.getByRole('dialog', { name: '创建设备' })
  await deviceDialog.locator('input').first().fill(`E2E设备-${suffix}`)
  await deviceDialog.getByPlaceholder('例如 sensor_01').fill(deviceKey)
  await deviceDialog.locator('.el-select').first().click()
  await page.getByText(typeName, { exact: true }).last().click()
  await deviceDialog.getByRole('button', { name: '确定' }).click()
  await expect(page.getByText(deviceKey, { exact: true }).first()).toBeVisible({ timeout: 15_000 })

  // 4. 409 + 慢网恢复：重复 deviceKey 必须收到真实 409，弹窗保留；修改标识符后在慢请求期间
  //    按钮禁用且只发一条 POST，最终恢复成功。核心 API 仍到真实后端，不 mock 响应。
  await page.getByRole('button', { name: '创建设备', exact: true }).click()
  const recoveryDialog = page.getByRole('dialog', { name: '创建设备' })
  await recoveryDialog.locator('input').first().fill(`E2E冲突恢复设备-${suffix}`)
  await recoveryDialog.getByPlaceholder('例如 sensor_01').fill(deviceKey)
  await recoveryDialog.locator('.el-select').first().click()
  await page.getByText(typeName, { exact: true }).last().click()
  const conflictResponse = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      /\/api\/v1\/projects\/[^/]+\/devices$/.test(new URL(response.url()).pathname),
    { timeout: 20_000 }
  )
  await recoveryDialog.getByRole('button', { name: '确定' }).click()
  expect((await conflictResponse).status()).toBe(409)
  await expectToast(page, '设备标识符已存在')
  await expect(recoveryDialog).toBeVisible()

  const recoveredDeviceKey = `${deviceKey}_recovered`
  await recoveryDialog.getByPlaceholder('例如 sensor_01').fill(recoveredDeviceKey)
  let delayedCreateRequests = 0
  await page.route('**/api/v1/projects/*/devices', async (route) => {
    if (route.request().method() === 'POST') {
      delayedCreateRequests++
      await new Promise((resolve) => setTimeout(resolve, 800))
    }
    await route.continue()
  })
  const confirmRecovery = recoveryDialog.getByRole('button', { name: '确定' })
  await confirmRecovery.click()
  await expect(confirmRecovery).toBeDisabled()
  await expect(page.getByText(recoveredDeviceKey).first()).toBeVisible({ timeout: 20_000 })
  expect(delayedCreateRequests).toBe(1)
  await page.unroute('**/api/v1/projects/*/devices')

  // 5. 一次性查看凭据：打开凭据弹窗 → 生成新密钥 → 明文只出现在警示框的只读输入框里
  const deviceRow = page
    .locator('tr')
    .filter({ has: page.getByText(deviceKey, { exact: true }) })
    .first()
  const credDialog = await openDeviceCredentials(page, deviceRow)
  await expect(credDialog.getByText('设备凭据').first()).toBeVisible({ timeout: 15_000 })
  await credDialog.getByRole('button', { name: '生成新密钥' }).click()
  const secretInput = credDialog.locator('.cred-secret input')
  await expect(secretInput).toBeVisible({ timeout: 15_000 })
  const plaintext = (await secretInput.inputValue()).trim()
  expect(plaintext.length).toBeGreaterThan(10)

  // 6. 刷新后：凭据列表只剩元数据，明文不再展示。先挂监听，断言刷新令牌轮换（/auth/refresh）成功。
  const refreshResp = page.waitForResponse(
    (r) => r.url().includes('/api/v1/auth/refresh') && r.request().method() === 'POST',
    { timeout: 30_000 }
  )
  await page.reload()
  expect((await refreshResp).status()).toBe(200)
  await expect(page.getByText(deviceKey, { exact: true }).first()).toBeVisible({ timeout: 15_000 })
  const refreshedRow = page
    .locator('tr')
    .filter({ has: page.getByText(deviceKey, { exact: true }) })
    .first()
  await openDeviceCredentials(page, refreshedRow)
  await expect(page.locator('.el-dialog:visible').getByText('设备凭据').first()).toBeVisible({
    timeout: 15_000
  })
  await expect(page.getByText(plaintext).first()).toHaveCount(0)
})
