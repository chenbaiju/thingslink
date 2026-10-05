import { expect, test } from '@playwright/test'
import {
  login,
  enterProject,
  OWNER_EMAIL,
  OWNER_PASSWORD,
  MEMBER_EMAIL,
  MEMBER_PASSWORD
} from './helpers'
import { createHash } from 'node:crypto'
import { readFile } from 'node:fs/promises'
test.use({ trace: 'off', video: 'off', screenshot: 'off' })
test('个人集合报告：四角色本人来源、实际下载与失效隔离', async ({ page, browser }) => {
  test.setTimeout(240_000)
  page.setDefaultTimeout(20_000)
  page.setDefaultNavigationTimeout(20_000)
  await login(page, OWNER_EMAIL, OWNER_PASSWORD)
  const suffix = Date.now(),
    name = `集合旅程-${suffix}`
  const fixture = await page.evaluate(
    async ({ name, email }) => {
      const path = '/src/api/project.ts',
        api = await import(path),
        pause = () => new Promise((r) => setTimeout(r, 250))
      const a = await api.fetchCreateProject({ name, region: 'sh-1' })
      await pause()
      const b = await api.fetchCreateProject({ name: name + '-B', region: 'sh-1' })
      await pause()
      const member = await api.fetchInviteMember(a.id, { email, role: 'ADMIN' })
      return { a: a.id as string, b: b.id as string, member: member.accountId as string }
    },
    { name, email: MEMBER_EMAIL }
  )
  const context = await browser.newContext({ baseURL: new URL(page.url()).origin }),
    member = await context.newPage()
  member.setDefaultTimeout(20_000)
  member.setDefaultNavigationTimeout(20_000)
  const panel = (target = page) => target.getByTestId('personal-fact-collection')
  const collectionPath = `/api/v1/projects/${fixture.a}/assistant/fact-reports/collection`
  const response = (target = page) =>
    target.waitForResponse(
      (r) => new URL(r.url()).pathname === collectionPath && r.request().method() === 'POST'
    )
  const hash = (text: string) => createHash('sha256').update(text, 'utf8').digest('hex')
  const assistantWrites: string[] = [],
    ownerCollections: string[] = [],
    memberCollections: string[] = []
  for (const [target, calls] of [
    [page, ownerCollections],
    [member, memberCollections]
  ] as const)
    target.on('request', (request) => {
      const path = new URL(request.url()).pathname
      if (path === collectionPath && request.method() === 'POST')
        calls.push(request.postData() || '')
      if (
        path.includes('/assistant/') &&
        request.method() !== 'GET' &&
        !(path === collectionPath && request.method() === 'POST')
      )
        assistantWrites.push(request.method() + ' ' + path)
    })
  let devices: string[] = [],
    sources: { id: string; deviceId: string; modelVersionId: string; contentSha256: string }[] = [],
    ownId = '',
    primary: unknown,
    checkpoint = 'fixture'
  try {
    await page.reload()
    await enterProject(page, name)
    devices = await page.evaluate(
      async ({ project, suffix }) => {
        const path = '/src/api/device.ts',
          api = await import(path),
          pause = () => new Promise((r) => setTimeout(r, 250))
        const type = await api.fetchCreateDeviceType(project, {
          typeKey: `collection_${suffix}`,
          name: '集合测试类型',
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
        const first = await api.fetchCreateDevice(project, {
          deviceTypeId: type.id,
          deviceKey: `collection_${suffix}_a`,
          name: '合成设备甲'
        })
        await pause()
        const second = await api.fetchCreateDevice(project, {
          deviceTypeId: type.id,
          deviceKey: `collection_${suffix}_b`,
          name: '合成设备乙'
        })
        return [first.id as string, second.id as string]
      },
      { project: fixture.a, suffix }
    )
    sources = await page.evaluate(
      async ({ project, devices }) => {
        const recordsPath = '/src/api/assistant-records.ts',
          bindingPath = '/src/api/dashboard-binding.ts',
          api = await import(recordsPath),
          binding = await import(bindingPath),
          rows = []
        for (const device of devices) {
          const metadata = await binding.fetchBindingMetadata(project, device)
          rows.push(
            await api.savePersonalRecord(
              project,
              device,
              metadata.model.versionId,
              ['temperature'],
              new AbortController().signal
            )
          )
          await new Promise((r) => setTimeout(r, 250))
        }
        return rows
      },
      { project: fixture.a, devices }
    )
    checkpoint = 'owner-manual-selection'
    await page.goto('/#/project/settings')
    await expect(panel()).toBeVisible()
    expect(ownerCollections).toEqual([])
    await expect(panel().getByRole('checkbox')).toHaveCount(0)
    await panel().getByRole('button', { name: '刷新本人项目记录', exact: true }).click()
    await expect(panel().getByRole('checkbox')).toHaveCount(2)
    for (const source of sources)
      await panel().getByLabel(`选择历史记录 ${source.id}`, { exact: true }).check()
    const generated = response()
    await panel().getByRole('button', { name: '生成集合事实报告', exact: true }).click()
    const receipt = await generated
    expect(receipt.status()).toBe(200)
    expect(receipt.headers()['cache-control']).toContain('no-store')
    const report = await receipt.json()
    const ids = sources.map((source) => source.id).sort()
    expect(report.sourceRecords.map((source: { id: string }) => source.id)).toEqual(ids)
    expect(
      report.sourceRecords.map((source: { contentSha256: string }) => source.contentSha256).sort()
    ).toEqual(sources.map((source) => source.contentSha256).sort())
    expect(report.schemaVersion).toBe(1)
    expect(report.mode).toBe('FACTS_ONLY')
    expect(report.scope).toBe('SELECTED_PERSONAL_RECORDS')
    expect(report.coverage).toEqual({
      devices: 2,
      selectedProperties: 2,
      availableValues: 0,
      unavailableValues: 2,
      omittedValues: 0
    })
    expect(hash(report.markdown)).toBe(report.contentSha256)
    await expect(panel().getByTestId('personal-collection-report').locator('pre')).toHaveText(
      report.markdown
    )
    checkpoint = 'owner-fresh-download'
    const downloaded = response(),
      fileEvent = page.waitForEvent('download')
    await panel().getByRole('button', { name: '重新确权并下载集合报告', exact: true }).click()
    const download = await fileEvent,
      downloadPath = await download.path()
    expect(downloadPath).toBeTruthy()
    expect(download.suggestedFilename()).toBe(`personal-fact-collection-${ids.join('_')}.md`)
    const newReport = await (await downloaded).json()
    expect(newReport.contentSha256).toBe(report.contentSha256)
    expect(
      createHash('sha256')
        .update(await readFile(downloadPath!))
        .digest('hex')
    ).toBe(report.contentSha256)
    expect(ownerCollections).toHaveLength(2)
    expect(ownerCollections.map((body) => JSON.parse(body))).toEqual([
      { recordIds: ids },
      { recordIds: ids }
    ])
    checkpoint = 'member-personal-boundary'
    await login(member, MEMBER_EMAIL, MEMBER_PASSWORD)
    await enterProject(member, name)
    await member.goto('/#/project/settings')
    await panel(member).getByRole('button', { name: '刷新本人项目记录', exact: true }).click()
    await expect(panel(member)).toContainText('当前项目没有本人有效记录')
    ownId = await member.evaluate(
      async ({ project, device, model }) => {
        const path = '/src/api/assistant-records.ts'
        return (
          await (
            await import(path)
          ).savePersonalRecord(
            project,
            device,
            model,
            ['temperature'],
            new AbortController().signal
          )
        ).id
      },
      { project: fixture.a, device: devices[0], model: sources[0].modelVersionId }
    )
    for (const role of ['ADMIN', 'OPERATOR', 'VIEWER']) {
      checkpoint = 'member-' + role
      if (role !== 'ADMIN')
        await page.evaluate(
          async ({ project, account, role }) => {
            const path = '/src/api/project.ts'
            await (await import(path)).fetchUpdateMemberRole(project, account, role)
          },
          { project: fixture.a, account: fixture.member, role }
        )
      await member.reload()
      await expect(member.getByRole('menuitem', { name: '设备', exact: true })).toBeVisible()
      await member.goto('/#/project/settings')
      await panel(member).getByRole('button', { name: '刷新本人项目记录', exact: true }).click()
      await expect(panel(member).getByRole('checkbox')).toHaveCount(1)
      await panel(member).getByLabel(`选择历史记录 ${ownId}`, { exact: true }).check()
      const own = response(member)
      await panel(member).getByRole('button', { name: '生成集合事实报告', exact: true }).click()
      const result = await (await own).json()
      expect(result.sourceRecords.map((source: { id: string }) => source.id)).toEqual([ownId])
      expect(result.coverage.devices).toBe(1)
      expect(hash(result.markdown)).toBe(result.contentSha256)
      await expect(
        panel(member).getByTestId('personal-collection-report').locator('pre')
      ).toHaveText(result.markdown)
      const rejected = response(member)
      await member.evaluate(
        async ({ project, ids }) => {
          const path = '/src/api/assistant-records.ts'
          try {
            await (
              await import(path)
            ).generatePersonalFactCollection(project, ids, new AbortController().signal)
          } catch {
            return
          }
          throw new Error('他人来源不可生成')
        },
        { project: fixture.a, ids }
      )
      const denied = await rejected
      expect(denied.status()).toBe(404)
      expect(denied.headers()['cache-control']).toContain('no-store')
      expect(await denied.json()).not.toHaveProperty('markdown')
    }
    expect(memberCollections).toHaveLength(6)
    checkpoint = 'owner-cannot-read-member'
    const deniedOwner = response()
    await page.evaluate(
      async ({ project, id }) => {
        const path = '/src/api/assistant-records.ts'
        try {
          await (
            await import(path)
          ).generatePersonalFactCollection(project, [id], new AbortController().signal)
        } catch {
          return
        }
        throw new Error('所有者不能读他人来源')
      },
      { project: fixture.a, id: ownId }
    )
    expect((await deniedOwner).status()).toBe(404)
    checkpoint = 'project-switch-clears-and-denies'
    await enterProject(page, name + '-B')
    await page.goto('/#/project/settings')
    await expect(panel().getByTestId('personal-collection-report')).toHaveCount(0)
    await expect(panel().getByRole('checkbox')).toHaveCount(0)
    const wrong = response()
    await page.evaluate(
      async ({ project, ids }) => {
        const path = '/src/api/assistant-records.ts'
        try {
          await (
            await import(path)
          ).generatePersonalFactCollection(project, ids, new AbortController().signal)
        } catch {
          return
        }
        throw new Error('旧项目来源不可生成')
      },
      { project: fixture.a, ids }
    )
    expect((await wrong).status()).toBe(404)
    await enterProject(page, name)
    await page.goto('/#/project/settings')
    await panel().getByRole('button', { name: '刷新本人项目记录', exact: true }).click()
    await expect(panel().getByRole('checkbox')).toHaveCount(2)
    for (const source of sources)
      await panel().getByLabel(`选择历史记录 ${source.id}`, { exact: true }).check()
    checkpoint = 'deleted-source-whole-rejection'
    await page.evaluate(
      async ({ project, id }) => {
        const path = '/src/api/assistant-records.ts'
        await (await import(path)).deletePersonalRecord(project, id, new AbortController().signal)
      },
      { project: fixture.a, id: sources[0].id }
    )
    const invalid = response()
    await panel().getByRole('button', { name: '生成集合事实报告', exact: true }).click()
    const expired = await invalid
    expect(expired.status()).toBe(404)
    expect(await expired.json()).not.toHaveProperty('markdown')
    await expect(panel()).toContainText('未显示或下载')
    await expect(panel().getByTestId('personal-collection-report')).toHaveCount(0)
    checkpoint = 'revoked-member-reload'
    await page.evaluate(
      async ({ project, account }) => {
        const path = '/src/api/project.ts'
        await (await import(path)).fetchRemoveMember(project, account)
      },
      { project: fixture.a, account: fixture.member }
    )
    await member.reload()
    await expect(member.getByPlaceholder('请输入邮箱')).toBeVisible()
    await expect(panel(member)).toHaveCount(0)
    expect(ownerCollections).toHaveLength(5)
    expect(memberCollections).toHaveLength(6)
    expect(assistantWrites.filter((call) => call.startsWith('POST '))).toHaveLength(3)
    expect(assistantWrites.filter((call) => call.startsWith('DELETE '))).toHaveLength(1)
    expect(assistantWrites.every((call) => call.includes('/assistant/evidence-records'))).toBe(true)
  } catch (error) {
    primary = error
    console.error('personal-fact-collection checkpoint=' + checkpoint)
  } finally {
    await context.close()
    try {
      const current = await page.evaluate(async () => {
        const path = '/src/store/modules/user.ts'
        return (await import(path)).useUserStore().info.currentProjectId
      })
      if (current !== fixture.a) await enterProject(page, name)
      await page.evaluate(
        async ({ fixture, devices }) => {
          const path = '/src/api/project.ts',
            devicePath = '/src/api/device.ts',
            api = await import(path),
            deviceApi = await import(devicePath),
            pause = () => new Promise((r) => setTimeout(r, 250))
          for (const device of devices) {
            await deviceApi.fetchDeleteDevice(fixture.a, device)
            await pause()
          }
          await api.fetchDeleteProject(fixture.b)
          await pause()
          await api.fetchDeleteProject(fixture.a)
        },
        { fixture, devices }
      )
    } catch {
      console.error('个人集合专属测试数据清理失败')
      if (primary === undefined) primary = new Error('个人集合专属测试数据清理失败')
    }
  }
  if (primary !== undefined) throw primary
})
