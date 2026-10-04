import { expect, test, type Page } from '@playwright/test'
import { randomBytes, createHash } from 'node:crypto'
import { readFileSync } from 'node:fs'
import {
  login,
  enterProject,
  acceptProjectInvitationFromInbox,
  OWNER_EMAIL,
  OWNER_PASSWORD
} from './helpers'
import { OwnedFixture } from './owned-fixture'
import { readIdentityLink } from './identity-mail-fixture'

// Secrets never appear in action titles, traces, screenshots or public attachments.
test.use({
  trace: 'off',
  video: 'off',
  screenshot: 'off',
  actionTimeout: 15_000,
  navigationTimeout: 30_000
})

const publicPost = (page: Page, url: string, body: unknown) =>
  page.evaluate(
    async ({ url, body }) => {
      const response = await fetch(url, {
        method: 'POST',
        signal: AbortSignal.timeout(10_000),
        redirect: 'error',
        credentials: 'include',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body)
      })
      const value = response.status === 204 ? null : await response.json()
      return { status: response.status, code: value?.code ?? 0 }
    },
    { url, body }
  )
const projectApi = (page: Page, method: string, ...args: unknown[]) =>
  page.evaluate(
    async ({ method, args }) => {
      const module = '/src/api/project.ts'
      return (await import(module))[method](...args)
    },
    { method, args }
  )

// Evaluate does not put the argument into Playwright's human-readable Fill/Goto step titles.
const secretInput = (page: Page, label: string, value: string) =>
  page.getByLabel(label, { exact: true }).evaluate((node, value) => {
    const input = node as HTMLInputElement
    input.value = value
    input.dispatchEvent(new Event('input', { bubbles: true }))
  }, value)

