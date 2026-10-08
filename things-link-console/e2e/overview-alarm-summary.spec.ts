import { expect, test, type Page } from '@playwright/test'
import type { OverviewResponse } from '../src/api/overview'
import { withAlarmSummaryProject } from './alarm-summary-fixture'
import { captureRealJsonResponses } from './real-json-response'

// 读取链验收使用明确的 PostgreSQL 告警事实，MQTT→告警评估由通知旅程独立验证。
async function readOverview(page: Page, projectId: string) {
  return page.evaluate(async (id) => {
    const path = '/src/api/overview.ts'
    return (await (await import(path)).fetchProjectOverview(id)) as OverviewResponse
  }, projectId)
}

// Canvas 无 DOM 文本：核对已挂载图表的数据；全零合同会清空图表并展示真实空态。
async function expectAlarmChart(page: Page, counts: number[] | null) {
  await expect
    .poll(() =>
      page.evaluate(async () => {
        const path = '/src/plugins/echarts.ts'
        const { echarts } = await import(path)
        const card = Array.from(document.querySelectorAll('.overview-card--rate')).find((element) =>
          element.textContent?.includes('告警设备分布')
        )
        const node = card?.querySelector('.overview-card__chart')
        const option = node ? echarts.getInstanceByDom(node)?.getOption() : undefined
        return {
          mounted: Boolean(node),
          data: option?.series?.[0]?.data ?? [],
          empty: Boolean(node?.textContent?.includes('暂无数据'))
        }
      })
    )
    .toEqual({
      mounted: true,
      data: counts
        ? ['正常', '严重', '主要', '次要', '警告', '提示'].map((name, index) => ({
            name,
            value: counts[index]
          }))
        : [],
      empty: counts === null
    })
}

async function awaitOverview(page: Page, projectId: string, total: number, critical: number) {
  // 生产短 TTL 为 30 秒；等待真实缓存自然更新，不删除 Redis 或覆盖缓存返回值。
  await expect
    .poll(
      async () => {
        const snapshot = await readOverview(page, projectId)
        return {
          total: snapshot.devices?.total,
          critical: snapshot.alarmSeverityDeviceCounts?.CRITICAL
        }
      },
      { timeout: 45_000, intervals: [1000, 2000] }
    )
    .toEqual({ total, critical })
  const snapshot = await readOverview(page, projectId)
  const counts = snapshot.alarmSeverityDeviceCounts!
  expect(Object.values(counts).reduce((sum, value) => sum + (value ?? 0), 0)).toBe(total)
  expect(snapshot.alarmRate?.available).toBe(true)
  expect(snapshot.alarmRate?.value).toBeCloseTo(
    total === 0 ? 0 : (total - counts.NORMAL!) / total,
    12
  )
  return snapshot
}

test('概要真实快照与环图按设备最高活动告警互斥分类，删除后及空集不沿用旧计数', async ({ page }) => {
  test.setTimeout(240_000)
  await withAlarmSummaryProject(page, async ({ id, devices }) => {
    const snapshot = await awaitOverview(page, id, 6, 1)
    expect(snapshot.alarmSeverityDeviceCounts).toEqual({
      NORMAL: 1,
      CRITICAL: 1,
      MAJOR: 1,
      MINOR: 1,
      WARNING: 1,
      INFO: 1
    })
    await page.goto('/#/dashboard/overview')
    await page.reload()
    const card = page.locator('.overview-card--rate').filter({ hasText: '告警设备分布' })
    await expect(
      page
        .locator('.overview-card--count')
        .filter({ hasText: '告警设备数' })
        .locator('.overview-card__value')
    ).toHaveText('5')
    await expect(card).toContainText('5 台告警设备，按最高活动告警级别归类')
    await expectAlarmChart(page, [1, 1, 1, 1, 1, 1])
    for (const [width, columns] of [
      [1800, 4],
      [1000, 2],
      [375, 1]
    ]) {
      await page.setViewportSize({ width: width!, height: 1200 })
      const grid = page.locator('.project-overview__cards--counts')
      await expect(grid).toHaveCSS('display', 'grid')
      await expect
        .poll(() =>
          grid.evaluate((el) => getComputedStyle(el).gridTemplateColumns.split(' ').length)
        )
        .toBe(columns)
      await expect
        .poll(() => page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth))
        .toBe(true)
    }
    await page.setViewportSize({ width: 1800, height: 1200 })
    await page.evaluate(
      async ({ id, deviceId }) => {
        const path = '/src/api/device.ts'
        await (await import(path)).fetchDeleteDevice(id, deviceId)
      },
      { id, deviceId: devices[1]!.id }
    )
    expect((await awaitOverview(page, id, 5, 0)).alarmSeverityDeviceCounts).toEqual({
      NORMAL: 1,
      CRITICAL: 0,
      MAJOR: 1,
      MINOR: 1,
      WARNING: 1,
      INFO: 1
    })
    await page.reload()
    await expect(
      page
        .locator('.overview-card--count')
        .filter({ hasText: '告警设备数' })
        .locator('.overview-card__value')
    ).toHaveText('4')
    await expect(card).toContainText('4 台告警设备，按最高活动告警级别归类')
    await expectAlarmChart(page, [1, 0, 1, 1, 1, 1])
    await page.evaluate(
      async ({ id, devices }) => {
        const path = '/src/api/device.ts'
        const api = await import(path)
        for (const device of devices) await api.fetchDeleteDevice(id, device.id)
      },
      { id, devices: devices.filter((_, index) => index !== 1) }
    )
    expect((await awaitOverview(page, id, 0, 0)).alarmSeverityDeviceCounts).toEqual({
      NORMAL: 0,
      CRITICAL: 0,
      MAJOR: 0,
      MINOR: 0,
      WARNING: 0,
      INFO: 0
    })
    await page.reload()
    await expect(
      page
        .locator('.overview-card--count')
        .filter({ hasText: '告警设备数' })
        .locator('.overview-card__value')
    ).toHaveText('0')
    await expect(card).toContainText('0 台告警设备，按最高活动告警级别归类')
    await expect(card).not.toContainText('告警分布暂不可用')
    await expectAlarmChart(page, null)
  })
})

