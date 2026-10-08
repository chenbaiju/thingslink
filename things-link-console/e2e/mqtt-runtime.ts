import { createHash } from 'node:crypto'
import { readFileSync, realpathSync, statSync } from 'node:fs'
import { isAbsolute, relative, resolve, sep } from 'node:path'
import { parseStrictPublicJson } from '../src/features/ota/strict-public-json'

export interface MqttTlsFixture {
  ca: Buffer
  servername: string
}
const failure = () => new Error('OWNED_MQTT_RUNTIME_REJECTED')
const hash = (value: Buffer) => createHash('sha256').update(value).digest('hex')
const digest = (value: unknown): value is string =>
  typeof value === 'string' && /^[0-9a-f]{64}$/.test(value)
function record(value: unknown): Record<string, unknown> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw failure()
  return value as Record<string, unknown>
}
function ownedFile(value: unknown): Buffer {
  if (typeof value !== 'string' || !isAbsolute(value)) throw failure()
  const root = realpathSync(resolve('logs')),
    path = realpathSync(value),
    suffix = relative(root, path),
    stat = statSync(path)
  if (
    !suffix ||
    isAbsolute(suffix) ||
    suffix === '..' ||
    suffix.startsWith(`..${sep}`) ||
    !stat.isFile() ||
    stat.size < 1 ||
    stat.size > 65_536
  )
    throw failure()
  const bytes = readFileSync(path)
  if (bytes.length < 1 || bytes.length > 65_536) throw failure()
  return bytes
}

/** 每次读取重新绑定完整公开材料摘要；缺项或材料漂移不回退明文，不捕获凭据。 */
export function readOwnedMqttRuntime():
  | { port: number; tls: MqttTlsFixture; simulatorPort: number }
  | undefined {
  const path = process.env.E2E_OWNED_RUNTIME
  if (path === undefined && process.env.E2E_OWNED_RUNTIME_SHA256 === undefined) return undefined
  try {
    const expected = process.env.E2E_OWNED_RUNTIME_SHA256,
      raw = ownedFile(path)
    if (!digest(expected) || hash(raw) !== expected) throw failure()
    const runtime = record(parseStrictPublicJson(raw)),
      tls = record(runtime.tls)
    if (
      runtime.qualification !== 'WP-03-DEFAULT-MATRIX' ||
      runtime.backendPort !== 8090 ||
      runtime.mqttPort !== 51884 ||
      runtime.mqttTlsPort !== 58884 ||
      runtime.simulatorPort !== 8092 ||
      !digest(tls.caSha256) ||
      !digest(tls.serverSha256)
    )
      throw failure()
    const ca = ownedFile(tls.caPath)
    if (hash(ca) !== tls.caSha256) throw failure()
    return { port: 58884, tls: { ca, servername: 'localhost' }, simulatorPort: 8092 }
  } catch {
    throw failure()
  }
}

/** 显式TLS保留其他受控夹具；否则独占模式必须使用本轮公开CA与严格TLS端口。 */
export function currentValueMqttRuntime(port?: number, tls?: MqttTlsFixture) {
  if (tls) return { port: port ?? 1883, tls }
  const owned = readOwnedMqttRuntime()
  if (owned) {
    if (port !== undefined && port !== owned.port) throw failure()
    return { port: owned.port, tls: owned.tls }
  }
  return { port: port ?? 1883, tls: undefined }
}

/** 三条模拟器旅程共享本轮控制面及Broker范围，stop/start均使用同一受核入口。 */
export function simulatorRuntime(): { baseURL: string; brokerUri: string } {
  try {
    const owned = readOwnedMqttRuntime(),
      baseURL = process.env.E2E_SIMULATOR_BASE_URL ?? 'http://localhost:8090',
      brokerUri = process.env.E2E_SIMULATOR_BROKER_URI ?? 'tcp://localhost:1883'
    if (owned) {
      const origin = new URL(baseURL)
      if (
        !process.env.E2E_SIMULATOR_BASE_URL ||
        !process.env.E2E_SIMULATOR_BROKER_URI ||
        origin.protocol !== 'http:' ||
        !['127.0.0.1', 'localhost'].includes(origin.hostname) ||
        origin.port !== String(owned.simulatorPort) ||
        origin.username ||
        origin.password ||
        origin.search ||
        origin.hash ||
        origin.pathname !== '/' ||
        brokerUri !== 'ssl://localhost:58884'
      )
        throw failure()
      return { baseURL: origin.origin, brokerUri }
    }
    return { baseURL: baseURL.replace(/\/$/, ''), brokerUri }
  } catch {
    throw failure()
  }
}
