import { expect, type Page, type Request } from '@playwright/test'
import {
  reportFromMessageLogs,
  synchronizeHistory,
  type ReportIdentity,
  type HistoryObservation
} from './history-contract'

type Identity = ReportIdentity

/** 只从本次真实创建响应取得项目/设备标识，避免把同名 DOM 行当数据隔离依据。 */
export async function observeCreatedHistoryDevice(
  page: Page,
  deviceKey: string
): Promise<Identity> {
  const response = await page.waitForResponse(
    (item) =>
      item.request().method() === 'POST' &&
      /^\/api\/v1\/projects\/[^/]+\/devices$/.test(new URL(item.url()).pathname) &&
      item.request().postDataJSON()?.deviceKey === deviceKey
  )
  expect(response.status()).toBe(201)
  const body = (await response.json()) as { id?: string; deviceKey?: string; createdAt?: string }
  expect(body.deviceKey).toBe(deviceKey)
  expect(body.id).toBeTruthy()
  expect(body.createdAt).toBeTruthy()
  return {
    projectId: new URL(response.url()).pathname.split('/')[4],
    deviceId: body.id!,
    propertyKey: 'temperature',
    createdAt: body.createdAt!
  }
}

/**
 * Q6-R1：保留 reported UI 断言，再从真实持久化消息日志取事实，最后由范围控件触发新历史请求。
 * 请求序号来自注册在打开详情之前的只读事件观察器；旧响应即使迟到也不能复用。
 */
export async function verifyReportedHistory(
  page: Page,
  identity: Identity,
  openDetail: () => Promise<unknown>,
  reportedTimeout: number
) {
  let sequence = 0
  const sequences = new WeakMap<Request, number>()
  const observe = (request: Request) => {
    sequences.set(request, ++sequence)
  }
  page.on('request', observe)
  const currentPath = `/api/v1/projects/${identity.projectId}/devices/current-values/query`
  const currentRequest = page.waitForRequest(
    (request) =>
      new URL(request.url()).pathname === currentPath &&
      request.method() === 'POST' &&
      request.postDataJSON()?.deviceIds?.includes(identity.deviceId),
    { timeout: 30_000 }
  )
  // 当前值请求失败应沿主 Promise 报错，不能在打开详情本身失败后遗留未处理 rejection。
  void currentRequest.catch(() => undefined)
  try {
    await openDetail()
    // 详情默认打开概览；属性页延迟挂载，须经真实页签操作显示影子和历史控件。
    await page.locator('.device-detail').getByRole('tab', { name: '属性', exact: true }).click()
    await expect(
      page.locator('.shadow-section', { hasText: '上报状态' }).last().locator('textarea')
    ).toHaveValue(/temperature/, { timeout: reportedTimeout })
    let observed: HistoryObservation | undefined
    const report = await synchronizeHistory(
      async () => {
        const sourceRequest = await currentRequest
        const authorization = await sourceRequest.headerValue('authorization')
        if (!authorization) throw new Error('真实当前值请求缺少会话授权')
        // 当前值请求只提供会话授权，不消费其毫秒缓存时间；消息页不轮询，不修改共享事实。
        const url = new URL(
          `/api/v1/projects/${identity.projectId}/devices/${identity.deviceId}/messages`,
          sourceRequest.url()
        )
        url.search = new URLSearchParams({
          direction: 'UP',
          protocol: 'MQTT',
          from: identity.createdAt,
          to: new Date().toISOString(),
          limit: '200'
        }).toString()
        const response = await page.request.get(url.toString(), { headers: { authorization } })
        expect(response.status()).toBe(200)
        return reportFromMessageLogs(identity, {
          url: url.toString(),
          method: 'GET',
          status: response.status(),
          body: await response.json()
        })
      },
      async () => {
        const afterSequence = sequence
        const path = `/api/v1/projects/${identity.projectId}/devices/${identity.deviceId}/telemetry/property/history`
        const freshRequest = page.waitForRequest(
          (request) => new URL(request.url()).pathname === path && request.method() === 'GET',
          { timeout: 30_000 }
        )
        void freshRequest.catch(() => undefined)
        await page.locator('.history-toolbar .el-select').nth(1).click()
        await page.getByRole('option', { name: '最近 1 小时', exact: true }).click()
        const request = await freshRequest
        const response = await request.response()
        if (!response) throw new Error('新历史请求没有响应')
        observed = {
          url: request.url(),
          method: request.method(),
          requestSequence: sequences.get(request) ?? -1,
          afterSequence,
          status: response.status(),
          body: await response.json()
        }
        return observed
      }
    )
    // 白名单事实供普通 L3/正式资格归档，无凭据或完整网络日志。
    console.info(`G2_HISTORY_SYNC ${JSON.stringify({ report, observation: observed })}`)
    return report
  } finally {
    page.off('request', observe)
  }
}
