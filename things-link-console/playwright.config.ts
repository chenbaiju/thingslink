import { defineConfig } from '@playwright/test'
import path from 'node:path'

const localChromiumExecutable = process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH
const evidenceDirectory = process.env.E2E_RUN_DIR

/**
 * G1-C2d 浏览器 E2E 配置。
 *
 * - 真实浏览器 + 真实后端 + 真实中间件（不允许 page.route 全 mock 核心 API）。
 * - 单 worker 顺序执行：E2E 共享真实后端数据，并发会撞限流计数与唯一键。
 * - 浏览器缓存装在仓库内 .playwright-browsers（沙箱禁止写 ~/Library/Caches）。
 * - 下载受限的本机可显式传 PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH；默认仍使用锁定 revision，CI 不受影响。
 * - 失败产物：trace / 截图 / 视频 保留，供 CI 上传复盘。
 */
export default defineConfig({
  testDir: './e2e',
  ...(process.env.E2E_OWNED_RUNTIME ? { globalSetup: './e2e/owned-runtime-setup.ts' } : {}),
  timeout: 120_000,
  expect: { timeout: 20_000 },
  fullyParallel: false,
  workers: 1,
  retries: 0,
  // 测试方针 §7.1：首个直接失败后停止，后续场景不能继续收集参考 PASS。
  maxFailures: 1,
  reporter: evidenceDirectory
    ? [
        ['list'],
        ['html', { open: 'never', outputFolder: path.join(evidenceDirectory, 'html') }],
        ['json', { outputFile: path.join(evidenceDirectory, 'playwright.json') }]
      ]
    : [['list'], ['html', { open: 'never', outputFolder: 'playwright-report' }]],
  use: {
    baseURL: process.env.E2E_BASE_URL ?? 'http://localhost:3006',
    launchOptions: localChromiumExecutable
      ? { executablePath: localChromiumExecutable }
      : undefined,
    trace: process.env.E2E_OWNED_RUNTIME ? 'off' : 'retain-on-failure',
    screenshot: process.env.E2E_OWNED_RUNTIME ? 'off' : 'only-on-failure',
    video: process.env.E2E_OWNED_RUNTIME ? 'off' : 'retain-on-failure'
  },
  outputDir: evidenceDirectory ? path.join(evidenceDirectory, 'test-results') : 'test-results'
})
