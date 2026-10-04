import { expect, test } from '@playwright/test'
import {
  enterProject,
  ensureViewerMember,
  login,
  openDeviceDetails,
  OWNER_EMAIL,
  OWNER_PASSWORD,
  MEMBER_EMAIL,
  MEMBER_PASSWORD
} from './helpers'

/** 菜单修复的真实只读角色反例；非空分页沿设备关联 HTTP 合同验证。 */
test('设备关联：VIEWER可读历史但不可读管理配置，撤权后原页面不能继续读取', async ({
  page,
  browser
}) => {
  test.setTimeout(180_000)
  await login(page, OWNER_EMAIL, OWNER_PASSWORD)
  const project = await page.evaluate(async () => {
    const path = '/src/api/project.ts'
    const api = await import(path)
    return (await api.fetchCreateProject({ name: `关联权限-${Date.now()}`, region: 'sh-1' })) as {
      id: string
      name: string
    }
  })
  const viewerContext = await browser.newContext({ baseURL: new URL(page.url()).origin })
  const viewer = await viewerContext.newPage()
  let memberId: string | undefined
  let primaryFailure: unknown
  const cleanupFailures: unknown[] = []
  try {
    await page.reload()
    await enterProject(page, project.name)
    const device = await page.evaluate(async (projectId) => {
      const path = '/src/api/device.ts'
      const api = await import(path)
      const key = `assoc_acl_${Date.now()}`
      const type = await api.fetchCreateDeviceType(projectId, {
        typeKey: key,
        name: key,
        deviceKind: 'DIRECT',
        payloadProtocol: 'STANDARD',
        networkType: 'WIFI'
      })
      await api.fetchCreateDevicePropertyDefinition(projectId, type.id, {
        propertyKey: 'relay',
        name: '继电器',
        dataType: 'SWITCH',
        accessType: 'REPORT',
        sortOrder: 0
      })
      await api.fetchPublishDeviceType(projectId, type.id)
      return (await api.fetchCreateDevice(projectId, {
        deviceTypeId: type.id,
        deviceKey: key,
        name: key
      })) as { id: string; deviceKey: string }
    }, project.id)
    await ensureViewerMember(page, project.id, MEMBER_EMAIL)
    memberId = await page.evaluate(
      async ({ projectId, email }) => {
        const path = '/src/api/project.ts'
        const api = await import(path)
        const member = (await api.fetchProjectMembers(projectId)).find(
          (item: { email: string }) => item.email === email
        )
        if (!member?.accountId) throw new Error('缺少自有项目成员身份')
        return member.accountId as string
      },
      { projectId: project.id, email: MEMBER_EMAIL }
    )
    await login(viewer, MEMBER_EMAIL, MEMBER_PASSWORD)
    await enterProject(viewer, project.name)
    await viewer.goto('/#/device/list')
    await expect(viewer.locator('.device-list__content')).toBeVisible()
    await expect(viewer.getByRole('button', { name: '创建设备', exact: true })).toHaveCount(0)
    await viewer.getByRole('textbox', { name: '名称 / 标识', exact: true }).fill(device.deviceKey)
    await viewer.getByRole('button', { name: '查询', exact: true }).click()
    await openDeviceDetails(viewer, viewer.locator('tr', { hasText: device.deviceKey }).first())
    const detail = viewer.locator('.device-detail')
    const prefix = `/api/v1/projects/${project.id}/devices/${device.id}/`
    const cases = [
      { tab: '终端用户', endpoint: 'end-users', forbiddenButton: '' },
      { tab: '任务调度', endpoint: 'task-jobs', forbiddenButton: '' },
      { tab: '自动化', endpoint: 'automation-executions', forbiddenButton: '当前自动化' },
      { tab: '场景', endpoint: 'scene-executions', forbiddenButton: '项目场景候选' },
      { tab: '消息规则', endpoint: 'message-rule-executions', forbiddenButton: '项目消息规则候选' }
    ]
    for (const entry of cases) {
      const pending = viewer.waitForResponse(
        (r) =>
          r.request().method() === 'GET' && new URL(r.url()).pathname === prefix + entry.endpoint
      )
      await detail.getByRole('tab', { name: entry.tab, exact: true }).click()
      const response = await pending
      expect(response.status()).toBe(200)
      expect(response.headers()['cache-control']).toBe('no-store')
      expect((await response.json()).items).toEqual([])
      if (entry.forbiddenButton)
        await expect(
          detail.getByRole('button', { name: entry.forbiddenButton, exact: true })
        ).toHaveCount(0)
    }
    await detail.getByRole('tab', { name: '命令', exact: true }).click()
    await expect(detail.getByText('当前角色没有设备控制权限', { exact: true })).toBeVisible()
    // 隐藏按钮不是服务端拒绝；直接向真实管理读取接口发请求。
    const forbidden = viewer.waitForResponse(
      (r) => new URL(r.url()).pathname === prefix + 'automations'
    )
    await viewer.evaluate(async (url) => {
      const path = '/src/utils/http/index.ts'
      const { default: request } = await import(path)
      try {
        await request.get({ url })
      } catch {
        /* 由真实HTTP响应断言拒绝 */
      }
    }, prefix + 'automations')
    expect((await forbidden).status()).toBe(403)
    await page.evaluate(
      async ({ projectId, accountId }) => {
        const path = '/src/api/project.ts'
        const api = await import(path)
        await api.fetchRemoveMember(projectId, accountId)
      },
      { projectId: project.id, accountId: memberId }
    )
    memberId = undefined
    const revoked = viewer.waitForResponse(
      (r) => new URL(r.url()).pathname === prefix + 'end-users'
    )
    await viewer.evaluate(async (url) => {
      const path = '/src/store/modules/user.ts'
      const { useUserStore } = await import(path)
      // 保留原项目令牌直接验证拒绝，避免客户端自动刷新掩盖第一次响应。
      await fetch(url, { headers: { Authorization: `Bearer ${useUserStore().accessToken}` } })
    }, prefix + 'end-users')
    expect((await revoked).status()).toBe(401)
  } catch (error) {
    primaryFailure = error
  } finally {
    try {
      await viewerContext.close()
    } catch (error) {
      cleanupFailures.push(error)
    }
    try {
      await page.evaluate(
        async ({ projectId, accountId }) => {
          const path = '/src/api/project.ts'
          const api = await import(path)
          if (accountId) await api.fetchRemoveMember(projectId, accountId)
          await api.fetchDeleteProject(projectId)
        },
        { projectId: project.id, accountId: memberId }
      )
    } catch (error) {
      cleanupFailures.push(error)
    }
  }
  if (primaryFailure || cleanupFailures.length)
    throw new AggregateError(
      [primaryFailure, ...cleanupFailures].filter(Boolean),
      '设备关联权限验证或自有夹具清理失败'
    )
})
