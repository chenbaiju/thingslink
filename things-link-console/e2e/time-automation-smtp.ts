import { expect, test, type Page } from '@playwright/test'
import { readFileSync } from 'node:fs'
import { createHash } from 'node:crypto'
import { setTimeout as delay } from 'node:timers/promises'
import { login, enterProject, OWNER_EMAIL, OWNER_PASSWORD } from './helpers'
import { OwnedFixture } from './owned-fixture'
import {
  prepareAutomationQuota,
  installAutomationQuota,
  enableOneAutomation,
  restoreAutomationQuota,
  readAutomationDatabaseTime,
  readTimeAutomationFacts
} from './automation-quota-fixture'

import { timeTriggerConfig, type TimeTrigger } from './time-automation-fixture'

export function registerTimeSmtpJourney(triggerType: TimeTrigger) {
  test.use({ trace: 'off', video: 'off', screenshot: 'off' })
  test(
    `时间自动化 ${triggerType}：实际调度、SMTP重试、唯一发生点与持久围栏`,
    { tag: '@simulator' },
    async ({ page }, testInfo) => {
      test.skip(
        process.env.E2E_CONTROLLED_SMTP !== '1' ||
          process.env.E2E_AUTOMATION_FIXTURE !== 'time-smtp',
        '需要独立受控SMTP/TLS选场'
      )
      test.setTimeout(300_000)
      const directory = process.env.E2E_TEST_TLS_DIRECTORY
      if (!directory) throw new Error('缺少受控TLS材料')
      const smtp = JSON.parse(readFileSync(`${directory}/smtp-config.json`, 'utf8'))
      type Receipt = {
        targetSha256: string
        subjectSha256: string
        bodySha256: string
        accepted: boolean
        receivedAt: string
        authenticated: boolean
        tls: string
      }
      const hash = (value: string) => createHash('sha256').update(value).digest('hex')
      const suffix = Date.now()
      const recipient = `time-${triggerType.toLowerCase()}-${suffix}@example.test`
      const subject = `Controlled automation ${suffix}`
      const body = `Automation notification ${suffix}`
      const receipts = (): Receipt[] =>
        JSON.parse(readFileSync(smtp.receiptFile, 'utf8')).filter(
          (value: Receipt) => value.targetSha256 === hash(recipient)
        )
      const call = (
        target: Page,
        module: 'project' | 'device' | 'automation-management',
        method: string,
        ...args: unknown[]
      ) =>
        target.evaluate(
          async ({ module, method, args }) => {
            const path = `/src/api/${module}.ts`
            return (await import(path))[method](...args)
          },
          { module, method, args }
        )
      const api = (
        module: 'project' | 'device' | 'automation-management',
        method: string,
        ...args: unknown[]
      ) => call(page, module, method, ...args)
      const owned = new OwnedFixture()
      let primary: { error: unknown } | undefined
      await login(page, OWNER_EMAIL, OWNER_PASSWORD)
      try {
        const project = await api('project', 'fetchCreateProject', {
          name: `时间自动化SMTP-${suffix}`,
          region: 'sh-1'
        })
        owned.own('rule test project', () => api('project', 'fetchDeleteProject', project.id))
        await page.reload()
        await enterProject(page, project.name)
        const quota = await prepareAutomationQuota(project.id)
        owned.own('isolated automation policy and operator', () => restoreAutomationQuota(quota))
        await installAutomationQuota(quota)
        let published = false
        const type = await api('device', 'fetchCreateDeviceType', project.id, {
          typeKey: `time_smtp_${suffix}`,
          name: `时间自动化温度${suffix}`,
          deviceKind: 'DIRECT',
          payloadProtocol: 'STANDARD',
          networkType: 'WIFI'
        })
        owned.own('draft device type', async () => {
          if (!published) await api('device', 'fetchDeleteDeviceType', project.id, type.id)
        })
        await api('device', 'fetchCreateDevicePropertyDefinition', project.id, type.id, {
          propertyKey: 'temperature',
          name: '温度',
          dataType: 'NUMBER',
          unit: '℃',
          decimalPlaces: 2,
          accessType: 'REPORT',
          sortOrder: 0
        })
        await api('device', 'fetchPublishDeviceType', project.id, type.id)
        published = true
        const device = await api('device', 'fetchCreateDevice', project.id, {
          deviceTypeId: type.id,
          deviceKey: `time_smtp_${suffix}`,
          name: `时间自动化设备${suffix}`
        })
        owned.own('rule test device', () =>
          api('device', 'fetchDeleteDevice', project.id, device.id)
        )
        const quotaOperation = await enableOneAutomation(quota)
        expect(quotaOperation).toMatchObject({
          policy_id: quota.policyId,
          result_version: 2,
          daily_limit: 1
        })
        const databaseNow = await readAutomationDatabaseTime()
        const triggerConfig = timeTriggerConfig(triggerType, device.id, databaseNow)
        const automation = await api('automation-management', 'saveAutomation', project.id, '', {
          name: `时间通知${triggerType}-${suffix}`,
          triggerType,
          triggerConfig,
          conditions: [],
          actions: [
            {
              nodeType: 'notification-action',
              config: { channel: 'EMAIL', recipient, subject, body }
            }
          ]
        })
        owned.own('time automation', async () => {
          const current = await api(
            'automation-management',
            'getAutomation',
            project.id,
            automation.id
          )
          await api(
            'automation-management',
            'deleteAutomation',
            project.id,
            automation.id,
            current.version
          )
        })
        const versions = await api(
          'automation-management',
          'automationHistory',
          project.id,
          automation.id
        )
        expect(versions.items).toHaveLength(1)
        const versionId = versions.items[0].id
        const scope = {
          projectId: project.id,
          automationId: automation.id,
          versionId,
          type: triggerType
        }
        const facts = () => readTimeAutomationFacts(scope)
        const detail = (id: string) =>
          api('automation-management', 'getAutomationExecution', project.id, id)
        const active = await api(
          'automation-management',
          'activateAutomation',
          project.id,
          automation.id,
          versionId,
          automation.version
        )
        expect(active).toMatchObject({
          status: 'ACTIVE',
          activeVersionId: versionId,
          activeTriggerType: triggerType
        })
        const fireAt = active.nextFireAt
        expect(Number.isFinite(Date.parse(fireAt))).toBe(true)
        expect(Date.parse(fireAt)).toBeGreaterThan(Date.parse(databaseNow))
        if (triggerType === 'ONE_SHOT')
          expect(Date.parse(fireAt)).toBe(
            Date.parse('runAt' in triggerConfig ? triggerConfig.runAt! : '')
          )
        else {
          expect(active.scheduleTimezone).toBe('UTC')
          expect(new Date(fireAt).getUTCSeconds().toString()).toBe(
            ('cronExpression' in triggerConfig ? triggerConfig.cronExpression! : '').split(' ')[0]
          )
        }
        expect((await facts()).executions).toEqual([])
        expect(receipts()).toEqual([])
        // Real production scheduler, database clock and durable admission. Never advance SQL timestamps.
        await expect
          .poll(async () => (await facts()).executions, { timeout: 110_000, intervals: [500] })
          .toEqual([
            expect.objectContaining({
              status: 'DISPATCHED',
              reasonCode: null,
              attemptCount: 1,
              reservations: 1,
              triggerType,
              sourceEventId: null
            })
          ])
        const fired = await facts(),
          execution = fired.executions[0]!
        expect(Date.parse(execution.scheduledFireAt)).toBe(Date.parse(fireAt))
        expect(Date.parse(execution.occurredAt)).toBe(Date.parse(fireAt))
        expect(fired.state?.consumed).toBe(triggerType === 'ONE_SHOT')
        let nextOriginalFire: string | null = null
        let rearmRefusal: { status: number; code: number } | null = null
        if (triggerType === 'CRON') {
          nextOriginalFire = fired.nextFireAt
          expect(nextOriginalFire).not.toBeNull()
          expect(Date.parse(nextOriginalFire!) - Date.parse(fireAt)).toBeGreaterThanOrEqual(60_000)
          // Stop only future scheduling; an already DISPATCHED notification remains independently deliverable.
          const current = await api(
            'automation-management',
            'getAutomation',
            project.id,
            automation.id
          )
          const paused = await api(
            'automation-management',
            'pauseAutomation',
            project.id,
            automation.id,
            current.version
          )
          expect(paused).toMatchObject({ status: 'PAUSED', nextFireAt: null })
        } else {
          expect(fired.nextFireAt).toBeNull()
          const current = await api(
            'automation-management',
            'getAutomation',
            project.id,
            automation.id
          )
          rearmRefusal = await page.evaluate(
            async ({ projectId, id, versionId, version }) => {
              const path = '/src/store/modules/user.ts'
              const { useUserStore } = await import(path)
              const response = await fetch(
                `/api/v1/projects/${projectId}/automations/${id}/versions/${versionId}/activate?expectedVersion=${version}`,
                {
                  method: 'POST',
                  headers: {
                    Authorization: `Bearer ${useUserStore().accessToken}`,
                    'Content-Type': 'application/json'
                  },
                  body: '{}'
                }
              )
              return { status: response.status, code: (await response.json()).code }
            },
            { projectId: project.id, id: automation.id, versionId, version: current.version }
          )
          expect(rearmRefusal).toEqual({ status: 409, code: 40051 })
        }
        await expect
          .poll(async () => (await detail(execution.id)).notifications, { timeout: 30_000 })
          .toEqual([
            expect.objectContaining({
              channel: 'EMAIL',
              status: 'RETRY_SCHEDULED',
              attemptCount: 1,
              lastErrorCode: 'SMTP_FAILURE'
            })
          ])
        expect(receipts().map((r) => r.accepted)).toEqual([false])
        await expect
          .poll(async () => (await detail(execution.id)).notifications, {
            timeout: 100_000,
            intervals: [1000]
          })
          .toEqual([
            expect.objectContaining({
              channel: 'EMAIL',
              status: 'DELIVERED',
              attemptCount: 2,
              lastErrorCode: null
            })
          ])
        const received = receipts(),
          delivered = await detail(execution.id)
        expect(received.map((r) => r.accepted)).toEqual([false, true])
        expect(
          Date.parse(received[1]!.receivedAt) - Date.parse(received[0]!.receivedAt)
        ).toBeGreaterThanOrEqual(60_000)
        for (const r of received) {
          expect(r.authenticated).toBe(true)
          expect(['TLSv1.2', 'TLSv1.3']).toContain(r.tls)
          expect(r.subjectSha256).toBe(hash(subject))
          expect(r.bodySha256).toBe(hash(body))
        }
        expect(delivered.summary).toMatchObject({
          id: execution.id,
          status: 'DISPATCHED',
          triggerType,
          automationVersionId: versionId,
          attemptCount: 1
        })
        expect(delivered.attempts).toHaveLength(1)
        expect(delivered.deviceActions).toEqual([])
        await page.goto('/#/rule/executions')
        await page.getByRole('tab', { name: '自动化执行', exact: true }).click()
        const row = page.locator('.el-table__body tr').filter({ hasText: automation.id })
        await expect(row).toHaveCount(1)
        await expect(row).toContainText(triggerType)
        await row.getByRole('button', { name: '运行详情', exact: true }).click()
        const dialog = page.getByRole('dialog', { name: '自动化运行详情' })
        await expect(dialog).toBeVisible()
        const shown = JSON.parse(
          await dialog.getByTestId('automation-execution-detail').innerText()
        )
        expect(shown.summary.id).toBe(execution.id)
        expect(shown.notifications).toEqual(delivered.notifications)
        const observedStart = Date.now()
        let final = await facts()
        const observationEnd = Math.max(
          Date.parse(final.databaseNow) + 10_000,
          nextOriginalFire ? Date.parse(nextOriginalFire) + 5000 : 0
        )
        while (true) {
          final = await facts()
          expect(final.executions).toEqual([execution])
          expect(final.nextFireAt).toBeNull()
          expect(final.state).toEqual(fired.state)
          expect(receipts().map((r) => r.accepted)).toEqual([false, true])
          if (Date.parse(final.databaseNow) >= observationEnd) break
          if (Date.now() - observedStart > 90_000)
            throw new Error('Database time observation deadline exceeded')
          await delay(1000)
        }
        await testInfo.attach(`time-${triggerType.toLowerCase()}-facts`, {
          contentType: 'application/json',
          body: JSON.stringify(
            {
              projectId: project.id,
              deviceId: device.id,
              automationId: automation.id,
              versionId,
              triggerType,
              fireAt,
              nextOriginalFire,
              rearmRefusal,
              quotaOperation,
              finalFacts: final,
              smtpReceipts: received,
              observationMillis: Date.now() - observedStart,
              limits: [
                'controlled local SMTP only',
                'no device action or hardware',
                'no restart/competition injected in this journey',
                'not whole R4-3d or D-058 qualification'
              ]
            },
            null,
            2
          )
        })
      } catch (error) {
        primary = { error }
      } finally {
        await owned.finish(primary)
      }
    }
  )
}
