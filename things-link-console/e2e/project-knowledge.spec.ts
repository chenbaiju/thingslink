import { expect, test } from '@playwright/test'
import {
  login,
  enterProject,
  OWNER_EMAIL,
  OWNER_PASSWORD,
  MEMBER_EMAIL,
  MEMBER_PASSWORD
} from './helpers'
test.use({ trace: 'off', video: 'off', screenshot: 'off' })
test('受控项目知识：明确批准、换版引用、四角色读取、项目隔离与删除', async ({ page, browser }) => {
  test.setTimeout(240_000)
  page.setDefaultTimeout(20_000)
  await login(page, OWNER_EMAIL, OWNER_PASSWORD)
  const name = `知识旅程-${Date.now()}`
  const fixture = await page.evaluate(
    async ({ name, email }) => {
      const path = '/src/api/project.ts',
        api = await import(path)
      const a = await api.fetchCreateProject({ name, region: 'sh-1' })
      await new Promise((r) => setTimeout(r, 250))
      const b = await api.fetchCreateProject({ name: name + '-B', region: 'sh-1' })
      await new Promise((r) => setTimeout(r, 250))
      const member = await api.fetchInviteMember(a.id, { email, role: 'ADMIN' })
      return { a: a.id as string, b: b.id as string, member: member.accountId as string }
    },
    { name, email: MEMBER_EMAIL }
  )
  let primary: unknown,
    checkpoint = 'fixture'
  const mutations: string[] = [],
    otherWrites: string[] = []
  page.on('request', (r) => {
    const path = new URL(r.url()).pathname
    if (!path.includes('/assistant/') || r.method() === 'GET') return
    if (path.includes('/knowledge/sources/')) mutations.push(r.method())
    else if (!path.endsWith('/knowledge/search')) otherWrites.push(path)
  })
  const panel = () => page.getByTestId('project-knowledge')
  const response = (method: string, tail: string) =>
    page.waitForResponse(
      (r) =>
        r.request().method() === method &&
        new URL(r.url()).pathname === `/api/v1/projects/${fixture.a}/assistant/knowledge${tail}`
    )
  const context = await browser.newContext({ baseURL: new URL(page.url()).origin }),
    member = await context.newPage()
  member.setDefaultTimeout(20_000)
  member.setDefaultNavigationTimeout(20_000)
  try {
    await page.reload()
    await enterProject(page, name)
    await page.goto('/#/project/settings')
    await expect(panel()).toBeVisible()
    await expect(panel().getByLabel('批准知识正文')).toBeDisabled()
    await panel().getByRole('button', { name: '刷新批准资料', exact: true }).click()
    await expect(panel()).toContainText('当前项目尚无批准资料')
    await panel().getByLabel('知识来源标识').fill('guide')
    const original = '灌溉检查 <img src=x onerror=alert(1)> 仅为受控测试资料'
    await panel().getByLabel('批准知识正文').fill(original)
    await expect(
      panel().getByRole('button', { name: '发布项目共享版本', exact: true })
    ).toBeDisabled()
    await panel()
      .getByText('我已筛选脱敏，并批准本项目全部当前成员读取本次正文', { exact: true })
      .click()
    const created = response('PUT', '/sources/guide')
    await panel().getByRole('button', { name: '发布项目共享版本', exact: true }).click()
    const firstResponse = await created
    expect(firstResponse.status()).toBe(201)
    expect(firstResponse.headers()['cache-control']).toContain('no-store')
    const first = await firstResponse.json()
    expect(first.versionNumber).toBe(1)
    await expect(panel()).toContainText('版本已发布')
    await expect(panel().getByLabel('批准知识正文')).toHaveValue('')
    checkpoint = 'replace-source'
    await panel().getByRole('button', { name: '查看当前正文', exact: true }).click()
    await expect(panel().locator('pre')).toHaveText(original)
    await expect(panel().locator('img')).toHaveCount(0)
    await panel().getByLabel('批准知识正文').fill('排水维护说明')
    await expect(
      panel().getByRole('button', { name: '发布项目共享版本', exact: true })
    ).toBeDisabled()
    await panel()
      .getByText('我已筛选脱敏，并批准本项目全部当前成员读取本次正文', { exact: true })
      .click()
    const replaced = response('PUT', '/sources/guide')
    await panel().getByRole('button', { name: '发布项目共享版本', exact: true }).click()
    const second = await (await replaced).json()
    expect(second.versionNumber).toBe(2)
    expect(second.id).not.toBe(first.id)
    await panel().getByLabel('知识字面关键词').fill('灌溉')
    const noMatch = response('POST', '/search')
    await panel().getByRole('button', { name: '手动本地检索', exact: true }).click()
    expect((await (await noMatch).json()).state).toBe('NO_MATCH')
    await expect(panel()).toContainText('不能推断设备正常')
    await panel().getByLabel('知识字面关键词').fill('排水')
    const matched = response('POST', '/search')
    await panel().getByRole('button', { name: '手动本地检索', exact: true }).click()
    const hits = await (await matched).json()
    expect(hits.externalAllowed).toBe(false)
    expect(hits.hits[0].source.id).toBe(second.id)
    await expect(panel()).toContainText(second.contentSha256)
    checkpoint = 'collaborator-login'
    console.log('knowledge-checkpoint=' + checkpoint)
    await login(member, MEMBER_EMAIL, MEMBER_PASSWORD)
    checkpoint = 'collaborator-enter-project'
    console.log('knowledge-checkpoint=' + checkpoint)
    await enterProject(member, name)
    for (const role of ['ADMIN', 'OPERATOR', 'VIEWER']) {
      checkpoint = 'role-' + role + '-update'
      console.log('knowledge-checkpoint=' + checkpoint)
      if (role !== 'ADMIN')
        await page.evaluate(
          async ({ f, role }) => {
            const path = '/src/api/project.ts'
            await (await import(path)).fetchUpdateMemberRole(f.a, f.member, role)
          },
          { f: fixture, role }
        )
      checkpoint = 'role-' + role + '-reload'
      console.log('knowledge-checkpoint=' + checkpoint)
      await member.reload()
      await expect(member.getByRole('menuitem', { name: '设备开发', exact: true })).toBeVisible()
      checkpoint = 'role-' + role + '-settings'
      console.log('knowledge-checkpoint=' + checkpoint)
      await member.goto('/#/project/settings')
      await expect(member).toHaveURL(/\/project\/settings$/)
      await expect(member.getByTestId('project-knowledge')).toBeVisible()
      checkpoint = 'role-' + role + '-read'
      console.log('knowledge-checkpoint=' + checkpoint)
      const shared = member.getByTestId('project-knowledge')
      await shared.getByRole('button', { name: '刷新批准资料', exact: true }).click()
      await expect(shared).toContainText('guide')
      await shared.getByRole('button', { name: '查看当前正文', exact: true }).click()
      await expect(shared.locator('pre')).toHaveText('排水维护说明')
      checkpoint = 'role-' + role + '-forbidden-write'
      console.log('knowledge-checkpoint=' + checkpoint)
      if (role !== 'ADMIN') {
        await expect(shared.getByLabel('批准知识正文')).toHaveCount(0)
        const code = await member.evaluate(async (project) => {
          const path = '/src/api/assistant-knowledge.ts'
          try {
            await (
              await import(path)
            ).publishKnowledge(
              project,
              'not_allowed',
              null,
              '排水说明',
              new AbortController().signal
            )
            return 200
          } catch (e) {
            return (e as { code: number }).code
          }
        }, fixture.a)
        expect(code).toBe(50002)
      }
    }
    checkpoint = 'project-switch'
    await enterProject(page, name + '-B')
    await page.goto('/#/project/settings')
    await expect(panel()).toBeVisible()
    await expect(panel()).not.toContainText(second.contentSha256)
    await panel().getByRole('button', { name: '刷新批准资料', exact: true }).click()
    await expect(panel()).toContainText('当前项目尚无批准资料')
    const code = await page.evaluate(async (project) => {
      const path = '/src/api/assistant-knowledge.ts'
      try {
        await (await import(path)).readKnowledge(project, 'guide', new AbortController().signal)
        return 200
      } catch (e) {
        return (e as { code: number }).code
      }
    }, fixture.a)
    expect(code).toBe(10004)
    await enterProject(page, name)
    await page.goto('/#/project/settings')
    await expect(panel()).toBeVisible()
    await panel().getByRole('button', { name: '刷新批准资料', exact: true }).click()
    await panel().getByRole('button', { name: '查看当前正文', exact: true }).click()
    checkpoint = 'delete-source'
    const removed = response('DELETE', '/sources/guide')
    await panel().getByRole('button', { name: '删除此来源全部版本', exact: true }).click()
    expect((await removed).status()).toBe(204)
    await panel().getByLabel('知识字面关键词').fill('排水')
    const absent = response('POST', '/search')
    await panel().getByRole('button', { name: '手动本地检索', exact: true }).click()
    expect((await (await absent).json()).state).toBe('NO_SOURCES')
    expect(mutations).toEqual(['PUT', 'PUT', 'DELETE'])
    expect(otherWrites).toEqual([])
  } catch (error) {
    primary = error
    console.error(`project-knowledge failed checkpoint=${checkpoint}`)
  } finally {
    try {
      await context.close()
    } catch (cleanup) {
      console.error('知识浏览器上下文关闭失败，保留原异常')
      if (primary === undefined) primary = cleanup
    }
    try {
      const current = await page.evaluate(async () => {
        const path = '/src/store/modules/user.ts'
        return (await import(path)).useUserStore().info.currentProjectId
      })
      if (current !== fixture.a) await enterProject(page, name)
      await page.evaluate(async (f) => {
        const path = '/src/api/project.ts',
          api = await import(path)
        await api.fetchRemoveMember(f.a, f.member)
        await new Promise((r) => setTimeout(r, 250))
        await api.fetchDeleteProject(f.b)
        await new Promise((r) => setTimeout(r, 250))
        await api.fetchDeleteProject(f.a)
      }, fixture)
    } catch {
      console.error('project-knowledge 专属资料清理失败')
      if (primary === undefined) primary = new Error('专属资料清理失败')
    }
  }
  if (primary !== undefined) throw primary
})
