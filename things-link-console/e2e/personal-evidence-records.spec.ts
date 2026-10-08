import { expect, test } from '@playwright/test'
import { login, enterProject, OWNER_EMAIL, OWNER_PASSWORD } from './helpers'
import { createHash } from 'node:crypto'
import { readFile } from 'node:fs/promises'

// 登录与个人事实不进入浏览器录屏、截图或追踪附件。
test.use({ trace: 'off', video: 'off', screenshot: 'off' })

test('个人事实记录：真实报告下载、空历史告警、恢复与项目隔离', async ({ page }) => {
  test.setTimeout(240_000)
  page.setDefaultTimeout(20_000)
  page.setDefaultNavigationTimeout(20_000)
  await login(page, OWNER_EMAIL, OWNER_PASSWORD)
  const suffix = Date.now(),
    name = `个人事实旅程-${suffix}`
  const projects = await page.evaluate(async (name) => {
    const path = '/src/api/project.ts',
      api = await import(path)
    const a = await api.fetchCreateProject({ name, region: 'sh-1' })
    await new Promise((done) => setTimeout(done, 250))
    const b = await api.fetchCreateProject({ name: name + '-B', region: 'sh-1' })
    return { a: a.id as string, b: b.id as string }
  }, name)
  let deviceId = '',
    recordId = '',
    primary: unknown,
    checkpoint = 'fixture'
  const writes: string[] = [],
    recordRequests: string[] = [],
    reportRequests: string[] = []
  page.on('request', (request) => {
    const path = new URL(request.url()).pathname
    if (!path.includes('/assistant/')) return
    if (path.includes('/evidence-records')) recordRequests.push(request.method())
    if (path.endsWith('/fact-report')) reportRequests.push(request.method())
    if (request.method() !== 'GET') writes.push(path)
  })
  const open = async () => {
    await expect(page.getByRole('menuitem', { name: '设备开发', exact: true })).toBeVisible()
    await page.goto(`/#/device/list?deviceId=${deviceId}`)
    await page.getByRole('tab', { name: '诊断证据', exact: true }).click()
    await expect(page.getByTestId('personal-records')).toBeVisible()
  }
  const records = () => page.getByTestId('personal-records')
  const response = (method: string, tail = '') =>
    page.waitForResponse(
      (r) =>
        r.request().method() === method &&
        new URL(r.url()).pathname ===
          `/api/v1/projects/${projects.a}/assistant/evidence-records${tail}`
    )
  try {
    await page.reload()
    await enterProject(page, name)
    deviceId = await page.evaluate(
      async ({ project, suffix }) => {
        const path = '/src/api/device.ts',
          api = await import(path)
        const pause = () => new Promise((done) => setTimeout(done, 250))
        const type = await api.fetchCreateDeviceType(project, {
          typeKey: `record_${suffix}`,
          name: `记录测试类型${suffix}`,
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
        await api.fetchPublishDeviceType(project, type.id)
        await pause()
        const device = await api.fetchCreateDevice(project, {
          deviceTypeId: type.id,
          deviceKey: `record_${suffix}`,
          name: `记录测试设备${suffix}`
        })
        return device.id as string
      },
      { project: projects.a, suffix }
    )
    checkpoint = 'manual-selection'
    await open()
    expect(recordRequests).toEqual([])
    await expect(records().getByRole('button', { name: '重新取证并保存' })).toBeDisabled()
    const panel = page.getByTestId('agent-evidence')
    await panel.getByRole('button', { name: '加载属性目录', exact: true }).click()
    await panel
      .locator('.el-select')
      .filter({
        has: page.getByRole('combobox', { name: '证据属性', exact: true })
      })
      .click()
    await page
      .locator('.el-select-dropdown:visible')
      .getByRole('option', { name: 'temperature', exact: true })
      .click()
    await page.keyboard.press('Escape')
    const saved = response('POST')
    await records().getByRole('button', { name: '重新取证并保存' }).click()
    const receipt = await saved
    expect(receipt.status()).toBe(201)
    expect(receipt.headers()['cache-control']).toContain('no-store')
    const row = await receipt.json()
    recordId = row.id
    expect(row.deviceId).toBe(deviceId)
    expect(row.contentSha256).toMatch(/^[0-9a-f]{64}$/)
    await expect(records()).toContainText('已保存本人历史事实。')
    expect(reportRequests).toEqual([])
    checkpoint = 'manual-fact-report'
    const reportResponse = response('GET', `/${recordId}/fact-report`)
    await records().getByRole('button', { name: '生成事实报告', exact: true }).click()
    const generated = await reportResponse
    expect(generated.status()).toBe(200)
    expect(generated.headers()['cache-control']).toContain('no-store')
    const report = await generated.json()
    expect(report.schemaVersion).toBe(1)
    expect(report.mode).toBe('FACTS_ONLY')
    expect(report.sourceRecord.id).toBe(recordId)
    expect(report.sourceRecord.contentSha256).toBe(row.contentSha256)
    expect(createHash('sha256').update(report.markdown, 'utf8').digest('hex')).toBe(
      report.contentSha256
    )
    await expect(records().getByTestId('personal-fact-report').locator('pre')).toHaveText(
      report.markdown
    )
    checkpoint = 'reauthorized-download'
    const downloadedResponse = response('GET', `/${recordId}/fact-report`)
    const downloaded = page.waitForEvent('download')
    await records().getByRole('button', { name: '重新确权并下载报告', exact: true }).click()
    const download = await downloaded
    const file = await download.path()
    expect(file).toBeTruthy()
    expect(download.suggestedFilename()).toBe(`personal-fact-report-${recordId}.md`)
    expect(
      createHash('sha256')
        .update(await readFile(file!))
        .digest('hex')
    ).toBe(report.contentSha256)
    expect((await downloadedResponse).status()).toBe(200)
    await expect(records()).toContainText('已发起浏览器下载')
    expect(reportRequests).toEqual(['GET', 'GET'])
    checkpoint = 'manual-empty-history-alarm'
    const tools = page.getByTestId('device-evidence-tools')
    await tools.getByLabel('历史数值属性').selectOption('temperature')
    const range = await page.evaluate(() => {
      const local = (date: Date) =>
        new Date(date.getTime() - date.getTimezoneOffset() * 60000).toISOString().slice(0, 16)
      return {
        from: local(new Date(Date.now() - 3600000)),
        to: local(new Date(Date.now() - 120000))
      }
    })
    await tools.getByLabel('历史起点').fill(range.from)
    await tools.getByLabel('历史终点').fill(range.to)
    const historyResponse = page.waitForResponse(
      (r) =>
        r.request().method() === 'GET' &&
        new URL(r.url()).pathname ===
          `/api/v1/projects/${projects.a}/assistant/devices/${deviceId}/history`
    )
    await tools.getByRole('button', { name: '读取历史', exact: true }).click()
    const historyReceipt = await historyResponse
    expect(historyReceipt.status()).toBe(200)
    const history = await historyReceipt.json()
    expect(history.state).toBe('NO_POINTS')
    expect(history.points).toEqual([])
    expect(history.modelVersionId).toBe(row.modelVersionId)
    await expect(tools.getByTestId('history-evidence-result')).toContainText('区间内未取得点')
    const alarmResponse = page.waitForResponse(
      (r) =>
        r.request().method() === 'GET' &&
        new URL(r.url()).pathname ===
          `/api/v1/projects/${projects.a}/assistant/devices/${deviceId}/alarms`
    )
    await tools.getByRole('button', { name: '读取告警首页', exact: true }).click()
    const alarmReceipt = await alarmResponse
    expect(alarmReceipt.status()).toBe(200)
    const alarms = await alarmReceipt.json()
    expect(alarms.items).toEqual([])
    expect(alarms.sourceModelState).toBe('NOT_PROVIDED')
    expect(alarms.limit).toBe(20)
    await expect(tools.getByTestId('alarm-evidence-result')).toContainText(
      '本页为空，不代表设备正常'
    )
    await expect(tools.getByRole('button', { name: '读取下一页', exact: true })).toBeDisabled()
    checkpoint = 'read-historical-detail'
    const detail = response('GET', `/${recordId}`)
    await records().getByRole('button', { name: '查看', exact: true }).click()
    const snapshot = (await (await detail).json()).snapshot
    expect(snapshot.deviceId).toBe(deviceId)
    expect(snapshot.properties[0].availability).toBe('MISSING')
    expect(snapshot.properties[0].value).toBeNull()
    await expect(records()).toContainText('历史采集区间')
    await expect(records()).toContainText('缺少可用值')
    checkpoint = 'reload-and-refresh'
    await page.reload()
    await open()
    await expect(records()).not.toContainText('历史采集区间')
    await expect(records().getByTestId('personal-fact-report')).toHaveCount(0)
    await expect(page.getByTestId('history-evidence-result')).toHaveCount(0)
    await expect(page.getByTestId('alarm-evidence-result')).toHaveCount(0)
    expect(reportRequests).toEqual(['GET', 'GET'])
    const listed = response('GET')
    await records().getByRole('button', { name: '刷新本人记录' }).click()
    expect((await (await listed).json()).some((r: { id: string }) => r.id === recordId)).toBe(true)
    await expect(records().getByRole('button', { name: '查看', exact: true })).toHaveCount(1)
    checkpoint = 'project-switch-rejection'
    await enterProject(page, name + '-B')
    await expect(page.getByTestId('personal-records')).toHaveCount(0)
    const rejected = await page.evaluate(
      async ({ project, id }) => {
        const path = '/src/api/assistant-records.ts'
        const api = await import(path),
          results: number[] = []
        for (const call of [api.readPersonalRecord, api.generatePersonalFactReport]) {
          try {
            await call(project, id, new AbortController().signal)
            results.push(200)
          } catch (error) {
            results.push((error as { code: number }).code)
          }
        }
        return results
      },
      { project: projects.a, id: recordId }
    )
    expect(rejected).toEqual([10004, 10004])
    await enterProject(page, name)
    await open()
    const restored = response('GET')
    await records().getByRole('button', { name: '刷新本人记录' }).click()
    expect((await restored).status()).toBe(200)
    checkpoint = 'manual-delete'
    const deleted = response('DELETE', `/${recordId}`)
    await records().getByRole('button', { name: '删除本人记录' }).click()
    expect((await deleted).status()).toBe(204)
    await expect(records()).toContainText('本人记录已删除。')
    await expect(records().getByRole('button', { name: '查看', exact: true })).toHaveCount(0)
    const missingReport = response('GET', `/${recordId}/fact-report`)
    await page.evaluate(
      async ({ project, id }) => {
        const path = '/src/api/assistant-records.ts'
        try {
          await (
            await import(path)
          ).generatePersonalFactReport(project, id, new AbortController().signal)
        } catch {
          return
        }
        throw new Error('已删除来源不应生成报告')
      },
      { project: projects.a, id: recordId }
    )
    expect((await missingReport).status()).toBe(404)
    expect(reportRequests).toEqual(['GET', 'GET', 'GET', 'GET'])
    expect(recordRequests.filter((method) => method === 'POST')).toHaveLength(1)
    expect(recordRequests.filter((method) => method === 'DELETE')).toHaveLength(1)
    expect(recordRequests.filter((method) => method === 'GET').length).toBeGreaterThanOrEqual(4)
    expect(writes.every((path) => path.includes('/assistant/evidence-records'))).toBe(true)
  } catch (failure) {
    primary = failure
    console.error(`personal-evidence-records failed checkpoint=${checkpoint}`)
  } finally {
    try {
      const current = await page.evaluate(async () => {
        const path = '/src/store/modules/user.ts'
        return (await import(path)).useUserStore().info.currentProjectId
      })
      if (current !== projects.a) await enterProject(page, name)
      await page.evaluate(
        async ({ projects, deviceId }) => {
          const devicePath = '/src/api/device.ts',
            projectPath = '/src/api/project.ts'
          const devices = await import(devicePath),
            api = await import(projectPath)
          if (deviceId) await devices.fetchDeleteDevice(projects.a, deviceId)
          await new Promise((done) => setTimeout(done, 250))
          await api.fetchDeleteProject(projects.b)
          await new Promise((done) => setTimeout(done, 250))
          await api.fetchDeleteProject(projects.a)
        },
        { projects, deviceId }
      )
    } catch {
      console.error('personal-evidence-records 专属测试数据清理失败')
      if (primary === undefined) primary = new Error('专属测试数据清理失败')
    }
  }
  if (primary !== undefined) throw primary
})
