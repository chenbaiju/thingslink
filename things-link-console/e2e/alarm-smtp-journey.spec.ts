import { expect, test } from '@playwright/test'
import { readFileSync } from 'node:fs'
import { createHash } from 'node:crypto'
import { login, enterProject, OWNER_EMAIL, OWNER_PASSWORD } from './helpers'
import { readBoundModelVersion } from './device-model-fixture'
import { currentValueMessage, publishCurrentValues } from './current-value-mqtt'
import { OwnedFixture } from './owned-fixture'

// 设备凭据只用于内存中的真实MQTT连接；失败产物不能保存创建凭据API正文。
test.use({ trace: 'off', video: 'off', screenshot: 'off' })

test(
  '受控 SMTP：真实 MQTT TLS 告警经451与生产退避收件，确认和恢复不重复通知意图',
  { tag: '@simulator' },
  async ({ page }) => {
    test.skip(process.env.E2E_CONTROLLED_SMTP !== '1', '独立受控SMTP选场，默认矩阵不改邮件负向环境')
    test.setTimeout(240_000)
    const directory = process.env.E2E_TEST_TLS_DIRECTORY
    if (!directory) throw new Error('缺少受控TLS材料')
    const tls = JSON.parse(readFileSync(`${directory}/state.json`, 'utf8'))
    const smtp = JSON.parse(readFileSync(`${directory}/smtp-config.json`, 'utf8'))
    if (tls.status !== 'READY') throw new Error('MQTT TLS夹具未就绪')
    type Receipt = {
      targetSha256: string
      subjectSha256: string
      bodySha256: string
      attempt: number
      accepted: boolean
      receivedAt: string
      authenticated: boolean
      tls: string
    }
    const hash = (value: string) => createHash('sha256').update(value).digest('hex')
    const receipts = (target: string): Receipt[] =>
      JSON.parse(readFileSync(smtp.receiptFile, 'utf8')).filter(
        (value: Receipt) => value.targetSha256 === hash(target)
      )
    await login(page, OWNER_EMAIL, OWNER_PASSWORD)
    const owned = new OwnedFixture()
    let primary: { error: unknown } | undefined
    let secret = ''
    const callApi = (module: 'device' | 'alarm' | 'project', method: string, ...args: unknown[]) =>
      page.evaluate(
        async ({ module, method, args }) => {
          const source = `/src/api/${module}.ts`
          return (await import(source))[method](...args)
        },
        { module, method, args }
      )
    try {
      const suffix = Date.now()
      const project = await callApi('project', 'fetchCreateProject', {
        name: `受控SMTP-${suffix}`,
        region: 'sh-1'
      })
      const projectId = project.id
      owned.own('isolated project', () => callApi('project', 'fetchDeleteProject', projectId))
      await page.reload()
      await enterProject(page, project.name)
      let typePublished = false
      const type = await callApi('device', 'fetchCreateDeviceType', projectId, {
        typeKey: `alarm_notification_${suffix}`,
        name: `通知旅程模型${suffix}`,
        deviceKind: 'DIRECT',
        payloadProtocol: 'STANDARD',
        networkType: 'WIFI'
      })
      owned.own('draft device type', async () => {
        // Published versions are immutable. The owned project is soft-deleted last.
        if (!typePublished) await callApi('device', 'fetchDeleteDeviceType', projectId, type.id)
      })
      await callApi('device', 'fetchCreateDevicePropertyDefinition', projectId, type.id, {
        propertyKey: 'temperature',
        name: '温度',
        dataType: 'NUMBER',
        unit: '℃',
        decimalPlaces: 2,
        accessType: 'REPORT',
        sortOrder: 0
      })
      await callApi('device', 'fetchPublishDeviceType', projectId, type.id)
      typePublished = true
      const created = await callApi('device', 'fetchCreateDevice', projectId, {
        deviceTypeId: type.id,
        deviceKey: `alarm_notification_${suffix}`,
        name: `通知旅程设备${suffix}`
      })
      owned.own('device', () => callApi('device', 'fetchDeleteDevice', projectId, created.id))
      const credential = await callApi('device', 'fetchGenerateCredential', projectId, created.id)
      secret = credential.plainSecret
      const credentialId = credential.id
      // Register immediately after each successful API response, before starting the next creation.
      owned.own('credential', () =>
        callApi('device', 'fetchRevokeCredential', projectId, created.id, credentialId)
      )
      const rule = await callApi('alarm', 'fetchCreateAlarmRule', projectId, {
        name: `通知旅程规则${suffix}`,
        alarmType: `NOTIFY_E2E_${suffix}`,
        deviceId: created.id,
        propertyKey: 'temperature',
        triggerOperator: 'GT',
        triggerThreshold: 30,
        triggerDurationSeconds: 0,
        clearOperator: 'LT',
        clearThreshold: 25,
        clearDurationSeconds: 0,
        severity: 'MAJOR',
        enabled: true
      })
      owned.own('alarm rule', () =>
        callApi('alarm', 'fetchDeleteAlarmRule', projectId, rule.id, rule.version)
      )
      const group = await callApi('alarm', 'fetchCreateAlarmNotificationGroup', projectId, {
        name: `通知旅程组${suffix}`,
        enabled: true
      })
      owned.own('notification group', () =>
        callApi('alarm', 'fetchDeleteAlarmNotificationGroup', projectId, group.id, group.version)
      )
      const recipient = await callApi(
        'alarm',
        'fetchCreateAlarmNotificationRecipient',
        projectId,
        group.id,
        {
          channel: 'EMAIL',
          target: `alarm-${suffix}@example.test`,
          enabled: true
        }
      )
      owned.own('notification recipient', () =>
        callApi(
          'alarm',
          'fetchDeleteAlarmNotificationRecipient',
          projectId,
          recipient.id,
          recipient.version
        )
      )
      const template = await callApi('alarm', 'fetchCreateAlarmNotificationTemplate', projectId, {
        name: `通知旅程模板${suffix}`,
        channel: 'EMAIL',
        subjectTemplate: 'Controlled alarm ${alarm.type}',
        bodyTemplate: 'Incident ${alarm.instanceId} alarm ${alarm.type}',
        enabled: true
      })
      owned.own('notification template', () =>
        callApi(
          'alarm',
          'fetchDeleteAlarmNotificationTemplate',
          projectId,
          template.id,
          template.version
        )
      )
      const binding = await callApi(
        'alarm',
        'fetchCreateAlarmNotificationBinding',
        projectId,
        rule.id,
        {
          groupId: group.id,
          templateId: template.id,
          channel: 'EMAIL',
          enabled: true
        }
      )
      owned.own('notification binding', () =>
        callApi(
          'alarm',
          'fetchDeleteAlarmNotificationBinding',
          projectId,
          binding.id,
          binding.version
        )
      )
      const fixture = {
        projectId,
        target: `alarm-${suffix}@example.test`,
        deviceId: created.id,
        deviceKey: created.deviceKey,
        alarmType: rule.alarmType,
        ruleId: rule.id
      }
      const projectKey = project.projectKey
      if (!projectKey) throw new Error('缺少真实上报项目标识')
      const modelVersion = await readBoundModelVersion(fixture.projectId, fixture.deviceId)
      const activationPayload = currentValueMessage(
        JSON.stringify({ temperature: 31 }),
        modelVersion
      )
      const report = (temperature: number) =>
        publishCurrentValues({
          projectKey,
          deviceKey: fixture.deviceKey,
          secret,
          port: tls.port,
          tls: { ca: readFileSync(tls.ca), servername: tls.host },
          payload:
            temperature === 31
              ? activationPayload
              : currentValueMessage(JSON.stringify({ temperature }), modelVersion)
        })
      await report(31)
      const readFact = () =>
        page.evaluate(
          async ({ projectId, ruleId }) => {
            const alarmPath = '/src/api/alarm.ts',
              httpPath = '/src/utils/http/index.ts'
            const alarm = await import(alarmPath)
            const request = (await import(httpPath)).default
            const instances = await alarm.fetchAlarmInstances(projectId, undefined, 50)
            const instance = instances.items?.find(
              (item: { ruleId?: string }) => item.ruleId === ruleId
            )
            if (!instance?.id) return null
            const events = await alarm.fetchAlarmEvents(projectId, instance.id)
            const deliveries = await request.get({
              url: `/api/v1/projects/${projectId}/alarm-notification-deliveries`,
              params: { instanceId: instance.id, limit: 20 }
            })
            return { instance, events: events.items ?? [], deliveries: deliveries.items ?? [] }
          },
          { projectId: fixture.projectId, ruleId: fixture.ruleId }
        )
      await expect
        .poll(async () => (await readFact())?.deliveries.length ?? 0, { timeout: 30_000 })
        .toBe(1)
      // 受控接收端第一次DATA明确451拒收，生产状态机使用原1分钟+抖动退避。
      await expect
        .poll(async () => (await readFact())?.deliveries[0]?.status, { timeout: 30_000 })
        .toBe('RETRY_SCHEDULED')
      const fact = await readFact()
      expect(fact?.deliveries[0]?.attemptCount).toBe(1)
      expect(fact?.instance.conditionState).toBe('ACTIVE')
      expect(fact?.instance.ackState).toBe('UNACKNOWLEDGED')
      const activation = fact!.events.find(
        (event: { eventType?: string }) => event.eventType === 'ACTIVATED'
      )!
      expect(activation.id).toBeTruthy()
      expect(activation.sourceMessageId).toBe(JSON.parse(activationPayload).messageId)
      expect(fact!.deliveries[0]!.alarmEventId).toBe(activation.id)
      expect(receipts(fixture.target)).toHaveLength(1)
      expect(receipts(fixture.target)[0]?.accepted).toBe(false)
      // 精确重放同一原始 messageId，不生成新的上行事实或通知意图。
      await report(31)
      await expect
        .poll(async () => (await readFact())?.deliveries[0]?.status, {
          timeout: 100_000,
          intervals: [1000]
        })
        .toBe('SUCCEEDED')
      const delivered = await readFact()
      expect(delivered?.deliveries).toHaveLength(1)
      expect(delivered?.deliveries[0]?.id).toBe(fact!.deliveries[0]!.id)
      expect(delivered?.deliveries[0]?.attemptCount).toBe(2)
      const received = receipts(fixture.target)
      expect(received).toHaveLength(2)
      expect(received.map((value) => value.accepted)).toEqual([false, true])
      expect(
        Date.parse(received[1]!.receivedAt) - Date.parse(received[0]!.receivedAt)
      ).toBeGreaterThanOrEqual(60_000)
      for (const value of received) {
        expect(value.authenticated).toBe(true)
        expect(['TLSv1.2', 'TLSv1.3']).toContain(value.tls)
        expect(value.subjectSha256).toBe(hash(`Controlled alarm ${fixture.alarmType}`))
        expect(value.bodySha256).toBe(
          hash(`Incident ${fact!.instance.id} alarm ${fixture.alarmType}`)
        )
      }

      await page.getByTestId('alarm-inbox-toggle').click()
      const inbox = page.getByTestId('alarm-inbox-panel')
      await expect(inbox.locator(`[data-event-id="${activation.id}"]`)).toBeVisible()
      await inbox
        .locator(`[data-event-id="${activation.id}"]`)
        .getByRole('button', { name: '标记已读' })
        .click()
      await expect(inbox.locator(`[data-event-id="${activation.id}"]`)).toContainText('已读')
      expect((await readFact())?.instance.ackState).toBe('UNACKNOWLEDGED')
      await inbox.getByRole('button', { name: '关闭告警通知' }).click()

      await page.goto('/#/alarm/history')
      const row = page.locator('.el-table__body tr').filter({ hasText: fixture.alarmType })
      await expect(row).toContainText('活动')
      await expect(row).toContainText('未确认')
      await row.getByRole('button', { name: '确认', exact: true }).click()
      await expect(row).toContainText('已确认')
      await report(24)
      await expect
        .poll(async () => (await readFact())?.instance.conditionState, { timeout: 30_000 })
        .toBe('CLEARED')
      await page.reload()
      await expect(row).toContainText('已清除')
      await expect(row).toContainText('已确认')
      await row.getByRole('button', { name: '事件', exact: true }).click()
      const timeline = page.getByRole('dialog', { name: '告警事件时间线' })
      await expect(timeline).toContainText('告警激活')
      await expect(timeline).toContainText('人工确认')
      await expect(timeline).toContainText('告警清除')
      const finalFact = await readFact()
      expect(
        finalFact?.events.filter((event: { eventType?: string }) => event.eventType === 'ACTIVATED')
      ).toHaveLength(1)
      expect(
        finalFact?.events.filter(
          (event: { eventType?: string }) => event.eventType === 'ACKNOWLEDGED'
        )
      ).toHaveLength(1)
      expect(
        finalFact?.events.filter((event: { eventType?: string }) => event.eventType === 'CLEARED')
      ).toHaveLength(1)
      expect(finalFact?.deliveries).toHaveLength(1)
      expect(finalFact?.deliveries[0]?.alarmEventId).toBe(activation.id)
      expect(finalFact?.deliveries[0]?.status).toBe('SUCCEEDED')
      expect(finalFact?.deliveries[0]?.attemptCount).toBe(2)
      expect(receipts(fixture.target).filter((value) => value.accepted)).toHaveLength(1)
    } catch (error) {
      primary = { error }
    } finally {
      secret = ''
      await owned.finish(primary)
    }
  }
)
