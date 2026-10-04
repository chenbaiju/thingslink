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
 * S13-4c-1：OTA审计时间线控制台页面。
 *
 * 本用例可以完全端到端验证：审计事实由真实业务事务写入`sys_audit_log`，
 * 因此用真实API产生一次"创建固件草稿"后，页面必须能看到对应动作、目标与操作者，
 * 且按动作过滤、只读边界与角色可见性都与服务端一致。
 */
test('OTA审计：真实动作留痕、按动作过滤与角色边界', async ({ page }) => {
  test.setTimeout(240_000)
  page.setDefaultTimeout(20_000)
  await login(page, OWNER_EMAIL, OWNER_PASSWORD)
  await enterProject(page, 'E2E项目')

  // 真实API产生一条`ota.firmware.created`审计事实（同一事务内写入）。
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
      typeKey: `e2e_audit_${suffix}`,
      name: `审计验收类型${suffix}`,
      deviceKind: 'DIRECT',
      payloadProtocol: 'STANDARD',
      networkType: 'WIFI'
    })
    await deviceApi.fetchPublishDeviceType(projectId, type.id as string)
    await http.post({
      url: `/api/v1/projects/${projectId}/device-types/${type.id as string}/product-credential`
    })
    const version = await otaApi.fetchLatestThingModelVersion(projectId, type.id as string)
    const firmwareVersion = `0.1.${Date.now() % 1000}`
    const firmware = await otaApi.createOtaFirmware(
      projectId,
      {
        deviceTypeId: type.id as string,
        thingModelVersionId: version.id as string,
        firmwareVersion
      },
      crypto.randomUUID()
    )
    return { firmwareId: firmware.id as string, firmwareVersion }
  })
  expect(fixture.firmwareId).toMatch(/^[0-9a-f-]{36}$/)

  // 1) 页面渲染真实审计行：动作、目标类型、目标ID与操作者都来自服务端事实。
  await page.goto('/#/ota/audits')
  await expect(page.locator('.ota-audits')).toBeVisible()
  const row = page.locator('tr').filter({ hasText: '创建固件草稿' }).first()
  await expect(row).toBeVisible({ timeout: 30_000 })
  await expect(row).toContainText('固件')
  await expect(row).toContainText(fixture.firmwareId)

  // 2) 明细抽屉原样投影审计明细，且能看到固件身份。
  await row.getByRole('button', { name: '查看' }).click()
  const drawer = page.getByRole('dialog', { name: '审计明细' })
  await expect(drawer).toBeVisible()
  await expect(drawer).toContainText('ota.firmware.created')
  await expect(drawer).toContainText(fixture.firmwareId)
  // ElDrawer的关闭按钮标签随语言包变化，用Esc关闭与用户操作等价且不绑定文案。
  await page.keyboard.press('Escape')
  await expect(drawer).toBeHidden()

  // 3) 按动作过滤：命中已产生的动作；换一个未发生的动作应当为空。
  const filter = page.locator('.ota-audits__filter')
  await filter.click()
  await page
    .locator('.el-select-dropdown__item')
    .filter({ hasText: '创建固件草稿' })
    .first()
    .click()
  await expect(page.locator('tr').filter({ hasText: '创建固件草稿' }).first()).toBeVisible()

  await filter.click()
  await page
    .locator('.el-select-dropdown__item')
    .filter({ hasText: '对象复验通过' })
    .first()
    .click()
  await expect(page.getByText('还没有OTA审计记录')).toBeVisible()

  // 4) 菜单过滤只影响呈现：VIEWER看不到审计菜单，服务端授权由真实PG用例单独证明。
  const projectId = await page.evaluate(async () => {
    const userPath = '/src/store/modules/user.ts'
    const { useUserStore } = await import(userPath)
    return useUserStore().info.currentProjectId as string
  })
  await ensureViewerMember(page, projectId, MEMBER_EMAIL)
  await resetSession(page)
  await login(page, MEMBER_EMAIL, MEMBER_PASSWORD)
  await enterProject(page, 'E2E项目')
  await expect(page.getByRole('menuitem', { name: 'OTA升级', exact: true })).toHaveCount(0)
})
