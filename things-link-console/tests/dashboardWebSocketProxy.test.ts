// @vitest-environment node
import { createServer as createHttpServer, request } from 'node:http'
import { createHash } from 'node:crypto'
import { once } from 'node:events'
import { mkdtemp, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import type { AddressInfo, Socket } from 'node:net'
import { createServer } from 'vite'
import { describe, expect, it } from 'vitest'
import { dashboardWebSocketProxy } from '../scripts/dashboard-websocket-proxy'

describe('Console看板精确WebSocket代理', () => {
  it('只匹配唯一无参数路径，不匹配App/分享/其他WS或前缀后缀', () => {
    const proxy = dashboardWebSocketProxy('http://127.0.0.1:8080')
    const keys = Object.keys(proxy)
    expect(keys).toEqual(['^/ws/dashboard/properties$'])
    const route = new RegExp(keys[0]!)
    expect(route.test('/ws/dashboard/properties')).toBe(true)
    for (const path of [
      '/ws/app/properties',
      '/ws/app/dashboard',
      '/ws/shares/test',
      '/ws/dashboard/properties/extra',
      '/ws/dashboard/properties?token=invalid',
      '/other/ws/dashboard/properties'
    ]) {
      expect(route.test(path)).toBe(false)
    }
    expect(proxy[keys[0]!]).toMatchObject({ ws: true, rewriteWsOrigin: false })
  })
  it('真实Vite升级保留浏览器Origin及协议集合，后端只回选公开协议', async () => {
    const root = await mkdtemp(join(tmpdir(), 'console-ws-proxy-'))
    const sockets = new Set<Socket>()
    let origin: string | undefined
    let protocols: string | undefined
    const upstream = createHttpServer()
    upstream.on('connection', (socket) => {
      sockets.add(socket)
      socket.on('close', () => sockets.delete(socket))
    })
    upstream.on('upgrade', (req, socket) => {
      origin = req.headers.origin
      protocols = req.headers['sec-websocket-protocol']
      const accept = createHash('sha1')
        .update(`${req.headers['sec-websocket-key']}258EAFA5-E914-47DA-95CA-C5AB0DC85B11`)
        .digest('base64')
      socket.write(
        `HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: ${accept}\r\nSec-WebSocket-Protocol: tc.dashboard.properties.v1\r\n\r\n`
      )
    })
    upstream.listen(0, '127.0.0.1')
    await once(upstream, 'listening')
    const port = (upstream.address() as AddressInfo).port
    const vite = await createServer({
      configFile: false,
      root,
      logLevel: 'silent',
      server: {
        host: '127.0.0.1',
        port: 0,
        proxy: dashboardWebSocketProxy(`http://127.0.0.1:${port}`)
      },
      optimizeDeps: { noDiscovery: true, include: [] }
    })
    try {
      await vite.listen()
      const vitePort = (vite.httpServer!.address() as AddressInfo).port
      const browserOrigin = `http://127.0.0.1:${vitePort}`
      const selected = await new Promise<string | undefined>((resolve, reject) => {
        const call = request({
          hostname: '127.0.0.1',
          port: vitePort,
          path: '/ws/dashboard/properties',
          headers: {
            Connection: 'Upgrade',
            Upgrade: 'websocket',
            Origin: browserOrigin,
            'Sec-WebSocket-Key': 'dGhlIHNhbXBsZSBub25jZQ==',
            'Sec-WebSocket-Version': '13',
            'Sec-WebSocket-Protocol': 'tc.dashboard.properties.v1, bearer.test-fixture-only'
          }
        })
        call.setTimeout(2000, () => call.destroy(new Error('升级超时')))
        call.on('upgrade', (response, socket) => {
          resolve(response.headers['sec-websocket-protocol'])
          socket.destroy()
          call.destroy()
        })
        call.on('response', (response) => {
          response.resume()
          reject(new Error('没有完成协议升级'))
        })
        call.on('error', reject)
        call.end()
      })
      expect(origin).toBe(browserOrigin)
      expect(protocols).toBe('tc.dashboard.properties.v1, bearer.test-fixture-only')
      expect(selected).toBe('tc.dashboard.properties.v1')
    } finally {
      for (const socket of sockets) socket.destroy()
      await vite.close()
      await new Promise<void>((resolve, reject) =>
        upstream.close((error) => (error ? reject(error) : resolve()))
      )
      await rm(root, { recursive: true, force: true })
    }
  })
})
