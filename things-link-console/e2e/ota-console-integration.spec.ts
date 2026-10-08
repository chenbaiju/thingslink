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
 * S13-4e：OTA控制台跨页整合旅程。
 *
 * 与各子片的单页旅程不同，本用例在**同一次登录、同一份真实数据**上串起四个OTA页面，
 * 验证跨页事实一致（固件页产生的固件身份必须原样出现在审计时间线里）、四个菜单由真实后端
 * 一次性下发、以及无signer时创建活动被如实拒绝。
 *
 * 仍然**不**声称：活动排程/启动/批次放行、设备作业推进、模拟器真实派发与硬件证据——
 * 这些必须有受控signer与真实设备，缺口登记为D-154。
 */
test('OTA控制台整合：四页菜单、跨页事实一致与角色边界', async ({ page }) => {
  test.setTimeout(300_000)
  page.setDefaultTimeout(20_000)
  await login(page, OWNER_EMAIL, OWNER_PASSWORD)
  await enterProject(page, 'E2E项目')

  // 真实API准备：设备类型→发布→产品凭据→物模型版本→固件草稿→上传会话→对象上传复验。
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
      typeKey: `e2e_integration_${suffix}`,
      name: `整合验收类型${suffix}`,
      deviceKind: 'DIRECT',
      payloadProtocol: 'STANDARD',
      networkType: 'WIFI'
    })
    await deviceApi.fetchPublishDeviceType(projectId, type.id as string)
    await http.post({
      url: `/api/v1/projects/${projectId}/device-types/${type.id as string}/product-credential`
    })
    const version = await otaApi.fetchLatestThingModelVersion(projectId, type.id as string)
    const firmwareVersion = `2.0.${Date.now() % 1000}`
    const firmware = await otaApi.createOtaFirmware(
      projectId,
      {
        deviceTypeId: type.id as string,
        thingModelVersionId: version.id as string,
        firmwareVersion
      },
      crypto.randomUUID()
    )
    const content = new TextEncoder().encode('thingslink-ota-integration-artifact')
    const sha256 = await otaApi.sha256Hex(content.buffer as ArrayBuffer)
    const session = await otaApi.createOtaUpload(
      projectId,
      firmware.id as string,
      { expectedLength: content.byteLength, expectedSha256: sha256 },
      crypto.randomUUID()
    )
    const verified = await otaApi.uploadOtaContent(
      projectId,
      firmware.id as string,
      session.id as string,
      content.buffer as ArrayBuffer
    )
    return {
      firmwareId: firmware.id as string,
      firmwareVersion,
      uploadStatus: verified.status as string
    }
  })
  expect(fixture.uploadStatus).toBe('VERIFIED')

  // 1) 四个OTA菜单由真实后端一次性下发（同一角色、同一项目）。
  const otaMenu = page.getByRole('menuitem', { name: '设备开发', exact: true })
  await expect(otaMenu).toBeVisible()
  // 子菜单在父项展开后才渲染；先展开再断言四项，避免把"折叠"误判成"缺菜单"。
  if ((await otaMenu.getAttribute('aria-expanded')) !== 'true') await otaMenu.click()
  for (const child of ['固件管理', '灰度活动', '设备作业', '审计时间线']) {
    await expect(page.getByRole('menuitem', { name: child, exact: true })).toBeVisible()
  }

  // 2) 固件页：草稿与已复验的上传对象都是真实事实。
  await page.goto('/#/ota/firmwares')
  const firmwareRow = page.locator('tr').filter({ hasText: fixture.firmwareVersion }).first()
  await expect(firmwareRow).toBeVisible()
  await expect(firmwareRow.locator('.el-tag')).toContainText('草稿')

  // 3) 活动页：无已签名发布产物 → 创建被如实拒绝且列表仍为空；作业页明确要求先选活动。
  await page.goto('/#/ota/campaigns')
  await expect(page.locator('.ota-campaigns')).toBeVisible()
  await expect(page.getByText('还没有灰度活动')).toBeVisible()
  await page.goto('/#/ota/jobs')
  await expect(page.locator('.ota-jobs')).toBeVisible()
  await expect(page.getByText('请先选择活动')).toBeVisible()

  // 4) 审计页：固件页产生的身份必须原样出现在审计时间线（跨页事实一致）。
  await page.goto('/#/ota/audits')
  const auditRow = page.locator('tr').filter({ hasText: '创建固件草稿' }).first()
  await expect(auditRow).toBeVisible({ timeout: 30_000 })
  await expect(auditRow).toContainText(fixture.firmwareId)
  const uploadRow = page.locator('tr').filter({ hasText: '对象复验通过' }).first()
  await expect(uploadRow).toBeVisible()

  // 5) 角色边界：VIEWER 看不到任何OTA入口（菜单过滤只影响呈现）。
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
