import { createServer, type Socket } from 'node:net'
import { afterEach, describe, expect, it } from 'vitest'
import { publishCurrentValues, currentValueMessage } from '../e2e/current-value-mqtt'

const cleanups: (() => Promise<void>)[] = []
afterEach(async () => {
  for (const cleanup of cleanups.splice(0)) await cleanup()
})
async function broker(rejected = false, wrongId = false) {
  let disconnected = false,
    physicalClose = false
  let closed!: () => void
  const close = new Promise<void>((resolve) => {
    closed = resolve
  })
  const sockets = new Set<Socket>()
  const server = createServer((socket) => {
    sockets.add(socket)
    socket.on('error', () => undefined)
    socket.on('close', () => {
      physicalClose = true
      sockets.delete(socket)
      closed()
    })
    let buffer = Buffer.alloc(0),
      step = 0
    socket.on('data', (data) => {
      buffer = Buffer.concat([buffer, data])
      for (;;) {
        if (buffer.length < 2) return
        let size = 0,
          factor = 1,
          offset = 1,
          digit: number
        do {
          if (offset >= buffer.length) return
          digit = buffer[offset++]!
          size += (digit & 127) * factor
          factor *= 128
        } while (digit & 128)
        if (buffer.length < offset + size) return
        const header = buffer[0]
        buffer = buffer.subarray(offset + size)
        if (step === 0 && header === 0x10) {
          step++
          socket.write(Buffer.from([0x20]))
          setImmediate(() => socket.write(Buffer.from([2, 0, rejected ? 5 : 0])))
        } else if (step === 1 && header === 0x32) {
          step++
          socket.write(Buffer.from([0x40, 2]))
          setImmediate(() => socket.write(Buffer.from([0, wrongId ? 2 : 1])))
        } else if (step === 2 && header === 0xe0) disconnected = true
      }
    })
  })
  await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve))
  cleanups.push(
    () =>
      new Promise((resolve) => {
        sockets.forEach((socket) => socket.destroy())
        server.close(() => resolve())
      })
  )
  return {
    port: (server.address() as { port: number }).port,
    close,
    state: () => ({ disconnected, physicalClose })
  }
}
const options = {
  projectKey: 'project',
  deviceKey: 'device',
  secret: 'in-memory-only',
  payload: '{}'
}
describe('真实MQTT夹具传输与关闭', () => {
  it('分片CONNACK/PUBACK成功，DISCONNECT到达并完成物理关闭', async () => {
    const server = await broker()
    await publishCurrentValues({ ...options, port: server.port })
    await server.close
    expect(server.state()).toEqual({ disconnected: true, physicalClose: true })
  })
  it.each([
    [true, false],
    [false, true]
  ])('拒绝连接或错误packetId失败并关闭', async (rejected, wrongId) => {
    const server = await broker(rejected, wrongId)
    await expect(publishCurrentValues({ ...options, port: server.port })).rejects.toThrow(
      'MQTT真实上报'
    )
    await server.close
    expect(server.state().physicalClose).toBe(true)
  })
  it.each(['', '1', '01.0.0', '1.0.0"', 'v1.0.0'])('拒绝缺失或非法版本%s', (version) => {
    expect(() => currentValueMessage('{}', version)).toThrow('显式声明')
  })
  it('上报生成UUIDv7，原大数词法不经JSON.stringify数值损失', () => {
    const text = currentValueMessage('{"value":9007199254740993}', '2.3.4')
    expect(text).toContain('9007199254740993')
    expect(JSON.parse(text).modelVersion).toBe('2.3.4')
    expect(JSON.parse(text).messageId).toMatch(
      /^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/
    )
  })
})
