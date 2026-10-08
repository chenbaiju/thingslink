import { expect, test } from '@playwright/test'
import { login, enterProject, OWNER_EMAIL, OWNER_PASSWORD } from './helpers'
import { readBoundModelVersion } from './device-model-fixture'
import { currentValueMessage, publishCurrentValues } from './current-value-mqtt'

test.use({ trace: 'off', video: 'off', screenshot: 'off' })
test(
  '原始属性历史：真实六类型遥测、精确JSON整数、筛选与游标分页',
  { tag: '@simulator' },
  async ({ page }) => {
    test.setTimeout(150_000)
    await login(page, OWNER_EMAIL, OWNER_PASSWORD)
    await enterProject(page, process.env.E2E_PROJECT_NAME ?? 'E2E项目')
    const fixture = await page.evaluate(async () => {
      const apiPath = '/src/api/device.ts',
        userPath = '/src/store/modules/user.ts'
      const api = await import(apiPath)
      const projectId = (await import(userPath)).useUserStore().info.currentProjectId as string
      const suffix = Date.now(),
        key = `raw_history_${suffix}`
      const type = await api.fetchCreateDeviceType(projectId, {
        typeKey: key,
        name: `原始历史模型${suffix}`,
        deviceKind: 'DIRECT',
        payloadProtocol: 'STANDARD',
        networkType: 'WIFI'
      })
      const definitions = [
        { propertyKey: 'temperature', name: '温度', dataType: 'NUMBER' },
        { propertyKey: 'label', name: '标签', dataType: 'TEXT' },
        { propertyKey: 'enabled', name: '开关', dataType: 'SWITCH' },
        { propertyKey: 'mode', name: '枚举', dataType: 'ENUM', enumOptions: ['ON', 'OFF'] },
        {
          propertyKey: 'payload',
          name: '对象',
          dataType: 'OBJECT',
          schema: JSON.stringify({
            type: 'object',
            additionalProperties: false,
            maxProperties: 2,
            properties: { big: { type: 'number' }, text: { type: 'string', maxLength: 128 } }
          })
        },
        {
          propertyKey: 'samples',
          name: '列表',
          dataType: 'LIST',
          schema: JSON.stringify({ type: 'array', maxItems: 4, items: { type: 'number' } })
        }
      ]
      for (const [index, definition] of definitions.entries())
        await api.fetchCreateDevicePropertyDefinition(projectId, type.id!, {
          ...definition,
          accessType: 'REPORT',
          sortOrder: index
        })
      await api.fetchPublishDeviceType(projectId, type.id!)
      const device = await api.fetchCreateDevice(projectId, {
        deviceTypeId: type.id!,
        deviceKey: key,
        name: `原始历史设备${suffix}`
      })
      const credential = await api.fetchGenerateCredential(projectId, device.id!)
      return {
        projectId,
        deviceId: device.id!,
        deviceKey: key,
        credentialId: credential.id!,
        secret: credential.plainSecret!
      }
    })
    try {
      const projectKey = process.env.E2E_PROJECT_KEY
      if (!projectKey) throw new Error('缺少真实上报项目标识')
      const version = await readBoundModelVersion(fixture.projectId, fixture.deviceId)
      for (let index = 0; index < 4; index++)
        await publishCurrentValues({
          projectKey,
          deviceKey: fixture.deviceKey,
          secret: fixture.secret,
          port: Number(process.env.EMQX_MQTT_PORT ?? 1883),
          payload: currentValueMessage(
            `{"temperature":${21 + index},"label":"原始文本${index}","enabled":false,"mode":"ON","payload":{"big":9007199254740993123456789,"text":"<script>literal</script>"},"samples":[9007199254740993,2.5]}`,
            version
          )
        })
      await expect
        .poll(
          async () =>
            page.evaluate(
              async (f) => {
                const path = '/src/api/device-property-history.ts'
                const api = await import(path)
                const filters = { propertyKey: '', from: '', to: '' }
                const first = await api.fetchDevicePropertyHistory(f.projectId, f.deviceId, filters)
                const last = first.nextCursor
                  ? await api.fetchDevicePropertyHistory(
                      f.projectId,
                      f.deviceId,
                      filters,
                      first.nextCursor
                    )
                  : { items: [] }
                return first.items.length + last.items.length
              },
              { projectId: fixture.projectId, deviceId: fixture.deviceId }
            ),
          { timeout: 30_000 }
        )
        .toBe(24)
      await page.goto(
        `/#/device/list?resourceId=${fixture.deviceId}&contextProjectId=${fixture.projectId}`
      )
      await page
        .locator('.device-detail')
        .getByRole('tab', { name: '原始属性历史', exact: true })
        .click()
      const history = page.getByTestId('device-property-history')
      const rows = history.locator('.el-table__body tr')
      await expect(rows).toHaveCount(20)
      await expect(history).toContainText('9007199254740993123456789')
      await expect(history).toContainText('<script>literal</script>')
      await expect(history.locator('script')).toHaveCount(0)
      for (const type of ['NUMBER', 'TEXT', 'SWITCH', 'ENUM', 'OBJECT', 'LIST'])
        await expect(history).toContainText(type)
      await history.getByTestId('device-property-history-next').click()
      await expect(rows).toHaveCount(4)
      await expect(history.getByTestId('device-property-history-next')).toBeDisabled()
      await history.getByRole('textbox', { name: '历史属性键' }).fill('payload')
      await history.getByTestId('device-property-history-refresh').click()
      await expect(rows).toHaveCount(4)
      await expect(history.getByTestId('device-property-history-next')).toBeDisabled()
      await expect(rows.first()).toContainText('OBJECT')
      await expect(rows.first()).toContainText('9007199254740993123456789')
      await history.getByRole('textbox', { name: '历史属性键' }).fill('missing_property')
      await history.getByTestId('device-property-history-refresh').click()
      await expect(history).toContainText('当前筛选与套餐可读窗口内暂无数据')
      await expect(history.getByTestId('device-property-history-error')).toHaveCount(0)
      await test.info().attach('raw-property-history-result', {
        body: JSON.stringify({
          projectId: fixture.projectId,
          deviceId: fixture.deviceId,
          types: 6,
          totalPoints: 24,
          pages: [20, 4],
          integerPreserved: true
        }),
        contentType: 'application/json'
      })
    } finally {
      fixture.secret = ''
      await page.evaluate(async (f) => {
        const path = '/src/api/device.ts'
        await (await import(path)).fetchRevokeCredential(f.projectId, f.deviceId, f.credentialId)
      }, fixture)
    }
  }
)
