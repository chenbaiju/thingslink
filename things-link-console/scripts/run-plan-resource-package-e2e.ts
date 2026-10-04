import { createServer, request } from 'node:http'
import { readFile, realpath, stat } from 'node:fs/promises'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { createRequire } from 'node:module'
import { spawn } from 'node:child_process'

/** Opt-in Java integration fixture driver. Serves the built Console and proxies only its real API. */
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const require = createRequire(import.meta.url)
const backend = new URL(process.env.E2E_RESOURCE_PACKAGE_BACKEND ?? '')
if (
  backend.protocol !== 'http:' ||
  backend.hostname !== '127.0.0.1' ||
  backend.pathname !== '/' ||
  backend.username ||
  backend.password ||
  backend.search ||
  backend.hash
) {
  throw new Error('Resource package backend must be a numeric loopback HTTP origin')
}
for (const name of [
  'E2E_RESOURCE_PACKAGE_CONTROL',
  'E2E_RESOURCE_PACKAGE_CREDENTIAL',
  'E2E_RESOURCE_PACKAGE_EMAIL',
  'E2E_RESOURCE_PACKAGE_PASSWORD',
  'E2E_RESOURCE_PACKAGE_PROJECT_ID',
  'E2E_RESOURCE_PACKAGE_PROJECT_NAME',
  'E2E_RUN_DIR'
]) {
  if (!process.env[name]) throw new Error(`Missing resource package fixture setting: ${name}`)
}
const control = new URL(process.env.E2E_RESOURCE_PACKAGE_CONTROL!)
if (
  control.protocol !== 'http:' ||
  control.hostname !== '127.0.0.1' ||
  control.pathname !== '/' ||
  control.username ||
  control.password ||
  control.search ||
  control.hash
) {
  throw new Error('Resource package control must be a numeric loopback HTTP origin')
}
const dist = await realpath(path.join(root, 'dist'))
await stat(path.join(dist, 'index.html'))
const mime: Record<string, string> = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'application/javascript',
  '.css': 'text/css',
  '.json': 'application/json',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.woff2': 'font/woff2',
  '.woff': 'font/woff',
  '.ico': 'image/x-icon'
}
const server = createServer(async (incoming, outgoing) => {
  try {
    const url = new URL(incoming.url ?? '/', 'http://127.0.0.1')
    if (url.pathname.startsWith('/api/')) {
      const proxy = request(
        new URL(url.pathname + url.search, backend),
        {
          method: incoming.method,
          headers: { ...incoming.headers, host: backend.host },
          timeout: 30_000
        },
        (response) => {
          outgoing.writeHead(response.statusCode ?? 502, response.headers)
          response.pipe(outgoing)
        }
      )
      proxy.on('timeout', () => proxy.destroy(new Error('Backend request timed out')))
      proxy.on('error', () => {
        if (!outgoing.headersSent) outgoing.writeHead(502)
        outgoing.end()
      })
      incoming.pipe(proxy)
      return
    }
    if (incoming.method !== 'GET' && incoming.method !== 'HEAD') {
      outgoing.writeHead(405).end()
      return
    }
    const candidate = path.resolve(dist, '.' + decodeURIComponent(url.pathname))
    if (candidate !== dist && !candidate.startsWith(dist + path.sep)) {
      outgoing.writeHead(403).end()
      return
    }
    let file: string
    try {
      file = await realpath(candidate === dist ? path.join(dist, 'index.html') : candidate)
    } catch {
      outgoing.writeHead(404).end()
      return
    }
    if (!file.startsWith(dist + path.sep)) {
      outgoing.writeHead(403).end()
      return
    }
    const bytes = await readFile(file)
    outgoing.writeHead(200, {
      'Content-Type': mime[path.extname(file)] ?? 'application/octet-stream',
      'Cache-Control': 'no-store'
    })
    outgoing.end(incoming.method === 'HEAD' ? undefined : bytes)
  } catch {
    if (!outgoing.headersSent) outgoing.writeHead(500)
    outgoing.end()
  }
})
await new Promise<void>((resolve, reject) => {
  server.once('error', reject)
  server.listen(0, '127.0.0.1', resolve)
})
const address = server.address()
if (!address || typeof address === 'string') throw new Error('Missing loopback port')
const child = spawn(
  process.execPath,
  [
    require.resolve('@playwright/test/cli'),
    'test',
    'e2e/plan-resource-package.spec.ts',
    '--reporter=json'
  ],
  {
    cwd: root,
    stdio: 'inherit',
    env: {
      ...process.env,
      E2E_BASE_URL: `http://127.0.0.1:${address.port}`,
      PLAYWRIGHT_JSON_OUTPUT_FILE: path.join(process.env.E2E_RUN_DIR!, 'playwright.json'),
      PLAYWRIGHT_BROWSERS_PATH:
        process.env.PLAYWRIGHT_BROWSERS_PATH ?? path.join(root, '.playwright-browsers')
    }
  }
)
const stop = () => child.kill('SIGTERM')
process.once('SIGTERM', stop)
process.once('SIGINT', stop)
try {
  process.exitCode = await new Promise<number>((resolve, reject) => {
    child.once('error', reject)
    child.once('exit', (code) => resolve(code ?? 1))
  })
} finally {
  process.removeListener('SIGTERM', stop)
  process.removeListener('SIGINT', stop)
  server.closeAllConnections()
  await new Promise<void>((resolve) => server.close(() => resolve()))
}
