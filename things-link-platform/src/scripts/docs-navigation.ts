// 搜索索引来自页面已有目录，不复制文章元数据或读取内部知识库。
const compact = (text: string) => text.normalize('NFKC').toLocaleLowerCase().replace(/\s+/g, '')
for (const root of document.querySelectorAll<HTMLElement>('[data-docs-search]')) {
  const input = root.querySelector<HTMLInputElement>('input')!
  const list = root.querySelector<HTMLElement>('[role="listbox"]')!
  const status = root.querySelector<HTMLElement>('[role="status"]')!
  const entries = Array.from(root.closest('[data-docs-navigation]')!.querySelectorAll<HTMLAnchorElement>('.docs-nav-link'))
    .map(link => ({ title: link.textContent!.trim(), href: link.getAttribute('href')!,
      group: link.closest('section')!.getAttribute('aria-label')! }))
  let options: HTMLAnchorElement[] = []
  let active = -1
  let composing = false
  const close = () => {
    list.hidden = true
    input.setAttribute('aria-expanded', 'false')
    input.removeAttribute('aria-activedescendant')
    active = -1
  }
  const select = (index: number) => {
    active = index
    options.forEach((option, i) => option.setAttribute('aria-selected', String(i === active)))
    input.setAttribute('aria-activedescendant', options[active].id)
    const option = options[active]
    if (option.offsetTop < list.scrollTop) list.scrollTop = option.offsetTop
    else if (option.offsetTop + option.offsetHeight > list.scrollTop + list.clientHeight)
      list.scrollTop = option.offsetTop + option.offsetHeight - list.clientHeight
  }
  const render = () => {
    if (composing) return
    const terms = input.value.trim().split(/\s+/).filter(Boolean).map(compact)
    list.replaceChildren()
    options = []
    active = -1
    input.removeAttribute('aria-activedescendant')
    if (!terms.length) { status.textContent = ''; close(); return }
    const matches = entries.filter(e => terms.every(t => compact(e.title + e.group).includes(t)))
    for (const [index, entry] of matches.entries()) {
      const option = document.createElement('a')
      option.id = `${input.id}-option-${index}`
      option.href = entry.href
      option.tabIndex = -1
      option.setAttribute('role', 'option')
      option.setAttribute('aria-selected', 'false')
      // 建议保留输入框焦点，链接交给统一导航并保留原生降级能力。
      option.addEventListener('mousedown', event => event.preventDefault())
      const title = document.createElement('span')
      title.textContent = entry.title
      const group = document.createElement('small')
      group.textContent = entry.group
      option.append(title, group)
      list.append(option)
      options.push(option)
    }
    if (!matches.length) {
      const empty = document.createElement('p')
      empty.textContent = '没有匹配的文档'
      list.append(empty)
    }
    status.textContent = matches.length ? `${matches.length} 篇匹配文档，可用方向键选择` : '没有匹配的文档'
    list.hidden = false
    input.setAttribute('aria-expanded', 'true')
  }
  root.hidden = false
  input.addEventListener('input', render)
  input.addEventListener('focus', render)
  input.addEventListener('compositionstart', () => { composing = true })
  input.addEventListener('compositionend', () => { composing = false; render() })
  input.addEventListener('keydown', event => {
    if (event.isComposing || composing) return
    if (event.key === 'Escape' && !list.hidden) {
      event.preventDefault(); event.stopPropagation(); close()
    } else if (event.key === 'Tab') close()
    else if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
      renderIfClosed()
      if (!options.length) return
      event.preventDefault()
      select(active === -1 ? (event.key === 'ArrowDown' ? 0 : options.length - 1)
        : (active + (event.key === 'ArrowDown' ? 1 : -1) + options.length) % options.length)
    } else if (event.key === 'Enter' && !list.hidden && options.length) {
      event.preventDefault()
      options[Math.max(active, 0)].click()
    }
  })
  function renderIfClosed() { if (list.hidden) render() }
  document.addEventListener('click', event => { if (event.target instanceof Node && !root.contains(event.target)) close() })
  root.addEventListener('focusout', event => { if (!root.contains(event.relatedTarget as Node | null)) close() })
  document.addEventListener('docs:page-change', () => { input.value = ''; status.textContent = ''; close() })
}

import './docs-router'

// 独立保存桌面滚动容器的位置；隐藏目录不覆盖桌面记忆。
const sidebar = document.querySelector<HTMLElement>('[data-docs-sidebar]')
if (sidebar) {
  const key = 'thingslink:docs:sidebar-scroll'
  const restore = () => {
    if (!sidebar.getClientRects().length) return
    try {
      const value = sessionStorage.getItem(key)
      const top = Number(value)
      if (value !== null && Number.isFinite(top) && top >= 0) sidebar.scrollTop = top
    } catch { /* 保留原生导航。 */ }
  }
  const save = () => {
    if (!sidebar.getClientRects().length) return
    try { sessionStorage.setItem(key, String(sidebar.scrollTop)) } catch { /* 保留原生导航。 */ }
  }
  restore()
  sidebar.addEventListener('scroll', save, { passive: true })
  sidebar.addEventListener('click', save)
  window.addEventListener('pagehide', save)
  window.addEventListener('pageshow', restore)
  window.addEventListener('resize', restore)
}
