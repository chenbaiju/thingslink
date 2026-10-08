import { expect, test } from '@playwright/test'
import { login, enterProject, OWNER_EMAIL, OWNER_PASSWORD } from './helpers'
import { readBoundModelVersion } from './device-model-fixture'
import { currentValueMessage, publishCurrentValues } from './current-value-mqtt'

test.use({ trace: 'off', video: 'off', screenshot: 'off' })

test(
  '告警详情双会话：外部确认与清除后打开、刷新均读取当前摘要和事件',
  { tag: '@simulator' },
  async ({ page, browser }) => {
    test.setTimeout(150_000)
    await login(page, OWNER_EMAIL, OWNER_PASSWORD)
    await enterProject(page, 'E2E项目')
    const fixture = await page.evaluate(async () => {
      const devicePath = '/src/api/device.ts',
        alarmPath = '/src/api/alarm.ts',
        userPath = '/src/store/modules/user.ts'
      const device = await import(devicePath),
        alarm = await import(alarmPath)
      const projectId = (await import(userPath)).useUserStore().info.currentProjectId as string
      const suffix = Date.now()
      const type = await device.fetchCreateDeviceType(projectId, {
        typeKey: `alarm_refresh_${suffix}`,
        name: `告警刷新模型${suffix}`,
        deviceKind: 'DIRECT',
        payloadProtocol: 'STANDARD',
        networkType: 'WIFI'
      })
      await device.fetchCreateDevicePropertyDefinition(projectId, type.id!, {
        propertyKey: 'temperature',
        name: '温度',
        dataType: 'NUMBER',
        accessType: 'REPORT',
        sortOrder: 0
      })
      await device.fetchPublishDeviceType(projectId, type.id!)
      const created = await device.fetchCreateDevice(projectId, {
        deviceTypeId: type.id!,
        deviceKey: `alarm_refresh_${suffix}`,
        name: `告警刷新设备${suffix}`
      })
      const credential = await device.fetchGenerateCredential(projectId, created.id!)
      const rule = await alarm.fetchCreateAlarmRule(projectId, {
        name: `告警刷新规则${suffix}`,
        alarmType: `REFRESH_${suffix}`,
        deviceId: created.id!,
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
      return {
        projectId,
        deviceId: created.id!,
        deviceKey: created.deviceKey!,
        credentialId: credential.id!,
        secret: credential.plainSecret!,
        ruleId: rule.id!,
        ruleVersion: rule.version!,
        alarmType: rule.alarmType!
      }
    })
    const other = await browser.newContext({ baseURL: test.info().project.use.baseURL })
    try {
      const projectKey = process.env.E2E_PROJECT_KEY
      if (!projectKey) throw new Error('缺少真实上报项目标识')
      const modelVersion = await readBoundModelVersion(fixture.projectId, fixture.deviceId)
      await publishCurrentValues({
        projectKey,
        deviceKey: fixture.deviceKey,
        secret: fixture.secret,
        port: Number(process.env.EMQX_MQTT_PORT ?? 1883),
        payload: currentValueMessage(JSON.stringify({ temperature: 31 }), modelVersion)
      })
      await expect
        .poll(
          async () =>
            page.evaluate(
              async ({ projectId, ruleId }) => {
                const path = '/src/api/alarm.ts'
                const page = await (await import(path)).fetchAlarmInstances(projectId)
                return page.items?.find((item: { ruleId?: string }) => item.ruleId === ruleId)
                  ?.conditionState
              },
              { projectId: fixture.projectId, ruleId: fixture.ruleId }
            ),
          { timeout: 30_000 }
        )
        .toBe('ACTIVE')
      await page.goto('/#/alarm/history')
      const row = page.locator('.el-table__body tr').filter({ hasText: fixture.alarmType })
      await expect(row).toContainText('未确认')
      const second = await other.newPage()
      await login(second, OWNER_EMAIL, OWNER_PASSWORD)
      await enterProject(second, 'E2E项目')
      await second.goto('/#/alarm/history')
      const secondRow = second.locator('.el-table__body tr').filter({ hasText: fixture.alarmType })
      await secondRow.getByRole('button', { name: '确认', exact: true }).click()
      await expect(secondRow).toContainText('已确认')
      // 首会话列表仍旧；打开抽屉必须单读摘要，不得复用旧行。
      await expect(row).toContainText('未确认')
      await row.getByRole('button', { name: '事件', exact: true }).click()
      const drawer = page.getByRole('dialog', { name: '告警事件时间线' })
      const summary = drawer.locator('.alarm-events__summary')
      await expect(summary).toContainText('已确认')
      await expect(summary).toContainText('活动')
      await expect(drawer).toContainText('人工确认')
      await secondRow.getByRole('button', { name: '人工清除', exact: true }).click()
      await second
        .getByRole('dialog', { name: '人工清除告警' })
        .getByRole('button', { name: '清除', exact: true })
        .click()
      await expect(secondRow).toContainText('已清除')
      await expect(summary).toContainText('活动')
      await drawer.getByRole('button', { name: '刷新详情', exact: true }).click()
      await expect(summary).toContainText('已清除')
      await expect(summary).toContainText('已确认')
      await expect(drawer).toContainText('告警清除')
      await expect(row).toContainText('已清除')
      await test.info().attach('alarm-refresh-result', {
        body: JSON.stringify({
          projectId: fixture.projectId,
          ruleId: fixture.ruleId,
          alarmType: fixture.alarmType,
          sessions: 2,
          state: 'CLEARED',
          ack: 'ACKNOWLEDGED'
        }),
        contentType: 'application/json'
      })
    } finally {
      fixture.secret = ''
      await other.close()
      await page.evaluate(async (f) => {
        const devicePath = '/src/api/device.ts',
          alarmPath = '/src/api/alarm.ts'
        const device = await import(devicePath),
          alarm = await import(alarmPath)
        await device.fetchRevokeCredential(f.projectId, f.deviceId, f.credentialId)
        await alarm.fetchDeleteAlarmRule(f.projectId, f.ruleId, f.ruleVersion)
      }, fixture)
    }
  }
)
