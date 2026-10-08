import { expect, test } from '@playwright/test'
import {
  login,
  enterProject,
  resetSession,
  OWNER_EMAIL,
  OWNER_PASSWORD,
  MEMBER_EMAIL,
  MEMBER_PASSWORD,
  ensureViewerMember
} from './helpers'

test.use({ trace: 'off', video: 'off', screenshot: 'off' })

/**
 * S13-4b-2：灰度活动与批次控制台页面。
 *
 * 本用例只断言平台在本机真实运行时**能诚实证明**的事实：
 * 活动页由真实后端菜单下发并渲染真实（本环境为空的）活动列表；创建流程把操作者输入提交给真实API，
 * 并在固件没有已签名发布产物时如实呈现服务端拒绝，绝不本地伪造成功；非管理角色看不到活动菜单。
 *
 * **未覆盖且不声称**：活动的排程、启动、暂停、恢复、取消与批次放行在真实栈里无法验证——
 * 活动只能引用已签名发布的固件产物，而本机没有受控signer（ADR0119 fail-closed），
 * 无法产出READY固件，因此也无法产出活动。这些路径由S13-4b-1的真实PostgreSQL读用例、
 * 活动运行控制既有集成用例与前端单元用例覆盖，不在此冒充端到端通过。
 */
test('OTA活动：菜单与页面渲染、创建被环境边界拒绝、角色不可见', async ({ page }) => {
  test.setTimeout(240_000)
  page.setDefaultTimeout(20_000)
  await login(page, OWNER_EMAIL, OWNER_PASSWORD)
  await enterProject(page, 'E2E项目')

  // 通过真实管理API准备"设备类型+产品凭据+物模型版本+固件草稿+设备"，
  // 使创建流程能提交一份形状完整的计划，从而让服务端的拒绝来自发布产物边界而不是本地校验。
  const fixture = await page.evaluate(async () => {
    const devicePath = '/src/api/device.ts'
    const otaPath = '/src/api/ota.ts'
    const userPath = '/src/store/modules/user.ts'
    const httpPath = '/src/utils/http/index.ts'
    const deviceApi = await import(devicePath)
    const otaApi = await import(otaPath)
    const { useUserStore } = await import(userPath)
    const http = (await import(httpPath)).default
    const projectId = useUserStore().info.currentProjectId as string
    const suffix = Date.now().toString(36)
    const type = await deviceApi.fetchCreateDeviceType(projectId, {
      typeKey: `e2e_campaign_${suffix}`,
      name: `活动验收类型${suffix}`,
      deviceKind: 'DIRECT',
      payloadProtocol: 'STANDARD',
      networkType: 'WIFI'
    })
    await deviceApi.fetchPublishDeviceType(projectId, type.id as string)
    await http.post({
      url: `/api/v1/projects/${projectId}/device-types/${type.id as string}/product-credential`
    })
    const version = await otaApi.fetchLatestThingModelVersion(projectId, type.id as string)
    const firmwareVersion = `0.0.${Date.now() % 1000}`
    await otaApi.createOtaFirmware(
      projectId,
      {
        deviceTypeId: type.id as string,
        thingModelVersionId: version.id as string,
        firmwareVersion
      },
      crypto.randomUUID()
    )
    const device = await deviceApi.fetchCreateDevice(projectId, {
      deviceTypeId: type.id as string,
      deviceKey: `e2e_campaign_device_${suffix}`,
      name: `活动验收设备${suffix}`
    })
    return { firmwareVersion, deviceName: device.name as string }
  })
  expect(fixture.firmwareVersion).toMatch(/^0\.0\./)

  // 1) 活动页由真实后端菜单下发；本环境没有已签名发布产物，因此列表为空。
  await page.goto('/#/ota/campaigns')
  await expect(page.locator('.ota-campaigns')).toBeVisible()
  await expect(page.getByText('还没有灰度活动')).toBeVisible()

  // 2) 创建流程：本地校验通过后提交真实API；服务端以"没有可用发布产物"拒绝。
  await page.getByRole('button', { name: '创建活动' }).click()
  const dialog = page.getByRole('dialog', { name: '创建灰度活动' })
  await dialog.getByTestId('ota-campaign-firmware').click()
  await page
    .locator('.el-select-dropdown__item')
    .filter({ hasText: fixture.firmwareVersion })
    .first()
    .click()
  await dialog.getByTestId('ota-campaign-devices').click()
  await page
    .locator('.el-select-dropdown__item')
    .filter({ hasText: fixture.deviceName })
    .first()
    .click()
  await page.keyboard.press('Escape')
  // 最早启动时间已由页面预填为"5分钟后"，操作者只需确认；批次大小保留默认值。
  await dialog.getByRole('button', { name: '创建草稿' }).click()
  const rejected = dialog.getByTestId('ota-campaign-create-error')
  await expect(rejected).toBeVisible({ timeout: 60_000 })
  await expect(rejected).toContainText(/ADR0119|发布|不可用|不存在|状态/)
  await dialog.getByRole('button', { name: '取消' }).click()

  // 3) 拒绝之后列表仍为空：没有任何本地或服务端的伪造活动事实。
  await page.reload()
  await expect(page.getByText('还没有灰度活动')).toBeVisible()

  // 3b) S13-4c-2：设备作业页由真实后端菜单下发；没有活动时明确要求先选择活动，
  //     不显示任何编造的作业行。
  await page.goto('/#/ota/jobs')
  await expect(page.locator('.ota-jobs')).toBeVisible()
  await expect(page.getByText('请先选择活动')).toBeVisible()

  // 4) 菜单过滤只影响呈现：VIEWER看不到活动菜单，服务端授权由真实PG用例单独证明。
  const projectId = await page.evaluate(async () => {
    const userPath = '/src/store/modules/user.ts'
    const { useUserStore } = await import(userPath)
    return useUserStore().info.currentProjectId as string
  })
  await ensureViewerMember(page, projectId, MEMBER_EMAIL)
  await resetSession(page)
  await login(page, MEMBER_EMAIL, MEMBER_PASSWORD)
  await enterProject(page, 'E2E项目')
  for (const name of ['固件管理', '灰度活动', '设备作业', '审计时间线']) {
    await expect(page.getByRole('menuitem', { name, exact: true })).toHaveCount(0)
  }
})
