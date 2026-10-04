import { expect, test, type Page } from '@playwright/test'
import { readFileSync } from 'node:fs'
import { createHash } from 'node:crypto'
import { setTimeout as delay } from 'node:timers/promises'
import { login, enterProject, OWNER_EMAIL, OWNER_PASSWORD } from './helpers'
import { readBoundModelVersion } from './device-model-fixture'
import { currentValueMessage, publishCurrentValues } from './current-value-mqtt'
import { OwnedFixture } from './owned-fixture'
import {
  prepareAutomationQuota,
  installAutomationQuota,
  enableOneAutomation,
  restoreAutomationQuota,
  readAutomationFacts
} from './automation-quota-fixture'

test.use({ trace: 'off', video: 'off', screenshot: 'off' })

test(
  '属性自动化：零预算发布拒绝、真实 MQTT 与 SMTP、唯一预留和暂停屏障',
  { tag: '@simulator' },
  async ({ page }, testInfo) => {
    test.skip(
      process.env.E2E_CONTROLLED_SMTP !== '1' ||
        process.env.E2E_AUTOMATION_FIXTURE !== 'property-smtp',
      '需要独立受控SMTP/TLS选场'
    )
    test.setTimeout(300_000)
    const directory = process.env.E2E_TEST_TLS_DIRECTORY
    if (!directory) throw new Error('缺少受控TLS材料')
    const tls = JSON.parse(readFileSync(`${directory}/state.json`, 'utf8'))
    const smtp = JSON.parse(readFileSync(`${directory}/smtp-config.json`, 'utf8'))
    if (tls.status !== 'READY') throw new Error('MQTT TLS夹具未就绪')
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
    const recipient = `automation-${suffix}@example.test`
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
    let secret = ''
    await login(page, OWNER_EMAIL, OWNER_PASSWORD)
    try {
      const project = await api('project', 'fetchCreateProject', {
        name: `属性自动化SMTP-${suffix}`,
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
        typeKey: `auto_smtp_${suffix}`,
        name: `属性自动化温度${suffix}`,
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
        deviceKey: `auto_smtp_${suffix}`,
        name: `属性自动化设备${suffix}`
      })
      owned.own('rule test device', () => api('device', 'fetchDeleteDevice', project.id, device.id))
      const credential = await api('device', 'fetchGenerateCredential', project.id, device.id)
      secret = credential.plainSecret
      const credentialId = credential.id
      owned.own('rule test credential', () =>
        api('device', 'fetchRevokeCredential', project.id, device.id, credentialId)
      )
      const automation = await api('automation-management', 'saveAutomation', project.id, '', {
        name: `属性通知${suffix}`,
        triggerType: 'PROPERTY_REPORTED',
        triggerConfig: { deviceId: device.id },
        conditions: [],
        actions: [
          {
            nodeType: 'notification-action',
            config: { channel: 'EMAIL', recipient, subject, body }
          }
        ]
      })
      owned.own('property automation', async () => {
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
      const refused = await page.evaluate(
        async ({ projectId, automationId, versionId, expectedVersion }) => {
          const path = '/src/store/modules/user.ts'
          const { useUserStore } = await import(path)
          const response = await fetch(
            `/api/v1/projects/${projectId}/automations/${automationId}/versions/${versionId}/activate?expectedVersion=${expectedVersion}`,
            {
              method: 'POST',
              headers: {
                Authorization: `Bearer ${useUserStore().accessToken}`,
                'Content-Type': 'application/json'
              },
              body: '{}'
            }
          )
          const value = await response.json()
          return { status: response.status, code: value.code }
        },
        {
          projectId: project.id,
          automationId: automation.id,
          versionId,
          expectedVersion: automation.version
        }
      )
      expect(refused).toEqual({ status: 403, code: 40055 })
      expect(await readAutomationFacts(project.id, automation.id)).toEqual([])
      expect(receipts()).toEqual([])
      const quotaOperation = await enableOneAutomation(quota)
      expect(quotaOperation).toMatchObject({
        policy_id: quota.policyId,
        result_version: 2,
        daily_limit: 1
      })
      const activated = await api(
        'automation-management',
        'activateAutomation',
        project.id,
        automation.id,
        versionId,
        automation.version
      )
      expect(activated).toMatchObject({ status: 'ACTIVE', activeVersionId: versionId })
      const modelVersion = await readBoundModelVersion(project.id, device.id)
      const original = currentValueMessage(JSON.stringify({ temperature: 26.5 }), modelVersion)
      const originalMessage = JSON.parse(original)
      const report = (payload: string) =>
        publishCurrentValues({
          projectKey: project.projectKey,
          deviceKey: device.deviceKey,
          secret,
          payload,
          port: tls.port,
          tls: { ca: readFileSync(tls.ca), servername: tls.host }
        })
      const verifyPersistedReport = async (payload: string, value: number) => {
        const message = JSON.parse(payload)
        const from = new Date(Date.parse(message.occurredAt) - 1000).toISOString()
        const to = new Date(Date.parse(message.occurredAt) + 1000).toISOString()
        await expect
          .poll(
            async () => {
              const history = await api('device', 'fetchPropertyHistory', project.id, device.id, {
                propertyKey: 'temperature',
                from,
                to,
                granularity: 'RAW',
                aggregation: 'AVG'
              })
              expect(history.actualGranularity).toBe('RAW')
              return history.points.filter(
                (point: { ts: string }) => Date.parse(point.ts) === Date.parse(message.occurredAt)
              )
            },
            { timeout: 30_000 }
          )
          .toEqual([expect.objectContaining({ value, sampleCount: 1, modelVersion })])
        const logs = await api(
          'device',
          'fetchDeviceMessages',
          project.id,
          device.id,
          undefined,
          50
        )
        expect(logs.hasMore).toBe(false)
        expect(
          logs.items.filter((item: { messageId: string }) => item.messageId === message.messageId)
        ).toEqual([
          expect.objectContaining({
            deviceId: device.id,
            protocol: 'MQTT',
            direction: 'UP',
            errorCode: null
          })
        ])
      }
      const facts = () => readAutomationFacts(project.id, automation.id)
      const detail = (executionId: string) =>
        api('automation-management', 'getAutomationExecution', project.id, executionId)
      await report(original)
      await verifyPersistedReport(original, 26.5)
      await expect.poll(facts, { timeout: 30_000 }).toEqual([
        expect.objectContaining({
          sourceEventId: originalMessage.messageId,
          status: 'DISPATCHED',
          attemptCount: 1,
          reservations: 1
        })
      ])
      const accepted = (await facts())[0]!
      await expect
        .poll(async () => (await detail(accepted.id)).notifications, { timeout: 30_000 })
        .toEqual([
          expect.objectContaining({
            channel: 'EMAIL',
            status: 'RETRY_SCHEDULED',
            attemptCount: 1,
            lastErrorCode: 'SMTP_FAILURE'
          })
        ])
      expect(receipts().map((r) => r.accepted)).toEqual([false])
      await report(original)
      await expect
        .poll(async () => (await detail(accepted.id)).notifications, {
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
      expect(await facts()).toEqual([accepted])
      const delivered = await detail(accepted.id)
      expect(delivered.summary).toMatchObject({
        id: accepted.id,
        automationVersionId: versionId,
        triggerType: 'PROPERTY_REPORTED',
        status: 'DISPATCHED',
        attemptCount: 1
      })
      expect(delivered.attempts).toHaveLength(1)
      expect(delivered.deviceActions).toEqual([])
      const received = receipts()
      expect(received.map((r) => r.accepted)).toEqual([false, true])
      expect(
        Date.parse(received[1]!.receivedAt) - Date.parse(received[0]!.receivedAt)
      ).toBeGreaterThanOrEqual(60_000)
      for (const receipt of received) {
        expect(receipt.authenticated).toBe(true)
        expect(['TLSv1.2', 'TLSv1.3']).toContain(receipt.tls)
        expect(receipt.subjectSha256).toBe(hash(subject))
        expect(receipt.bodySha256).toBe(hash(body))
      }
      await page.goto('/#/rule/executions')
      await page.getByRole('tab', { name: '自动化执行', exact: true }).click()
      const row = page.locator('.el-table__body tr').filter({ hasText: automation.id })
      await expect(row).toHaveCount(1)
      await expect(row).toContainText('DISPATCHED')
      await row.getByRole('button', { name: '运行详情', exact: true }).click()
      const dialog = page.getByRole('dialog', { name: '自动化运行详情' })
      await expect(dialog).toBeVisible()
      const displayed = JSON.parse(
        await dialog.getByTestId('automation-execution-detail').innerText()
      )
      expect(displayed.summary.id).toBe(accepted.id)
      expect(displayed.notifications).toEqual(delivered.notifications)
      await dialog.locator('.el-dialog__headerbtn').click()
      // A new source UUID consumes no second reservation: daily hard limit remains one.
      const excess = currentValueMessage(JSON.stringify({ temperature: 28 }), modelVersion)
      await report(excess)
      await verifyPersistedReport(excess, 28)
      await expect.poll(facts, { timeout: 30_000 }).toEqual([
        accepted,
        expect.objectContaining({
          sourceEventId: JSON.parse(excess).messageId,
          status: 'REJECTED',
          reasonCode: 'QUOTA',
          attemptCount: 0,
          reservations: 0
        })
      ])
      const rejected = (await facts())[1]!
      expect((await detail(rejected.id)).notifications).toEqual([])
      await report(excess)
      const current = await api('automation-management', 'getAutomation', project.id, automation.id)
      expect(
        await api(
          'automation-management',
          'pauseAutomation',
          project.id,
          automation.id,
          current.version
        )
      ).toMatchObject({ status: 'PAUSED' })
      const afterPause = currentValueMessage(JSON.stringify({ temperature: 29 }), modelVersion)
      await report(afterPause)
      await verifyPersistedReport(afterPause, 29)
      const started = Date.now()
      while (true) {
        expect(await facts()).toEqual([accepted, rejected])
        expect(receipts().map((r) => r.accepted)).toEqual([false, true])
        if (Date.now() - started >= 10_000) break
        await delay(1000)
      }
      await testInfo.attach('property-automation-notification-facts', {
        contentType: 'application/json',
        body: JSON.stringify(
          {
            projectId: project.id,
            deviceId: device.id,
            automationId: automation.id,
            versionId,
            zeroBudget: refused,
            quotaOperation,
            executionFacts: await facts(),
            smtpReceipts: received,
            pausedMessageId: JSON.parse(afterPause).messageId,
            pausedObservationMillis: Date.now() - started,
            limits: [
              'controlled local SMTP only',
              'no device PROPERTY_SET or hardware response',
              'no new revoked-author wire journey',
              'not whole R4-3c or D-058 qualification'
            ]
          },
          null,
          2
        )
      })
    } catch (error) {
      primary = { error }
    } finally {
      secret = ''
      await owned.finish(primary)
    }
  }
)
