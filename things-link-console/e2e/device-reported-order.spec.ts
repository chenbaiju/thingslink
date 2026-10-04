import { expect, test, type Request, type WebSocketRoute } from '@playwright/test'
import {
  login,
  enterProject,
  openDeviceList,
  openDeviceDetails,
  OWNER_EMAIL,
  OWNER_PASSWORD
} from './helpers'
import { currentValueMessage, publishCurrentValues } from './current-value-mqtt'
import { readBoundModelVersion } from './device-model-fixture'

test.use({ trace: 'off', video: 'off', screenshot: 'off' })
test('设备详情迟到真实 REST 不覆盖同时间新 WS 接受事实', async ({ page }) => {
  test.setTimeout(180_000)
  let realtimeSocket: WebSocketRoute | undefined
  // 在首次导航前安装真实WS透传，只用断线触发正常重连后的REST补读。
  await page.routeWebSocket(/\/api\/v1\/realtime\/ws$/, (socket) => {
    realtimeSocket = socket
    socket.connectToServer()
  })
  await test.step(
    '登录并选择真实项目',
    async () => {
      await login(page, OWNER_EMAIL, OWNER_PASSWORD)
      await enterProject(page, 'E2E项目')
    },
    { timeout: 45_000 }
  )
  const fixture = await test.step(
    '创建真实模型、设备和凭据',
    async () =>
      page.evaluate(async () => {
        const apiPath = '/src/api/device.ts',
          storePath = '/src/store/modules/user.ts'
        const api = await import(apiPath),
          { useUserStore } = await import(storePath)
        const projectId = useUserStore().info.currentProjectId,
          suffix = Date.now()
        const deviceKey = `order_${suffix}`
        const type = await api.fetchCreateDeviceType(projectId, {
          typeKey: deviceKey,
          name: `接受顺序模型${suffix}`,
          deviceKind: 'DIRECT',
          payloadProtocol: 'STANDARD',
          networkType: 'WIFI'
        })
        await api.fetchCreateDevicePropertyDefinition(projectId, type.id, {
          propertyKey: 'temperature',
          name: '温度',
          dataType: 'NUMBER',
          accessType: 'REPORT',
          decimalPlaces: 2,
          minimumValue: -100,
          maximumValue: 100,
          sortOrder: 0
        })
        await api.fetchPublishDeviceType(projectId, type.id)
        const device = await api.fetchCreateDevice(projectId, {
          deviceTypeId: type.id,
          deviceKey,
          name: `接受顺序设备${suffix}`
        })
        const credential = await api.fetchGenerateCredential(projectId, device.id)
        return {
          projectId: projectId as string,
          deviceId: device.id as string,
          deviceKey,
          credentialId: credential.id as string,
          secret: credential.plainSecret as string
        }
      }),
    { timeout: 60_000 }
  )
  let primaryFailure: unknown, cleanupFailure: unknown
  let releaseTimer: ReturnType<typeof setTimeout> | undefined
  let releaseOld: (() => void) | undefined
  try {
    const projectKey = process.env.E2E_PROJECT_KEY
    if (!projectKey) throw new Error('缺少真实上报项目标识')
    const modelVersion = await readBoundModelVersion(fixture.projectId, fixture.deviceId)
    const occurredAt = new Date().toISOString()
    const publish = (value: number) =>
      publishCurrentValues({
        projectKey,
        deviceKey: fixture.deviceKey,
        secret: fixture.secret,
        port: Number(process.env.EMQX_MQTT_PORT ?? 1883),
        payload: currentValueMessage(`{"temperature":${value}}`, modelVersion).replace(
          /"occurredAt":"[^"]+"/,
          `"occurredAt":"${occurredAt}"`
        )
      })
    await test.step('真实 MQTT 提交初始接受值', () => publish(13.75), { timeout: 17_000 })
    let sourceId = '',
      oldRevision = ''
    // PUBACK 不等于 PG 成功；真实 REST 的值、接受序号与来源三者齐备才进入交错。
    await test.step(
      '等 PG 当前值、序号和来源齐备',
      async () => {
        await expect(async () => {
          const fact = await page.evaluate(async ({ projectId, deviceId }) => {
            const path = '/src/api/device.ts',
              api = await import(path)
            return (
              await api.fetchBatchCurrentValues(projectId, {
                deviceIds: [deviceId],
                propertyKeys: ['temperature']
              })
            ).items[0]
          }, fixture)
          expect(fact.values.temperature).toBe(13.75)
          expect(fact.reportedRevisions.temperature).toMatch(/^[1-9][0-9]*$/)
          expect(fact.thingModelVersionIds.temperature).toMatch(/^[0-9a-f-]{36}$/)
          sourceId = fact.thingModelVersionIds.temperature
          oldRevision = fact.reportedRevisions.temperature
        }).toPass({ timeout: 30_000 })
      },
      { timeout: 35_000 }
    )
    await test.step(
      '导航真实设备菜单并核验页面',
      async () => {
        await openDeviceList(page)
      },
      { timeout: 20_000 }
    )
    // 只延迟真实 HTTP 正文，不生成成功响应或改造 WS 业务帧。
    let holdNext = false,
      held = false,
      delivered = false,
      timedOut = false,
      failed = false
    let started = 0,
      oldRequest: Request | undefined
    let routingFailure: unknown
    const remaining = () => Math.max(1, started + 12_000 - Date.now())
    page.on('requestfailed', (request) => {
      if (request === oldRequest) failed = true
    })
    const gate = new Promise<void>((resolve) => {
      releaseOld = resolve
    })
    const path = `/api/v1/projects/${fixture.projectId}/devices/current-values/query`
    await page.route(`**${path}`, async (route) => {
      if (
        !holdNext ||
        oldRequest ||
        !route.request().postDataJSON()?.deviceIds?.includes(fixture.deviceId)
      ) {
        await route.continue()
        return
      }
      try {
        oldRequest = route.request()
        started = Date.now()
        releaseTimer = setTimeout(() => {
          timedOut = true
          releaseOld?.()
        }, 12_000)
        const response = await route.fetch({ timeout: 8000 })
        expect(response.status()).toBe(200)
        const body = await response.json()
        expect(body.items[0].values.temperature).toBe(13.75)
        expect(body.items[0].reportedRevisions.temperature).toBe(oldRevision)
        held = true
        await gate
        await route.fulfill({ response })
        delivered = true
      } catch (error) {
        routingFailure = error
        await route.abort().catch(() => undefined)
      } finally {
        if (releaseTimer) clearTimeout(releaseTimer)
      }
    })
    await test.step(
      '进入真实设备详情列表并筛选目标',
      async () => {
        const row = page.locator('tr', { hasText: fixture.deviceKey }).first()
        // 使用真实搜索过滤，避免随机夹具不在当前首屏。
        const search = page.getByRole('textbox', { name: '名称 / 标识', exact: true })
        await search.fill(fixture.deviceKey)
        await page.getByRole('button', { name: '查询', exact: true }).click()
        await expect(row).toBeVisible({ timeout: 15_000 })
        await openDeviceDetails(page, row, 5000)
        await page.locator('.device-detail').getByRole('tab', { name: '属性', exact: true }).click()
        await expect(page.getByText('实时连接已建立', { exact: true })).toBeVisible()
        await expect(
          page.locator('.shadow-section', { hasText: '上报状态' }).last().locator('textarea')
        ).toHaveValue(/13\.75/)
      },
      { timeout: 20_000 }
    )
    // 只观察原 HTTP API Promise 正常交付，不替换业务返回或延长生产 timeout。
    await test.step(
      '安装只读API交付观察器',
      async () =>
        page.evaluate(
          async ({ path, oldRevision }) => {
            const modulePath = '/src/utils/http/index.ts'
            const api = (await import(modulePath)).default
            const original = api.post
            const state = window as unknown as {
              __reportedDelivered?: boolean
              __restoreReportedObserver?: () => void
            }
            state.__reportedDelivered = false
            api.post = (config: { url: string }) => {
              const request = original(config)
              if (config.url !== path) return request
              return request.then(
                (value: { items?: Array<{ reportedRevisions?: Record<string, string> }> }) => {
                  if (value.items?.[0]?.reportedRevisions?.temperature === oldRevision) {
                    // 下一任务在调用者 await 和 Vue 的微任务刷新之后执行。
                    setTimeout(() => {
                      state.__reportedDelivered = true
                    }, 0)
                  }
                  return value
                }
              )
            }
            state.__restoreReportedObserver = () => {
              api.post = original
            }
          },
          { path, oldRevision }
        ),
      { timeout: 5000 }
    )
    await test.step(
      '12秒内真实旧REST与新WS交错并确认交付',
      async () => {
        // 初载完成后才扣住重连补读，避免测试自身的挂起请求锁住详情加载遮罩。
        holdNext = true
        if (!realtimeSocket) throw new Error('缺少真实已连接的设备实时socket')
        await realtimeSocket.close({ code: 1012, reason: 'e2e late REST ordering' })
        await expect.poll(() => held || routingFailure !== undefined, { timeout: 8000 }).toBe(true)
        if (routingFailure !== undefined) throw routingFailure
        await expect(page.getByText('实时连接已建立', { exact: true })).toBeVisible({
          timeout: remaining()
        })
        const reported = page
          .locator('.shadow-section', { hasText: '上报状态' })
          .last()
          .locator('textarea')
        await publish(14.75)
        await expect(async () =>
          expect(JSON.parse(await reported.inputValue()).temperature).toBe(14.75)
        ).toPass({ timeout: remaining(), intervals: [100, 200, 300] })
        await expect(page.getByTestId('reported-property-sources')).toContainText(sourceId, {
          timeout: remaining()
        })
        // 观察真实 textarea 写入，防止旧值瞬间回退后又被补拉修正而假通过；不改业务返回。
        await reported.evaluate((element) => {
          const descriptor = Object.getOwnPropertyDescriptor(
            HTMLTextAreaElement.prototype,
            'value'
          )!
          Object.defineProperty(element, 'value', {
            get() {
              return descriptor.get!.call(this)
            },
            set(value: string) {
              try {
                if (JSON.parse(value).temperature === 13.75)
                  element.setAttribute('data-regressed', 'true')
              } catch {
                /* 非业务文本不计。 */
              }
              descriptor.set!.call(this, value)
            }
          })
        })
        const oldDelivered = page.waitForResponse(
          (response) => response.request() === oldRequest && response.status() === 200,
          { timeout: remaining() }
        )
        expect(timedOut).toBe(false)
        expect(failed).toBe(false)
        releaseOld!()
        const finalResponse = await oldDelivered
        const finishError = await bounded(
          finalResponse.finished(),
          remaining(),
          '旧REST正文未在交错截止内完成'
        )
        expect(finishError).toBeNull()
        await expect.poll(() => delivered, { timeout: remaining() }).toBe(true)
        await expect
          .poll(
            () =>
              page.evaluate(
                () => (window as unknown as { __reportedDelivered?: boolean }).__reportedDelivered
              ),
            { timeout: remaining() }
          )
          .toBe(true)
        expect(failed).toBe(false)
        expect(timedOut).toBe(false)
        expect(Date.now() - started).toBeLessThan(12_000)
        await expect(reported).not.toHaveAttribute('data-regressed', 'true')
        await expect(async () => {
          expect(JSON.parse(await reported.inputValue()).temperature).toBe(14.75)
          const text = await page.getByTestId('reported-property-sources').innerText()
          const revision = text.match(/接受序号\s+(\d+)/)?.[1]
          expect(revision).toBeDefined()
          expect(BigInt(revision!)).toBeGreaterThan(BigInt(oldRevision))
        }).toPass({ timeout: remaining() })
      },
      { timeout: 14_000 }
    )
  } catch (error) {
    primaryFailure = error
  } finally {
    if (releaseTimer) clearTimeout(releaseTimer)
    releaseOld?.()
    fixture.secret = ''
    try {
      await test.step(
        '恢复观察器并撤销测试凭据',
        async () =>
          page.evaluate(async ({ projectId, deviceId, credentialId }) => {
            ;(
              window as unknown as { __restoreReportedObserver?: () => void }
            ).__restoreReportedObserver?.()
            const path = '/src/api/device.ts',
              api = await import(path)
            await api.fetchRevokeCredential(projectId, deviceId, credentialId)
          }, fixture),
        { timeout: 20_000 }
      )
    } catch (error) {
      cleanupFailure = error
    }
  }
  if (primaryFailure !== undefined) {
    if (cleanupFailure !== undefined)
      test.info().annotations.push({
        type: 'cleanup-failure',
        description: '测试凭据撤销失败；保留原始验收失败为首因。'
      })
    throw primaryFailure
  }
  if (cleanupFailure !== undefined) throw new Error('测试凭据撤销失败', { cause: cleanupFailure })
})

/** 包装没有独立timeout参数的Playwright完成等待，超时保留明确阶段首因。 */
async function bounded<T>(promise: Promise<T>, timeout: number, message: string): Promise<T> {
  let timer: ReturnType<typeof setTimeout> | undefined
  try {
    return await Promise.race([
      promise,
      new Promise<never>((_, reject) => {
        timer = setTimeout(() => reject(new Error(message)), timeout)
      })
    ])
  } finally {
    if (timer) clearTimeout(timer)
  }
}
