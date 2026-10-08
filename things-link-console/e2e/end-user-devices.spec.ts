import { expect, test } from '@playwright/test'
import {
  login,
  enterProject,
  openDeviceList,
  openDeviceDetails,
  OWNER_EMAIL,
  OWNER_PASSWORD
} from './helpers'

test('设备详情签发令牌，真实App认领后用户目录受权解绑且旧设备访问拒绝', async ({ page }) => {
  test.skip(!process.env.E2E_END_USER_PROJECT_NAME, '需要独立已配置套餐的管理项目')
  test.setTimeout(180_000)
  await login(page, OWNER_EMAIL, OWNER_PASSWORD)
  await enterProject(page, process.env.E2E_END_USER_PROJECT_NAME!)
  const fixture = await page.evaluate(async () => {
    const devicePath = '/src/api/device.ts',
      userPath = '/src/api/end-users.ts',
      projectPath = '/src/api/project.ts',
      storePath = '/src/store/modules/user.ts'
    const devices = await import(devicePath),
      users = await import(userPath)
    const projectId = (await import(storePath)).useUserStore().info.currentProjectId!
    const project = (await (await import(projectPath)).fetchProjects()).find(
      (p: { id?: string }) => p.id === projectId
    )
    const key = `user_binding_${Date.now()}`
    const type = await devices.fetchCreateDeviceType(projectId, {
      typeKey: key,
      name: key,
      deviceKind: 'DIRECT',
      payloadProtocol: 'STANDARD',
      networkType: 'WIFI'
    })
    await devices.fetchCreateDevicePropertyDefinition(projectId, type.id!, {
      propertyKey: 'relay',
      name: '开关',
      dataType: 'SWITCH',
      accessType: 'REPORT',
      sortOrder: 0
    })
    await devices.fetchPublishDeviceType(projectId, type.id!)
    const device = await devices.fetchCreateDevice(projectId, {
      deviceTypeId: type.id!,
      deviceKey: key,
      name: key
    })
    const username = `binding-user-${Date.now()}`
    const account = await users.provisionEndUser(projectId, {
      username,
      password: 'binding-fixture-123',
      displayName: '设备关系验收'
    })
    await users.assignEndUserRole(projectId, account.id!, 'MAINTAINER')
    return {
      projectId,
      projectKey: project!.projectKey!,
      deviceId: device.id!,
      deviceKey: key,
      username,
      userId: account.id!
    }
  })
  const messages: string[] = []
  page.on('console', (message) => messages.push(message.text()))
  await openDeviceList(page)
  await page.getByRole('textbox', { name: '名称 / 标识', exact: true }).fill(fixture.deviceKey)
  await page.getByRole('button', { name: '查询', exact: true }).click()
  await openDeviceDetails(page, page.locator('tr', { hasText: fixture.deviceKey }).first())
  const detail = page.locator('.device-detail')
  await detail.getByRole('tab', { name: '终端用户', exact: true }).click()
  const issuer = detail.locator('.device-claim-token')
  await expect(issuer.getByRole('button', { name: '签发认领令牌', exact: true })).toBeEnabled()
  await issuer.getByRole('button', { name: '签发认领令牌', exact: true }).click()
  await page
    .getByRole('dialog', { name: '确认签发认领令牌' })
    .getByRole('button', { name: '确定', exact: true })
    .click()
  const secret = issuer.getByLabel('一次性认领令牌', { exact: true })
  await expect(secret).toBeVisible()
  const capability = await secret.inputValue()
  const claimed = await page.evaluate(
    async ({ fixture, token }) => {
      const login = await fetch('/api/v1/app/auth/login', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        credentials: 'omit',
        body: JSON.stringify({
          projectKey: fixture.projectKey,
          username: fixture.username,
          password: 'binding-fixture-123'
        })
      })
      if (!login.ok) throw new Error(`App登录失败HTTP${login.status}`)
      const session = await login.json()
      const response = await fetch('/api/v1/app/device-claims', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          Authorization: `Bearer ${session.accessToken}`
        },
        credentials: 'omit',
        body: JSON.stringify({ token })
      })
      return { status: response.status, body: await response.json() }
    },
    { fixture, token: capability }
  )
  expect(claimed.status).toBe(200)
  expect(claimed.body.deviceId).toBe(fixture.deviceId)
  await issuer.getByRole('button', { name: '关闭令牌展示', exact: true }).click()
  await expect(secret).toHaveCount(0)
  await detail.getByRole('button', { name: '刷新用户', exact: true }).click()
  await expect(detail.locator('tr').filter({ hasText: fixture.userId })).toContainText('主控')
  await expect(issuer.getByRole('button', { name: '签发认领令牌', exact: true })).toBeDisabled()
  await page.goto('/#/project/end-users')
  await page
    .locator('tr')
    .filter({ hasText: fixture.username })
    .getByRole('button', { name: '管理角色', exact: true })
    .click()
  const relations = page.locator('.end-user-devices')
  const relationship = relations.locator('tr').filter({ hasText: fixture.deviceId })
  await expect(relationship).toContainText('PRIMARY')
  await expect(relationship).toContainText('ACTIVE')
  await relationship.getByRole('button', { name: '解绑设备', exact: true }).click()
  await page
    .getByRole('dialog', { name: '确认解绑设备' })
    .getByRole('button', { name: '确定', exact: true })
    .click()
  await expect(relationship).toContainText('CLOSED')
  await expect(relationship.getByRole('button', { name: '解绑设备', exact: true })).toBeDisabled()
  const repeated = await page.evaluate(async (fixture) => {
    const path = '/src/api/end-users.ts',
      api = await import(path)
    await api.unbindEndUserDevice(fixture.projectId, fixture.userId, fixture.deviceId)
    return api.fetchEndUserDevices(fixture.projectId, fixture.userId)
  }, fixture)
  expect(
    repeated.find((row: { deviceId?: string }) => row.deviceId === fixture.deviceId)?.status
  ).toBe('CLOSED')
  const denied = await page.evaluate(async (fixture) => {
    const login = await fetch('/api/v1/app/auth/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      credentials: 'omit',
      body: JSON.stringify({
        projectKey: fixture.projectKey,
        username: fixture.username,
        password: 'binding-fixture-123'
      })
    })
    if (!login.ok) throw new Error(`App登录失败HTTP${login.status}`)
    const session = await login.json()
    const response = await fetch(`/api/v1/app/devices/${fixture.deviceId}`, {
      headers: { Authorization: `Bearer ${session.accessToken}` },
      credentials: 'omit'
    })
    return { status: response.status, code: (await response.json()).code }
  }, fixture)
  expect(denied).toEqual({ status: 404, code: 60010 })
  const stored = await page.evaluate(() =>
    JSON.stringify({
      local: { ...localStorage },
      session: { ...sessionStorage },
      href: location.href
    })
  )
  expect(stored).not.toContain(capability)
  expect(messages.join('\n')).not.toContain(capability)
})
