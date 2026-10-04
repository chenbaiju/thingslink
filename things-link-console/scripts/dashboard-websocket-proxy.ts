import type { ProxyOptions } from 'vite'
/** 独立Console看板升级路径；保留原始浏览器Origin由后端显式白名单验证。 */
export function dashboardWebSocketProxy(target: string): Record<string, ProxyOptions> {
  return {
    '^/ws/dashboard/properties$': {
      target,
      changeOrigin: true,
      ws: true,
      rewriteWsOrigin: false
    }
  }
}
