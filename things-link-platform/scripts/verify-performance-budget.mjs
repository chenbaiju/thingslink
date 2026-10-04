import { readFile, readdir, stat } from 'node:fs/promises'
import { extname, relative, resolve } from 'node:path'

const root = resolve('dist')
const limits = {
  total: 220 * 1024,
  css: 40 * 1024,
  font: 70 * 1024,
  homeHtml: 50 * 1024,
  otherHtml: 30 * 1024,
  inlineScript: 5 * 1024
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

const paths = await files(root)
const assets = await Promise.all(paths.map(async (path) => ({
  bytes: (await stat(path)).size,
  extension: extname(path),
  name: relative(root, path).replaceAll('\\', '/')
})))
const total = assets.reduce((sum, asset) => sum + asset.bytes, 0)

expect(total <= limits.total, `构建产物 ${total} 字节超过 ${limits.total} 字节预算`)
expect(!assets.some(({ extension }) => extension === '.js'), '构建目录不应含未使用的外部 JavaScript')

for (const asset of assets) {
  if (asset.extension === '.css') expect(asset.bytes <= limits.css, `${asset.name} 超过 CSS 预算`)
  if (asset.extension === '.woff2') expect(asset.bytes <= limits.font, `${asset.name} 超过字体预算`)
  if (asset.extension !== '.html') continue

  const htmlLimit = asset.name === 'index.html' ? limits.homeHtml : limits.otherHtml
  expect(asset.bytes <= htmlLimit, `${asset.name} 超过 HTML 预算`)
  const html = await readFile(resolve(root, asset.name), 'utf8')
  const inlineBytes = [...html.matchAll(/<script\b(?![^>]*\bsrc=)[^>]*>([\s\S]*?)<\/script>/gi)]
    .reduce((sum, match) => sum + Buffer.byteLength(match[1]), 0)
  expect(inlineBytes <= limits.inlineScript, `${asset.name} 内联脚本超过预算`)
}

const summary = Object.fromEntries(['.html', '.css', '.js', '.woff2'].map((extension) => [
  extension.slice(1),
  assets.filter((asset) => asset.extension === extension).reduce((sum, asset) => sum + asset.bytes, 0)
]))
console.log(`性能预算通过：总计 ${total} 字节；HTML ${summary.html}、CSS ${summary.css}、JS ${summary.js}、字体 ${summary.woff2}`)
