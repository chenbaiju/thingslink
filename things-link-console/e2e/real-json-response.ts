import type { Page, Request, Response, Route } from '@playwright/test'

/**
 * 对精确端点读取真实后端正文后原样透传，避免流关闭后CDP无法重读正文。
 * 不合成响应、不改请求参数/身份/状态/头/正文，不记录凭据；每个请求独立绑定。
 */
export async function captureRealJsonResponses(page: Page, pathname: string) {
  const bodies = new WeakMap<Request, { status: number; body: unknown }>()
  const matches = (url: URL) => url.pathname === pathname
  const forward = async (route: Route) => {
    const response = await route.fetch({ maxRedirects: 0 })
    bodies.set(route.request(), { status: response.status(), body: await response.json() })
    await route.fulfill({ response })
  }
  await page.route(matches, forward)
  return {
    read<T>(response: Response): T {
      const captured = bodies.get(response.request())
      if (!captured || captured.status !== response.status())
        throw new Error('缺少同一真实请求的透传响应证据')
      return captured.body as T
    },
    stop: () => page.unroute(matches, forward)
  }
}
