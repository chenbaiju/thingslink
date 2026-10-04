import {
  expect,
  test,
  type Page,
  type Request,
  type WebSocket as ObservedSocket
} from '@playwright/test'
import { login, enterProject, OWNER_EMAIL, OWNER_PASSWORD } from './helpers'
import { currentValueMessage, publishCurrentValues } from './current-value-mqtt'
import { readBoundModelVersion } from './device-model-fixture'
import { captureRealJsonResponses } from './real-json-response'

test.use({ trace: 'off', video: 'off', screenshot: 'off' })
interface EventFact {
  sequence: number
  kind: 'SUBSCRIBE' | 'SUBSCRIBED' | 'INVALIDATE' | 'REQUEST' | 'RESPONSE' | 'CLOSE'
  pathname?: string
  requestId?: string
  subscriptionId?: string
  status?: number
}
/** 真实WS只观测公开业务控制帧；不读取握手头、协议数组或设备凭据。 */
test('Console实时：关联ACK后权威首读、真实MQTT提示补拉与断线显式恢复', async ({ page }) => {
  test.setTimeout(180_000)
  await page.setViewportSize({ width: 1920, height: 1080 })
  await page.addInitScript(() => {
    const NativeSocket = window.WebSocket
    // 只保存实际连接引用用于故障注入；不替换send/接收帧，也不保存构造器凭据。
    class ProbeSocket extends NativeSocket {
      constructor(url: string | URL, protocols?: string | string[]) {
        super(url, protocols)
        if (new URL(String(url), window.location.href).pathname === '/ws/dashboard/properties') {
          ;(window as unknown as { __designerRealtimeSocket: WebSocket }).__designerRealtimeSocket =
            this
        }
      }
    }
    window.WebSocket = ProbeSocket
  })
  await login(page, OWNER_EMAIL, OWNER_PASSWORD)
  await enterProject(page, 'E2E项目')
  const fixture = await page.evaluate(async () => {
    const devicePath = '/src/api/device.ts',
      userPath = '/src/store/modules/user.ts'
    const api = await import(devicePath),
      { useUserStore } = await import(userPath)
    const projectId = useUserStore().info.currentProjectId
    const suffix = Date.now(),
      deviceKey = `realtime_${suffix}`
    const type = await api.fetchCreateDeviceType(projectId, {
      typeKey: deviceKey,
      name: `实时模型${suffix}`,
      deviceKind: 'DIRECT',
      payloadProtocol: 'STANDARD',
      networkType: 'WIFI'
    })
    await api.fetchCreateDevicePropertyDefinition(projectId, type.id, {
      propertyKey: 'temperature',
      name: '温度',
      accessType: 'REPORT',
      dataType: 'NUMBER',
      unit: '℃',
      decimalPlaces: 2,
      sortOrder: 0
    })
    await api.fetchPublishDeviceType(projectId, type.id)
    const device = await api.fetchCreateDevice(projectId, {
      deviceTypeId: type.id,
      deviceKey,
      name: `实时设备${suffix}`
    })
    const credential = await api.fetchGenerateCredential(projectId, device.id)
    return {
      projectId: projectId as string,
      deviceId: device.id as string,
      deviceKey,
      credentialId: credential.id as string,
      secret: credential.plainSecret as string
    }
  })
  const facts: EventFact[] = []
  let sequence = 0
  const record = (fact: Omit<EventFact, 'sequence'>) =>
    facts.push({ sequence: ++sequence, ...fact })
  const currentPath = `/api/v1/projects/${fixture.projectId}/devices/current-value-snapshots/query`
  const captured = await captureRealJsonResponses(page, currentPath)
  const dataRequest = (request: Request) => {
    const pathname = new URL(request.url()).pathname
    if (isDataPath(pathname, fixture.projectId)) record({ kind: 'REQUEST', pathname })
  }
  const socketObserved = (socket: ObservedSocket) => {
    if (new URL(socket.url()).pathname !== '/ws/dashboard/properties') return
    const control = (payload: string | Buffer, sent: boolean) => {
      if (typeof payload !== 'string' || Buffer.byteLength(payload) > 32768) return
      let value: Record<string, unknown>
      try {
        value = JSON.parse(payload) as Record<string, unknown>
      } catch {
        return
      }
      if (sent && value.type === 'SUBSCRIBE' && typeof value.requestId === 'string') {
        record({ kind: 'SUBSCRIBE', requestId: value.requestId })
      } else if (
        !sent &&
        value.type === 'SUBSCRIBED' &&
        typeof value.requestId === 'string' &&
        typeof value.subscriptionId === 'string'
      ) {
        record({
          kind: 'SUBSCRIBED',
          requestId: value.requestId,
          subscriptionId: value.subscriptionId
        })
      } else if (!sent && value.type === 'INVALIDATE' && typeof value.subscriptionId === 'string') {
        record({ kind: 'INVALIDATE', subscriptionId: value.subscriptionId })
      }
    }
    socket.on('framesent', (event) => control(event.payload, true))
    socket.on('framereceived', (event) => control(event.payload, false))
    socket.on('close', () => record({ kind: 'CLOSE' }))
  }
  page.on('request', dataRequest)
  page.on('websocket', socketObserved)
  page.on('response', (response) => {
    const pathname = new URL(response.url()).pathname
    if (pathname === currentPath) record({ kind: 'RESPONSE', pathname, status: response.status() })
  })
  try {
    const projectKey = process.env.E2E_PROJECT_KEY
    if (!projectKey) throw new Error('缺少真实上报项目标识')
    const modelVersion = await readBoundModelVersion(fixture.projectId, fixture.deviceId)
    await page.goto('/#/dashboard/designer')
    await page.getByTestId('dashboard-create').click()
    await page.getByTestId('dashboard-name').fill(`实时草稿${Date.now()}`)
    await page.getByTestId('dashboard-create-confirm').click()
    await expect(page).toHaveURL(/dashboardId=/)
    await page.getByRole('button', { name: '读取设备目录', exact: true }).click()
    const chooser = page.getByLabel('绑定设备', { exact: true })
    await expect(chooser.locator(`option[value="${fixture.deviceId}"]`)).toHaveCount(1)
    const metadata = page.waitForResponse((response) =>
      new URL(response.url()).pathname.endsWith(`/${fixture.deviceId}/binding-metadata`)
    )
    await chooser.selectOption(fixture.deviceId)
    expect((await metadata).status()).toBe(200)
    await page
      .locator('.el-select')
      .filter({ has: page.getByRole('combobox', { name: '绑定顶层属性', exact: true }) })
      .click()
    await page.getByRole('option', { name: 'temperature（temperature）', exact: true }).click()
    await page.getByRole('button', { name: '添加设备组件', exact: true }).click()
    await expect(page.locator('[data-kind="VALUE_CARD"]')).toHaveCount(1)
    await expect(page.getByTestId('designer-save-state')).toHaveAttribute('data-status', 'saved')
    await ready(page)
    await managementLoaded(page)
    const preview = page.getByRole('region', { name: '草稿设备数据预览' })
    await expect(preview).toContainText('暂无采集值')
    const subscribe = facts.find((fact) => fact.kind === 'SUBSCRIBE')
    const ack = facts.find((fact) => fact.kind === 'SUBSCRIBED')
    const initialCurrent = facts.find(
      (fact) => fact.kind === 'REQUEST' && fact.pathname === currentPath
    )
    expect(subscribe).toBeDefined()
    expect(ack).toBeDefined()
    expect(initialCurrent).toBeDefined()
    expect(ack!.requestId).toBe(subscribe!.requestId)
    expect(subscribe!.sequence).toBeLessThan(ack!.sequence)
    expect(ack!.sequence).toBeLessThan(initialCurrent!.sequence)
    expect(facts.filter((fact) => fact.kind === 'SUBSCRIBE')).toHaveLength(1)

    const update = async (value: number, subscription: string) => {
      const before = sequence
      const response = page.waitForResponse(
        (candidate) =>
          candidate.request().method() === 'POST' &&
          new URL(candidate.url()).pathname === currentPath &&
          candidate.status() === 200
      )
      const publication = publishCurrentValues({
        projectKey,
        deviceKey: fixture.deviceKey,
        secret: fixture.secret,
        port: Number(process.env.EMQX_MQTT_PORT ?? 1883),
        payload: currentValueMessage(`{"temperature":${value}}`, modelVersion)
      })
      // PUBACK仅证明接收；必须继续看到真实INVALIDATE、PG投影响应和最终UI值。
      const [current] = await Promise.all([response, publication])
      const body = captured.read<{
        devices: {
          deviceId: string
          status: string
          values: { propertyKey: string; state: string; value?: number }[]
        }[]
      }>(current)
      const device = body.devices.find((device) => device.deviceId === fixture.deviceId)
      expect(device?.status).toBe('AVAILABLE')
      expect(
        device?.values.find((property) => property.propertyKey === 'temperature')
      ).toMatchObject({ state: 'VALUE', value })
      await expect(preview).toContainText(value.toFixed(2))
      await ready(page)
      const observed = facts.filter((fact) => fact.sequence > before)
      const hint = observed.find(
        (fact) => fact.kind === 'INVALIDATE' && fact.subscriptionId === subscription
      )
      const reread = observed.find(
        (fact) => fact.kind === 'REQUEST' && fact.pathname === currentPath
      )
      expect(hint).toBeDefined()
      expect(reread).toBeDefined()
      expect(hint!.sequence).toBeLessThan(reread!.sequence)
      const requests = observed.filter((fact) => fact.kind === 'REQUEST')
      expect(requests.map((fact) => fact.pathname)).toEqual([currentPath])
    }
    await update(13.75, ack!.subscriptionId!)

    const beforeClose = sequence
    await page.evaluate(() => {
      const socket = (window as unknown as { __designerRealtimeSocket?: WebSocket })
        .__designerRealtimeSocket
      if (!socket || socket.readyState !== WebSocket.OPEN)
        throw new Error('缺少真实已连接的实时socket')
      socket.close(1000, 'E2E explicit transport interruption')
    })
    await expect(page.getByTestId('preview-realtime-state')).toHaveAttribute(
      'data-state',
      'REST_READY'
    )
    await expect(page.getByTestId('preview-rest-state')).toHaveAttribute('data-state', 'REST_READY')
    await expect(preview).toContainText('13.75')
    await expect
      .poll(() => facts.some((fact) => fact.sequence > beforeClose && fact.kind === 'CLOSE'))
      .toBe(true)
    expect(
      facts.filter((fact) => fact.sequence > beforeClose && fact.kind === 'SUBSCRIBE')
    ).toHaveLength(0)
    expect(
      facts.filter((fact) => fact.sequence > beforeClose && fact.kind === 'REQUEST')
    ).toHaveLength(0)

    await page.getByRole('button', { name: '刷新草稿数据', exact: true }).click()
    await ready(page)
    const recoveredAck = facts.filter((fact) => fact.kind === 'SUBSCRIBED').at(-1)!
    expect(facts.filter((fact) => fact.kind === 'SUBSCRIBE')).toHaveLength(2)
    expect(recoveredAck.subscriptionId).not.toBe(ack!.subscriptionId)
    const recoveredCurrent = facts.find(
      (fact) =>
        fact.sequence > beforeClose && fact.kind === 'REQUEST' && fact.pathname === currentPath
    )
    expect(recoveredCurrent).toBeDefined()
    expect(recoveredAck.sequence).toBeLessThan(recoveredCurrent!.sequence)
    await update(14.75, recoveredAck.subscriptionId!)
  } finally {
    const evidence = { body: JSON.stringify(facts, null, 2), contentType: 'application/json' }
    await test.info().attach('console-realtime-causal-events', evidence)
    fixture.secret = ''
    await captured.stop()
    page.off('request', dataRequest)
    page.off('websocket', socketObserved)
    await page.evaluate(
      async ({ projectId, deviceId, credentialId }) => {
        const apiPath = '/src/api/device.ts',
          api = await import(apiPath)
        await api.fetchRevokeCredential(projectId, deviceId, credentialId)
      },
      {
        projectId: fixture.projectId,
        deviceId: fixture.deviceId,
        credentialId: fixture.credentialId
      }
    )
  }
})
function isDataPath(pathname: string, projectId: string) {
  const base = `/api/v1/projects/${projectId}`
  return (
    pathname === `${base}/devices/snapshots/query` ||
    pathname === `${base}/devices/current-value-snapshots/query` ||
    pathname === `${base}/devices/catalog` ||
    pathname === `${base}/alarms/query` ||
    (pathname.startsWith(`${base}/devices/`) && pathname.includes('/telemetry/property/history'))
  )
}
async function ready(page: Page) {
  await expect(page.getByTestId('preview-rest-state')).toHaveAttribute('data-state', 'REST_READY')
  await expect(page.getByTestId('preview-realtime-state')).toHaveAttribute(
    'data-state',
    'SUBSCRIBED'
  )
}
/** 新看板无发布/分享，等管理链消费完响应再观察dirty轮，避免把初载GET误归因。 */
async function managementLoaded(page: Page) {
  await expect(
    page
      .getByRole('region', { name: '看板发布与历史恢复' })
      .getByText('暂无已发布历史版本。', { exact: true })
  ).toBeVisible()
  const sharing = page.getByRole('region', { name: '匿名只读分享管理' })
  await expect(sharing.getByText('当前页没有分享记录。', { exact: true })).toBeVisible()
  await expect(sharing.getByText('暂无可选择的已发布版本。', { exact: true })).toBeVisible()
}
