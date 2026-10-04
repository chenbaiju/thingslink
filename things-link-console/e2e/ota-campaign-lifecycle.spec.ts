import { expect, test, type Page } from '@playwright/test'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'

/**
 * S13-4d-2b-2b-13：真实栈上的OTA活动生命周期浏览器旅程（关闭D-154路径①）。
 *
 * 与既有OTA旅程不同，本用例由 `run-e2e-tests.sh` 在**后端启动前**预置固定
 * (tenant, project, deviceType) 夹具、注入与已提交根签名信任包一致的受控类型基线
 * 与信任锚，并启动实现ADR0139的受控signer桩。因此这里可以在真实浏览器里把固件推到
 * READY，再驱动活动 排程→启动→暂停→恢复→取消。生产语义未改变：基线仍是精确三元组、
 * 无通配默认，后端没有新增任何测试端点。
 *
 * 本用例诚实划定的边界：没有真实设备报告（浏览器栈无法产生），设备准入必然
 * IDENTITY_CHANGED；因此受控准入调度在**本测试后端**按worker自带的测试开关停用
 * （`things-link.ota.campaign.runtime-enabled=false`，生产默认仍为true），使人工
 * 暂停/恢复/取消确定性可验。真实派发（通知、下载、安装、批次推进）由4g与4d-2b-2b
 * 的真实PG/MinIO/EMQX证据覆盖，本用例不声称。
 */

interface LifecycleFixture {
  tenantId: string
  projectId: string
  deviceTypeId: string
  thingModelVersionId: string
  projectName: string
  ownerEmail: string
  trustDomain: string
  productKey: string
  releaseFingerprint: string
  rootFingerprint: string
  bootloaderMinimumVersion: string
  hardware: { model: string; boardRevisionMin: number; boardRevisionMax: number }
}

const FIXTURE_DIR = process.env.E2E_OTA_FIXTURE_DIR ?? resolve(process.cwd(), 'e2e/fixtures')
const fixture = JSON.parse(
  readFileSync(resolve(FIXTURE_DIR, 'ota-lifecycle-fixture.json'), 'utf8')
) as LifecycleFixture
const bundleEnvelope = JSON.parse(
  readFileSync(resolve(FIXTURE_DIR, 'ota-trust-bundle.json'), 'utf8')
) as Record<string, unknown>
const OWNER_EMAIL = process.env.E2E_OTA_OWNER_EMAIL ?? fixture.ownerEmail
const OWNER_PASSWORD = process.env.E2E_OTA_OWNER_PASSWORD ?? process.env.E2E_OWNER_PASSWORD ?? ''

test.use({ trace: 'off', video: 'off', screenshot: 'off' })

/** 读取当前项目的固件草稿身份；列表是唯一权威来源。 */
async function findFirmware(
  page: Page,
  version: string
): Promise<{ id: string; revision: string }> {
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
    return { id: found.id, revision: found.revision ?? '' }
  }, version)
}

/** 活动创建对话框把最早启动时间预填为5分钟后；旅程需要已到期的时间才能启动。 */
async function setNotBeforeInPast(page: Page) {
  const input = page.getByPlaceholder('到达该时间后才能启动第一批')
  await input.click()
  await input.fill('2020-01-01 00:00:00')
  await input.press('Enter')
  // 关闭时间面板而不触发 ElDialog 的 Escape 关闭。
  await page.locator('.el-dialog__header').first().click()
}

/** 暂停/恢复/取消都通过 ElMessageBox 要求非空原因；确认按钮是主按钮。 */
async function confirmReason(page: Page, reason: string) {
  const box = page.locator('.el-message-box')
  await expect(box).toBeVisible()
  await box.locator('input').fill(reason)
  // 取消动作的确认按钮与默认关闭按钮同名，按主按钮定位。
  await box.locator('.el-message-box__btns .el-button--primary').last().click()
}

