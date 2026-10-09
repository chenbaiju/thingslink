import { readFile, readdir, stat } from 'node:fs/promises'
import { extname, relative, resolve } from 'node:path'

const root = resolve('dist')
const limits = {
  // 三篇文档基线250 KiB；新增文档按既有30 KiB单页配额扩展总量。
  // CSS、字体、首页、单篇和脚本预算不随文档数量放宽。
  total: 250 * 1024,
  css: 40 * 1024,
  font: 70 * 1024,
  homeHtml: 50 * 1024,
  otherHtml: 30 * 1024,
  inlineScript: 5 * 1024,
  // 目录搜索与局部导航共用脚本；仅文档页加载，不随文章数量增加。
  docsScript: 8 * 1024
}

function expect(condition, message) {
  if (!condition) throw new Error(message)
}

async function files(directory) {
  const entries = await readdir(directory, { withFileTypes: true })
  const nested = await Promise.all(entries.map((entry) => {
    const path = resolve(directory, entry.name)
    return entry.isDirectory() ? files(path) : [path]
  }))
  return nested.flat()
}

const documentCount = (await files(resolve('src/content/docs')))
  .filter((path) => path.endsWith('.md') && !path.split(/[\\/]/).at(-1).startsWith('_')).length
limits.total += Math.max(0, documentCount - 3) * limits.otherHtml

const paths = await files(root)
const assets = await Promise.all(paths.map(async (path) => ({
  bytes: (await stat(path)).size,
  extension: extname(path),
  name: relative(root, path).replaceAll('\\', '/')
})))
const total = assets.reduce((sum, asset) => sum + asset.bytes, 0)

expect(total <= limits.total, `构建产物 ${total} 字节超过 ${limits.total} 字节预算`)
expect(assets.filter(({ extension }) => extension === '.css').reduce((sum, asset) => sum + asset.bytes, 0)
  <= limits.css, '构建 CSS 总量超过40 KiB预算')
const scripts = assets.filter(({ extension }) => extension === '.js')
expect(scripts.length === 1 && /^_astro\/DocsSidebar\..+\.js$/.test(scripts[0].name),
  '只允许文档目录实际使用的共享脚本，不允许闲置运行时')
expect(scripts[0].bytes <= limits.docsScript, '文档交互脚本超过8 KiB预算')

for (const asset of assets) {
  if (asset.extension === '.css') expect(asset.bytes <= limits.css, `${asset.name} 超过 CSS 预算`)
  if (asset.extension === '.woff2') expect(asset.bytes <= limits.font, `${asset.name} 超过字体预算`)
  if (asset.extension !== '.html') continue

  const htmlLimit = asset.name === 'index.html' ? limits.homeHtml : limits.otherHtml
  expect(asset.bytes <= htmlLimit, `${asset.name} 超过 HTML 预算`)
  const html = await readFile(resolve(root, asset.name), 'utf8')
  const searchScript = `src="/${scripts[0].name}"`
  expect(asset.name.startsWith('docs/') ? html.includes(searchScript) : !html.includes(searchScript),
    `${asset.name} 的文档脚本加载范围错误`)
  const inlineBytes = [...html.matchAll(/<script\b(?![^>]*\bsrc=)[^>]*>([\s\S]*?)<\/script>/gi)]
    .reduce((sum, match) => sum + Buffer.byteLength(match[1]), 0)
  expect(inlineBytes <= limits.inlineScript, `${asset.name} 内联脚本超过预算`)
}

const summary = Object.fromEntries(['.html', '.css', '.js', '.woff2'].map((extension) => [
  extension.slice(1),
  assets.filter((asset) => asset.extension === extension).reduce((sum, asset) => sum + asset.bytes, 0)
]))
console.log(`性能预算通过：总计 ${total} 字节；HTML ${summary.html}、CSS ${summary.css}、JS ${summary.js}、字体 ${summary.woff2}`)
