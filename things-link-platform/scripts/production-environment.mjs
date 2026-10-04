const REQUIRED_URLS = [
  ['SITE_URL', '官网地址']
]

const OPTIONAL_URLS = [
  ['PUBLIC_CONSOLE_URL', '控制台地址']
]

const RESERVED_HOSTS = new Set(['example.com', 'example.org', 'example.net'])

function validateHttpsUrl(name, label, value) {
  if (!value?.trim()) return `${name}（${label}）不能为空`

  let url
  try {
    url = new URL(value)
  } catch {
    return `${name}（${label}）必须是完整 URL`
  }

  const hostname = url.hostname.toLowerCase()
  if (url.protocol !== 'https:') return `${name}（${label}）必须使用 https`
  if (url.username || url.password) return `${name}（${label}）不能包含用户名或密码`
  if (url.search || url.hash) return `${name}（${label}）不能包含查询参数或片段`
  if (hostname === 'localhost' || hostname.endsWith('.localhost') || hostname === '127.0.0.1' || hostname === '0.0.0.0' || hostname === '[::1]') {
    return `${name}（${label}）不能指向本机地址`
  }
  if (RESERVED_HOSTS.has(hostname) || hostname.endsWith('.example') || hostname.endsWith('.invalid') || hostname.endsWith('.test')) {
    return `${name}（${label}）不能使用示例或保留域名`
  }
  if (name === 'SITE_URL' && url.pathname !== '/') {
    return `${name}（${label}）当前必须部署在域名根路径`
  }

  return undefined
}

export function validateProductionEnvironment(environment) {
  const requiredErrors = REQUIRED_URLS
    .map(([name, label]) => validateHttpsUrl(name, label, environment[name]))
    .filter(Boolean)

  const optionalErrors = OPTIONAL_URLS
    .filter(([name]) => environment[name]?.trim())
    .map(([name, label]) => validateHttpsUrl(name, label, environment[name]))
    .filter(Boolean)

  return [...requiredErrors, ...optionalErrors]
}

export function assertProductionEnvironment(environment) {
  const errors = validateProductionEnvironment(environment)
  if (errors.length) {
    throw new Error(`生产配置校验失败：\n- ${errors.join('\n- ')}`)
  }
}