test(
  '未注册邀请：真实TLS邮件、绑定注册和邮箱验证后明确接受一次',
  { tag: '@simulator' },
  async ({ page, browser }, testInfo) => {
    test.skip(
      process.env.E2E_SMTP_PRIVATE_IDENTITY !== '1' || process.env.E2E_CONTROLLED_SMTP !== '1',
      '需要精确隔离的私有身份SMTP选场'
    )
    test.setTimeout(240_000)
    const owned = new OwnedFixture()
    let primary: { error: unknown } | undefined
    let stage = 'private prerequisites'
    const results: Record<string, unknown> = {}
    try {
      const directory = process.env.E2E_TEST_TLS_DIRECTORY
      const recipientEmail = process.env.E2E_INVITATION_RECIPIENT
      if (
        !directory ||
        !recipientEmail ||
        !/^invited-[a-f0-9]{32}@example\.test$/.test(recipientEmail)
      )
        throw new Error('Missing private identity prerequisites')
      const password = 'Local-' + randomBytes(24).toString('hex')
      const targetSha256 = createHash('sha256').update(recipientEmail).digest('hex')
      stage = 'owner project'
      await login(page, OWNER_EMAIL, OWNER_PASSWORD)
      const projectName = `邮件邀请-${Date.now()}`
      const project = await projectApi(page, 'fetchCreateProject', {
        name: projectName,
        region: 'sh-1'
      })
      owned.own('owned project', () => projectApi(page, 'fetchDeleteProject', project.id))
      // Each cleanup is attempted separately; account/tenant has no public deletion API and belongs to disposable stack.
      owned.own('owned invited membership', async () => {
        const rows = await projectApi(page, 'fetchProjectMembers', project.id)
        const member = rows.find((row: { email: string }) => row.email === recipientEmail)
        if (member) await projectApi(page, 'fetchRemoveMember', project.id, member.accountId)
      })
      await page.reload()
      await enterProject(page, projectName)
      const origin = new URL(page.url()).origin
      const context = await browser.newContext({ baseURL: origin })
      owned.own('recipient context', () => context.close())
      const recipient = await context.newPage()
      // browser.newContext is explicit: give its actions/navigation the same bounded budgets.
      recipient.setDefaultTimeout(15_000)
      recipient.setDefaultNavigationTimeout(30_000)
      const members = async () =>
        (await projectApi(page, 'fetchProjectMembers', project.id))
          .filter((row: { email: string }) => row.email === recipientEmail)
          .map((row: { accountId: string; role: string }) => ({
            accountId: row.accountId,
            role: row.role
          }))
      const invitation = () =>
        page.evaluate(async (projectId) => {
          const module = '/src/api/project-invitations.ts'
          const values = (await (await import(module)).fetchProjectInvitations(projectId)).items
          if (values.length !== 1) throw new Error('Expected single owned invitation')
          const value = values[0]
          return {
            id: value.id,
            status: value.status,
            channel: value.deliveryChannel,
            delivery: value.deliveryStatus,
            hasCode: Boolean(value.code)
          }
        }, project.id)
      stage = 'create email invitation'
      await page.goto('/#/project/members')
      await page.getByRole('button', { name: '邀请成员', exact: true }).click()
      const dialog = page.getByRole('dialog', { name: '邀请成员', exact: true })
      await dialog.getByPlaceholder('接收邀请的邮箱').evaluate((node, email) => {
        const input = node as HTMLInputElement
        input.value = email
        input.dispatchEvent(new Event('input', { bubbles: true }))
      }, recipientEmail)
      await dialog.locator('.el-select').click()
      await page.getByRole('option', { name: 'VIEWER — 只读', exact: true }).click()
      await dialog.getByRole('button', { name: '确定', exact: true }).click()
      await expect(dialog).not.toBeVisible()
      const created = await invitation()
      expect(created.channel).toBe('EMAIL')
      expect(created.hasCode).toBe(false)
      await expect.poll(async () => (await invitation()).delivery).toBe('SENT')
      expect(await members()).toEqual([])
      const mail = (kind: 'invitation' | 'verification') =>
        readIdentityLink(directory, recipientEmail, origin, kind, created.id)
      stage = 'received invitation mail'
      await expect.poll(() => Boolean(mail('invitation'))).toBe(true)
      const proof = mail('invitation')!
      stage = 'recipient login application ready'
      await recipient.goto('/#/auth/login', { waitUntil: 'domcontentloaded' })
      // DOMContentLoaded only means Vite's document is ready, not the async Vue router.
      // Wait for the real mounted login form before a fragment change can race bootstrap.
      await expect(recipient.getByPlaceholder('请输入邮箱')).toBeVisible({ timeout: 30_000 })
      stage = 'received invitation preview'
      const [preview] = await Promise.all([
        recipient.waitForResponse(
          (r) =>
            new URL(r.url()).pathname === '/api/v1/auth/project-invitation/preview' &&
            r.request().method() === 'POST',
          { timeout: 15_000 }
        ),
        recipient.evaluate((fragment) => {
          window.location.hash = fragment
        }, proof.fragment)
      ])
      expect(preview.status()).toBe(200)
      expect(preview.headers()['cache-control']).toContain('no-store')
      await expect(recipient.getByLabel('邀请绑定邮箱')).toHaveAttribute('readonly')
      expect(
        await recipient
          .getByLabel('邀请绑定邮箱')
          .evaluate((node, email) => (node as HTMLInputElement).value === email, recipientEmail)
      ).toBe(true)
      expect(await members()).toEqual([])
      stage = 'wrong target rejected'
      const mismatched = await publicPost(recipient, '/api/v1/auth/project-invitation/register', {
        invitationId: created.id,
        code: proof.secret,
        email: 'wrong-' + recipientEmail,
        password
      })
      expect(mismatched).toEqual({ status: 400, code: 10001 })
      results.targetMismatch = mismatched
      expect(await members()).toEqual([])
      stage = 'bound registration'
      await secretInput(recipient, '注册口令', password)
      await secretInput(recipient, '确认口令', password)
      // Element Plus visually hides its native input; click the real visible label.
      const consent = '我同意注册账号并接收用于验证该邮箱的邮件'
      await recipient.getByText(consent, { exact: true }).click()
      await expect(recipient.getByRole('checkbox', { name: consent })).toBeChecked()
      stage = 'bound registration submission'
      const [registered] = await Promise.all([
        recipient.waitForResponse(
          (r) =>
            new URL(r.url()).pathname === '/api/v1/auth/project-invitation/register' &&
            r.request().method() === 'POST',
          { timeout: 15_000 }
        ),
        recipient.getByRole('button', { name: '按此邮箱注册', exact: true }).click()
      ])
      results.registrationHttpStatus = registered.status()
      expect(registered.status()).toBe(204)
      stage = 'registered pending verification'
      await expect(
        recipient.getByText(
          '账号已注册。请查收邮箱验证邮件，完成验证并登录后，在个人中心确认接受邀请。',
          { exact: true }
        )
      ).toBeVisible()
      expect(await members()).toEqual([])
      expect((await invitation()).status).toBe('PENDING')
      stage = 'unverified login refused'
      const unverified = await publicPost(recipient, '/api/v1/auth/login', {
        email: recipientEmail,
        password
      })
      expect(unverified).toEqual({ status: 403, code: 20022 })
      results.unverifiedLogin = unverified
      stage = 'received verification and explicit verify'
      await expect.poll(() => Boolean(mail('verification'))).toBe(true)
      const verification = mail('verification')!
      await recipient.evaluate((fragment) => {
        window.location.hash = fragment
      }, verification.fragment)
      await expect(recipient.getByRole('button', { name: '验证邮箱', exact: true })).toBeVisible()
      // Opening a real mailbox URL must not consume it or create membership.
      expect(await members()).toEqual([])
      expect(
        await publicPost(recipient, '/api/v1/auth/login', { email: recipientEmail, password })
      ).toEqual({ status: 403, code: 20022 })
      const [verified] = await Promise.all([
        recipient.waitForResponse(
          (r) =>
            new URL(r.url()).pathname === '/api/v1/auth/email/verify' &&
            r.request().method() === 'POST',
          { timeout: 15_000 }
        ),
        recipient.getByRole('button', { name: '验证邮箱', exact: true }).click()
      ])
      expect(verified.status()).toBe(200)
      expect((await verified.json()).email === recipientEmail).toBe(true)
      expect(await members()).toEqual([])
      expect((await invitation()).status).toBe('PENDING')
      stage = 'verified login without membership'
      // Leave the verification component and its redirect timer before starting real UI login.
      await recipient.goto('/#/auth/login')
      await recipient.getByPlaceholder('请输入邮箱').evaluate((node, email) => {
        const input = node as HTMLInputElement
        input.value = email
        input.dispatchEvent(new Event('input', { bubbles: true }))
      }, recipientEmail)
      await recipient.getByPlaceholder('请输入密码').evaluate((node, value) => {
        const input = node as HTMLInputElement
        input.value = value
        input.dispatchEvent(new Event('input', { bubbles: true }))
      }, password)
      const { dragToPass } = await import('./helpers')
      await dragToPass(recipient)
      await recipient.getByRole('button', { name: '登录', exact: true }).click()
      await recipient.waitForURL((url) => !url.hash.includes('/auth/login'), { timeout: 20_000 })
      const me = await recipient.evaluate(async () => {
        const module = '/src/api/auth.ts'
        const value = await (await import(module)).fetchGetUserInfo()
        return { id: value.userId, email: value.email }
      })
      expect(me.email === recipientEmail).toBe(true)
      expect(
        (await projectApi(recipient, 'fetchProjects')).some(
          (p: { id: string }) => p.id === project.id
        )
      ).toBe(false)
      expect(await members()).toEqual([])
      stage = 'explicit acceptance'
      await acceptProjectInvitationFromInbox(recipient, projectName)
      expect(await members()).toEqual([{ accountId: me.id, role: 'VIEWER' }])
      expect((await invitation()).status).toBe('ACCEPTED')
      expect(
        (await projectApi(recipient, 'fetchProjects')).some(
          (p: { id: string }) => p.id === project.id
        )
      ).toBe(true)
      stage = 'accepted proof replay refused'
      const replay = await recipient.evaluate(
        async ({ id, code }) => {
          const module = '/src/utils/http/index.ts'
          try {
            await (
              await import(module)
            ).default.post({
              url: `/api/v1/project-invitations/${id}/accept`,
              params: { code },
              showErrorMessage: false
            })
            return 0
          } catch (error) {
            return (error as { code: number }).code
          }
        },
        { id: created.id, code: proof.secret }
      )
      expect(replay).toBe(50057)
      expect(await members()).toEqual([{ accountId: me.id, role: 'VIEWER' }])
      const receipt = JSON.parse(readFileSync(`${directory}/smtp-receipts.json`, 'utf8'))
      expect(receipt).toHaveLength(2)
      expect(
        receipt.every(
          (row: { targetSha256: string; accepted: boolean; authenticated: boolean; tls: string }) =>
            row.targetSha256 === targetSha256 &&
            row.accepted &&
            row.authenticated &&
            ['TLSv1.2', 'TLSv1.3'].includes(row.tls)
        )
      ).toBe(true)
      results.acceptedReplayCode = replay
      results.controlledSmtp = { attempts: 2, accepted: 2, targetSha256 }
      results.scope =
        'real local TLS SMTP to bound registration, explicit verification and single acceptance; no external mailbox qualification'
      results.cleanup =
        'owned project/member via public API; registered account/tenant only in disposable stack'
      stage = 'completed'
    } catch {
      // Browser errors can include fragment tokens or input values: publish only the stable checkpoint.
      primary = { error: new Error(`Private invitation journey failed at checkpoint: ${stage}`) }
    } finally {
      results.checkpoint = stage
      await testInfo.attach('identity-mail-receipt', {
        body: JSON.stringify(results),
        contentType: 'application/json'
      })
      try {
        await owned.finish(primary)
      } finally {
        // Prevent Playwright's automatic error context from retaining recipient UI contents.
        await page.goto('about:blank', { timeout: 5_000 }).catch(() => {})
      }
    }
  }
)
