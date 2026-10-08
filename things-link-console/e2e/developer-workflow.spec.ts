import { expect, test } from '@playwright/test'
import {
  enterProject,
  login,
  openDeviceCredentials,
  openDeviceDetails,
  OWNER_EMAIL,
  OWNER_PASSWORD
} from './helpers'
import { simulatorRuntime } from './mqtt-runtime'

// 一次性设备凭据只在内存中消费；禁止默认失败产物把密钥或认证请求写入附件。
test.use({ trace: 'off', video: 'off', screenshot: 'off', actionTimeout: 20_000 })

// 一次性凭据只留在浏览器内存，失败时也不生成页面快照。
test.afterEach(async ({ page }) => {
  await page.close()
})

test(
  '开发者闭环：同一物模型与设备经严格TLS上报、消息调试、规则执行到看板实际值',
  { tag: '@simulator' },
  async ({ page, request }, testInfo) => {
    test.setTimeout(300_000)
    const runtime = simulatorRuntime()
    expect(runtime.brokerUri, '本闭环必须使用严格TLS模拟器配置').toMatch(/^ssl:\/\//)
    const projectKey = process.env.E2E_PROJECT_KEY
    if (!projectKey) throw new Error('缺少本轮模拟器项目标识')
    const suffix = Date.now()
    const typeName = `开发闭环模型${suffix}`
    const deviceName = `开发闭环设备${suffix}`
    const deviceKey = `workflow_${suffix}`
    const ruleName = `开发闭环规则${suffix}`
    let ruleId = ''
    let projectId = ''
    let simulatorStarted = false

    await page.setViewportSize({ width: 1920, height: 1080 })
    await login(page, OWNER_EMAIL, OWNER_PASSWORD)
    await enterProject(page, process.env.E2E_PROJECT_NAME ?? 'E2E项目')
    try {
      // 模型、属性、发布均为真实浏览器操作，不用API夹具冒充开发流程。
      await page.goto('/#/device/types')
      await page.getByRole('button', { name: '创建设备类型', exact: true }).click()
      const typeDialog = page.getByRole('dialog', { name: '创建设备类型', exact: true })
      await typeDialog.locator('input').first().fill(typeName)
      await typeDialog.getByPlaceholder('例如 temperature_sensor').fill(`workflow_${suffix}`)
      await typeDialog.getByRole('button', { name: '确定', exact: true }).click()
      const typeRow = page
        .locator('tr')
        .filter({ has: page.getByText(typeName, { exact: true }) })
        .first()
      await expect(typeRow).toBeVisible()
      await typeRow.getByRole('button', { name: '属性', exact: true }).click()
      await page
        .getByRole('dialog', { name: /属性定义/ })
        .getByRole('button', { name: '添加属性', exact: true })
        .click()
      const propertyDialog = page.getByRole('dialog', { name: '添加属性', exact: true })
      await propertyDialog.locator('input').first().fill('温度')
      await propertyDialog.getByPlaceholder('例如 temperature').fill('temperature')
      await propertyDialog.getByRole('button', { name: '确定', exact: true }).click()
      await expect(propertyDialog).toBeHidden()
      await page
        .getByRole('dialog', { name: /属性定义/ })
        .getByLabel('关闭此对话框')
        .click()
      await typeRow.getByRole('button', { name: '发布', exact: true }).click()
      await page.getByRole('button', { name: '确定发布', exact: true }).click()
      await expect(typeRow).toContainText('已发布')
      await typeRow.getByRole('button', { name: typeName, exact: true }).click()
      const workspace = page.locator('.device-types__workspace')
      await expect(workspace).toContainText('已发布 · 物模型已冻结')
      await workspace.getByRole('button', { name: '继续创建设备', exact: true }).click()
      const deviceDialog = page.getByRole('dialog', { name: '创建设备', exact: true })
      await expect(deviceDialog.locator('.el-select')).toContainText(typeName)
      await deviceDialog.getByRole('textbox', { name: '设备名称', exact: true }).fill(deviceName)
      await deviceDialog.getByRole('textbox', { name: '设备标识符', exact: true }).fill(deviceKey)
      const createdResponse = page.waitForResponse(
        (response) =>
          response.request().method() === 'POST' &&
          /\/api\/v1\/projects\/[^/]+\/devices$/.test(new URL(response.url()).pathname)
      )
      await deviceDialog.getByRole('button', { name: '确定', exact: true }).click()
      const created = await createdResponse
      expect(created.ok()).toBeTruthy()
      const device = (await created.json()) as { id: string; deviceTypeId: string }
      projectId = new URL(created.url()).pathname.split('/')[4]
      const deviceRow = page
        .locator('tr')
        .filter({ has: page.getByText(deviceKey, { exact: true }) })
        .first()
      await expect(deviceRow).toBeVisible()

      const credentials = await openDeviceCredentials(page, deviceRow)
      await credentials.getByRole('button', { name: '生成新密钥', exact: true }).click()
      const secretInput = credentials.locator('.cred-secret input')
      await expect(secretInput).toBeVisible()
      const accessToken = (await secretInput.inputValue()).trim()
      expect(accessToken.length).toBeGreaterThan(10)
      await credentials.getByText('我已复制并安全保存该密钥').click()
      await credentials.getByLabel('关闭此对话框').click()
      await expect(credentials).toBeHidden()

      await openDeviceDetails(page, deviceRow)
      // 接入配置读取使用真实设备详情页面，不能仅凭模拟器连接参数推断控制台可用。
      await page
        .locator('.device-detail')
        .getByRole('tab', { name: '接入配置', exact: true })
        .click()
      await expect(page.getByTestId('access-current')).toContainText('MQTT')
      await expect(page.getByTestId('access-current')).toContainText('已启用')

      const start = async () => {
        const response = await request.post(`${runtime.baseURL}/simulations/start`, {
          data: {
            brokerUri: runtime.brokerUri,
            projectKey,
            devices: [{ deviceKey, accessToken, gateway: false }],
            intervalSeconds: 2,
            autoReplyCommands: false,
            runId: `e2e-developer-workflow-${suffix}`,
            shardId: 'shard-000',
            propertiesPerReport: 1
          }
        })
        expect(response.ok(), `模拟器启动 HTTP ${response.status()}`).toBeTruthy()
        simulatorStarted = true
      }
      await start()
      await page.getByRole('button', { name: '消息调试', exact: true }).click()
      await expect(page).toHaveURL(/\/device\/messages/)
      await expect(page.getByText(`来源设备：${deviceName}`, { exact: true })).toBeVisible()
      await expect(async () => {
        await page.getByRole('button', { name: '查询', exact: true }).click()
        const messageRow = page
          .locator('.message-logs tbody tr')
          .filter({ hasText: deviceName })
          .first()
        await expect(messageRow).toContainText('MQTT', { timeout: 3000 })
        await expect(messageRow).toContainText('temperature', { timeout: 3000 })
      }).toPass({ timeout: 30_000 })

      // UI保存与启用真实透传规则；不调用调试接口来伪造执行成功。
      await page.goto('/#/rule/messages')
      await page.getByRole('button', { name: '创建规则', exact: true }).click()
      const ruleDialog = page.getByRole('dialog', { name: '消息规则版本管理', exact: true })
      await ruleDialog.getByRole('textbox', { name: '规则名称', exact: true }).fill(ruleName)
      await ruleDialog
        .getByRole('textbox', { name: '规则源码', exact: true })
        .fill('input => input')
      const ruleSaved = page.waitForResponse(
        (response) =>
          response.request().method() === 'POST' &&
          /\/projects\/[^/]+\/message-rules$/.test(new URL(response.url()).pathname)
      )
      await ruleDialog.getByRole('button', { name: '保存新版本', exact: true }).click()
      const saved = await ruleSaved
      expect(saved.ok()).toBeTruthy()
      ruleId = ((await saved.json()) as { id: string }).id
      await ruleDialog.getByRole('button', { name: '发布此版本', exact: true }).click()
      await page.getByRole('button', { name: '确定', exact: true }).click()
      await expect(ruleDialog).toContainText('状态 ACTIVE')
      await ruleDialog.getByLabel('关闭此对话框').click()

      // 只读回查将执行的messageId与该设备真实MQTT消息关联，项目内其他设备不能贡献PASS。
      let messageId = ''
      await expect(async () => {
        const result = await page.evaluate(
          async ({ projectId, deviceId, ruleId }) => {
            const devicePath = '/src/api/device.ts'
            const rulePath = '/src/api/rule-execution.ts'
            const deviceApi = await import(devicePath)
            const ruleApi = await import(rulePath)
            const [messages, executions] = await Promise.all([
              deviceApi.fetchDeviceMessages(projectId, deviceId, undefined, 50),
              ruleApi.fetchRuleExecutions(projectId, { ruleId, limit: 50 })
            ])
            const ids = new Set(
              messages.items
                .filter(
                  (item: {
                    deviceId: string
                    direction: string
                    protocol: string
                    errorCode?: string
                  }) =>
                    item.deviceId === deviceId &&
                    item.direction === 'UP' &&
                    item.protocol === 'MQTT' &&
                    !item.errorCode
                )
                .map((item: { messageId: string }) => item.messageId)
            )
            return (
              executions.items.find(
                (item: { ruleId: string; messageId: string; status: string }) =>
                  item.ruleId === ruleId && item.status === 'SUCCESS' && ids.has(item.messageId)
              )?.messageId ?? ''
            )
          },
          { projectId, deviceId: device.id, ruleId }
        )
        expect(result).not.toBe('')
        messageId = result
      }).toPass({ timeout: 40_000 })
      expect((await request.post(`${runtime.baseURL}/simulations/stop`)).ok()).toBeTruthy()
      simulatorStarted = false
      await page.getByRole('button', { name: '执行记录', exact: true }).click()
      await expect(page.getByRole('tab', { name: '上行规则执行', exact: true })).toHaveAttribute(
        'aria-selected',
        'true'
      )
      await page
        .locator('.el-select')
        .filter({ has: page.getByRole('combobox', { name: '消息规则', exact: true }) })
        .locator('.el-select__wrapper')
        .click()
      await page.getByRole('option', { name: ruleName, exact: true }).click()
      await page.getByRole('button', { name: '查询', exact: true }).click()
      const executionRow = page
        .locator('.rule-executions tbody tr')
        .filter({ hasText: ruleName })
        .first()
      await expect(executionRow).toContainText('成功')
      await executionRow.getByRole('button', { name: '时间线', exact: true }).click()
      const timeline = page.getByRole('dialog', { name: ruleName, exact: true })
      // stop前最后一个在途报文可能晚于首次成功轮询；关联当前UI选中的真实记录而非猜测排序。
      const messageIds = await page.evaluate(
        async ({ projectId, deviceId }) => {
          const path = '/src/api/device.ts'
          const api = await import(path)
          const messages = await api.fetchDeviceMessages(projectId, deviceId, undefined, 50)
          return messages.items
            .filter(
              (item: {
                deviceId: string
                protocol: string
                direction: string
                errorCode?: string
              }) =>
                item.deviceId === deviceId &&
                item.protocol === 'MQTT' &&
                item.direction === 'UP' &&
                !item.errorCode
            )
            .map((item: { messageId: string }) => item.messageId) as string[]
        },
        { projectId, deviceId: device.id }
      )
      const timelineText = await timeline.innerText()
      messageId = messageIds.find((id) => timelineText.includes(id)) ?? ''
      expect(messageId).not.toBe('')
      await expect(timeline.locator('.el-timeline')).toContainText('成功')
      await timeline.getByLabel('关闭此对话框').click()

      // 同一设备从下一步入口进入看板，显式选择绑定；来源提示不会偷偷写入草稿。
      await page.goto(`/#/device/list?resourceId=${device.id}&contextProjectId=${projectId}`)
      await expect(page.locator('.device-detail')).toContainText(deviceName)
      await page.getByRole('button', { name: '看板设计', exact: true }).click()
      await expect(page.getByRole('region', { name: '来源设备', exact: true })).toContainText(
        deviceName
      )
      await page.getByTestId('dashboard-create').click()
      await page.getByTestId('dashboard-name').fill(`开发闭环看板${suffix}`)
      await page.getByTestId('dashboard-create-confirm').click()
      await expect(page).toHaveURL(/dashboardId=/)
      await page.getByRole('button', { name: '读取设备目录', exact: true }).click()
      const chooser = page.getByLabel('绑定设备', { exact: true })
      await expect(chooser.locator(`option[value="${device.id}"]`)).toHaveCount(1)
      await chooser.selectOption(device.id)
      await page
        .locator('.el-select')
        .filter({ has: page.getByRole('combobox', { name: '绑定顶层属性', exact: true }) })
        .click()
      await page.getByRole('option', { name: 'temperature（temperature）', exact: true }).click()
      await page.getByRole('button', { name: '添加设备组件', exact: true }).click()
      await expect(page.locator('[data-kind="VALUE_CARD"]')).toHaveCount(1)
      await expect(page.getByTestId('designer-save-state')).toHaveAttribute('data-status', 'saved')
      await page.reload()
      await expect(page.locator('[data-kind="VALUE_CARD"]')).toHaveCount(1)
      const currentResponse = page.waitForResponse(
        (response) =>
          response.request().method() === 'POST' &&
          new URL(response.url()).pathname ===
            `/api/v1/projects/${projectId}/devices/current-value-snapshots/query` &&
          response.ok()
      )
      await page.getByRole('button', { name: '刷新草稿数据', exact: true }).click()
      const current = (await (await currentResponse).json()) as {
        devices: {
          deviceId: string
          status: string
          values: {
            propertyKey: string
            state: string
            value?: number
            reportedModelVersionId?: string
          }[]
        }[]
      }
      const item = current.devices.find((item) => item.deviceId === device.id)
      expect(item?.status).toBe('AVAILABLE')
      const value = item?.values.find((item) => item.propertyKey === 'temperature')
      expect(value?.state).toBe('VALUE')
      expect(typeof value?.value).toBe('number')
      const preview = page.getByRole('region', { name: '草稿设备数据预览', exact: true })
      await expect(preview).toContainText(value!.value!.toFixed(2))
      await expect(preview).not.toContainText('暂无采集值')
      await testInfo.attach('developer-workflow-public-receipt', {
        contentType: 'application/json',
        body: Buffer.from(
          JSON.stringify({
            projectId,
            deviceId: device.id,
            deviceTypeId: device.deviceTypeId,
            ruleId,
            messageId,
            propertyKey: 'temperature',
            value: value!.value,
            reportedModelVersionId: value!.reportedModelVersionId,
            transport: 'MQTT_TLS',
            dashboardUrl: page.url()
          })
        )
      })
    } finally {
      if (simulatorStarted)
        expect((await request.post(`${runtime.baseURL}/simulations/stop`)).ok()).toBeTruthy()
      // 本用例创建的项目级规则及时暂停，避免影响后续旅程；不删除其他资源或关闭共享服务。
      if (ruleId)
        await page.evaluate(
          async ({ projectId, ruleId }) => {
            const path = '/src/api/rule-management.ts'
            const api = await import(path)
            const current = await api.getRule(projectId, ruleId)
            if (current.status === 'ACTIVE') await api.pauseRule(projectId, ruleId, current.version)
          },
          { projectId, ruleId }
        )
    }
  }
)
