import type { ProjectResponse } from '@/api/project'

export const localAppDebugAddress = 'http://127.0.0.1:8080'

/** 仅本机开发Console预填已约定的调试入口，不推测其他环境的API源站。 */
export function defaultAppProjectAddress(hostname: string) {
  return import.meta.env.DEV && ['localhost', '127.0.0.1', '[::1]'].includes(hostname)
    ? localAppDebugAddress
    : ''
}

/** ThingsX项目入口v1；仅包含接入信息，不授予登录或设备权限。 */
export function appProjectEntry(project: ProjectResponse, address: string) {
  const input = address.trim()
  let url: URL
  try {
    url = new URL(input)
  } catch {
    throw new Error('请输入手机可访问的 HTTPS 平台地址')
  }
  const localDebug =
    import.meta.env.DEV && (input === localAppDebugAddress || input === `${localAppDebugAddress}/`)
  if (
    input.length > 2048 ||
    /[\s\\]/u.test(input) ||
    (url.protocol !== 'https:' && !localDebug) ||
    !url.hostname ||
    url.username ||
    url.password ||
    url.search ||
    url.hash ||
    input.includes('?') ||
    input.includes('#') ||
    url.pathname !== '/' ||
    url.port === '0'
  ) {
    throw new Error(
      import.meta.env.DEV
        ? '请填写 HTTPS 平台根地址；本机调试仅支持 http://127.0.0.1:8080'
        : '平台地址只允许 HTTPS 源站，不包含路径、账号或查询参数'
    )
  }
  const projectKey = project.projectKey?.trim() ?? ''
  if (
    !projectKey ||
    projectKey.length > 64 ||
    Array.from(projectKey).some((c) => c.charCodeAt(0) <= 32 || c.charCodeAt(0) === 127)
  ) {
    throw new Error('项目标识不可用，请刷新项目列表后重试')
  }
  const label = project.name?.trim() ?? ''
  const omitLabel =
    label.length > 80 ||
    Array.from(label).some((c) => c.charCodeAt(0) < 32 || c.charCodeAt(0) === 127)
  const payload = JSON.stringify({
    version: 1,
    baseUrl: url.origin,
    projectKey,
    ...(omitLabel ? {} : { label })
  })
  const bytes = new TextEncoder().encode(payload).length
  if (bytes > 4096) throw new Error('接入信息过长，请使用较短的平台地址')
  return { payload, qrAvailable: bytes <= 1800, omitLabel }
}
