import { expect, test, type Page } from '@playwright/test'
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

/** 读取本次用例刚创建的固件草稿身份；列表是唯一权威来源，不靠界面属性推断。 */
async function findFirmware(
  page: Page,
  version: string
): Promise<{ id: string; revision: string; uploadSessionId: string }> {
  return page.evaluate(async (target) => {
    const otaPath = '/src/api/ota.ts'
    const userPath = '/src/store/modules/user.ts'
    const otaApi = await import(otaPath)
    const { useUserStore } = await import(userPath)
    const projectId = useUserStore().info.currentProjectId as string
    const page_ = await otaApi.fetchOtaFirmwares(projectId, undefined, 100)
    const items = (page_.items ?? []) as Array<{
      id?: string
      revision?: string
      firmwareVersion?: string
    }>
    const found = items.find((item) => item.firmwareVersion === target)
    if (!found?.id) throw new Error(`未找到固件 ${target}`)
    return {
      id: found.id,
      revision: found.revision ?? '',
      uploadSessionId: ''
    }
  }, version)
}

/**
 * S13-4a：OTA固件控制台闭环。
 *
 * 覆盖平台在本机真实运行时能诚实断言的链路：已发布设备类型 → 物模型版本读取 → 固件草稿 →
 * 私有对象上传与复验 → 提交发布尝试 → 环境边界如实拒绝且不产生READY事实 → 取消草稿进入终态；
 * 另验证非管理角色看不到OTA菜单（菜单过滤不是授权，服务端授权由真实PG用例单独证明）。
 *
 * 本用例**不**声称发布成功、刷写成功或G3资格：没有受控signer时服务端必须fail-closed，
 * 断言点就是"被拒绝且没有伪造成功事实"。
 */
