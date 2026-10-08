import { createServer } from 'node:http'
import { readFile, readdir, stat } from 'node:fs/promises'
import { extname, resolve, sep } from 'node:path'

const root = resolve('dist')
const contentTypes = {
  '.css': 'text/css; charset=utf-8',
  '.html': 'text/html; charset=utf-8',
  '.js': 'application/javascript; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.txt': 'text/plain; charset=utf-8',
  '.woff2': 'font/woff2',
  '.xml': 'application/xml; charset=utf-8'
}

function cacheControl(urlPath, filePath, statusCode) {
  if (statusCode === 404 || filePath.endsWith('.html') || urlPath === '/robots.txt' || urlPath === '/sitemap.xml') {
    return 'no-cache'
  }
  if (urlPath.startsWith('/_astro/') && /\.(?:css|js)$/.test(filePath)) {
    return 'public, max-age=31536000, immutable'
  }
  return 'public, max-age=3600, must-revalidate'
}

async function isFile(path) {
  try {
    return (await stat(path)).isFile()
  } catch {
    return false
  }
}

async function resolveFile(urlPath) {
  const relative = decodeURIComponent(urlPath).replace(/^\/+/, '')
  const base = resolve(root, relative)
  if (base !== root && !base.startsWith(`${root}${sep}`)) return undefined

  const candidates = urlPath.endsWith('/')
    ? [resolve(base, 'index.html')]
    : [resolve(`${base}/index.html`), base, resolve(`${base}.html`)]
  for (const candidate of candidates) {
    if (await isFile(candidate)) return candidate
  }
  return undefined
}

const server = createServer(async (request, response) => {
  const url = new URL(request.url ?? '/', 'http://127.0.0.1')
  let filePath
  try {
    filePath = await resolveFile(url.pathname)
  } catch {
    filePath = undefined
  }
  const statusCode = filePath ? 200 : 404
  filePath ??= resolve(root, '404.html')
  const body = await readFile(filePath)
  response.writeHead(statusCode, {
    'Cache-Control': cacheControl(url.pathname, filePath, statusCode),
    'Content-Type': contentTypes[extname(filePath)] ?? 'application/octet-stream'
  })
  response.end(body)
})

async function request(origin, path) {
  const response = await fetch(`${origin}${path}`)
  return {
    body: await response.text(),
    cache: response.headers.get('cache-control'),
    status: response.status
  }
}

function expect(condition, message) {
  if (!condition) throw new Error(message)
}

await new Promise((resolveListen, reject) => {
  server.once('error', reject)
  server.listen(0, '127.0.0.1', resolveListen)
})

try {
  const address = server.address()
  const origin = `http://127.0.0.1:${address.port}`
  const routes = ['/', '/docs', '/docs/', '/docs/getting-started', '/docs/getting-started/', '/docs/agent', '/docs/agent/', '/robots.txt', '/sitemap.xml']
  for (const path of routes) {
    const result = await request(origin, path)
    expect(result.status === 200, `${path} 应返回 200，实际为 ${result.status}`)
    expect(result.cache === 'no-cache', `${path} 缓存策略错误：${result.cache}`)
  }

  const missing = await request(origin, '/missing-static-route')
  expect(missing.status === 404, `未知路径应返回 404，实际为 ${missing.status}`)
  expect(missing.cache === 'no-cache', `404 缓存策略错误：${missing.cache}`)
  expect(missing.body.includes('页面未找到'), '404 未返回自定义页面')
  expect(!missing.body.includes('物联网云平台 让设备连接更简单'), '未知路径错误回退到了首页')

  const assetFile = (await readdir(resolve(root, '_astro'))).find((file) => /\.(?:css|js)$/.test(file))
  expect(assetFile, '未找到带哈希的 CSS/JS 资源')
  const assetPath = `/_astro/${assetFile}`
  const asset = await request(origin, assetPath)
  expect(asset.status === 200, `${assetPath} 应返回 200`)
  expect(asset.cache === 'public, max-age=31536000, immutable', `哈希资源缓存策略错误：${asset.cache}`)

  const font = await request(origin, '/fonts/ThingsLink_Cereal_VF_W_Wght.woff2')
  expect(font.status === 200, '字体资源应返回 200')
  expect(font.cache === 'public, max-age=3600, must-revalidate', `字体缓存策略错误：${font.cache}`)

  console.log(`静态部署验收通过：${routes.length} 个正常路由、真实 404、哈希资源与字体缓存`)
} finally {
  await new Promise((resolveClose, reject) => server.close((error) => error ? reject(error) : resolveClose()))
}
