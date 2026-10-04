/**
 * 站点全局配置。
 *
 * 所有链接、站点名称、导航项都从这里引用，不硬编码到组件中。
 * 改一处即改全站。
 */

/**
 * 控制台尚未配置公开地址时保持为空，调用组件隐藏或禁用控制台入口。
 * 开发环境如需连接本地控制台，通过 PUBLIC_CONSOLE_URL 显式配置。
 */
export const CONSOLE_URL = import.meta.env.PUBLIC_CONSOLE_URL?.trim() || ''

export const SITE = {
  name: 'ThingsLink',
  description: 'ThingsLink 物联网云平台 — 设备接入、数据采集、远程控制与规则告警',
  consoleUrl: CONSOLE_URL,
  /** 文档首页路径 */
  docsUrl: '/docs',
  docsEnabled: false
} as const

/**
 * 顶部导航。
 *
 * 桌面导航（Header）与移动菜单（MobileMenu）都从这里取，
 * 不要在任何组件里再写一份同样的数组。
 */
export const HOME_NAV = [
  { label: '平台能力', href: '/#features' },
  { label: '应用示例', href: '/#application-examples' },
  { label: '开发者资源', href: '/#developer-resources' }
] as const

export const NAV = [
  ...HOME_NAV
] as const

/** 归一化路径：去掉查询串、哈希与末尾斜杠，供当前项比较使用 */
export function normalizePath(path: string): string {
  const withoutQuery = path.split(/[?#]/)[0]
  const withoutTrailingSlash = withoutQuery.replace(/\/+$/, '')
  return withoutTrailingSlash === '' ? '/' : withoutTrailingSlash
}

/** 文档 id → 路由路径；index 归到 /docs，与 DocsSidebar 的既有算法一致 */
export function docsPath(id: string): string {
  return id === 'index' ? SITE.docsUrl : `${SITE.docsUrl}/${id}`
}

/**
 * 当前项判定。
 *
 * 同时接受带与不带末尾斜杠的地址，避免 `/docs/` 与 `/docs` 被判成两项。
 */
export function isCurrentPath(current: string, href: string): boolean {
  return normalizePath(current) === normalizePath(href)
}

interface FooterGroup {
  title: string
  links: readonly { label: string; href: string; external?: boolean }[]
}

const footerGroups: readonly FooterGroup[] = [
  { title: '平台导览', links: HOME_NAV },
  ...(CONSOLE_URL ? [{ title: '控制台', links: [
    { label: '进入控制台', href: SITE.consoleUrl, external: true }
  ] }] : [])
]

export const FOOTER = {
  copyright: `© ${new Date().getFullYear()} ThingsLink. All rights reserved.`,
  groups: footerGroups
} as const
