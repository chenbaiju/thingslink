// 仅增强同源文档链接：保留导航外壳，原生链接仍承担无脚本及失败降级。
const main = document.querySelector<HTMLElement>('main#main-content')!
const isDocs = (url: URL) => url.origin === location.origin && /^\/docs(?:\/|$)/.test(url.pathname)
const pathname = (url: URL) => url.pathname.replace(/\/+$/, '')
const seo = 'meta[name="description"], meta[name="robots"], meta[property^="og:"], meta[name^="twitter:"], link[rel="canonical"]'
let current = new URL(location.href)
let pending: AbortController | undefined
let scrollTimer = 0
type Position = { url: string; x: number; y: number }
const position = () => history.state?.thingslinkDocs as Position | undefined
const remember = () => {
  if (pending || location.href !== current.href) return
  history.replaceState({ ...history.state, thingslinkDocs: { url: current.href, x: scrollX, y: scrollY } }, '')
}
const restoreReadingPosition = (url: URL, saved?: Position) => {
  if (saved?.url === url.href) window.scrollTo({ left: saved.x, top: saved.y, behavior: 'instant' })
  else {
    window.scrollTo({ left: 0, top: 0, behavior: 'instant' })
    if (url.hash) {
      try { document.getElementById(decodeURIComponent(url.hash.slice(1)))?.scrollIntoView() } catch { /* 非法片段不影响正文。 */ }
    }
  }
}
const cancel = () => { pending?.abort(); pending = undefined; main.removeAttribute('aria-busy') }
const navigate = async (url: URL, push: boolean, saved?: Position) => {
  cancel()
  if (push) remember()
  const request = new AbortController()
  pending = request
  main.setAttribute('aria-busy', 'true')
  const timeout = window.setTimeout(() => request.abort(), 10000)
  try {
    const response = await fetch(url.href, { signal: request.signal })
    if (!response.ok || !isDocs(new URL(response.url)) || !response.headers.get('content-type')?.includes('text/html'))
      throw new Error('文档响应不可用')
    const next = new DOMParser().parseFromString(await response.text(), 'text/html')
    const content = next.querySelector<HTMLElement>('main#main-content')
    if (!content?.querySelector('.docs-content') || !next.querySelector('[data-docs-sidebar]'))
      throw new Error('文档结构不可用')
    // 样式版本变化时回到完整加载，避免热更新或部署跨版本导致混合页面。
    const styles = (doc: Document) => Array.from(doc.querySelectorAll('link[rel="stylesheet"]')).map(e => e.getAttribute('href')).join('|')
    if (styles(document) !== styles(next)) throw new Error('文档样式已更新')
    if (pending !== request) return
    if (push) history.pushState({ thingslinkDocs: { url: url.href, x: 0, y: 0 } }, '', url)
    current = url
    main.replaceChildren(...content.childNodes)
    document.title = next.title
    document.head.querySelectorAll(seo).forEach(e => e.remove())
    next.head.querySelectorAll(seo).forEach(e => document.head.append(e))
    document.querySelectorAll<HTMLAnchorElement>('.docs-nav-link').forEach(link => {
      if (pathname(new URL(link.href)) === pathname(url)) link.setAttribute('aria-current', 'page')
      else link.removeAttribute('aria-current')
    })
    pending = undefined
    main.removeAttribute('aria-busy')
    document.dispatchEvent(new Event('docs:page-change'))
    main.focus({ preventScroll: true })
    restoreReadingPosition(url, saved)
    remember()
    const announcer = document.querySelector<HTMLElement>('[data-docs-announcer]')
    if (announcer) announcer.textContent = next.title
  } catch {
    // 已取消或被新导航替代的请求不回写页面；实际读取失败回到浏览器原生加载。
    if (pending === request) { cancel(); location.assign(url.href) }
  } finally { clearTimeout(timeout) }
}

history.scrollRestoration = 'manual'
restoreReadingPosition(current, position())
remember()
window.addEventListener('scroll', () => {
  // 滚动停止后保存，避免每帧调用History API触发浏览器频率限制。
  clearTimeout(scrollTimer)
  scrollTimer = window.setTimeout(remember, 150)
}, { passive: true })
document.addEventListener('click', event => {
  if (event.defaultPrevented || event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return
  const link = event.target instanceof Element ? event.target.closest<HTMLAnchorElement>('a[href]') : null
  if (!link || link.hasAttribute('download') || (link.target && link.target !== '_self')) return
  const url = new URL(link.href)
  if (!isDocs(url)) return
  if (pathname(url) === pathname(current) && url.hash) return
  event.preventDefault()
  if (url.href === current.href) { cancel(); document.dispatchEvent(new Event('docs:page-change')); return }
  void navigate(url, true)
})
window.addEventListener('popstate', () => {
  cancel()
  const url = new URL(location.href)
  if (!isDocs(url)) { location.reload(); return }
  if (pathname(url) === pathname(current)) { current = url; restoreReadingPosition(url, position()); return }
  void navigate(url, false, position())
})
window.addEventListener('pagehide', () => { remember(); cancel(); history.scrollRestoration = 'auto' })
window.addEventListener('pageshow', () => { history.scrollRestoration = 'manual' })
window.addEventListener('hashchange', () => {
  const url = new URL(location.href)
  if (!pending && pathname(url) === pathname(current)) { current = url; remember() }
})

export {}
