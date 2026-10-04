import { controlledRelease } from '../pwa/release-identity'
import registeredResources from '../../resources/registry.json'
import type { PublishedBuiltinResource } from './published'

/** 平台构建旁置清单；当前只用于未发布候选，不代表生产不可变登记。 */
export interface HostCandidate {
  hostVersion: '1.0.0' | '1.1.0' | '1.1.1'
  artifactDigest: string
  resources: readonly PublishedBuiltinResource[]
}

export function validateHostCandidate(value: unknown): HostCandidate {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error('宿主清单不可用')
  const data = value as Record<string, unknown>
  if (Object.keys(data).sort().join(',') !== 'artifactDigest,artifactDigestAlgorithm,components,formatVersion,hostVersion,manifest,resources,supportedApplicationFormats,supportedSchemas'
    || data.formatVersion !== 'tc.webapp-host/v1' || (data.hostVersion !== '1.0.0' && data.hostVersion !== '1.1.0' && data.hostVersion !== '1.1.1')
    || data.artifactDigestAlgorithm !== 'SHA-256' || typeof data.artifactDigest !== 'string'
    || !/^[0-9a-f]{64}$/.test(data.artifactDigest)) throw new Error('宿主版本或制品清单不匹配')
  // 平台受控清单与编译期实物登记双向相等，不能仅凭服务端声明名称认领尚未实现组件。
  if (JSON.stringify(data.supportedApplicationFormats) !== '["tc.application/v1"]'
    || JSON.stringify(data.supportedSchemas) !== '["tc.dashboard/v1"]'
    || JSON.stringify(data.components) !== JSON.stringify(['TEXT', 'IMAGE', 'DEVICE_SELECTOR', 'VALUE_CARD', 'STATUS', 'GAUGE', 'TABLE', 'JSON_VIEW', 'LINE_CHART', 'ALARM_LIST']
      .map(kind => ({ kind, componentVersion: data.hostVersion === '1.0.0' ? '1.0.0' : '1.0.1' })))
    || JSON.stringify(data.resources) !== JSON.stringify(registeredResources)
    || JSON.stringify(data.manifest) !== '{"id":"/app/","startUrl":"/app/","scope":"/app/"}') throw new Error('宿主能力或资源登记不一致')
  return { hostVersion: data.hostVersion, artifactDigest: data.artifactDigest, resources: registeredResources as readonly PublishedBuiltinResource[] }
}

/** 静态清单不携带身份，不跨origin、不重定向，64KiB上限与元数据合同4.2一致。 */
async function publicJson(path: string, fetcher: typeof fetch, signal?: AbortSignal): Promise<unknown> {
  const response = await fetcher(path, { signal, credentials: 'omit', cache: 'no-store', mode: 'same-origin', redirect: 'error' })
  if (!response.ok || !response.body) throw new Error('宿主构建不可用')
  const reader = response.body.getReader()
  const bytes = new Uint8Array(64 * 1024)
  let length = 0
  try {
    while (true) {
      const { value, done } = await reader.read()
      if (done) break
      if (value.length > bytes.length - length) { await reader.cancel(); throw new Error('宿主清单过大') }
      bytes.set(value, length); length += value.length
    }
  } finally { reader.releaseLock() }
  return JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(bytes.subarray(0, length)))
}

/** 一个加载只选择一次D；两个可变latest文件不能拼成伪造的同代候选。 */
export async function loadExactHostCandidate(sourceDigest: string, fetcher: typeof fetch, controlledDigest?: string): Promise<HostCandidate> {
  const controller = new AbortController()
  // 公开构建元数据总等待有界，不能让一次慢响应永久占住页面候选Promise。
  const timer = setTimeout(() => controller.abort(), 8000)
  try {
    if (!/^[a-f0-9]{64}$/.test(sourceDigest)) throw new Error('宿主构建身份不可用')
    const digest = controlledDigest ?? validateHostCandidate(await publicJson('/app/host-candidate.json', fetcher, controller.signal)).artifactDigest
    if (!/^[a-f0-9]{64}$/.test(digest)) throw new Error('宿主构建身份不可用')
    const prefix = `/app/releases/${digest}`
    const receipt = await publicJson(`${prefix}/source-receipt.json`, fetcher, controller.signal) as Record<string, unknown> | null
    if (!receipt || typeof receipt !== 'object' || Array.isArray(receipt)
      || Object.keys(receipt).sort().join(',') !== 'artifactDigest,sourceDigest'
      || receipt.sourceDigest !== sourceDigest || receipt.artifactDigest !== digest) throw new Error('宿主已更新，请重新打开')
    const candidate = validateHostCandidate(await publicJson(`${prefix}/host-candidate.json`, fetcher, controller.signal))
    if (candidate.artifactDigest !== digest) throw new Error('宿主已更新，请重新打开')
    return candidate
  } finally { clearTimeout(timer) }
}

let candidate: Promise<HostCandidate> | undefined
/** 页面整个生命周期绑定同一公开构建；业务恢复不能把运行代码重新标记成另一制品。 */
export function loadHostCandidate(): Promise<HostCandidate> {
  candidate ??= (async () => {
    const controller = typeof navigator !== 'undefined' && 'serviceWorker' in navigator ? navigator.serviceWorker.controller : null
    const digest = controller ? await controlledRelease(controller, window.location.origin) : undefined
    const result = await loadExactHostCandidate(__HOST_SOURCE_DIGEST__, fetch, digest)
    if (result.hostVersion !== __HOST_VERSION__) throw new Error('宿主已更新，请重新打开')
    if (controller && navigator.serviceWorker.controller !== controller) throw new Error('宿主已更新，请重新打开')
    return result
  })().catch(error => { candidate = undefined; throw error })
  return candidate
}

/** 最新版本只用于发现待安装的完整公开SW，不能替换当前页面已绑定的业务宿主。 */
export async function discoverHostCandidate(): Promise<HostCandidate> {
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), 8000)
  try { return validateHostCandidate(await publicJson('/app/host-candidate.json', fetch, controller.signal)) }
  finally { clearTimeout(timer) }
}