test('OTA固件：草稿、上传复验、发布边界与取消终态', async ({ page }) => {
  test.setTimeout(300_000)
  page.setDefaultTimeout(20_000)
  await login(page, OWNER_EMAIL, OWNER_PASSWORD)
  await enterProject(page, 'E2E项目')

  // 通过真实管理API准备已发布设备类型，并证明控制台真的能读到物模型版本身份。
  const fixture = await page.evaluate(async () => {
    const devicePath = '/src/api/device.ts'
    const otaPath = '/src/api/ota.ts'
    const userPath = '/src/store/modules/user.ts'
    const deviceApi = await import(devicePath)
    const otaApi = await import(otaPath)
    const { useUserStore } = await import(userPath)
    const projectId = useUserStore().info.currentProjectId as string
    const suffix = Date.now().toString(36)
    const created = await deviceApi.fetchCreateDeviceType(projectId, {
      typeKey: `e2e_ota_${suffix}`,
      name: `OTA验收类型${suffix}`,
      deviceKind: 'DIRECT',
      payloadProtocol: 'STANDARD',
      networkType: 'WIFI'
    })
    await deviceApi.fetchPublishDeviceType(projectId, created.id as string)
    // 固件模型资格要求类型已有产品标识：productKey由产品凭据生成，缺失时创建草稿必被70003拒绝。
    const httpPath = '/src/utils/http/index.ts'
    const http = (await import(httpPath)).default
    const credential = await http.post({
      url: `/api/v1/projects/${projectId}/device-types/${created.id as string}/product-credential`
    })
    const version = await otaApi.fetchLatestThingModelVersion(projectId, created.id as string)
    return {
      deviceTypeId: created.id as string,
      productKey: (credential.productKey as string) ?? '',
      versionId: version.id as string,
      versionNumber: version.versionNumber as string,
      schemaDigest: version.schemaDigest as string
    }
  })
  expect(fixture.versionNumber).toMatch(/^\d+\.\d+\.\d+$/)
  expect(fixture.schemaDigest).toMatch(/^[0-9a-f]{64}$/)

  // 1) 固件草稿：物模型版本由控制台显式读取后自动回填，不允许手工捏造。
  await page.goto('/#/ota/firmwares')
  await expect(page.locator('.ota-firmwares')).toBeVisible()
  await page.getByRole('button', { name: '创建固件草稿' }).click()
  const createDialog = page.getByRole('dialog', { name: '创建固件草稿' })
  await createDialog.getByTestId('ota-create-device-type').click()
  await page.locator('.el-select-dropdown__item').filter({ hasText: 'OTA验收类型' }).first().click()
  await expect(createDialog.locator('.ota-firmwares__resolved')).toContainText(fixture.versionId)
  const firmwareVersion = `1.0.${Date.now() % 1000}`
  await page.getByPlaceholder('例如 1.0.1').fill(firmwareVersion)
  await page.getByRole('button', { name: '创建草稿' }).click()
  await expect(page.locator('.el-message').filter({ hasText: '固件草稿已创建' })).toBeVisible()

  const draft = await findFirmware(page, firmwareVersion)
  const row = page.locator('tr').filter({ hasText: firmwareVersion }).first()
  await expect(row).toBeVisible()
  await expect(row.locator('.el-tag')).toContainText('草稿')

  // 2) 上传对象：长度与摘要由浏览器计算，服务端复验后才算事实。
  const artifact = Buffer.from('thingslink-ota-e2e-artifact', 'utf8')
  const artifactSha256 = await page.evaluate(async (text) => {
    const otaPath = '/src/api/ota.ts'
    const api = await import(otaPath)
    const bytes = new TextEncoder().encode(text)
    return api.sha256Hex(bytes.buffer as ArrayBuffer)
  }, 'thingslink-ota-e2e-artifact')

  const uploadDialog = page.getByRole('dialog', { name: '上传固件对象' })
  await expect(uploadDialog).toBeVisible({ timeout: 30_000 })
  await uploadDialog.locator('input[type=file]').setInputFiles({
    name: 'firmware.bin',
    mimeType: 'application/octet-stream',
    buffer: artifact
  })
  await expect(uploadDialog.locator('.ota-firmwares__mono').last()).toHaveText(artifactSha256)
  await uploadDialog.getByRole('button', { name: '创建会话并上传' }).click()
  const sessionStatus = uploadDialog.getByTestId('ota-upload-session')
  await expect(sessionStatus.locator('.el-tag')).toContainText('对象已复验', { timeout: 90_000 })
  const uploadSessionId = await sessionStatus.locator('.ota-firmwares__mono').textContent()
  expect(uploadSessionId).toMatch(/^[0-9a-f-]{36}$/)
  await uploadDialog.getByRole('button', { name: '关闭', exact: true }).click()

  // 3) 提交发布尝试：清单形状由控制台校验，语义与签名由服务端与外部signer判定。
  await row.getByRole('button', { name: '提交发布' }).click()
  const publishDialog = page.getByRole('dialog', { name: '提交发布尝试' })
  await publishDialog
    .getByPlaceholder('已完成对象复验的上传会话ID')
    .fill('00000000-0000-7000-8000-000000000000')
  await publishDialog.getByPlaceholder('固件当前修订').fill(draft.revision)
  await publishDialog.getByPlaceholder('粘贴发布流程冻结的 tc-ota-manifest/v1 JSON').fill('{}')
  await publishDialog.getByRole('button', { name: '提交发布' }).click()
  await expect(publishDialog.locator('.el-alert')).toContainText(
    'contractVersion必须是tc-ota-manifest/v1'
  )

  const manifest = {
    contractVersion: 'tc-ota-manifest/v1',
    firmwareId: draft.id,
    firmwareVersion,
    trustDomain: 'e2e-ota-domain',
    deviceTypeId: fixture.deviceTypeId,
    productKey: fixture.productKey,
    hardware: { model: 'E2E-BOARD', boardRevisionMin: 1, boardRevisionMax: 1 },
    bootloaderMinimumVersion: '1.0.0',
    artifactSize: artifact.byteLength,
    artifactSha256,
    compression: 'NONE',
    delta: { mode: 'NONE' },
    securityVersion: 2,
    thingModelVersionId: fixture.versionId,
    thingModelSchemaDigestAlgorithm: 'PG_JSONB_TEXT_V1_SHA256',
    thingModelSchemaDigest: fixture.schemaDigest,
    allowedSourceThingModelVersionIds: [],
    requirements: {
      profile: 'TC_PROPERTY_COMPOSITE_V1',
      minimumRamBytes: 1,
      minimumFlashBytes: 1,
      requiresAbSlots: false,
      requiresRangeDownload: false,
      requiresProtectedSecurityCounter: true
    },
    signatureProfile: 'TC_OTA_ED25519_V1',
    signingKeyFingerprint: 'f'.repeat(64),
    minimumTrustBundleVersion: 1
  }
  await publishDialog
    .getByPlaceholder('粘贴发布流程冻结的 tc-ota-manifest/v1 JSON')
    .fill(JSON.stringify(manifest))
  await publishDialog.getByPlaceholder('已完成对象复验的上传会话ID').fill(uploadSessionId as string)
  await publishDialog.getByRole('button', { name: '提交发布' }).click()
  // 本环境没有受控signer（或尚未登记发布信任），服务端必须拒绝；界面必须如实呈现拒绝。
  await expect(publishDialog.getByTestId('ota-publish-outcome')).toBeVisible({ timeout: 90_000 })
  await publishDialog.getByRole('button', { name: '关闭', exact: true }).click()

  // 4) 拒绝之后固件仍是草稿：没有任何本地或服务端的伪造成功事实。
  await page.reload()
  await expect(row.locator('.el-tag')).toContainText('草稿')

  // 5) 取消草稿进入终态，上传与发布入口都消失。
  await row.getByRole('button', { name: /更多/ }).click()
  await page.getByRole('menu', { name: '更多' }).getByRole('menuitem', { name: '取消草稿' }).click()
  await page.getByRole('button', { name: '取消草稿' }).last().click()
  await expect(row.locator('.el-tag')).toContainText('已取消')
  await expect(row.getByRole('button', { name: '上传对象' })).toHaveCount(0)
  await expect(row.getByRole('button', { name: '提交发布' })).toHaveCount(0)

  // 6) 菜单过滤只影响呈现：把成员以VIEWER加入本项目后看不到OTA菜单，
  //    服务端授权由真实PG用例单独证明（种子里的成员是自己项目的OWNER，不能直接复用）。
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
