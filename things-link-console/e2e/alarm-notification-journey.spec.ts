import { expect, test } from '@playwright/test'
import { login, enterProject, OWNER_EMAIL, OWNER_PASSWORD } from './helpers'
import { readBoundModelVersion } from './device-model-fixture'
import { currentValueMessage, publishCurrentValues } from './current-value-mqtt'

test(
  '告警通知旅程：真实上报的同一事件贯穿投递、个人阅读、确认与自动恢复',
  { tag: '@simulator' },
  async ({ page }) => {
    test.setTimeout(180_000)
    await login(page, OWNER_EMAIL, OWNER_PASSWORD)
    await enterProject(page, 'E2E项目')
    const fixture = await page.evaluate(async () => {
      const devicePath = '/src/api/device.ts',
        alarmPath = '/src/api/alarm.ts',
        userPath = '/src/store/modules/user.ts'
      const device = await import(devicePath)
      const alarm = await import(alarmPath)
      const { useUserStore } = await import(userPath)
      const projectId = useUserStore().info.currentProjectId as string
      const suffix = Date.now()
      const type = await device.fetchCreateDeviceType(projectId, {
        typeKey: `alarm_notification_${suffix}`,
        name: `通知旅程模型${suffix}`,
        deviceKind: 'DIRECT',
        payloadProtocol: 'STANDARD',
        networkType: 'WIFI'
      })
      await device.fetchCreateDevicePropertyDefinition(projectId, type.id!, {
        propertyKey: 'temperature',
        name: '温度',
        dataType: 'NUMBER',
        unit: '℃',
        decimalPlaces: 2,
        accessType: 'REPORT',
        sortOrder: 0
      })
      await device.fetchPublishDeviceType(projectId, type.id!)
      const created = await device.fetchCreateDevice(projectId, {
        deviceTypeId: type.id!,
        deviceKey: `alarm_notification_${suffix}`,
        name: `通知旅程设备${suffix}`
      })
      const credential = await device.fetchGenerateCredential(projectId, created.id!)
      const rule = await alarm.fetchCreateAlarmRule(projectId, {
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
      const group = await alarm.fetchCreateAlarmNotificationGroup(projectId, {
        name: `通知旅程组${suffix}`,
        enabled: true
      })
      const recipient = await alarm.fetchCreateAlarmNotificationRecipient(projectId, group.id!, {
        channel: 'EMAIL',
        target: `alarm-${suffix}@example.com`,
        enabled: true
      })
      const template = await alarm.fetchCreateAlarmNotificationTemplate(projectId, {
        name: `通知旅程模板${suffix}`,
        channel: 'EMAIL',
        subjectTemplate: '告警 ${alarm.type}',
        bodyTemplate: '事故 ${alarm.instanceId} 告警 ${alarm.type}',
        enabled: true
      })
      const binding = await alarm.fetchCreateAlarmNotificationBinding(projectId, rule.id!, {
        groupId: group.id!,
        templateId: template.id!,
        channel: 'EMAIL',
        enabled: true
      })
      return {
        projectId,
        deviceId: created.id!,
        deviceKey: created.deviceKey!,
        credentialId: credential.id!,
        secret: credential.plainSecret!,
        alarmType: rule.alarmType!,
        ruleId: rule.id!,
        ruleVersion: rule.version!,
        groupId: group.id!,
        groupVersion: group.version!,
        recipientId: recipient.id!,
        recipientVersion: recipient.version!,
        templateId: template.id!,
        templateVersion: template.version!,
        bindingId: binding.id!,
        bindingVersion: binding.version!
      }
    })
    try {
      const projectKey = process.env.E2E_PROJECT_KEY
      if (!projectKey) throw new Error('缺少真实上报项目标识')
      const modelVersion = await readBoundModelVersion(fixture.projectId, fixture.deviceId)
      const report = (temperature: number) =>
        publishCurrentValues({
          projectKey,
          deviceKey: fixture.deviceKey,
          secret: fixture.secret,
          port: Number(process.env.EMQX_MQTT_PORT ?? 1883),
          payload: currentValueMessage(JSON.stringify({ temperature }), modelVersion)
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
      // 本地未配置 SMTP：Worker 必须显式留下失败及待重试事实，不能把日志发信当送达。
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
      expect(fact!.deliveries[0]!.alarmEventId).toBe(activation.id)

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
    } finally {
      fixture.secret = ''
      await page.evaluate(async (f) => {
        const alarmPath = '/src/api/alarm.ts',
          devicePath = '/src/api/device.ts'
        const alarm = await import(alarmPath)
        const device = await import(devicePath)
        const actions = [
          () => device.fetchRevokeCredential(f.projectId, f.deviceId, f.credentialId),
          () =>
            alarm.fetchDeleteAlarmNotificationBinding(f.projectId, f.bindingId, f.bindingVersion),
          () =>
            alarm.fetchDeleteAlarmNotificationRecipient(
              f.projectId,
              f.recipientId,
              f.recipientVersion
            ),
          () =>
            alarm.fetchDeleteAlarmNotificationTemplate(
              f.projectId,
              f.templateId,
              f.templateVersion
            ),
          () => alarm.fetchDeleteAlarmNotificationGroup(f.projectId, f.groupId, f.groupVersion),
          () => alarm.fetchDeleteAlarmRule(f.projectId, f.ruleId, f.ruleVersion)
        ]
        for (const action of actions) await action()
      }, fixture)
    }
  }
)
