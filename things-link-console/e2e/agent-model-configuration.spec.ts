import { expect, test } from '@playwright/test'
import {
  login,
  enterProject,
  OWNER_EMAIL,
  OWNER_PASSWORD,
  MEMBER_EMAIL,
  MEMBER_PASSWORD
} from './helpers'

test('项目模型Key：真实保存启停、跨项目隔离、管理员边界及秘密不回显', async ({ page, browser }) => {
  await login(page, OWNER_EMAIL, OWNER_PASSWORD)
  const name = `Agent配置-${Date.now()}`
  const fixture = await page.evaluate(
    async ({ name, email }) => {
      const path = '/src/api/project.ts'
      const api = await import(path)
      const a = await api.fetchCreateProject({ name, region: 'sh-1' })
      const b = await api.fetchCreateProject({ name: name + '-B', region: 'sh-1' })
      const member = await api.fetchInviteMember(a.id, { email, role: 'ADMIN' })
      return { a: a.id as string, b: b.id as string, member: member.accountId as string }
    },
    { name, email: MEMBER_EMAIL }
  )
  await page.reload()
  await enterProject(page, name)
  await page.goto('/#/project/settings')
  const panel = page.getByTestId('agent-model-panel'),
    status = panel.getByTestId('model-status')
  await expect(status).toHaveText('未配置')
  const secret = `synthetic-browser-key-${Date.now()}`
  await panel.getByLabel('新的 DeepSeek API Key').fill(secret)
  const response = page.waitForResponse(
    (r) => r.request().method() === 'PUT' && r.url().includes('/assistant/model-configurations/')
  )
  await panel.getByRole('button', { name: '保存并启用', exact: true }).click()
  expect(JSON.stringify(await (await response).json())).not.toContain(secret)
  await expect(status).toHaveText('已启用')
  await expect(panel.getByLabel('新的 DeepSeek API Key')).toHaveValue('')
  await panel.getByRole('button', { name: '停用', exact: true }).click()
  await expect(status).toHaveText('已配置，已停用')
  await panel.getByRole('button', { name: '启用', exact: true }).click()
  await expect(status).toHaveText('已启用')
  await panel.getByRole('button', { name: '停用', exact: true }).click()
  await expect(status).toHaveText('已配置，已停用')
  await page.screenshot({ path: test.info().outputPath('agent-model-config.png'), fullPage: true })
  await panel.getByLabel('新的 DeepSeek API Key').fill('unsent-project-a-secret')
  await enterProject(page, name + '-B')
  await page.goto('/#/project/settings')
  await expect(status).toHaveText('未配置')
  await expect(panel.getByLabel('新的 DeepSeek API Key')).toHaveValue('')
  const storage = await page.evaluate(() =>
    JSON.stringify([Object.entries(localStorage), Object.entries(sessionStorage)])
  )
  expect(storage).not.toContain(secret)
  expect(storage).not.toContain('unsent-project-a-secret')
  await enterProject(page, name)
  await page.goto('/#/project/settings')
  await expect(status).toHaveText('已配置，已停用')
  const context = await browser.newContext({ baseURL: new URL(page.url()).origin }),
    member = await context.newPage()
  try {
    await login(member, MEMBER_EMAIL, MEMBER_PASSWORD)
    await enterProject(member, name)
    await member.goto('/#/project/settings')
    await expect(member.getByTestId('model-status')).toHaveText('已配置，已停用')
    await expect(member.getByLabel('新的 DeepSeek API Key')).toHaveValue('')
    for (const role of ['OPERATOR', 'VIEWER']) {
      await page.evaluate(
        async ({ project, account, role }) => {
          const path = '/src/api/project.ts'
          const api = await import(path)
          await api.fetchUpdateMemberRole(project, account, role)
        },
        { project: fixture.a, account: fixture.member, role }
      )
      await member.reload()
      await member.goto('/#/project/settings')
      await expect(member.getByTestId('agent-model-panel')).toHaveCount(0)
      const code = await member.evaluate(async (project) => {
        const path = '/src/api/assistant-model.ts'
        const api = await import(path)
        try {
          await api.readModelConfiguration(project)
          return 200
        } catch (error) {
          return (error as { code: number }).code
        }
      }, fixture.a)
      expect(code).toBe(50002)
    }
    await panel.getByRole('button', { name: '移除 Key', exact: true }).click()
    await page.getByRole('button', { name: '确定', exact: true }).click()
    await expect(status).toHaveText('未配置')
    await page.evaluate(async (f) => {
      const path = '/src/api/project.ts'
      const api = await import(path)
      await api.fetchRemoveMember(f.a, f.member)
      await api.fetchDeleteProject(f.b)
      await api.fetchDeleteProject(f.a)
    }, fixture)
  } finally {
    await context.close()
  }
})
