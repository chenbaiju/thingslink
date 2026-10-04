import { createServer, type TLSSocket } from 'node:tls'
import { mkdtempSync, readFileSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import path from 'node:path'
import { execFileSync } from 'node:child_process'
import { expect, it } from 'vitest'
import { publishCurrentValues } from '../e2e/current-value-mqtt'

it('MQTT受信TLS取得PUBACK，错误服务器名称在发送凭据前拒绝', async () => {
  const base = mkdtempSync(path.join(tmpdir(), 'mqtt-tls-fixture-'))
  const directory = path.join(base, 'tls')
  const sockets = new Set<TLSSocket>()
  let mqttConnections = 0
  try {
    execFileSync(process.env.PYTHON || (process.platform === 'win32' ? 'python' : 'python3'), [
      'scripts/emqx-test-tls.py',
      'generate',
      '--directory',
      directory
    ])
  } catch (error) {
    rmSync(base, { recursive: true, force: true })
    throw error
  }
  const server = createServer(
    {
      cert: readFileSync(path.join(directory, 'server.pem')),
      key: readFileSync(path.join(directory, 'server.key')),
      minVersion: 'TLSv1.2'
    },
    (socket) => {
      sockets.add(socket)
      socket.on('error', () => {})
      socket.on('close', () => sockets.delete(socket))
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
            mqttConnections++
            socket.write(Buffer.from([0x20, 2, 0, 0]))
          } else if (step === 1 && header === 0x32) {
            step++
            socket.write(Buffer.from([0x40, 2, 0, 1]))
          }
        }
      })
    }
  )
  server.on('tlsClientError', () => {})
  try {
    await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve))
    const input = {
      projectKey: 'fixture',
      deviceKey: 'fixture',
      secret: 'memory-only',
      payload: '{}',
      port: (server.address() as { port: number }).port,
      tls: { ca: readFileSync(path.join(directory, 'ca.pem')), servername: 'localhost' }
    }
    await publishCurrentValues(input)
    expect(mqttConnections).toBe(1)
    await expect(
      publishCurrentValues({ ...input, tls: { ...input.tls, servername: 'wrong.example.test' } })
    ).rejects.toThrow('MQTT真实上报')
    expect(mqttConnections).toBe(1)
  } finally {
    for (const socket of sockets) socket.destroy()
    await new Promise<void>((resolve) => server.close(() => resolve()))
    rmSync(base, { recursive: true, force: true })
  }
})
