import { expect, test, type Page } from '@playwright/test'
import {
  acceptProjectInvitationFromInbox,
  enterProject,
  login,
  MEMBER_EMAIL,
  MEMBER_PASSWORD,
  OWNER_EMAIL,
  OWNER_PASSWORD
} from './helpers'

// 接受证明只留在本场内存，失败追踪不保留含码的请求/fragment。
test.use({ trace: 'off', video: 'off', screenshot: 'off' })

// 只使用隔离栈的已验证夹具账号。邮件实际到达、过期/重发/席位竞争由独立 HTTP 门禁承担。
async function createInvitation(page: Page, name: string) {
  await login(page, OWNER_EMAIL, OWNER_PASSWORD)
  const projectId = await page.evaluate(async (name) => {
    const path = '/src/api/project.ts'
    const { fetchCreateProject } = await import(path)
    return (await fetchCreateProject({ name, region: 'sh-1' })).id as string
  }, name)
  expect(projectId).toBeTruthy()
  // API 夹具不会触发项目页刷新；登录可能已落在同一路由，先重载真实目录。
  await page.reload()
  await enterProject(page, name)
  await page.goto('/#/project/members')
  await page.getByRole('button', { name: '邀请成员', exact: true }).click()
  const dialog = page.getByRole('dialog', { name: '邀请成员', exact: true })
  await dialog.getByPlaceholder('接收邀请的邮箱').fill(MEMBER_EMAIL)
  await dialog.locator('.el-select').click()
  await page.getByRole('option', { name: 'VIEWER — 只读', exact: true }).click()
  await dialog.getByRole('button', { name: '确定', exact: true }).click()
  await expect(dialog).not.toBeVisible()
  const invitationRow = page.locator('.project-invitations tr').filter({ hasText: MEMBER_EMAIL })
  await expect(invitationRow).toContainText('待接受')
  await expect(invitationRow).toContainText('已放入站内收件箱')
  await expect(
    page.locator('.project-members__email').filter({ hasText: MEMBER_EMAIL })
  ).toHaveCount(0)
  const invitation = await page.evaluate(async (projectId) => {
    const path = '/src/api/project-invitations.ts'
    const { fetchProjectInvitations } = await import(path)
    const rows = (await fetchProjectInvitations(projectId)).items
    if (rows.length !== 1) throw new Error('新项目邀请夹具应只有一条记录')
    return rows[0] as { id: string; code?: string; status: string }
  }, projectId)
  expect(invitation.id).toBeTruthy()
  expect(invitation.code).toBeFalsy() // 管理响应不能泄漏接受证明。
  return { projectId, invitationId: invitation.id }
}

async function readRecipientProof(page: Page, invitationId: string) {
  return page.evaluate(async (id) => {
    const path = '/src/api/project-invitations.ts'
    const { fetchMyProjectInvitations } = await import(path)
    const value = (await fetchMyProjectInvitations()).items.find(
      (row: { id: string }) => row.id === id
    )
    if (!value?.code) throw new Error('收件人缺少当前邀请证明')
    return { invitationId: id, code: value.code as string }
  }, invitationId)
}

async function attemptAcceptance(page: Page, proof: { invitationId: string; code: string }) {
  // 返回错误码而非带凭据的异常对象；请求仍由当前浏览器实际会话发送。
  return page.evaluate(async ({ invitationId, code }) => {
    const path = '/src/utils/http/index.ts'
    const { default: request } = await import(path)
    try {
      await request.post({
        url: `/api/v1/project-invitations/${invitationId}/accept`,
        params: { code },
        showErrorMessage: false
      })
      return 0
    } catch (error) {
      return (error as { code: number }).code
    }
  }, proof)
}

async function hasProject(page: Page, projectId: string) {
  return page.evaluate(async (id) => {
    const path = '/src/api/project.ts'
    const { fetchProjects } = await import(path)
    return (await fetchProjects()).some((project: { id: string }) => project.id === id)
  }, projectId)
}

// 成功场景通过真实管理接口回收自身项目；失败时保留事实供复盘，由隔离栈清理接管。
async function cleanupProject(page: Page, projectId: string) {
  await page.evaluate(
    async ({ id, email }) => {
      const path = '/src/api/project.ts'
      const api = await import(path)
      const member = (await api.fetchProjectMembers(id)).find(
        (entry: { email: string }) => entry.email === email
      )
      if (member) await api.fetchRemoveMember(id, member.accountId)
      await api.fetchDeleteProject(id)
    },
    { id: projectId, email: MEMBER_EMAIL }
  )
}

