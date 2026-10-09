import { defineConfig } from 'astro/config'
import tailwindcss from '@tailwindcss/vite'
import { fileURLToPath } from 'url'

const site = process.env.SITE_URL

// https://astro.build/config
export default defineConfig({
  output: 'static',
  site: site || undefined,

  vite: {
    build: {
      // 文档搜索在多篇文章间共享缓存，避免复制脚本挤占单篇HTML预算。
      assetsInlineLimit: (path) => path.includes('DocsSidebar') && path.endsWith('.js') ? false : undefined
    },
    plugins: [tailwindcss()],
    resolve: {
      alias: {
        '@': fileURLToPath(new URL('./src', import.meta.url))
      }
    }
  },

  // 部署到 /docs 子路径时取消注释下面这行
  // base: '/',

  server: {
    port: 4321
  }
})
