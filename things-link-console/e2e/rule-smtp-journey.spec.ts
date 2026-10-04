import { expect, test, type Page } from '@playwright/test'
import { readFileSync } from 'node:fs'
import { createHash } from 'node:crypto'
import { setTimeout as delay } from 'node:timers/promises'
import {
  login,
  enterProject,
  OWNER_EMAIL,
  OWNER_PASSWORD,
  MEMBER_EMAIL,
  MEMBER_PASSWORD
} from './helpers'
import { readBoundModelVersion } from './device-model-fixture'
import { currentValueMessage, publishCurrentValues } from './current-value-mqtt'
import { OwnedFixture } from './owned-fixture'
import { readRuleDeliveries } from './rule-notification-fixture'

test.use({ trace: 'off', video: 'off', screenshot: 'off' })

test(
  '消息规则：原始 MQTT TLS 经变换、真实 SMTP 重试、重放去重与暂停后业务屏障',
  { tag: '@simulator' },
  async ({ page, browser }, testInfo) => {
    test.skip(process.env.E2E_CONTROLLED_SMTP !== '1', '需要独立受控SMTP/TLS选场')
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
    const recipient = `rule-${suffix}@example.test`
    const subject = `Controlled rule ${suffix}`
    const body = `Rule notification ${suffix}`
    const receipts = (): Receipt[] =>
      JSON.parse(readFileSync(smtp.receiptFile, 'utf8')).filter(
        (value: Receipt) => value.targetSha256 === hash(recipient)
      )
    const call = (
      target: Page,
      module: 'project' | 'device' | 'rule-management' | 'rule-execution',
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
      module: 'project' | 'device' | 'rule-management' | 'rule-execution',
      method: string,
      ...args: unknown[]
    ) => call(page, module, method, ...args)
    const owned = new OwnedFixture()
    let primary: { error: unknown } | undefined
    let secret = ''
    await login(page, OWNER_EMAIL, OWNER_PASSWORD)
    try {
      const project = await api('project', 'fetchCreateProject', {
        name: `规则SMTP-${suffix}`,
        region: 'sh-1'
      })
      owned.own('rule test project', () => api('project', 'fetchDeleteProject', project.id))
      await page.reload()
      await enterProject(page, project.name)
      let published = false
      const type = await api('device', 'fetchCreateDeviceType', project.id, {
        typeKey: `rule_smtp_${suffix}`,
        name: `规则温度${suffix}`,
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
        deviceKey: `rule_smtp_${suffix}`,
        name: `规则设备${suffix}`
      })
      owned.own('rule test device', () => api('device', 'fetchDeleteDevice', project.id, device.id))
      const credential = await api('device', 'fetchGenerateCredential', project.id, device.id)
      secret = credential.plainSecret
      const credentialId = credential.id
      owned.own('rule test credential', () =>
        api('device', 'fetchRevokeCredential', project.id, device.id, credentialId)
      )
      const rule = await api('rule-management', 'saveRule', project.id, '', {
        name: `原消息规则${suffix}`,
        source: 'input => ({temperature: input.temperature + 0.5})',
        actions: [
          {
            nodeType: 'notification-action',
            config: { channel: 'EMAIL', recipient, subject, body }
          }
        ]
      })
      owned.own('message rule', async () => {
        const current = await api('rule-management', 'getRule', project.id, rule.id)
        await api('rule-management', 'deleteRule', project.id, rule.id, current.version)
      })
      const versions = await api('rule-management', 'ruleHistory', project.id, rule.id)
      expect(versions.items).toHaveLength(1)
      const versionId = versions.items[0].id
      let active = await api(
        'rule-management',
        'activateRule',
        project.id,
        rule.id,
        versionId,
        rule.version
      )
      expect(active.activeVersionId).toBe(versionId)
      expect(active.status).toBe('ACTIVE')
      const scope = { projectId: project.id, deviceId: device.id, ruleId: rule.id }
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
      const executions = () =>
        api('rule-execution', 'fetchRuleExecutions', project.id, { ruleId: rule.id, limit: 50 })
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
      await report(original)
      await verifyPersistedReport(original, 27)
      await expect
        .poll(async () => (await executions()).items, { timeout: 30_000 })
        .toEqual([
          expect.objectContaining({
            messageId: originalMessage.messageId,
            ruleId: rule.id,
            ruleVersionId: versionId,
            status: 'SUCCESS',
            attemptCount: 1
          })
        ])
      await expect
        .poll(async () => (await readRuleDeliveries(scope))[0]?.status, { timeout: 30_000 })
        .toBe('RETRY_SCHEDULED')
      const initial = await readRuleDeliveries(scope)
      expect(initial).toHaveLength(1)
      expect(initial[0]).toMatchObject({
        messageId: originalMessage.messageId,
        ruleVersionId: versionId,
        attemptCount: 1,
        lastErrorCode: 'SMTP_FAILURE'
      })
      expect(receipts().map((value) => value.accepted)).toEqual([false])
      await report(original) // Replay original UUID and bytes; never construct a fresh report here.
      await expect
        .poll(async () => (await readRuleDeliveries(scope))[0]?.status, {
          timeout: 100_000,
          intervals: [1000]
        })
        .toBe('DELIVERED')
      expect(await readRuleDeliveries(scope)).toEqual([
        expect.objectContaining({
          id: initial[0]!.id,
          messageId: originalMessage.messageId,
          ruleVersionId: versionId,
          status: 'DELIVERED',
          attemptCount: 2,
          nextAttemptAt: null,
          lastErrorCode: null
        })
      ])
      const received = receipts()
      expect(received.map((value) => value.accepted)).toEqual([false, true])
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
      await page.getByRole('tab', { name: '上行规则执行', exact: true }).click()
      const row = page.locator('.el-table__body tr').filter({ hasText: rule.name })
      await expect(row).toHaveCount(1)
      await expect(row).toContainText('成功')
      await row.getByRole('button', { name: '时间线', exact: true }).click()
      const timeline = page.getByRole('dialog', { name: rule.name })
      await expect(timeline).toContainText(originalMessage.messageId)
      await expect(timeline).toContainText('第 1 次尝试')
      await expect(timeline).not.toContainText('第 2 次尝试') // Delivery retry is a separate state machine.
      await timeline.locator('.el-drawer__close-btn').click()

      // Pause affects future planning, not the accepted notification above.
      active = await api('rule-management', 'getRule', project.id, rule.id)
      const paused = await api('rule-management', 'pauseRule', project.id, rule.id, active.version)
      expect(paused.status).toBe('PAUSED')
      const afterPause = currentValueMessage(JSON.stringify({ temperature: 29 }), modelVersion)
      expect(JSON.parse(afterPause).messageId).not.toBe(originalMessage.messageId)
      await report(afterPause)
      await verifyPersistedReport(afterPause, 29) // Positive downstream barrier; no transformation when paused.
      const observationStarted = Date.now()
      const until = observationStarted + 10_000
      let observations = 0
      while (true) {
        const observed = await executions()
        expect(observed.hasMore).toBe(false)
        expect(observed.items).toEqual([
          expect.objectContaining({
            messageId: originalMessage.messageId,
            ruleVersionId: versionId,
            status: 'SUCCESS',
            attemptCount: 1
          })
        ])
        expect(await readRuleDeliveries(scope)).toEqual([
          expect.objectContaining({
            id: initial[0]!.id,
            messageId: originalMessage.messageId,
            status: 'DELIVERED',
            attemptCount: 2
          })
        ])
        expect(receipts().map((value) => value.accepted)).toEqual([false, true])
        observations++
        if (Date.now() >= until) break
        await delay(1000)
      }
      const observationMillis = Date.now() - observationStarted

      const foreignContext = await browser.newContext({ baseURL: new URL(page.url()).origin })
      owned.own('foreign browser context', () => foreignContext.close())
      const foreign = await foreignContext.newPage()
      await login(foreign, MEMBER_EMAIL, MEMBER_PASSWORD)
      const foreignProject = await call(foreign, 'project', 'fetchCreateProject', {
        name: `规则隔离-${suffix}`,
        region: 'sh-1'
      })
      owned.own('foreign own project', () =>
        call(foreign, 'project', 'fetchDeleteProject', foreignProject.id)
      )
      await foreign.reload()
      await enterProject(foreign, foreignProject.name)
      expect(
        (await call(foreign, 'rule-execution', 'fetchRuleExecutions', foreignProject.id)).items
      ).toEqual([])
      const refused = await foreign.evaluate(async (projectId) => {
        const path = '/src/store/modules/user.ts'
        const { useUserStore } = await import(path)
        const response = await fetch(`/api/v1/projects/${projectId}/rule-executions`, {
          headers: { Authorization: `Bearer ${useUserStore().accessToken}` }
        })
        const body = await response.json()
        return { status: response.status, code: body.code, hasItems: Object.hasOwn(body, 'items') }
      }, project.id)
      expect(refused).toEqual({ status: 404, code: 10004, hasItems: false })
      await testInfo.attach('rule-notification-facts', {
        contentType: 'application/json',
        body: JSON.stringify(
          {
            projectId: project.id,
            deviceId: device.id,
            ruleId: rule.id,
            ruleVersionId: versionId,
            sourceMessageId: originalMessage.messageId,
            deliveryId: initial[0]!.id,
            ruleAttemptCount: 1,
            deliveryAttemptCount: 2,
            deliveryStatus: 'DELIVERED',
            smtpReceipts: received,
            pausedMessageId: JSON.parse(afterPause).messageId,
            pausedObservationMillis: observationMillis,
            observations,
            crossProjectRead: refused,
            limits: [
              'local controlled SMTP only',
              'no physical actuation',
              'not whole D-058 qualification'
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
