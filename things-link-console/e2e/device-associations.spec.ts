import { expect, test } from '@playwright/test'
import type { SaveDevicePropertyDefinitionRequest } from '../src/api/device'
import {
  login,
  enterProject,
  openDeviceList,
  openDeviceDetails,
  OWNER_EMAIL,
  OWNER_PASSWORD
} from './helpers'

/** 真实后端空目录接线；非空分页、角色与历史归属仍由独立HTTP/组件矩阵验证。 */
test('设备六类关联页签使用真实设备端点，空目录保留候选与历史边界', async ({ page }) => {
  test.setTimeout(180_000)
  await login(page, OWNER_EMAIL, OWNER_PASSWORD)
  const project = await page.evaluate(async () => {
    const path = '/src/api/project.ts'
    const api = await import(path)
    return (await api.fetchCreateProject({ name: `关联目录-${Date.now()}`, region: 'sh-1' })) as {
      id: string
      name: string
    }
  })
  let primaryFailure: unknown, cleanupFailure: unknown
  try {
    // API 夹具绕过列表创建回调；同一路由不会重新挂载，先刷新读取新项目。
    await page.reload()
    await enterProject(page, project.name)
    const device = await page.evaluate(async (projectId) => {
      const path = '/src/api/device.ts'
      const api = await import(path)
      const key = `assoc_${Date.now()}`
      const type = await api.fetchCreateDeviceType(projectId, {
        typeKey: key,
        name: key,
        deviceKind: 'DIRECT',
        payloadProtocol: 'STANDARD',
        networkType: 'WIFI'
      })
      await api.fetchCreateDevicePropertyDefinition(projectId, type.id, {
        propertyKey: 'relay',
        name: '继电器状态',
        dataType: 'SWITCH',
        accessType: 'REPORT',
        sortOrder: 0
      } satisfies SaveDevicePropertyDefinitionRequest)
      await api.fetchPublishDeviceType(projectId, type.id)
      return (await api.fetchCreateDevice(projectId, {
        deviceTypeId: type.id,
        deviceKey: key,
        name: key
      })) as { id: string; deviceKey: string }
    }, project.id)
    await openDeviceList(page)
    await page.getByRole('textbox', { name: '名称 / 标识', exact: true }).fill(device.deviceKey)
    await page.getByRole('button', { name: '查询', exact: true }).click()
    await openDeviceDetails(page, page.locator('tr', { hasText: device.deviceKey }).first())
    const detail = page.locator('.device-detail')
    const cases = [
      { tab: '命令', endpoint: 'commands', text: '暂无可查询的命令历史' },
      { tab: '终端用户', endpoint: 'end-users', text: '暂无当前有效终端用户' },
      { tab: '任务调度', endpoint: 'task-jobs', text: '暂无当前关联任务' },
      { tab: '自动化', endpoint: 'automations', text: '暂无当前发布的关联自动化' },
      { tab: '场景', endpoint: 'scene-candidates', text: '暂无当前发布的项目场景候选' },
      {
        tab: '消息规则',
        endpoint: 'message-rule-candidates',
        text: '暂无当前发布的项目消息规则候选'
      }
    ]
    const prefix = `/api/v1/projects/${project.id}/devices/${device.id}/`
    for (const entry of cases) {
      const response = page.waitForResponse(
        (r) =>
          r.request().method() === 'GET' && new URL(r.url()).pathname === prefix + entry.endpoint
      )
      await detail.getByRole('tab', { name: entry.tab, exact: true }).click()
      const result = await response
      expect(result.status()).toBe(200)
      expect(result.headers()['cache-control']).toBe('no-store')
      expect((await result.json()).items).toEqual([])
      await expect(detail.getByText(entry.text, { exact: true })).toBeVisible()
    }
    await expect(detail.getByText(/项目规则候选未绑定此设备/)).toBeVisible()
    for (const entry of [
      {
        button: '执行尝试',
        endpoint: 'message-rule-executions',
        text: '暂无已知设备身份的执行尝试'
      },
      { button: '设备动作', endpoint: 'message-rule-actions', text: '暂无该设备消息规则动作记录' }
    ]) {
      const response = page.waitForResponse(
        (r) =>
          r.request().method() === 'GET' && new URL(r.url()).pathname === prefix + entry.endpoint
      )
      await detail.getByRole('button', { name: entry.button, exact: true }).click()
      expect((await response).status()).toBe(200)
      await expect(detail.getByText(entry.text, { exact: true })).toBeVisible()
    }
  } catch (error) {
    primaryFailure = error
  } finally {
    try {
      await page.evaluate(async (id) => {
        const path = '/src/api/project.ts'
        const api = await import(path)
        await api.fetchDeleteProject(id)
      }, project.id)
    } catch (error) {
      cleanupFailure = error
    }
  }
  if (primaryFailure || cleanupFailure)
    throw new AggregateError(
      [primaryFailure, cleanupFailure].filter(Boolean),
      '设备关联浏览器旅程或自有项目清理失败'
    )
})
