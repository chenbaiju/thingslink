import { expect, test, type Page } from '@playwright/test'
import {
  login,
  enterProject,
  OWNER_EMAIL,
  OWNER_PASSWORD,
  MEMBER_EMAIL,
  MEMBER_PASSWORD
} from './helpers'

// 登录与会话只留在一次性测试环境，不记录 trace、视频或登录截图。
test.use({ trace: 'off', video: 'off', screenshot: 'off' })

test('设备证据与默认关闭模型：真实手动读取、四角色与跨项目隔离', async ({ page, browser }) => {
  test.setTimeout(240_000)
  page.setDefaultTimeout(20_000)
  await login(page, OWNER_EMAIL, OWNER_PASSWORD)
  const suffix = Date.now()
  const name = `只读证据-${suffix}`
  const projects = await page.evaluate(
    async ({ name }) => {
      const path = '/src/api/project.ts'
      const api = await import(path)
      const a = await api.fetchCreateProject({ name, region: 'sh-1' })
      await new Promise((done) => setTimeout(done, 250))
      const b = await api.fetchCreateProject({ name: name + '-B', region: 'sh-1' })
      return { a: a.id as string, b: b.id as string }
    },
    { name }
  )
  const context = await browser.newContext({ baseURL: new URL(page.url()).origin })
  context.setDefaultTimeout(20_000)
  let checkpoint = 'fixture',
    primary: unknown
  let deviceId = '',
    memberId = ''
  const snapshotRequests: string[] = []
  const assistantWrites: string[] = []
  const modelStatusRequests: string[] = []
  const observe = (target: Page) =>
    target.on('request', (request) => {
      if (request.url().includes('/assistant/') && request.method() !== 'GET')
        assistantWrites.push(request.method())
      if (request.url().includes('/assistant/') && request.url().includes('/snapshot'))
        snapshotRequests.push(request.url())
      if (new URL(request.url()).pathname.endsWith('/assistant/analysis-runs/status'))
        modelStatusRequests.push(request.url())
    })
  observe(page)
  const open = async (target: Page) => {
    const before = modelStatusRequests.length
    await target.goto(`/#/device/list?deviceId=${deviceId}`)
    await target.getByRole('tab', { name: '诊断证据', exact: true }).click()
    await expect(target.getByTestId('agent-evidence')).toBeVisible()
    await expect(target.getByTestId('agent-evidence')).toContainText('读取证据不会调用外部模型')
    expect(modelStatusRequests).toHaveLength(before)
  }
  const read = async (target: Page) => {
    const panel = target.getByTestId('agent-evidence')
    const directory = target.waitForResponse((r) =>
      new URL(r.url()).pathname.endsWith(`/${deviceId}/binding-metadata`)
    )
    await panel.getByRole('button', { name: '加载属性目录', exact: true }).click()
    expect((await directory).status()).toBe(200)
    await panel
      .locator('.el-select')
      .filter({
        has: target.getByRole('combobox', { name: '证据属性', exact: true })
      })
      .click()
    await target
      .locator('.el-select-dropdown:visible')
      .getByRole('option', { name: 'temperature', exact: true })
      .click()
    await target
      .locator('.el-select-dropdown:visible')
      .getByRole('option', { name: 'humidity', exact: true })
      .click()
    await target.keyboard.press('Escape')
    const response = target.waitForResponse((r) =>
      new URL(r.url()).pathname.endsWith(`/assistant/devices/${deviceId}/snapshot`)
    )
    await panel.getByRole('button', { name: '读取证据', exact: true }).click()
    const http = await response
    expect(http.status()).toBe(200)
    expect(http.headers()['cache-control']).toContain('no-store')
    const body = await http.json()
    expect(body.projectId).toBe(projects.a)
    expect(body.deviceId).toBe(deviceId)
    expect(body.properties).toHaveLength(2)
    for (const property of body.properties) {
      expect(property.availability).toBe('MISSING')
      expect(property.value).toBeNull()
      expect(property.sourceModelVersionId).toBeNull()
    }
    expect(new URL(http.url()).searchParams.getAll('propertyKey')).toEqual([
      'temperature',
      'humidity'
    ])
    expect(new URL(http.url()).searchParams.get('expectedModelVersionId')).toBe(body.modelVersionId)
    await expect(panel).toContainText('未上报')
    await expect(panel).toContainText('不可用')
    await expect(panel).toContainText(body.collectionFinishedAt)
    return body.modelVersionId as string
  }
  const modelStatus = async (target: Page, role: string) => {
    const panel = target.getByTestId('agent-analysis')
    await expect(panel).toBeVisible()
    const statusPath = `/api/v1/projects/${projects.a}/assistant/analysis-runs/status`
    if (role === 'VIEWER') {
      await expect(panel.getByRole('button', { name: '查看模型状态', exact: true })).toHaveCount(0)
      await expect(panel).toContainText('查看者可继续读取事实证据')
      const denied = target.waitForResponse((r) => new URL(r.url()).pathname === statusPath)
      await target.evaluate(async (project) => {
        const path = '/src/api/assistant-analysis.ts'
        try {
          await (await import(path)).readAnalysisAvailability(project, new AbortController().signal)
        } catch {
          /* 实际拒绝以HTTP状态取证，不回显身份。 */
        }
      }, projects.a)
      expect((await denied).status()).toBe(403)
      return
    }
    await expect(panel.getByRole('button', { name: '确认并分析一次', exact: true })).toBeDisabled()
    const before = modelStatusRequests.length
    await panel
      .locator('.el-select')
      .filter({
        has: target.getByRole('combobox', { name: '固定分析问题', exact: true })
      })
      .click()
    await target
      .locator('.el-select-dropdown:visible')
      .getByRole('option', { name: '活动告警说明', exact: true })
      .click()
    await target.keyboard.press('Escape')
    expect(modelStatusRequests).toHaveLength(before)
    const response = target.waitForResponse((r) => new URL(r.url()).pathname === statusPath)
    await panel.getByRole('button', { name: '查看模型状态', exact: true }).click()
    const http = await response
    expect(http.status()).toBe(200)
    expect(http.headers()['cache-control']).toContain('no-store')
    expect(await http.json()).toEqual({
      businessAvailable: false,
      reason: 'INTERNAL_TRANSPORT_DISABLED'
    })
    await expect(panel).toContainText('模型分析服务尚未启用')
    await expect(panel.getByRole('button', { name: '确认并分析一次', exact: true })).toBeDisabled()
    expect(modelStatusRequests).toHaveLength(before + 1)
  }
  try {
    await page.reload()
    await enterProject(page, name)
    const fixture = await page.evaluate(
      async ({ project, suffix, email }) => {
        const devicePath = '/src/api/device.ts',
          projectPath = '/src/api/project.ts'
        const api = await import(devicePath),
          projectsApi = await import(projectPath)
        const pause = () => new Promise((done) => setTimeout(done, 250))
        const type = await api.fetchCreateDeviceType(project, {
          typeKey: `evidence_${suffix}`,
          name: `证据类型${suffix}`,
          deviceKind: 'DIRECT',
          payloadProtocol: 'STANDARD',
          networkType: 'WIFI'
        })
        await pause()
        await api.fetchCreateDevicePropertyDefinition(project, type.id, {
          propertyKey: 'temperature',
          name: '温度',
          accessType: 'REPORT',
          dataType: 'NUMBER',
          unit: '℃',
          decimalPlaces: 2,
          sortOrder: 0
        })
        await pause()
        await api.fetchCreateDevicePropertyDefinition(project, type.id, {
          propertyKey: 'humidity',
          name: '湿度',
          accessType: 'REPORT',
          dataType: 'NUMBER',
          unit: '%',
          decimalPlaces: 2,
          sortOrder: 1
        })
        await pause()
        await api.fetchPublishDeviceType(project, type.id)
        await pause()
        const device = await api.fetchCreateDevice(project, {
          deviceTypeId: type.id,
          deviceKey: `evidence_${suffix}`,
          name: `证据设备${suffix}`
        })
        await pause()
        const member = await projectsApi.fetchInviteMember(project, { email, role: 'ADMIN' })
        return {
          device: device.id as string,
          type: type.id as string,
          member: member.accountId as string
        }
      },
      { project: projects.a, suffix, email: MEMBER_EMAIL }
    )
    deviceId = fixture.device
    memberId = fixture.member
    await open(page)
    expect(snapshotRequests).toHaveLength(0)
    const version = await read(page)
    await modelStatus(page, 'OWNER')
    expect(snapshotRequests).toHaveLength(1)
    checkpoint = 'member-create-page'
    const member = await context.newPage()
    observe(member)
    checkpoint = 'member-login'
    await login(member, MEMBER_EMAIL, MEMBER_PASSWORD)
    checkpoint = 'member-project'
    await enterProject(member, name)
    for (const role of ['ADMIN', 'OPERATOR', 'VIEWER']) {
      checkpoint = `member-${role}-role`
      if (role !== 'ADMIN')
        await page.evaluate(
          async ({ project, member, role }) => {
            const path = '/src/api/project.ts'
            await (await import(path)).fetchUpdateMemberRole(project, member, role)
          },
          { project: projects.a, member: memberId, role }
        )
      checkpoint = `member-${role}-reload`
      await member.reload()
      await expect(member.getByRole('menuitem', { name: '设备开发', exact: true })).toBeVisible()
      await expect(member.getByRole('button', { name: /未进入项目/ })).toHaveCount(0)
      checkpoint = `member-${role}-open`
      await open(member)
      checkpoint = `member-${role}-read`
      await read(member)
      await modelStatus(member, role)
    }
    expect(snapshotRequests).toHaveLength(4)
    expect(assistantWrites).toEqual([])
    expect(modelStatusRequests).toHaveLength(4)
    checkpoint = 'owner-switch-project'
    await enterProject(page, name + '-B')
    await expect(page.getByTestId('agent-evidence')).toHaveCount(0)
    const foreignStatus = page.waitForResponse(
      (r) =>
        new URL(r.url()).pathname ===
        `/api/v1/projects/${projects.a}/assistant/analysis-runs/status`
    )
    await page.evaluate(async (project) => {
      const path = '/src/api/assistant-analysis.ts'
      try {
        await (await import(path)).readAnalysisAvailability(project, new AbortController().signal)
      } catch {
        /* 错误项目必须由真实后端拒绝。 */
      }
    }, projects.a)
    expect((await foreignStatus).status()).toBe(404)
    const rejected = await page.evaluate(
      async ({ project, device, version }) => {
        const path = '/src/api/assistant-evidence.ts'
        try {
          await (
            await import(path)
          ).readDeviceEvidence(
            project,
            device,
            version,
            ['temperature'],
            new AbortController().signal
          )
          return 200
        } catch (error) {
          return (error as { code: number }).code
        }
      },
      { project: projects.a, device: deviceId, version }
    )
    expect(rejected).toBe(10004)
    await enterProject(page, name)
    await open(page)
    await expect(page.getByTestId('agent-evidence')).not.toContainText('未上报')
    await expect(page.getByTestId('agent-analysis')).toContainText('尚未读取模型状态')
    expect(modelStatusRequests).toHaveLength(5)
    expect(snapshotRequests).toHaveLength(5)
    expect(assistantWrites).toEqual([])
  } catch (failure) {
    primary = failure
    console.error(`agent-evidence failed checkpoint=${checkpoint}`)
  } finally {
    try {
      checkpoint = 'cleanup-context'
      await context.close()
      checkpoint = 'cleanup-project'
      const currentProject = await page.evaluate(async () => {
        const path = '/src/store/modules/user.ts'
        return (await import(path)).useUserStore().info.currentProjectId
      })
      if (currentProject !== projects.a) await enterProject(page, name)
      await page.evaluate(
        async ({ projects, deviceId, memberId }) => {
          const devicePath = '/src/api/device.ts',
            projectPath = '/src/api/project.ts'
          const devices = await import(devicePath),
            api = await import(projectPath)
          const pause = () => new Promise((done) => setTimeout(done, 250))
          if (deviceId) {
            await devices.fetchDeleteDevice(projects.a, deviceId)
            await pause()
          }
          // 已发布类型保持不可变；项目删除拒绝当前访问，物理回收由专属Dind结束完成。
          if (memberId) {
            await api.fetchRemoveMember(projects.a, memberId)
            await pause()
          }
          await api.fetchDeleteProject(projects.b)
          await pause()
          await api.fetchDeleteProject(projects.a)
        },
        { projects, deviceId, memberId }
      )
    } catch (cleanupFailure) {
      const code = (cleanupFailure as { code?: unknown } | null)?.code
      console.error(
        `agent-evidence cleanup failed checkpoint=${checkpoint} code=${typeof code === 'number' ? code : 'unknown'}`
      )
      if (primary === undefined) primary = cleanupFailure
    }
  }
  if (primary !== undefined) throw primary
})
