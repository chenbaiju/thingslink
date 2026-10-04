/** ADR0108：只接受当前控制器提供的公开版本，不传业务或凭据。 */
export async function controlledRelease(controller: ServiceWorker, origin: string): Promise<string> {
  const url = new URL(controller.scriptURL)
  const match = /^\/app\/releases\/([a-f0-9]{64})\/sw\.js$/.exec(url.pathname)
  if (url.origin !== origin || url.search || url.hash || !match) throw new Error('宿主控制器版本不可用')
  const expected = match[1]!
  const requestId = crypto.randomUUID()
  const channel = new MessageChannel()
  return new Promise<string>((resolve, reject) => {
    const finish = (digest?: string) => {
      clearTimeout(timer); channel.port1.close(); channel.port2.close()
      if (digest) resolve(digest); else reject(new Error('宿主控制器版本不可用'))
    }
    const timer = setTimeout(() => finish(), 2000)
    channel.port1.onmessage = event => {
      const value = event.data as Record<string, unknown> | null
      if (!value || typeof value !== 'object' || Array.isArray(value)
        || Object.keys(value).sort().join(',') !== 'artifactDigest,hostVersion,requestId,type'
        || value.type !== 'TC_HOST_RELEASE' || value.requestId !== requestId
        || (value.hostVersion !== '1.0.0' && value.hostVersion !== '1.1.0' && value.hostVersion !== '1.1.1') || value.artifactDigest !== expected) { finish(); return }
      finish(expected)
    }
    try { controller.postMessage({ type: 'TC_HOST_RELEASE_REQUEST', requestId }, [channel.port2]) }
    catch { finish() }
  })
}
