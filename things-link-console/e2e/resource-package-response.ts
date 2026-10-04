import type { Page, Request, Response, Route } from '@playwright/test'

/** Capture only the owned quota request; preserve the real response status, headers and bytes. */
export async function captureResourcePackageQuota(
  page: Page,
  quotaUrl: string,
  checkpoint: (stage: 'response' | 'capture-failed', status?: number) => void,
  timeoutMs = 30_000
) {
  const match = new RegExp(`^${quotaUrl.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}$`)
  const bodies = new WeakMap<Request, { status: number; bytes: Buffer }>()
  const forward = async (route: Route) => {
    if (route.request().method() !== 'GET') {
      await route.continue()
      return
    }
    let expired = false
    let timer: ReturnType<typeof setTimeout> | undefined
    try {
      await Promise.race([
        (async () => {
          const response = await route.fetch({ maxRedirects: 0, timeout: timeoutMs })
          const bytes = await response.body()
          if (expired) return
          bodies.set(route.request(), { status: response.status(), bytes })
          checkpoint('response', response.status())
          await route.fulfill({ response, body: bytes })
        })(),
        new Promise<never>((_, reject) => {
          timer = setTimeout(() => {
            expired = true
            reject(new Error('Quota response capture timeout'))
          }, timeoutMs)
        })
      ])
    } catch {
      checkpoint('capture-failed')
      // Raw network errors can include authenticated request headers; never forward those details.
      throw new Error('Owned quota response capture failed or timed out')
    } finally {
      if (timer) clearTimeout(timer)
    }
  }
  await page.route(match, forward)
  return {
    read<T>(response: Response): T {
      const captured = bodies.get(response.request())
      if (!captured || captured.status !== response.status()) {
        throw new Error('Missing real quota body for the same request and status')
      }
      try {
        return JSON.parse(captured.bytes.toString('utf8')) as T
      } catch {
        throw new Error('Real quota response is not valid JSON')
      }
    }
  }
}
