import { expect, test } from '@playwright/test'
import { randomUUID } from 'node:crypto'
import { existsSync } from 'node:fs'
import {
  login,
  enterProject,
  openDeviceList,
  openDeviceDetails,
  OWNER_EMAIL,
  OWNER_PASSWORD
} from './helpers'
import { OwnedFixture } from './owned-fixture'
import { AssociationRequester } from './association-request-fixture'
import {
  assertAssociationFixture,
  prepareAssociationQuota,
  installAssociationQuota,
  restoreAssociationQuota
} from './association-quota-fixture'

// App login/claim return credentials: never persist traces, screenshots or videos.
test.use({ trace: 'off', video: 'off', screenshot: 'off' })

/** All rows are public-API products. Policy CAS is the sole controlled fixture mutation.
 * Commands are accepted history, not proof of physical execution. Configuration candidates
 * are published then paused, not invented automation/scene/message-rule execution records.
 */
test(
  '设备关联非空目录、20+1游标分页与跨项目拒绝',
  { tag: '@simulator' },
  async ({ page }, testInfo) => {
    test.skip(process.env.E2E_ASSOCIATION_FIXTURE !== '1', '需要专属一次性关联目录选场')
    assertAssociationFixture(process.env, existsSync('/.thingslink-local-ci'))
    test.setTimeout(420_000)
    const owned = new OwnedFixture()
    let primary: { error: unknown } | undefined
    let checkpoint = 'login'
    const requests = new AssociationRequester()
    // Bulk fixture setup/cleanup stays below the unchanged account write budget.
    const api = (module: string, method: string, ...args: unknown[]) =>
      requests.request(`${module}.${method}`, () =>
        page.evaluate(
          async ({ module, method, args }) => {
            try {
              if (module === 'http') {
                const path = '/src/utils/http/index.ts'
                return {
                  ok: true as const,
                  value: await (await import(path)).default.post(args[0])
                }
              }
              const path = `/src/api/${module}.ts`
              return { ok: true as const, value: await (await import(path))[method](...args) }
            } catch (failure) {
              const error = failure as { code?: unknown; response?: { status?: unknown } }
              // Do not serialize an Axios/HTTP error: it can contain credentials or request data.
              return {
                ok: false as const,
                code:
                  typeof error?.code === 'number' && Number.isSafeInteger(error.code)
                    ? error.code
                    : null,
                status:
                  typeof error?.response?.status === 'number' &&
                  Number.isSafeInteger(error.response.status)
                    ? error.response.status
                    : null
              }
            }
          },
          { module, method, args }
        )
      )
    const post = (url: string, params: unknown) => api('http', 'post', { url, params })
    const read = (url: string) =>
      requests.request('directory.read', async () => ({
        ok: true,
        value: await page.evaluate(async (url) => {
          const path = '/src/store/modules/user.ts'
          const { useUserStore } = await import(path)
          const result = await fetch(url, {
            headers: { Authorization: `Bearer ${useUserStore().accessToken}` },
            redirect: 'error'
          })
          return {
            status: result.status,
            cache: result.headers.get('cache-control'),
            body: await result.json()
          }
        }, url)
      }))
    try {
      await login(page, OWNER_EMAIL, OWNER_PASSWORD)
      checkpoint = 'project setup'
      const suffix = Date.now()
      const project = await api('project', 'fetchCreateProject', {
        name: `非空关联-${suffix}`,
        region: 'sh-1'
      })
      owned.own('association project', () => api('project', 'fetchDeleteProject', project.id))
      // Both projects belong to this run. A mixed project/device request must still reject.
      const other = await api('project', 'fetchCreateProject', {
        name: `关联隔离-${suffix}`,
        region: 'sh-1'
      })
      owned.own('association second project', () => api('project', 'fetchDeleteProject', other.id))
      await page.reload()
      await enterProject(page, project.name)
      checkpoint = 'owned quota preparation'
      const quota = await prepareAssociationQuota(project.id)
      owned.own('association quota', () => restoreAssociationQuota(quota))
      checkpoint = 'owned quota installation'
      await installAssociationQuota(quota)
      checkpoint = 'device type setup'
      const type = await api('device', 'fetchCreateDeviceType', project.id, {
        typeKey: `assoc_${suffix}`,
        name: `关联类型${suffix}`,
        deviceKind: 'DIRECT',
        payloadProtocol: 'STANDARD',
        networkType: 'WIFI'
      })
      await api('device', 'fetchCreateDevicePropertyDefinition', project.id, type.id, {
        propertyKey: 'relay',
        name: '继电器状态',
        dataType: 'SWITCH',
        accessType: 'REPORT',
        sortOrder: 0
      })
      await api('device', 'fetchCreateDeviceCommandDefinition', project.id, type.id, {
        commandKey: 'restart',
        name: '重启',
        inputSchema: '{"type":"object","additionalProperties":false}',
        outputSchema: '{"type":"object"}',
        timeoutSeconds: 5,
        sortOrder: 0
      })
      await api('device', 'fetchPublishDeviceType', project.id, type.id)
      checkpoint = 'device setup'
      const device = await api('device', 'fetchCreateDevice', project.id, {
        deviceTypeId: type.id,
        deviceKey: `assoc_${suffix}`,
        name: `非空关联设备${suffix}`
      })
      owned.own('association device', () =>
        api('device', 'fetchDeleteDevice', project.id, device.id)
      )
      const second = await api('device', 'fetchCreateDevice', project.id, {
        deviceTypeId: type.id,
        deviceKey: `assoc_other_${suffix}`,
        name: `游标隔离设备${suffix}`
      })
      owned.own('association second device', () =>
        api('device', 'fetchDeleteDevice', project.id, second.id)
      )
      const base = `/api/v1/projects/${project.id}`
      checkpoint = 'real app claim'
      const username = `assoc_${suffix}`
      const password = `Assoc!${randomUUID()}Aa1`
      const appUser = await post(`${base}/end-users`, {
        username,
        password,
        displayName: `关联终端用户${suffix}`
      })
      await post(`${base}/end-users/${appUser.id}/role`, { role: 'MAINTAINER' })
      owned.own('association app role', () => post(`${base}/end-users/${appUser.id}/suspend`, {}))
      // Keep session and one-use claim secret entirely inside the browser callback.
      const binding = await page.evaluate(
        async ({ projectKey, username, password, base, deviceId }) => {
          const requestPath = '/src/utils/http/index.ts'
          const request = (await import(requestPath)).default
          const app = async (url: string, body: unknown, token?: string) => {
            await new Promise((resolve) => setTimeout(resolve, 250))
            const response = await fetch(url, {
              method: 'POST',
              redirect: 'error',
              headers: {
                'Content-Type': 'application/json',
                ...(token ? { Authorization: `Bearer ${token}` } : {})
              },
              body: JSON.stringify(body)
            })
            if (!response.ok) throw new Error('Controlled app fixture request failed')
            return response.status === 204 ? undefined : response.json()
          }
          const session = await app('/api/v1/app/auth/login', { projectKey, username, password })
          try {
            await new Promise((resolve) => setTimeout(resolve, 250))
            const issued = await request.post({
              url: `${base}/end-users/device-claim-tokens`,
              params: { deviceId }
            })
            const result = await app(
              '/api/v1/app/device-claims',
              { token: issued.token },
              session.accessToken
            )
            return { id: result.bindingId, role: result.relationRole, deviceId: result.deviceId }
          } finally {
            await app('/api/v1/app/auth/logout', { refreshToken: session.refreshToken })
          }
        },
        { projectKey: project.projectKey, username, password, base, deviceId: device.id }
      )
      expect(binding.role).toBe('PRIMARY')
      expect(binding.deviceId).toBe(device.id)
      checkpoint = 'real commands and tasks'
      const commandIds: string[] = [],
        taskIds: string[] = []
      for (let index = 0; index < 21; index++) {
        checkpoint = `create command ${index + 1} of 21`
        const command = await api(
          'device',
          'fetchSubmitDeviceCommand',
          project.id,
          device.id,
          { commandKey: 'restart', input: {} },
          randomUUID()
        )
        commandIds.push(command.id)
        checkpoint = `create task ${index + 1} of 21`
        const job = await api('task', 'fetchCreateTaskJob', project.id, {
          name: `目录任务${index}-${suffix}`,
          scheduleType: 'ONCE',
          runAt: new Date(Date.now() + 86_400_000).toISOString(),
          targetType: 'ALL_DEVICES',
          commandKey: 'restart',
          input: {},
          enabled: false
        })
        taskIds.push(job.id)
        owned.own(`association task ${index}`, () =>
          api('task', 'fetchDeleteTaskJob', project.id, job.id, job.version)
        )
      }
      checkpoint = 'published configuration candidates'
      const action = {
        nodeType: 'device-command-action',
        config: { commandKey: 'restart', input: {} }
      }
      const definitions: { endpoint: string; tab: string; id: string }[] = []
      for (const kind of ['automation', 'scene', 'rule'] as const) {
        const naming = kind === 'automation' ? 'Automation' : kind === 'scene' ? 'Scene' : 'Rule'
        const module =
          kind === 'automation'
            ? 'automation-management'
            : kind === 'scene'
              ? 'scene-management'
              : 'rule-management'
        const payload =
          kind === 'automation'
            ? {
                name: `目录自动化${suffix}`,
                triggerType: 'PROPERTY_REPORTED',
                triggerConfig: { deviceId: device.id },
                conditions: [],
                actions: [action]
              }
            : kind === 'scene'
              ? { name: `目录场景${suffix}`, conditions: [], actions: [action] }
              : { name: `目录消息规则${suffix}`, source: 'input => input', actions: [action] }
        const definition = await api(module, `save${naming}`, project.id, '', payload)
        owned.own(`association ${kind}`, async () => {
          const current = await api(module, `get${naming}`, project.id, definition.id)
          await api(module, `delete${naming}`, project.id, definition.id, current.version)
        })
        const versions = await api(module, `${kind}History`, project.id, definition.id)
        expect(versions.items).toHaveLength(1)
        const active = await api(
          module,
          `activate${naming}`,
          project.id,
          definition.id,
          versions.items[0].id,
          definition.version
        )
        const paused = await api(
          module,
          `pause${naming}`,
          project.id,
          definition.id,
          active.version
        )
        expect(paused.activeVersionId).toBe(versions.items[0].id)
        expect(paused.status).toBe('PAUSED')
        definitions.push({
          id: definition.id,
          endpoint:
            kind === 'automation'
              ? 'automations'
              : kind === 'scene'
                ? 'scene-candidates'
                : 'message-rule-candidates',
          tab: kind === 'automation' ? '自动化' : kind === 'scene' ? '场景' : '消息规则'
        })
      }
      checkpoint = 'six populated browser directories'
      await openDeviceList(page)
      await page.getByRole('textbox', { name: '名称 / 标识', exact: true }).fill(device.deviceKey)
      await page.getByRole('button', { name: '查询', exact: true }).click()
      await openDeviceDetails(page, page.locator('tr', { hasText: device.deviceKey }).first())
      const detail = page.locator('.device-detail')
      const prefix = `${base}/devices/${device.id}/`
      const matrices = [
        { tab: '命令', endpoint: 'commands', ids: commandIds, refresh: '刷新历史' },
        { tab: '终端用户', endpoint: 'end-users', ids: [binding.id], visible: appUser.id },
        { tab: '任务调度', endpoint: 'task-jobs', ids: taskIds, refresh: '刷新任务' },
        ...definitions.map((entry) => ({ ...entry, ids: [entry.id] }))
      ]
      for (const entry of matrices) {
        const load = (cursor: boolean) =>
          page.waitForResponse(
            (r) =>
              r.request().method() === 'GET' &&
              new URL(r.url()).pathname === prefix + entry.endpoint &&
              new URL(r.url()).searchParams.has('cursor') === cursor
          )
        const pending = load(false)
        await detail.getByRole('tab', { name: entry.tab, exact: true }).click()
        const response = await pending
        expect(response.status()).toBe(200)
        expect(response.headers()['cache-control']).toBe('no-store')
        const first = await response.json()
        expect(first.items).toHaveLength(Math.min(20, entry.ids.length))
        const firstIds = first.items.map((item: { id: string }) => item.id)
        for (const item of first.items) {
          expect(entry.ids).toContain(item.id)
          const visible = 'visible' in entry && entry.visible ? entry.visible : item.id
          await expect(detail.getByText(visible, { exact: true }).first()).toBeVisible()
        }
        const next = detail
          .getByRole('button', { name: '下一页', exact: true })
          .filter({ visible: true })
        if (entry.ids.length > 20) {
          expect(typeof first.nextCursor).toBe('string')
          const pendingNext = load(true)
          await next.click()
          const nextResponse = await pendingNext
          expect(nextResponse.status()).toBe(200)
          const secondPage = await nextResponse.json()
          expect(secondPage.items).toHaveLength(1)
          expect(secondPage.nextCursor ?? null).toBeNull()
          expect(new Set([...firstIds, secondPage.items[0].id])).toEqual(new Set(entry.ids))
          await expect(
            detail.getByText(secondPage.items[0].id, { exact: true }).first()
          ).toBeVisible()
          await expect(next).toBeDisabled()
          const previous = load(false)
          await detail
            .getByRole('button', { name: '上一页', exact: true })
            .filter({ visible: true })
            .click()
          expect(
            (await (await previous).json()).items.map((item: { id: string }) => item.id)
          ).toEqual(firstIds)
          if ('refresh' in entry && entry.refresh) {
            const refreshed = load(false)
            await detail.getByRole('button', { name: entry.refresh, exact: true }).click()
            expect(
              (await (await refreshed).json()).items.map((item: { id: string }) => item.id)
            ).toEqual(firstIds)
          }
          const wrongCursor = await read(
            `${base}/devices/${second.id}/${entry.endpoint}?limit=20&cursor=${encodeURIComponent(first.nextCursor)}`
          )
          expect(wrongCursor.status).toBe(400)
        } else {
          expect(first.nextCursor ?? null).toBeNull()
          await expect(next).toBeDisabled()
        }
        const mixed = await read(
          `/api/v1/projects/${other.id}/devices/${device.id}/${entry.endpoint}?limit=20`
        )
        expect(mixed.status).toBe(404)
        expect(mixed.cache).toBe('no-store')
        expect(mixed.body.items).toBeUndefined()
      }
      await testInfo.attach('association-scope', {
        body: Buffer.from(
          JSON.stringify({
            directories: 6,
            paginated: ['commands', 'task-jobs'],
            rowsPerPaginatedDirectory: 21,
            appBinding: 'public-login-and-claim',
            configuration: 'published-then-paused',
            executionHistory: 'accepted-commands-only',
            crossProjectDenials: 6,
            crossDeviceCursorDenials: 2,
            hardwareSuccess: false,
            quota: 'owned-policy-CAS-1'
          })
        ),
        contentType: 'application/json'
      })
    } catch {
      // Credentials are in API fixture callbacks; never include an original browser call log.
      await testInfo.attach('association-first-failure', {
        body: Buffer.from(JSON.stringify({ checkpoint, api: requests.failure })),
        contentType: 'application/json'
      })
      primary = {
        error: new Error(`Populated association journey failed at checkpoint: ${checkpoint}`)
      }
    } finally {
      await owned.finish(primary)
    }
  }
)