test.describe('OTA活动生命周期（真实栈，固定夹具+受控signer桩）', () => {
  test.skip(
    process.env.E2E_OTA_LIFECYCLE !== 'true',
    '需要 run-e2e-tests.sh 的生命周期模式（固定夹具+受控signer桩）'
  )

  test('固件发布到READY并驱动活动 排程→启动→暂停→恢复→取消', async ({ page }) => {
    test.setTimeout(600_000)
    page.setDefaultTimeout(20_000)

    const { login, enterProject } = await import('./helpers')
    await login(page, OWNER_EMAIL, OWNER_PASSWORD)
    await enterProject(page, fixture.projectName)

    // 0) 控制台当前项目必须就是固定夹具项目——否则后续断言会打在别的项目上。
    const userPath = '/src/store/modules/user.ts'
    const projectId = await page.evaluate(async (path) => {
      const { useUserStore } = await import(path)
      return useUserStore().info.currentProjectId as string
    }, userPath)
    expect(projectId).toBe(fixture.projectId)

    // 1) 通过真实管理API导入根签名信任包并登记受控类型基线（控制台无该写入口）。
    const provisioned = await page.evaluate(
      async (input) => {
        const httpPath = '/src/utils/http/index.ts'
        const http = (await import(httpPath)).default
        const trust = await http.post({
          url: `/api/v1/projects/${input.projectId}/ota/trust-domains/${input.trustDomain}/bundles`,
          params: input.envelope,
          headers: { 'Idempotency-Key': crypto.randomUUID() }
        })
        const baseline = await http.post({
          url: `/api/v1/projects/${input.projectId}/ota/device-types/${input.deviceTypeId}/baseline/registrations`,
          params: { expectedRevision: '0' },
          headers: { 'Idempotency-Key': crypto.randomUUID() }
        })
        return {
          bundleVersion: String((trust as Record<string, unknown>).bundleVersion),
          baselineHash: String((baseline as Record<string, unknown>).baselineHash)
        }
      },
      {
        projectId: fixture.projectId,
        trustDomain: fixture.trustDomain,
        deviceTypeId: fixture.deviceTypeId,
        envelope: bundleEnvelope
      }
    )
    expect(provisioned.bundleVersion).toBe('1')
    expect(provisioned.baselineHash).toMatch(/^[0-9a-f]{64}$/)

    // 2) 固定设备类型已由夹具预发布，创建一台真实设备作为活动目标。
    const device = await page.evaluate(
      async (input) => {
        const devicePath = '/src/api/device.ts'
        const deviceApi = await import(devicePath)
        const created = await deviceApi.fetchCreateDevice(input.projectId, {
          deviceTypeId: input.deviceTypeId,
          deviceKey: `e2eotalifecycle${Date.now().toString(36)}`,
          name: 'E2E-OTA生命周期设备'
        })
        return { id: created.id as string, name: created.name as string }
      },
      { projectId: fixture.projectId, deviceTypeId: fixture.deviceTypeId }
    )
    expect(device.name).toBe('E2E-OTA生命周期设备')

    // 3) 固件草稿：设备类型由夹具预置，物模型版本由控制台真实读取后自动回填。
    await page.goto('/#/ota/firmwares')
    await expect(page.locator('.ota-firmwares')).toBeVisible()
    await page.getByRole('button', { name: '创建固件草稿' }).click()
    const createDialog = page.getByRole('dialog', { name: '创建固件草稿' })
    await createDialog.getByTestId('ota-create-device-type').click()
    await page
      .locator('.el-select-dropdown__item')
      .filter({ hasText: 'E2E-OTA生命周期类型' })
      .first()
      .click()
    await expect(createDialog.locator('.ota-firmwares__resolved')).toContainText(
      fixture.thingModelVersionId
    )
    const firmwareVersion = `1.0.${Date.now() % 1000}`
    await page.getByPlaceholder('例如 1.0.1').fill(firmwareVersion)
    await page.getByRole('button', { name: '创建草稿' }).click()
    await expect(page.locator('.el-message').filter({ hasText: '固件草稿已创建' })).toBeVisible()

    const draft = await findFirmware(page, firmwareVersion)
    const row = page.locator('tr').filter({ hasText: firmwareVersion }).first()
    await expect(row).toBeVisible()
    await expect(row.locator('.el-tag')).toContainText('草稿')

    // 4) 真实对象上传与复验（真实MinIO，桶已由部署侧版本化）。
    const artifactText = 'thingslink-ota-lifecycle-e2e-artifact'
    const artifact = Buffer.from(artifactText, 'utf8')
    const artifactSha256 = await page.evaluate(
      async (input) => {
        const otaApi = await import(input.path)
        return otaApi.sha256Hex(new TextEncoder().encode(input.text).buffer as ArrayBuffer)
      },
      { path: '/src/api/ota.ts', text: artifactText }
    )
    const uploadDialog = page.getByRole('dialog', { name: '上传固件对象' })
    await expect(uploadDialog).toBeVisible({ timeout: 30_000 })
    await uploadDialog.locator('input[type=file]').setInputFiles({
      name: 'firmware.bin',
      mimeType: 'application/octet-stream',
      buffer: artifact
    })
    await uploadDialog.getByRole('button', { name: '创建会话并上传' }).click()
    const sessionStatus = uploadDialog.getByTestId('ota-upload-session')
    await expect(sessionStatus.locator('.el-tag')).toContainText('对象已复验', { timeout: 90_000 })
    const uploadSessionId = (
      (await sessionStatus.locator('.ota-firmwares__mono').textContent()) ?? ''
    ).trim()
    expect(uploadSessionId).toMatch(/^[0-9a-f-]{36}$/)
    await uploadDialog.getByRole('button', { name: '关闭', exact: true }).click()

    // 5) 提交发布尝试：根签名信任包与受控signer桩使服务端真的产出READY。
    const model = await page.evaluate(
      async (input) => {
        const otaPath = '/src/api/ota.ts'
        const otaApi = await import(otaPath)
        const version = await otaApi.fetchLatestThingModelVersion(
          input.projectId,
          input.deviceTypeId
        )
        return { id: version.id as string, digest: version.schemaDigest as string }
      },
      { projectId: fixture.projectId, deviceTypeId: fixture.deviceTypeId }
    )
    expect(model.id).toBe(fixture.thingModelVersionId)
    const manifest = {
      contractVersion: 'tc-ota-manifest/v1',
      firmwareId: draft.id,
      firmwareVersion,
      trustDomain: fixture.trustDomain,
      deviceTypeId: fixture.deviceTypeId,
      productKey: fixture.productKey,
      hardware: fixture.hardware,
      bootloaderMinimumVersion: fixture.bootloaderMinimumVersion,
      artifactSize: artifact.byteLength,
      artifactSha256,
      compression: 'NONE',
      delta: { mode: 'NONE' },
      securityVersion: 1,
      thingModelVersionId: fixture.thingModelVersionId,
      thingModelSchemaDigestAlgorithm: 'PG_JSONB_TEXT_V1_SHA256',
      thingModelSchemaDigest: model.digest,
      allowedSourceThingModelVersionIds: [fixture.thingModelVersionId],
      requirements: {
        profile: 'TC_PROPERTY_COMPOSITE_V1',
        minimumRamBytes: 1,
        minimumFlashBytes: 1,
        requiresAbSlots: true,
        requiresRangeDownload: true,
        requiresProtectedSecurityCounter: true
      },
      signatureProfile: 'TC_OTA_ED25519_V1',
      signingKeyFingerprint: fixture.releaseFingerprint,
      minimumTrustBundleVersion: 1
    }
    await row.getByRole('button', { name: '提交发布' }).click()
    const publishDialog = page.getByRole('dialog', { name: '提交发布尝试' })
    await publishDialog.getByPlaceholder('已完成对象复验的上传会话ID').fill(uploadSessionId)
    await publishDialog.getByPlaceholder('固件当前修订').fill(draft.revision)
    await publishDialog
      .getByPlaceholder('粘贴发布流程冻结的 tc-ota-manifest/v1 JSON')
      .fill(JSON.stringify(manifest))
    await publishDialog.getByRole('button', { name: '提交发布' }).click()
    await expect(page.locator('.el-message').filter({ hasText: '已建立发布尝试' })).toBeVisible()
    await publishDialog.getByRole('button', { name: '关闭', exact: true }).click()

    // 服务端异步完成发布：先轮询真实后端事实，再刷新列表断言控制台呈现。
    await expect
      .poll(
        async () =>
          page.evaluate(
            async (input) => {
              const otaApi = await import(input.path)
              const detail = await otaApi.fetchOtaFirmware(input.projectId, input.firmwareId)
              return detail.status as string
            },
            { path: '/src/api/ota.ts', projectId: fixture.projectId, firmwareId: draft.id }
          ),
        { timeout: 120_000, intervals: [1_000] }
      )
      .toBe('READY')
    await page.reload()
    await expect(row.locator('.el-tag')).toContainText('已就绪', { timeout: 30_000 })
    const ready = await findFirmware(page, firmwareVersion)

    // 6) 创建活动（真实设备类型+设备），断言草稿事实。
    await page.goto('/#/ota/campaigns')
    await expect(page.locator('.ota-campaigns')).toBeVisible()
    await page.getByRole('button', { name: '创建活动' }).click()
    const campaignDialog = page.getByRole('dialog', { name: '创建灰度活动' })
    await campaignDialog.getByTestId('ota-campaign-firmware').click()
    await page
      .locator('.el-select-dropdown__item')
      .filter({ hasText: firmwareVersion })
      .first()
      .click()
    await campaignDialog.getByTestId('ota-campaign-devices').click()
    await page.locator('.el-select-dropdown__item').filter({ hasText: device.name }).first().click()
    await page.keyboard.press('Escape')
    await setNotBeforeInPast(page)
    await campaignDialog.getByRole('button', { name: '创建草稿' }).click()
    await expect(page.locator('.el-message').filter({ hasText: '活动草稿已创建' })).toBeVisible()

    const drawer = page.locator('.el-drawer')
    const status = drawer.getByTestId('ota-campaign-status')
    await expect(status).toHaveText('草稿')
    const campaignPaths = { ota: '/src/api/ota.ts', user: '/src/store/modules/user.ts' }
    const campaignId = await page.evaluate(async (paths) => {
      const otaApi = await import(paths.ota)
      const { useUserStore } = await import(paths.user)
      const project = useUserStore().info.currentProjectId as string
      const list = await otaApi.fetchOtaCampaigns(project)
      const id = (list.items ?? [])[0]?.id
      if (!id) throw new Error('未找到刚创建的活动')
      return id as string
    }, campaignPaths)
    expect(campaignId).toMatch(/^[0-9a-f-]{36}$/)

    // 7) 排程 → 启动 → 暂停 → 恢复 → 取消：每一步都由真实服务端裁决并回读。
    await drawer.getByRole('button', { name: '冻结目标并排程' }).click()
    await expect(status).toHaveText('已排程', { timeout: 30_000 })
    await expect(drawer.getByText('1 / 1')).toBeVisible()

    await drawer.getByRole('button', { name: '启动第一批' }).click()
    await expect(status).toHaveText('运行中', { timeout: 30_000 })

    await drawer.getByRole('button', { name: '暂停', exact: true }).click()
    await confirmReason(page, 'E2E生命周期：人工暂停')
    await expect(status).toHaveText('已暂停', { timeout: 30_000 })
    await expect(drawer.getByText(/MANUAL：E2E生命周期：人工暂停/)).toBeVisible()

    await drawer.getByRole('button', { name: '恢复', exact: true }).click()
    await confirmReason(page, 'E2E生命周期：人工恢复')
    await expect(status).toHaveText('运行中', { timeout: 30_000 })

    // 未出现"放行下一批"：人工扩批只在当前批SUCCEEDED后呈现，而成功批需要真实设备收敛。
    await expect(drawer.getByRole('button', { name: '放行下一批' })).toHaveCount(0)

    await drawer.getByRole('button', { name: '取消活动' }).click()
    await confirmReason(page, 'E2E生命周期：取消')
    await expect(status).toHaveText('已取消', { timeout: 60_000 })

    // 8) 断言控制台背后的真实后端事实：目标数、作业终态、批次终态与真实准入原因。
    const facts = await page.evaluate(
      async (input) => {
        const otaPath = '/src/api/ota.ts'
        const otaApi = await import(otaPath)
        const detail = await otaApi.fetchOtaCampaign(input.projectId, input.campaignId)
        const execution = await otaApi.fetchOtaCampaignExecution(input.projectId, input.campaignId)
        const eligibility = await otaApi.fetchOtaDeviceEligibility(
          input.projectId,
          input.deviceId,
          input.firmwareId
        )
        return {
          status: detail.status,
          targetCount: detail.targetCount,
          batchCount: detail.batchCount,
          jobStatuses: ((detail.jobs ?? []) as Array<{ status?: string }>).map((job) => job.status),
          batchStatus: execution.batchProgress?.currentBatchStatus,
          requiresManualApproval: execution.batchProgress?.requireManualBatchApproval,
          eligible: eligibility.eligible,
          eligibilityReason: eligibility.reason
        }
      },
      {
        projectId: fixture.projectId,
        campaignId,
        deviceId: device.id,
        firmwareId: ready.id
      }
    )
    expect(facts.status).toBe('CANCELLED')
    expect(facts.targetCount).toBe(1)
    expect(facts.batchCount).toBe(1)
    expect(facts.jobStatuses).toEqual(['CANCELLED'])
    expect(facts.batchStatus).toBe('CANCELLED')
    expect(facts.requiresManualApproval).toBe(true)
    // 没有真实设备报告：准入如实给出未合格原因，而不是伪造合格。
    expect(facts.eligible).toBe(false)
    expect(['IDENTITY_CHANGED', 'REPORT_MISSING']).toContain(facts.eligibilityReason)
  })
})