test('设备可见行合批读取真实告警摘要，整批断网显示不可用且单行重试恢复', async ({ page }) => {
  test.setTimeout(180_000)
  await page.setViewportSize({ width: 1800, height: 1200 })
  await withAlarmSummaryProject(page, async ({ id, devices }) => {
    const pathname = `/api/v1/projects/${id}/alarms/device-status`
    const capture = await captureRealJsonResponses(page, pathname)
    const batches: string[][] = []
    page.on('request', (request) => {
      const url = new URL(request.url())
      if (url.pathname === pathname) batches.push(url.searchParams.getAll('deviceId'))
    })
    try {
      // 冷 Vite 设备页及样式在隔离栈中实测超过20秒；这里只扩页面挂载预算，
      // 不放宽业务状态/批次数量。并行等待立即接管拒绝，导航失败不遗留悬空响应Promise。
      const [response] = await Promise.all([
        page.waitForResponse((response) => new URL(response.url()).pathname === pathname, {
          timeout: 60_000
        }),
        (async () => {
          await page.goto('/#/device/list')
          await expect(page).toHaveURL(/\/device\/list$/)
          await expect(page.locator('.device-list__content')).toBeVisible({ timeout: 60_000 })
          await expect(page.getByRole('button', { name: '创建设备', exact: true })).toBeVisible()
        })()
      ])
      expect(response.status()).toBe(200)
      expect(response.headers()['cache-control']).toBe('no-store')
      const body = capture.read<{
        observedAt: string
        devices: { deviceId: string; state: string }[]
      }>(response)
      expect(Number.isFinite(Date.parse(body.observedAt))).toBe(true)
      const rows = page.locator('.device-list__data .el-table__body tr')
      for (const [index, device] of devices.entries()) {
        await expect(
          rows.filter({ hasText: device.deviceKey }).locator('.device-alarm-status')
        ).toHaveText(index === 0 ? '无告警' : '有告警')
        expect(body.devices.find((item) => item.deviceId === device.id)?.state).toBe(
          index === 0 ? 'NORMAL' : 'ACTIVE'
        )
      }
      expect(batches).toHaveLength(1)
      expect([...batches[0]!].sort()).toEqual(devices.map((device) => device.id).sort())
      expect(batches[0]!.length).toBeLessThanOrEqual(20)
      const matches = (url: URL) => url.pathname === pathname
      // 唯一注入为网络中断，没有合成状态或成功响应。
      await page.route(matches, (route) => route.abort('connectionfailed'))
      try {
        await page.getByRole('button', { name: '查询', exact: true }).click()
        await expect(rows.locator('.device-alarm-status')).toHaveText(
          Array(6).fill('状态不可用 重试')
        )
      } finally {
        await page.unroute(matches)
      }
      const normal = rows.filter({ hasText: devices[0]!.deviceKey }).locator('.device-alarm-status')
      const [retry] = await Promise.all([
        page.waitForResponse((r) => new URL(r.url()).pathname === pathname),
        normal.getByRole('button', { name: '重试', exact: true }).click()
      ])
      expect(retry.status()).toBe(200)
      await expect(normal).toHaveText('无告警')
      await expect(
        rows.filter({ hasText: devices[1]!.deviceKey }).locator('.device-alarm-status')
      ).toContainText('状态不可用')
      expect(batches.at(-1)).toEqual([devices[0]!.id])
    } finally {
      await capture.stop()
    }
  })
})
