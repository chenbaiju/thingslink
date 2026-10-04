import { fileURLToPath, URL } from 'url'
import vue from '@vitejs/plugin-vue'
import AutoImport from 'unplugin-auto-import/vite'
import { defineConfig } from 'vitest/config'

/**
 * Vitest 配置：与 vite.config.ts 的路径别名保持一致，复用 Vue 插件。
 *
 * 组件在 <script setup> 里大量使用自动导入的 Vue API（ref/computed/watch/nextTick 等），
 * 测试环境必须同样启用 AutoImport，否则挂载组件会报「ref is not defined」。
 * 环境默认 jsdom（为组件测试预留）；时区由 tests/setup.ts 钉死为 Asia/Shanghai。
 */
export default defineConfig({
  plugins: [
    vue(),
    AutoImport({ imports: ['vue', 'vue-router', 'pinia', '@vueuse/core'], dts: false })
  ],
  // 与 vite.config.ts 的 define 保持一致，否则 import 链里引用 __APP_VERSION__ 的模块会 ReferenceError。
  define: {
    __APP_VERSION__: JSON.stringify('0.0.0-test')
  },
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
      '@views': fileURLToPath(new URL('./src/views', import.meta.url)),
      '@utils': fileURLToPath(new URL('./src/utils', import.meta.url)),
      '@stores': fileURLToPath(new URL('./src/store', import.meta.url)),
      '@styles': fileURLToPath(new URL('./src/assets/styles', import.meta.url)),
      '@imgs': fileURLToPath(new URL('./src/assets/images', import.meta.url)),
      '@icons': fileURLToPath(new URL('./src/assets/icons', import.meta.url))
    }
  },
  test: {
    // Bound native Windows workers to avoid import contention; CI keeps its default.
    maxWorkers: process.platform === 'win32' ? 2 : undefined,
    environment: 'jsdom',
    setupFiles: ['./tests/setup.ts'],
    include: ['tests/**/*.test.ts', 'src/**/*.test.ts'],
    // 契约测试依赖真实后端，由 vitest.contract.config.ts 单独收集，不能混入单测
    exclude: ['tests/contract/**']
  }
})