test('项目邀请：绑定邮箱预览不入成员，跨身份拒绝，收件人显式接受且不可重放', async ({
  page,
  browser
}) => {
  const projectName = `邀请接受-${Date.now()}`
  const { projectId, invitationId } = await createInvitation(page, projectName)
  const baseURL = new URL(page.url()).origin
  const recipientContext = await browser.newContext({ baseURL })
  const recipient = await recipientContext.newPage()
  const anonymousContext = await browser.newContext({ baseURL })
  const anonymous = await anonymousContext.newPage()
  try {
    await login(recipient, MEMBER_EMAIL, MEMBER_PASSWORD)
    expect(await hasProject(recipient, projectId)).toBe(false)
    const proof = await readRecipientProof(recipient, invitationId)
    // URL proof stays in the fragment; the preview sends it in a POST body.
    const previewResponse = anonymous.waitForResponse(
      (response) =>
        new URL(response.url()).pathname === '/api/v1/auth/project-invitation/preview' &&
        response.request().method() === 'POST'
    )
    await anonymous.goto(`/#/auth/project-invitation/${invitationId}?code=${proof.code}`)
    const preview = await previewResponse
    expect(preview.status()).toBe(200)
    expect(preview.headers()['cache-control']).toContain('no-store')
    expect(new URL(preview.url()).search).toBe('')
    await expect(anonymous.getByLabel('邀请绑定邮箱')).toHaveValue(MEMBER_EMAIL)
    await expect(anonymous.getByLabel('邀请绑定邮箱')).toHaveAttribute('readonly')
    await expect(anonymous.getByText('项目：' + projectName, { exact: false })).toBeVisible()
    expect(await hasProject(recipient, projectId)).toBe(false)
    expect(await attemptAcceptance(page, proof)).toBe(50056)
    expect(await hasProject(recipient, projectId)).toBe(false)

    await acceptProjectInvitationFromInbox(recipient, projectName)
    expect(await hasProject(recipient, projectId)).toBe(true)
    expect(await attemptAcceptance(recipient, proof)).toBe(50057)
    await enterProject(recipient, projectName)
    await recipient.goto('/#/project/members')
    await expect(recipient.getByRole('button', { name: '邀请成员', exact: true })).toHaveCount(0)
    const memberships = await page.evaluate(
      async ({ projectId, email }) => {
        const path = '/src/api/project.ts'
        const { fetchProjectMembers } = await import(path)
        return (await fetchProjectMembers(projectId))
          .filter((member: { email: string }) => member.email === email)
          .map((member: { role: string }) => member.role)
      },
      { projectId, email: MEMBER_EMAIL }
    )
    expect(memberships).toEqual(['VIEWER'])
    await cleanupProject(page, projectId)
  } finally {
    await recipientContext.close()
    await anonymousContext.close()
  }
})

test('项目邀请：管理员撤回后旧链接与收件人接受均拒绝，不建立成员关系', async ({
  page,
  browser
}) => {
  const projectName = `邀请撤回-${Date.now()}`
  const { projectId, invitationId } = await createInvitation(page, projectName)
  const recipientContext = await browser.newContext({ baseURL: new URL(page.url()).origin })
  const recipient = await recipientContext.newPage()
  try {
    await login(recipient, MEMBER_EMAIL, MEMBER_PASSWORD)
    const proof = await readRecipientProof(recipient, invitationId)
    const row = page.locator('.project-invitations tr').filter({ hasText: MEMBER_EMAIL })
    await row.getByRole('button', { name: '撤回', exact: true }).click()
    await page.getByRole('dialog').getByRole('button', { name: '确定', exact: true }).click()
    await expect(row).toContainText('已撤回')
    expect(await attemptAcceptance(recipient, proof)).toBe(50057)
    expect(await hasProject(recipient, projectId)).toBe(false)
    await recipient.goto(`/#/auth/project-invitation/${invitationId}?code=${proof.code}`)
    await expect(recipient.getByText('邀请不可用，请联系邀请方确认或重发')).toBeVisible()
    await expect(recipient.getByLabel('邀请绑定邮箱')).toHaveCount(0)
    expect(await hasProject(recipient, projectId)).toBe(false)
    await cleanupProject(page, projectId)
  } finally {
    await recipientContext.close()
  }
})
