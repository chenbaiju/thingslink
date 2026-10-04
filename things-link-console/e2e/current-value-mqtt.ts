import { createConnection } from 'node:net'
import { randomBytes } from 'node:crypto'
import { connect as tlsConnect } from 'node:tls'

/** 单次本机MQTT 3.1.1 QoS1夹具；凭据仅进内存，不写日志/命令行。PUBACK不冒充落库成功。 */
export function publishCurrentValues(input: {
  projectKey: string
  deviceKey: string
  secret: string
  payload: string
  port?: number
  tls?: { ca: Buffer; servername: string }
}): Promise<void> {
  const port = input.port ?? 1883
  if (!Number.isInteger(port) || port < 1025 || port > 65535)
    return Promise.reject(new Error('MQTT夹具端口无效'))
  const field = (value: string) => {
    const bytes = Buffer.from(value)
    if (bytes.length > 65535) throw new Error('MQTT夹具字段超限')
    const length = Buffer.alloc(2)
    length.writeUInt16BE(bytes.length)
    return Buffer.concat([length, bytes])
  }
  const packet = (header: number, body: Buffer) => {
    if (body.length > 64 * 1024) throw new Error('MQTT夹具报文超限')
    let remaining = body.length
    const prefix = [header]
    do {
      const digit = remaining % 128
      remaining = Math.floor(remaining / 128)
      prefix.push(digit | (remaining ? 128 : 0))
    } while (remaining)
    return Buffer.concat([Buffer.from(prefix), body])
  }
  const connect = packet(
    0x10,
    Buffer.concat([
      field('MQTT'),
      Buffer.from([4, 0xc2, 0, 20]),
      field(`e2e-current-${randomBytes(8).toString('hex')}`),
      field(`${input.projectKey}/${input.deviceKey}`),
      field(input.secret)
    ])
  )
  const report = packet(
    0x32,
    Buffer.concat([
      field(`tc/v1/${input.projectKey}/${input.deviceKey}/up/property/report`),
      Buffer.from([0, 1]),
      Buffer.from(input.payload)
    ])
  )
  return new Promise((resolve, reject) => {
    const socket = input.tls
      ? tlsConnect({
          host: '127.0.0.1',
          port,
          ca: input.tls.ca,
          servername: input.tls.servername,
          rejectUnauthorized: true,
          minVersion: 'TLSv1.2'
        })
      : createConnection({ host: '127.0.0.1', port })
    let buffer = Buffer.alloc(0),
      phase = 0,
      failed = false
    const fail = () => {
      failed = true
      socket.destroy()
    }
    const timer = setTimeout(fail, 15_000)
    socket.on(input.tls ? 'secureConnect' : 'connect', () => socket.write(connect))
    socket.on('error', fail)
    socket.on('close', () => {
      clearTimeout(timer)
      if (phase === 2 && !failed) resolve()
      else reject(new Error('MQTT真实上报未取得确认或未完成关闭'))
    })
    socket.on('data', (chunk) => {
      buffer = Buffer.concat([buffer, chunk])
      if (buffer.length > 1024) return fail()
      while (buffer.length >= 4) {
        // 夹具只接受固定两字节CONNACK/PUBACK；不订阅，无任意响应分配。
        if (buffer[1] !== 2) return fail()
        if (phase === 0 && buffer[0] === 0x20 && buffer[2] === 0 && buffer[3] === 0) {
          phase = 1
          buffer = buffer.subarray(4)
          socket.write(report)
        } else if (phase === 1 && buffer[0] === 0x40 && buffer[2] === 0 && buffer[3] === 1) {
          phase = 2
          socket.end(Buffer.from([0xe0, 0]))
          return
        } else return fail()
      }
    })
  })
}
export function currentValueMessage(payload: string, modelVersion: string): string {
  if (
    typeof modelVersion !== 'string' ||
    modelVersion.length > 32 ||
    !/^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$/.test(modelVersion)
  )
    throw new Error('复合遥测必须显式声明有效物模型版本')
  const bytes = randomBytes(16)
  bytes.writeUIntBE(Date.now(), 0, 6)
  bytes[6] = (bytes[6]! & 0x0f) | 0x70
  bytes[8] = (bytes[8]! & 0x3f) | 0x80
  const hex = bytes.toString('hex')
  const messageId = `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
  return `{"messageId":"${messageId}","occurredAt":"${new Date().toISOString()}","modelVersion":"${modelVersion}","payload":${payload}}`
}
