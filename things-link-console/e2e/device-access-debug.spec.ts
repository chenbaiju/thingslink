import { expect, test } from '@playwright/test'
import { enterProject, login, OWNER_EMAIL, OWNER_PASSWORD } from './helpers'
import { readBoundModelVersion } from './device-model-fixture'
import { currentValueMessage, publishCurrentValues } from './current-value-mqtt'

/**
 * AX-5e：设备消息调试只读面的真实浏览器旅程（连接诊断／消息时间线／消息详情）与项目切换失权。
 *
 * <p>不拦截也不伪造核心 API：页面必须走真实后端与真实数据库，因此这条旅程同时验证双端契约（生成类型）与
 * OWNER只读查询口径（VIEWER独立夹具见G3-LOCAL-2）。项目切换后的"看不到"是本条的关键断言——调试面一旦跨项目可见，
 * 就等于把最敏感的原始摘要暴露给了错误的租户。</p>
 */
test(
  '设备消息调试：时间线筛选、详情脱敏与项目切换失权',
  { tag: ['@device-access', '@simulator'] },
  async ({ page }) => {
    test.setTimeout(180_000)

    // 每轮通过正式项目接口创建独立空项目，避免借上一轮留下的成员关系冒充失权。
    const projectName = process.env.E2E_PROJECT_NAME ?? 'E2E项目'
    const otherProjectName = `E2E调试隔离${Date.now()}`
    await login(page, OWNER_EMAIL, OWNER_PASSWORD)
    await enterProject(page, projectName)
    const fixture = await page.evaluate(async (isolationName) => {
      const projectPath = '/src/api/project.ts'
      const devicePath = '/src/api/device.ts'
      const userPath = '/src/store/modules/user.ts'
      const project = await import(projectPath)
      const device = await import(devicePath)
      const { useUserStore } = await import(userPath)
      const projectId = useUserStore().info.currentProjectId as string
      const suffix = Date.now()
      const type = await device.fetchCreateDeviceType(projectId, {
        typeKey: `debug_${suffix}`,
        name: `调试模型${suffix}`,
        deviceKind: 'DIRECT',
        payloadProtocol: 'STANDARD',
        networkType: 'WIFI'
      })
      await device.fetchCreateDevicePropertyDefinition(projectId, type.id!, {
        propertyKey: 'temperature',
        name: '温度',
        dataType: 'NUMBER',
        accessType: 'REPORT',
        sortOrder: 0
      })
      await device.fetchPublishDeviceType(projectId, type.id!)
      const created = await device.fetchCreateDevice(projectId, {
        deviceTypeId: type.id!,
        deviceKey: `debug_${suffix}`,
        name: `调试设备${suffix}`
      })
      const credential = await device.fetchGenerateCredential(projectId, created.id!)
      const isolated = await project.fetchCreateProject({ name: isolationName, region: 'sh-1' })
      return {
        projectId,
        deviceId: created.id!,
        deviceKey: created.deviceKey!,
        secret: credential.plainSecret!,
        isolatedProjectId: isolated.id!
      }
    }, otherProjectName)
    expect(fixture.isolatedProjectId).toBeTruthy()
    expect(fixture.isolatedProjectId).not.toBe(fixture.projectId)
    const projectKey = process.env.E2E_PROJECT_KEY
    expect(projectKey, '必须有本轮 OWNER 的真实 MQTT 项目标识').toBeTruthy()
    const modelVersion = await readBoundModelVersion(fixture.projectId, fixture.deviceId)
    await publishCurrentValues({
      projectKey: projectKey!,
      deviceKey: fixture.deviceKey,
      secret: fixture.secret,
      port: Number(process.env.EMQX_MQTT_PORT ?? 1883),
      payload: currentValueMessage(JSON.stringify({ temperature: 26 }), modelVersion)
    })

    // 1) 本机最近访问深链重新单读设备，再通过接入工作区进入该设备的消息调试。
    await page.goto(
      `/#/device/list?resourceId=${fixture.deviceId}&contextProjectId=${fixture.projectId}`
    )
    const deviceDetail = page.locator('.device-detail')
    await expect(deviceDetail).toBeVisible()
    await deviceDetail.getByRole('tab', { name: '接入配置', exact: true }).click()
    await expect(page.getByTestId('access-current')).toContainText('MQTT')
    await deviceDetail.getByRole('button', { name: '消息调试', exact: true }).click()
    await expect(page).toHaveURL(/\/device\/messages/)
    await expect(page.getByText('日志仅保留报文摘要')).toBeVisible()
    await expect(page.getByText(/^来源设备：调试设备/)).toBeVisible()
    await expect(page).toHaveURL(new RegExp(`deviceId=${fixture.deviceId}`))

    // 2) 按消息类型筛选：类型列必须随筛选结果出现（契约新增字段在真实页面上可见）。
    await page.getByText('展开', { exact: true }).click()
    // Element Plus 的只读 input 被占位文本覆盖；点击所属选择器的真实交互区域。
    await page
      .locator('.el-select')
      .filter({ has: page.getByRole('combobox', { name: '消息类型' }) })
      .locator('.el-select__wrapper')
      .click()
    await page.getByRole('option', { name: '属性上报' }).click()
    await page.getByRole('button', { name: '查询' }).click()
    const rows = page.locator('.el-table__body tbody tr')
    await expect(rows, '必须有真实属性上报，禁止空数据跳过').not.toHaveCount(0)
    await expect(rows.first()).toContainText('PROPERTY_REPORT')

    // 3) 打开消息详情：JSON/HEX 可切换，且页面明确说明凭据已整字段移除。
    await expect(rows, '必须有真实属性上报，禁止空数据跳过').not.toHaveCount(0)
    await rows
      .first()
      .getByRole('button', { name: /查看详情|报文摘要|\{/ })
      .first()
      .click()
    const dialog = page.getByRole('dialog', { name: '消息详情' })
    await expect(dialog).toBeVisible()
    await expect(dialog).toContainText('凭据类字段由服务端整字段移除')
    await dialog.locator('.el-radio-button').filter({ hasText: 'HEX' }).click()
    await expect(dialog.locator('.message-logs__summary')).not.toBeEmpty()
    await dialog.getByRole('button', { name: '关闭', exact: true }).click()

    // 4) 连接诊断：字段集来自冻结清单（协议／配置版本／状态／会话代次）。
    await page.getByRole('button', { name: '重置' }).click()
    const firstRow = page.locator('.el-table__body tbody tr').first()
    await expect(rows, '必须有真实属性上报，禁止空数据跳过').not.toHaveCount(0)
    await firstRow.getByRole('button', { name: '连接诊断' }).click()
    const diagnostics = page.getByRole('dialog', { name: '接入连接诊断' })
    await expect(diagnostics).toBeVisible()
    await expect(diagnostics).toContainText('配置版本')
    await expect(diagnostics).toContainText('会话代次')
    await expect(diagnostics).toContainText(/连接在线|最近活动内|离线/)
    await diagnostics.getByRole('button', { name: '关闭', exact: true }).click()

    // 5) 失权：切到另一个项目后，本项目的消息与诊断不得再出现（会话＋项目作用域双约束）。
    await enterProject(page, otherProjectName)
    const loaded = page.waitForResponse(
      (response) =>
        response.url().includes('/messages') &&
        response.request().method() === 'GET' &&
        response.status() === 200
    )
    const messageMenu = page.locator('#app-sidebar').getByText('消息日志', { exact: true })
    if (!(await messageMenu.isVisible())) {
      await page.locator('#app-sidebar').getByText('设备开发', { exact: true }).click()
    }
    await messageMenu.click()
    await loaded
    await expect(page.getByText('当前筛选条件下没有消息日志')).toBeVisible()
    await expect(page.locator('.el-table__body tbody tr')).toHaveCount(0)
    await expect(page.getByText('日志仅保留报文摘要')).toBeVisible()
  }
)
