import { fileURLToPath, URL } from 'url'
import { defineConfig } from 'vitest/config'

/**
 * 契约测试专用 Vitest 配置：只跑 tests/contract 下的真实后端行为契约。
 * 与单测配置分离的原因：契约测试依赖真实后端 + 真实数据库，不能混入单测的 jsdom 环境；
 * 这里用 node 环境，且默认跳过（CI 在专门工作流里以 CONTRACT_BASE_URL 指向真实后端运行）。
 */
export default defineConfig({
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
      '@types': fileURLToPath(new URL('./src/types', import.meta.url))
    }
  },
  test: {
    environment: 'node',
    include: ['tests/contract/**/*.test.ts'],
    // 契约测试要起真实后端 + 数据库，超时给足
    testTimeout: 120000,
    hookTimeout: 120000
  }
})
