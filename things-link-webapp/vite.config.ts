import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { spawnSync } from 'node:child_process'

const root = fileURLToPath(new URL('.', import.meta.url))
const hostVersion = process.env.TC_WEBAPP_HOST_VERSION ?? '1.0.0'
if (!['1.0.0', '1.1.0', '1.1.1'].includes(hostVersion)) throw new Error('未知显式宿主版本')

// 独立同源宿主：精确App与匿名分享路径，正式部署仍需配置受管Origin。
export default defineConfig(({ command }) => {
  const source = spawnSync(process.env.PYTHON || (process.platform === 'win32' ? 'python' : 'python3'), ['scripts/host-candidate.py', 'source-digest'], { cwd: root, timeout: 10000, encoding: 'utf8' })
  if (source.status !== 0 || !/^[a-f0-9]{64}$/.test(source.stdout.trim())) throw new Error('宿主源码摘要不可用')
  const sourceDigest = source.stdout.trim()
  if (command === 'build') {
    const prepared = JSON.parse(readFileSync(new URL('./artifacts/prepared-source.json', import.meta.url), 'utf8')) as { sourceDigest?: string }
    if (prepared.sourceDigest !== sourceDigest) throw new Error('构建源码与准备记录不同')
  }
  return {
  define: { __HOST_SOURCE_DIGEST__: JSON.stringify(sourceDigest), __HOST_VERSION__: JSON.stringify(hostVersion) },
  base: '/app/',
  plugins: [vue(), {
    name: 'verified-unpublished-host-candidate',
    configureServer(server) {
      server.middlewares.use((request, response, next) => {
        const url = request.url ?? ''
        const latest = url === '/app/host-candidate.json' || url === '/host-candidate.json'
          ? 'host-candidate.json' : url === '/app/source-receipt.json' ? 'source-receipt.json' : undefined
        const release = /^\/app\/releases\/([a-f0-9]{64})\/(host-candidate\.json|source-receipt\.json|precache\.json|index\.html|manifest\.webmanifest|sw\.js|assets\/[A-Za-z0-9_-]+\.(?:js|css|png|svg|woff2))$/.exec(url)
        if (!latest && !release) {
          if (url.startsWith('/app/releases/')) { response.statusCode = 404; response.end(); return }
          return next()
        }
        response.setHeader('Cache-Control', 'no-store')
        response.setHeader('X-Content-Type-Options', 'nosniff')
        if (request.method !== 'GET' && request.method !== 'HEAD') { response.statusCode = 405; response.end(); return }
        const verification = spawnSync(process.env.PYTHON || (process.platform === 'win32' ? 'python' : 'python3'), ['scripts/host-candidate.py', 'verify'], { cwd: root, timeout: 10000 })
        if (verification.status !== 0) { response.statusCode = 503; response.end(); return }
        let relative = latest
        if (release) {
          const verified = spawnSync(process.env.PYTHON || (process.platform === 'win32' ? 'python' : 'python3'), ['scripts/host-candidate.py', 'verify-release', release[1]!], { cwd: root, timeout: 10000 })
          if (verified.status !== 0) { response.statusCode = 503; response.end(); return }
          relative = `releases/${release[1]}/${release[2]}`
        }
        const extension = relative!.split('.').at(-1)!
        const types: Record<string, string> = { json: 'application/json', webmanifest: 'application/manifest+json',
          html: 'text/html; charset=utf-8', js: 'text/javascript; charset=utf-8', css: 'text/css; charset=utf-8',
          png: 'image/png', svg: 'image/svg+xml', woff2: 'font/woff2' }
        response.setHeader('Content-Type', types[extension] ?? 'application/octet-stream')
        if (release?.[2] === 'sw.js') response.setHeader('Service-Worker-Allowed', '/app/')
        try {
          const bytes = readFileSync(new URL(`./artifacts/${relative}`, import.meta.url))
          response.end(request.method === 'HEAD' ? undefined : bytes)
        } catch { response.statusCode = 404; response.end() }
      })
    },
  }],
  server: {
    host: 'localhost', port: 3007, strictPort: true,
    headers: { 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff', 'X-Frame-Options': 'DENY', 'Referrer-Policy': 'same-origin' },
    proxy: { '/api/v1/shares/': { target: process.env.VITE_DEV_API_TARGET ?? 'http://127.0.0.1:8080', changeOrigin: false }, '/ws/shares/': { target: process.env.VITE_DEV_API_TARGET ?? 'http://127.0.0.1:8080', changeOrigin: false, ws: true }, '/ws/app/dashboard': { target: process.env.VITE_DEV_API_TARGET ?? 'http://127.0.0.1:8080', changeOrigin: false, ws: true }, '/ws/app/properties': { target: process.env.VITE_DEV_API_TARGET ?? 'http://127.0.0.1:8080', changeOrigin: false, ws: true }, '/api/v1/app': { target: process.env.VITE_DEV_API_TARGET ?? 'http://127.0.0.1:8080', changeOrigin: false } }
  }
  }
})
