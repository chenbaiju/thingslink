import { createHash } from 'node:crypto'
import { EventEmitter } from 'node:events'
import { mkdtempSync, rmSync, writeFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { publishCurrentValues } from '../e2e/current-value-mqtt'

const sockets = vi.hoisted(() => ({ net: vi.fn(), tls: vi.fn() }))
vi.mock('node:net', () => ({
  createConnection: sockets.net,
  default: { createConnection: sockets.net }
}))
vi.mock('node:tls', () => ({ connect: sockets.tls, default: { connect: sockets.tls } }))
let directory: string
const ca = Buffer.from('PUBLIC_SYNTHETIC_CA_BYTES_FOR_SOCKET_SELECTION_ONLY'),
  hash = (value: Buffer | string) => createHash('sha256').update(value).digest('hex')
function material() {
  const caPath = resolve(directory, 'ca.pem'),
    path = resolve(directory, 'runtime.json')
  writeFileSync(caPath, ca)
  const bytes = JSON.stringify({
    qualification: 'WP-03-DEFAULT-MATRIX',
    backendPort: 8090,
    mqttPort: 51884,
    mqttTlsPort: 58884,
    simulatorPort: 8092,
    tls: { caPath, caSha256: hash(ca), serverSha256: 'a'.repeat(64) }
  })
  writeFileSync(path, bytes)
  vi.stubEnv('E2E_OWNED_RUNTIME', path)
  vi.stubEnv('E2E_OWNED_RUNTIME_SHA256', hash(bytes))
}
beforeEach(() => {
  directory = mkdtempSync(resolve('logs/mqtt-owned-unit-'))
  vi.stubEnv('E2E_OWNED_RUNTIME', undefined)
  vi.stubEnv('E2E_OWNED_RUNTIME_SHA256', undefined)
  vi.clearAllMocks()
})
afterEach(() => {
  vi.useRealTimers()
  vi.unstubAllEnvs()
  rmSync(directory, { recursive: true, force: true })
})
const input = {
  projectKey: 'synthetic-project',
  deviceKey: 'synthetic-device',
  secret: 'SYNTHETIC_MEMORY_ONLY',
  payload: '{}'
}
function socket() {
  const value = new EventEmitter(),
    write = vi.fn(),
    end = vi.fn(() => value.emit('close')),
    destroy = vi.fn(() => value.emit('close'))
  return Object.assign(value, { write, end, destroy })
}
it('owned参数真实选择严格TLS，secureConnect之前不发送MQTT认证', async () => {
  material()
  const connection = socket()
  sockets.tls.mockReturnValue(connection)
  const finished = publishCurrentValues({ ...input, port: 58884 })
  expect(sockets.net).not.toHaveBeenCalled()
  expect(sockets.tls).toHaveBeenCalledWith({
    host: '127.0.0.1',
    port: 58884,
    ca,
    servername: 'localhost',
    rejectUnauthorized: true,
    minVersion: 'TLSv1.2'
  })
  connection.emit('connect')
  expect(connection.write).not.toHaveBeenCalled()
  connection.emit('secureConnect')
  expect(connection.write).toHaveBeenCalledTimes(1)
  connection.emit('data', Buffer.from([0x20, 2, 0, 0]))
  expect(connection.write).toHaveBeenCalledTimes(2)
  connection.emit('data', Buffer.from([0x40, 2, 0, 1]))
  await finished
  expect(connection.end).toHaveBeenCalledWith(Buffer.from([0xe0, 0]))
})
it('owned错端口和未受核材料在任何socket与认证前拒绝', async () => {
  material()
  await expect(publishCurrentValues({ ...input, port: 51884 })).rejects.toThrow(
    'OWNED_MQTT_RUNTIME_REJECTED'
  )
  vi.stubEnv('E2E_OWNED_RUNTIME_SHA256', '0'.repeat(64))
  await expect(publishCurrentValues({ ...input, port: 58884 })).rejects.toThrow(
    'OWNED_MQTT_RUNTIME_REJECTED'
  )
  expect(sockets.net).not.toHaveBeenCalled()
  expect(sockets.tls).not.toHaveBeenCalled()
})
it('TLS握手失败只返回固定错误并且零认证字节', async () => {
  material()
  const connection = socket()
  sockets.tls.mockReturnValue(connection)
  const finished = publishCurrentValues(input)
  connection.emit('error', new Error('SYNTHETIC_SENSITIVE_LOWER_ERROR'))
  await expect(finished).rejects.toThrow('MQTT真实上报未取得确认或未完成关闭')
  expect(connection.write).not.toHaveBeenCalled()
  expect(sockets.net).not.toHaveBeenCalled()
})

it.each([
  ['ECONNREFUSED', 'SOCKET_ECONNREFUSED'],
  ['ECONNRESET', 'SOCKET_ECONNRESET'],
  ['EPIPE', 'SOCKET_EPIPE'],
  ['ETIMEDOUT', 'SOCKET_ETIMEDOUT'],
  ['ERR_TLS_CERT_ALTNAME_INVALID', 'SOCKET_ERR_TLS_CERT_ALTNAME_INVALID'],
  ['CERT_HAS_EXPIRED', 'SOCKET_CERT_HAS_EXPIRED'],
  ['SYNTHETIC_PRIVATE_CODE', 'SOCKET_OTHER']
])('握手异常code仅映射白名单%s', async (code, reason) => {
  material()
  const connection = socket()
  sockets.tls.mockReturnValue(connection)
  const finished = publishCurrentValues(input)
  connection.emit('error', Object.assign(new Error('SYNTHETIC_PRIVATE_MESSAGE'), { code }))
  await expect(finished).rejects.toThrow(`[stage=TLS_HANDSHAKE;reason=${reason}]`)
  await expect(finished).rejects.not.toThrow('SYNTHETIC_PRIVATE_MESSAGE')
  expect(connection.write).not.toHaveBeenCalled()
})
it.each([1, 2, 3, 4, 5, 255])('真实CONNACK拒绝固定RC分类%s而不发送属性报文', async (code) => {
  material()
  const connection = socket()
  sockets.tls.mockReturnValue(connection)
  const finished = publishCurrentValues(input)
  connection.emit('secureConnect')
  connection.emit('data', Buffer.from([0x20, 2, 0, code]))
  await expect(finished).rejects.toThrow(
    `[stage=CONNACK;reason=${code <= 5 ? `CONNACK_RC_${code}` : 'CONNACK_RC_INVALID'}]`
  )
  expect(connection.write).toHaveBeenCalledTimes(1)
})
it.each([
  [[0x20, 3, 0, 0], 'FRAME_LENGTH'],
  [[0x30, 2, 0, 0], 'FRAME_TYPE'],
  [[0x20, 2, 1, 0], 'CONNACK_FLAGS']
])('CONNACK协议错误不会被折成连接拒绝%j', async (bytes, reason) => {
  material()
  const connection = socket()
  sockets.tls.mockReturnValue(connection)
  const finished = publishCurrentValues(input)
  connection.emit('secureConnect')
  connection.emit('data', Buffer.from(bytes as number[]))
  await expect(finished).rejects.toThrow(`[stage=CONNACK;reason=${reason}]`)
  expect(connection.write).toHaveBeenCalledTimes(1)
})
it('错误PUBACK、超预算和提前关闭具有有限分类且不冒称成功', async () => {
  material()
  for (const kind of ['packet', 'budget', 'close']) {
    const connection = socket()
    sockets.tls.mockReturnValue(connection)
    const finished = publishCurrentValues(input)
    connection.emit('secureConnect')
    connection.emit('data', Buffer.from([0x20, 2, 0, 0]))
    if (kind === 'packet') connection.emit('data', Buffer.from([0x40, 2, 0, 2]))
    else if (kind === 'budget') connection.emit('data', Buffer.alloc(1025))
    else connection.emit('close')
    const reason =
      kind === 'packet' ? 'PUBACK_PACKET_ID' : kind === 'budget' ? 'FRAME_BUDGET' : 'EARLY_CLOSE'
    await expect(finished).rejects.toThrow(`[stage=PUBACK;reason=${reason}]`)
    expect(connection.end).not.toHaveBeenCalled()
  }
})
it.each(['TLS_HANDSHAKE', 'CONNACK', 'PUBACK', 'CLOSE'])(
  '保持15秒预算并标明超时阶段%s',
  async (stage) => {
    material()
    vi.useFakeTimers()
    const connection = socket()
    connection.end.mockImplementation(() => false)
    sockets.tls.mockReturnValue(connection)
    const finished = publishCurrentValues(input),
      assertion = expect(finished).rejects.toThrow(`[stage=${stage};reason=TIMEOUT]`)
    if (stage !== 'TLS_HANDSHAKE') connection.emit('secureConnect')
    if (stage === 'PUBACK' || stage === 'CLOSE')
      connection.emit('data', Buffer.from([0x20, 2, 0, 0]))
    if (stage === 'CLOSE') connection.emit('data', Buffer.from([0x40, 2, 0, 1]))
    vi.advanceTimersByTime(14_999)
    expect(connection.destroy).not.toHaveBeenCalled()
    vi.advanceTimersByTime(1)
    await assertion
    expect(connection.destroy).toHaveBeenCalledTimes(1)
  }
)
it('PUBACK后的socket错误仍失败且保留首次原因，不能只凭ACK通过', async () => {
  material()
  const connection = socket()
  connection.end.mockImplementation(() => false)
  sockets.tls.mockReturnValue(connection)
  const finished = publishCurrentValues(input)
  connection.emit('secureConnect')
  connection.emit('data', Buffer.from([0x20, 2, 0, 0]))
  connection.emit('data', Buffer.from([0x40, 2, 0, 1]))
  connection.emit(
    'error',
    Object.assign(new Error('SYNTHETIC_PRIVATE_MESSAGE'), { code: 'ECONNRESET' })
  )
  connection.emit('error', Object.assign(new Error('SYNTHETIC_SECOND_MESSAGE'), { code: 'EPIPE' }))
  await expect(finished).rejects.toThrow('[stage=CLOSE;reason=SOCKET_ECONNRESET]')
  expect(sockets.tls).toHaveBeenCalledTimes(1)
  expect(sockets.net).not.toHaveBeenCalled()
})
it('建socket同步异常与异常code getter都只形成固定错误且无cause', async () => {
  const error = Object.assign(new Error('SYNTHETIC_PRIVATE_MESSAGE'), { code: 'ECONNREFUSED' })
  sockets.net.mockImplementationOnce(() => {
    throw error
  })
  const first = publishCurrentValues(input)
  await expect(first).rejects.toThrow('[stage=CONNECT;reason=SOCKET_ECONNREFUSED]')
  const connection = socket()
  sockets.net.mockReturnValue(connection)
  const finished = publishCurrentValues(input),
    opaque = Object.defineProperty({}, 'code', {
      get() {
        throw new Error('SYNTHETIC_CODE_GETTER')
      }
    })
  connection.emit('error', opaque)
  await expect(finished).rejects.toThrow('[stage=CONNECT;reason=SOCKET_OTHER]')
  const failure = await finished.catch((failure: Error) => failure)
  expect(failure).toBeInstanceOf(Error)
  expect((failure as Error).cause).toBeUndefined()
})
