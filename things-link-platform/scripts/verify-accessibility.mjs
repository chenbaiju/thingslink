import { readFile, readdir } from 'node:fs/promises'
import { resolve } from 'node:path'

const dist = resolve('dist')
const sourceCss = await readFile(resolve('src/styles/global.css'), 'utf8')

function expect(condition, message) {
  if (!condition) throw new Error(message)
}

function count(source, pattern) {
  return [...source.matchAll(pattern)].length
}

async function htmlFiles(directory) {
  const entries = await readdir(directory, { withFileTypes: true })
  const nested = await Promise.all(entries.map((entry) => {
    const path = resolve(directory, entry.name)
    return entry.isDirectory() ? htmlFiles(path) : entry.name.endsWith('.html') ? [path] : []
  }))
  return nested.flat()
}

function luminance(hex) {
  const channels = hex.match(/[\da-f]{2}/gi).map((value) => Number.parseInt(value, 16) / 255)
  const linear = channels.map((value) => value <= 0.04045 ? value / 12.92 : ((value + 0.055) / 1.055) ** 2.4)
  return linear[0] * 0.2126 + linear[1] * 0.7152 + linear[2] * 0.0722
}

function contrast(foreground, background) {
  const values = [luminance(foreground), luminance(background)].sort((a, b) => b - a)
  return (values[0] + 0.05) / (values[1] + 0.05)
}

const files = await htmlFiles(dist)
expect(files.length >= 4, `HTML 页面数不足：${files.length}`)

for (const file of files) {
  const html = await readFile(file, 'utf8')
  const name = file.slice(dist.length + 1)
  expect(/<html[^>]+lang="zh-CN"/.test(html), `${name} 缺少 zh-CN 页面语言`)
  expect(/<meta[^>]+name="viewport"[^>]+width=device-width/.test(html), `${name} 缺少响应式 viewport`)
  expect(count(html, /<main(?:\s|>)/g) === 1, `${name} 应且仅应包含一个 main`)
  expect(count(html, /<h1(?:\s|>)/g) === 1, `${name} 应且仅应包含一个 h1`)
  expect(/<a[^>]+class="skip-link"[^>]+href="#main-content"/.test(html), `${name} 缺少跳到主要内容链接`)
  expect(/<main[^>]+id="main-content"[^>]+tabindex="-1"/.test(html), `${name} 主内容不是可聚焦跳转目标`)
  expect(!/<(a|button|summary)\b[^>]*>\s*<\/\1>/i.test(html), `${name} 含空交互元素`)
  for (const image of html.match(/<img\b[^>]*>/gi) ?? []) {
    expect(/\balt=(?:"[^"]*"|'[^']*')/i.test(image), `${name} 含缺少 alt 的图片`)
  }
}

const home = await readFile(resolve(dist, 'index.html'), 'utf8')
expect(count(home, /class="demo-panel\b/g) === 3, '无脚本首页应保留三组产品示意内容')
expect(!/class="demo-panel\b[^>]*\shidden(?:\s|>|=)/.test(home), '产品示意不应在初始 HTML 中隐藏')

const cssFiles = (await readdir(resolve(dist, '_astro'))).filter((file) => file.endsWith('.css'))
expect(cssFiles.length, '未找到构建后的 CSS')
const css = (await Promise.all(cssFiles.map((file) => readFile(resolve(dist, '_astro', file), 'utf8')))).join('\n')
expect(css.includes('prefers-reduced-motion:reduce'), '构建 CSS 缺少减少动态效果规则')
expect(css.includes('.skip-link'), '构建 CSS 缺少跳过导航样式')

function color(name) {
  const match = sourceCss.match(new RegExp(`--color-${name}:\\s*(#[\\da-f]{6})`, 'i'))
  expect(match, `未找到颜色变量 --color-${name}`)
  return match[1]
}

const palette = {
  primary: [color('text-primary'), color('bg-primary')],
  secondary: [color('text-secondary'), color('bg-primary')],
  muted: [color('text-muted'), color('bg-primary')],
  link: [color('brand-700'), color('bg-primary')],
  inverse: [color('bg-primary'), color('brand-800')],
  buttonStart: [color('bg-primary'), color('brand-600')],
  buttonEnd: [color('bg-primary'), color('brand-700')]
}
for (const [name, colors] of Object.entries(palette)) {
  const ratio = contrast(...colors)
  expect(ratio >= 4.5, `${name} 对比度 ${ratio.toFixed(2)}:1 低于 4.5:1`)
}

console.log(`可访问性合同通过：${files.length} 个页面、响应式/无脚本结构、跳过导航、标题与替代文本、减少动态效果及 ${Object.keys(palette).length} 组颜色`)
