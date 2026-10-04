import { expect, test, type WebSocketRoute } from '@playwright/test'
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

/**
 * 旅程 3（@simulator，需要 run-e2e-tests.sh --with-simulator）：
 * 真实 MQTT 模拟器上报 → 影子当前值 / 历史 / WebSocket 实时 → 下发命令 → ACK → 命令终态。
 *
 * 全链路真实：控制台建类型（含 temperature 属性 + reboot 命令）→ 发布 → 建设备 →
 * 生成一次性密钥 → 用该密钥启动模拟器连接真实 EMQX（projectKey 由 run 脚本从种子库查询注入）
 * → 设备在线、影子出现上报值、历史有数据点、WS 增量更新 → 下发命令，模拟器自动回 SUCCESS
 * → 命令显示执行成功。
 */
test(
  '旅程3：模拟器上报→当前值/历史/实时→命令→ACK',
  { tag: '@simulator' },
  async ({ page, request }) => {
    const projectKey = process.env.E2E_PROJECT_KEY
    expect(
      projectKey,
      'E2E_PROJECT_KEY 未注入：请用 run-e2e-tests.sh --with-simulator 运行'
    ).toBeTruthy()

    const suffix = Date.now()
    const typeKey = `e2e_sim_type_${suffix}`
    const typeName = `E2E模拟类型-${suffix}`
    const deviceKey = `e2e_sim_dev_${suffix}`

    // Playwright 通过 init script 接管 WebSocket 构造器，必须在首次导航前注册；若在当前文档
    // 已加载后再调用 routeWebSocket，只会影响后续文档，无法捕获详情页在同一 SPA 文档内创建的连接。
    let realtimeSocket: WebSocketRoute | undefined
    let realtimeConnections = 0
    await page.routeWebSocket(/\/api\/v1\/realtime\/ws$/, (socket) => {
      realtimeConnections++
      realtimeSocket = socket
      socket.connectToServer()
    })

    await login(page, OWNER_EMAIL, OWNER_PASSWORD)
    // backend 权限模式下，设备菜单只在进入项目后注册；进入预置账号的项目
    await enterProject(page, 'E2E项目')

    // 1. 建类型（DRAFT）
    await page.goto('/#/device/types')
    await page.getByRole('button', { name: '创建设备类型' }).click()
    const typeDialog = page.locator('.el-dialog:visible')
    await typeDialog.locator('input').first().fill(typeName)
    await typeDialog.getByPlaceholder('例如 temperature_sensor').fill(typeKey)
    await page.getByRole('button', { name: '确定' }).click()
    await expect(page.getByText(typeName).first()).toBeVisible({ timeout: 15_000 })
    const typeRow = page.locator('tr', { hasText: typeName }).first()

    // 2. 物模型：加 temperature 数值属性（影子/历史曲线要展示上报值，必须先在物模型声明）
    await typeRow.getByRole('button', { name: '属性' }).click()
    await page.locator('.el-dialog:visible').getByRole('button', { name: '添加属性' }).click()
    // 「添加属性」表单弹窗 append-to-body，用对话框标题定位而不是 .el-dialog:visible（列表弹窗同时可见）
    const propertyDialog = page.getByRole('dialog', { name: '添加属性' })
    await propertyDialog.locator('input').first().fill('温度')
    await propertyDialog.getByPlaceholder('例如 temperature').fill('temperature')
    await propertyDialog.getByRole('button', { name: '确定' }).click()
    await expect(page.getByText('temperature').first()).toBeVisible({ timeout: 15_000 })
    // 等表单弹窗关闭（Element Plus 淡出动画期间仍算可见），再关「属性定义」列表弹窗
    await expect(page.getByRole('dialog', { name: '添加属性' })).toBeHidden({ timeout: 10_000 })
    await page
      .getByRole('dialog', { name: /属性定义/ })
      .getByLabel('关闭此对话框')
      .click()

    // 3. 加 reboot 命令（命令下发入口需要物模型命令定义）
    await typeRow.getByRole('button', { name: '命令' }).click()
    await page.locator('.el-dialog:visible').getByRole('button', { name: '添加命令' }).click()
    const commandDialog = page.getByRole('dialog', { name: '添加命令' })
    await commandDialog.locator('input').first().fill('重启')
    await commandDialog.getByPlaceholder('例如 reboot').fill('reboot')
    await commandDialog.getByRole('button', { name: '确定' }).click()
    await expect(page.getByText('reboot').first()).toBeVisible({ timeout: 15_000 })
    await expect(page.getByRole('dialog', { name: '添加命令' })).toBeHidden({ timeout: 10_000 })
    await page
      .getByRole('dialog', { name: /命令定义/ })
      .getByLabel('关闭此对话框')
      .click()

    // 4. 发布
    await typeRow.getByRole('button', { name: '发布' }).click()
    await page.getByRole('button', { name: '确定发布' }).click()
    await expect(typeRow.getByText('已发布')).toBeVisible({ timeout: 15_000 })

    // 5. 建设备并绑定类型：等待实际列表交互区，不依赖已移除的标题。
    await openDeviceList(page)
    await page.getByRole('button', { name: '创建设备', exact: true }).click()
    const deviceDialog = page.getByRole('dialog', { name: '创建设备' })
    await deviceDialog.locator('input').first().fill(`E2E模拟设备-${suffix}`)
    await deviceDialog.getByPlaceholder('例如 sensor_01').fill(deviceKey)
    await deviceDialog.locator('.el-select').first().click()
    await page.getByText(typeName, { exact: true }).last().click()
    const historyIdentity = observeCreatedHistoryDevice(page, deviceKey)
    await deviceDialog.getByRole('button', { name: '确定' }).click()
    const targetHistoryDevice = await historyIdentity
    await expect(page.getByText(deviceKey).first()).toBeVisible({ timeout: 15_000 })
    const deviceRow = page.locator('tr', { hasText: deviceKey }).first()

    // 6. 生成一次性密钥并取出明文 Access Token
    const credDialog = await openDeviceCredentials(page, deviceRow)
    await credDialog.getByRole('button', { name: '生成新密钥' }).click()
    const secretInput = credDialog.locator('.cred-secret input')
    await expect(secretInput).toBeVisible({ timeout: 15_000 })
    const accessToken = (await secretInput.inputValue()).trim()
    expect(accessToken.length).toBeGreaterThan(10)
    // 关闭凭据弹窗：before-close 要求先勾选「已保存」
    await credDialog.getByText('我已复制并安全保存该密钥').click()
    await credDialog.locator('.el-dialog__headerbtn').click()

    // 7. 启动模拟器：真实 MQTT 连接 EMQX，username={projectKey}/{deviceKey}，password=明文密钥
    const startResp = await request.post('http://localhost:8090/simulations/start', {
      data: {
        brokerUri: 'tcp://localhost:1883',
        projectKey,
        devices: [{ deviceKey, accessToken, gateway: false }],
        intervalSeconds: 2,
        autoReplyCommands: true,
        // A4-0 将 manifest 隔离身份与单报文属性数提升为启动契约；旅程只定义 temperature，故固定为 1。
        runId: `e2e-journey-3-${suffix}`,
        shardId: 'shard-000',
        propertiesPerReport: 1
      }
    })
    if (!startResp.ok()) {
      throw new Error(`启动模拟器失败：HTTP ${startResp.status()} ${await startResp.text()}`)
    }

    // 8. 用既有在线事实有界刷新，不以固定 5 秒推测已经发生属性上报。
    await expect(async () => {
      await page.reload()
      await expect(deviceRow.getByText('在线')).toBeVisible({ timeout: 3_000 })
    }).toPass({ timeout: 30_000 })

    // 9. 详情：影子「上报状态」出现 temperature + 历史至少一个点 + WebSocket 增量更新
    // Q6 先取得目标真实上报，再由范围控件发起新历史查询。WebSocket 路由只做代理并连接真实后端，
    // 保存页面侧 route 供步骤 10 注入 1012 断线；全部帧默认双向透传，不 mock 实时数据。
    await verifyReportedHistory(
      page,
      targetHistoryDevice,
      () => openDeviceDetails(page, deviceRow),
      20_000
    )
    const reportedSection = page.locator('.shadow-section', { hasText: '上报状态' }).last()
    const reportedTextarea = reportedSection.locator('textarea')
    // WebSocket 增量：必须先证明实时连接已建立，再等下一次上报后值变化；
    // 不能靠固定等待掩盖「一直停在 connecting」的握手问题。
    await expect(page.getByText('实时连接已建立')).toBeVisible({ timeout: 20_000 })
    const beforeValue = await reportedTextarea.inputValue()
    await expect(async () => {
      const value = await reportedTextarea.inputValue()
      expect(value).not.toBe(beforeValue)
    }).toPass({ timeout: 30_000 })

    // 10. WS 断线/重连/补拉：代理主动关闭页面侧真实连接后必须显示断开；自动重连后不仅恢复状态，
    //     还必须真实发出 current-values REST 补拉，覆盖 Redis Pub/Sub 在断线窗口允许丢失的增量。
    const beforeDisconnect = await reportedTextarea.inputValue()
    const backfillResponse = page.waitForResponse(
      (response) =>
        response.request().method() === 'POST' &&
        response.url().includes('/devices/current-values/query'),
      { timeout: 30_000 }
    )
    expect(realtimeConnections).toBe(1)
    if (!realtimeSocket) throw new Error('实时 WebSocket 未被测试代理捕获')
    await realtimeSocket.close({ code: 1012, reason: 'e2e injected disconnect' })
    await expect(page.getByText('实时连接已断开')).toBeVisible({ timeout: 15_000 })
    await expect.poll(() => realtimeConnections, { timeout: 30_000 }).toBeGreaterThanOrEqual(2)
    await expect(page.getByText('实时连接已建立')).toBeVisible({ timeout: 30_000 })
    expect((await backfillResponse).status()).toBe(200)
    await expect(async () => {
      expect(await reportedTextarea.inputValue()).not.toBe(beforeDisconnect)
    }).toPass({ timeout: 30_000 })

    // 11. 响应丢失：首个命令 POST 必须真实到达后端，再由浏览器路由丢弃响应；第二次点击复用
    //     同一 Idempotency-Key，回读同一命令 ID。随后模拟器自动回 SUCCESS → 终态「执行成功」。
    await page.locator('.device-detail').getByRole('tab', { name: '命令', exact: true }).click()
    const commandPanel = page.locator('.command-panel')
    await expect(commandPanel).toBeVisible({ timeout: 10_000 })
    const commandKeys: string[] = []
    let acceptedCommandId = ''
    let commandPosts = 0
    await page.route('**/api/v1/projects/*/devices/*/commands', async (route) => {
      if (route.request().method() !== 'POST') {
        await route.continue()
        return
      }
      commandPosts++
      commandKeys.push((await route.request().headerValue('idempotency-key')) ?? '')
      if (commandPosts === 1) {
        const upstream = await route.fetch()
        const body = (await upstream.json()) as { id?: string }
        acceptedCommandId = body.id ?? ''
        // 后端已经返回 202，此处只切断浏览器可见响应，复现“事实已提交、客户端不知道结果”。
        await route.abort('connectionfailed')
        return
      }
      await route.continue()
    })
    const submitCommand = commandPanel.getByRole('button', { name: '下发命令' })
    await submitCommand.click()
    await expect.poll(() => commandPosts).toBe(1)
    await expect(submitCommand).toBeEnabled({ timeout: 10_000 })
    expect(acceptedCommandId).not.toBe('')

    await submitCommand.click()
    await expect(page.getByText('命令已受理').first()).toBeVisible({ timeout: 10_000 })
    await expect.poll(() => commandPosts).toBe(2)
    expect(commandKeys[0]).not.toBe('')
    expect(commandKeys[1]).toBe(commandKeys[0])
    const commandResult = commandPanel.locator('.command-result')
    await expect(commandResult.getByText(acceptedCommandId, { exact: true })).toBeVisible()
    await page.unroute('**/api/v1/projects/*/devices/*/commands')
    await expect(async () => {
      await commandPanel.getByRole('button', { name: '刷新状态' }).click()
      await expect(commandResult.getByText('执行成功', { exact: true })).toBeVisible({
        timeout: 3_000
      })
    }).toPass({ timeout: 30_000 })
  }
)
