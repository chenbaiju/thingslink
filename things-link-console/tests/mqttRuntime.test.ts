import { createHash } from 'node:crypto'
import { mkdtempSync, rmSync, symlinkSync, writeFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import {
  currentValueMqttRuntime,
  readOwnedMqttRuntime,
  simulatorRuntime
} from '../e2e/mqtt-runtime'

let directory: string
let outsideDirectory: string | undefined
const hash = (value: Buffer | string) => createHash('sha256').update(value).digest('hex')
const ca = Buffer.from('PUBLIC_SYNTHETIC_CA_BYTES_FOR_PURE_GUARD_TEST_ONLY')
const runtime = () => ({
  qualification: 'WP-03-DEFAULT-MATRIX',
  backendPort: 8090,
  mqttPort: 51884,
  mqttTlsPort: 58884,
  simulatorPort: 8092,
  tls: {
    caPath: resolve(directory, 'ca.pem'),
    caSha256: hash(ca),
    serverSha256: 'a'.repeat(64)
  },
  // 完整运行守卫另核容器和进程，本子域不删除或忽略它们的原始摘要绑定。
  extraPublicArtifact: { sha256: 'b'.repeat(64) }
})
function material(value: unknown = runtime()) {
  const bytes = typeof value === 'string' ? value : JSON.stringify(value),
    path = resolve(directory, 'runtime.json')
  writeFileSync(path, bytes)
  vi.stubEnv('E2E_OWNED_RUNTIME', path)
  vi.stubEnv('E2E_OWNED_RUNTIME_SHA256', hash(bytes))
  return path
}
beforeEach(() => {
  directory = mkdtempSync(resolve('logs/mqtt-guard-unit-'))
  writeFileSync(resolve(directory, 'ca.pem'), ca)
  vi.stubEnv('E2E_OWNED_RUNTIME', undefined)
  vi.stubEnv('E2E_OWNED_RUNTIME_SHA256', undefined)
  vi.stubEnv('E2E_SIMULATOR_BASE_URL', undefined)
  vi.stubEnv('E2E_SIMULATOR_BROKER_URI', undefined)
})
afterEach(() => {
  vi.unstubAllEnvs()
  if (outsideDirectory) rmSync(outsideDirectory, { recursive: true, force: true })
  outsideDirectory = undefined
  rmSync(directory, { recursive: true, force: true })
})

it('legacy默认保持原端口和明文模拟器，但owned只返回本轮受核TLS', () => {
  expect(currentValueMqttRuntime()).toEqual({ port: 1883, tls: undefined })
  expect(simulatorRuntime()).toEqual({
    baseURL: 'http://localhost:8090',
    brokerUri: 'tcp://localhost:1883'
  })
  material()
  expect(currentValueMqttRuntime(58884)).toEqual({
    port: 58884,
    tls: { ca, servername: 'localhost' }
  })
  expect(currentValueMqttRuntime()).toEqual(currentValueMqttRuntime(58884))
  expect(() => currentValueMqttRuntime(51884)).toThrow('OWNED_MQTT_RUNTIME_REJECTED')
  expect(() => currentValueMqttRuntime(1883)).toThrow('OWNED_MQTT_RUNTIME_REJECTED')
})
it('完整manifest摘要或CA漂移每次重新读取均拒绝且不回退', () => {
  const path = material()
  expect(readOwnedMqttRuntime()?.port).toBe(58884)
  writeFileSync(
    path,
    JSON.stringify({ ...runtime(), extraPublicArtifact: { sha256: 'c'.repeat(64) } })
  )
  expect(() => readOwnedMqttRuntime()).toThrow('OWNED_MQTT_RUNTIME_REJECTED')
  material()
  writeFileSync(resolve(directory, 'ca.pem'), 'OTHER_PUBLIC_CA')
  expect(() => readOwnedMqttRuntime()).toThrow('OWNED_MQTT_RUNTIME_REJECTED')
})
it('不能用相对、日志外或symlink外逸路径读取CA', () => {
  for (const caPath of ['ca.pem', '/etc/hosts']) {
    material({ ...runtime(), tls: { ...runtime().tls, caPath } })
    expect(() => readOwnedMqttRuntime()).toThrow('OWNED_MQTT_RUNTIME_REJECTED')
  }
  // 使用同盘真实目录链接验证日志外CA，即使字节和摘要正确也必须拒绝。
  outsideDirectory = mkdtempSync(resolve('logs', '..', 'mqtt-guard-outside-'))
  writeFileSync(resolve(outsideDirectory, 'ca.pem'), ca)
  symlinkSync(
    outsideDirectory,
    resolve(directory, 'outside'),
    process.platform === 'win32' ? 'junction' : 'dir'
  )
  material({
    ...runtime(),
    tls: { ...runtime().tls, caPath: resolve(directory, 'outside', 'ca.pem') }
  })
  expect(() => readOwnedMqttRuntime()).toThrow('OWNED_MQTT_RUNTIME_REJECTED')
  vi.stubEnv('E2E_OWNED_RUNTIME', '/etc/hosts')
  expect(() => readOwnedMqttRuntime()).toThrow('OWNED_MQTT_RUNTIME_REJECTED')
})
it.each([
  ['qualification', '010-C-SYNTHETIC-REVIEWED-INTERACTION'],
  ['backendPort', 8088],
  ['mqttPort', 1883],
  ['mqttTlsPort', 58883],
  ['simulatorPort', 8090],
  ['simulatorPort', '8092']
])('拒绝错运行范围%s', (field, value) => {
  material({ ...runtime(), [field]: value })
  expect(() => readOwnedMqttRuntime()).toThrow('OWNED_MQTT_RUNTIME_REJECTED')
})
it('缺hash、缺runtime、错CAhash或非法JSON固定拒绝不带材料', () => {
  material()
  vi.stubEnv('E2E_OWNED_RUNTIME_SHA256', undefined)
  expect(() => readOwnedMqttRuntime()).toThrow('OWNED_MQTT_RUNTIME_REJECTED')
  material()
  vi.stubEnv('E2E_OWNED_RUNTIME', undefined)
  expect(() => readOwnedMqttRuntime()).toThrow('OWNED_MQTT_RUNTIME_REJECTED')
  material({ ...runtime(), tls: { ...runtime().tls, caSha256: 'NOT_HASH' } })
  expect(() => readOwnedMqttRuntime()).toThrow('OWNED_MQTT_RUNTIME_REJECTED')
  material('{"qualification":"WP-03-DEFAULT-MATRIX","qualification":"OTHER"}')
  expect(() => readOwnedMqttRuntime()).toThrow('OWNED_MQTT_RUNTIME_REJECTED')
  material(' '.repeat(65_537))
  expect(() => readOwnedMqttRuntime()).toThrow('OWNED_MQTT_RUNTIME_REJECTED')
})
it('owned模拟器只能使用显式新控制面与严格TLS Broker', () => {
  material()
  expect(() => simulatorRuntime()).toThrow('OWNED_MQTT_RUNTIME_REJECTED')
  vi.stubEnv('E2E_SIMULATOR_BASE_URL', 'http://127.0.0.1:8092')
  vi.stubEnv('E2E_SIMULATOR_BROKER_URI', 'ssl://localhost:58884')
  expect(simulatorRuntime()).toEqual({
    baseURL: 'http://127.0.0.1:8092',
    brokerUri: 'ssl://localhost:58884'
  })
  for (const value of [
    'http://127.0.0.1:8090',
    'http://user@127.0.0.1:8092',
    'http://127.0.0.1:8092/api',
    'http://127.0.0.1:8092/?query=1',
    'http://127.0.0.1:8092/#hash',
    'http://external.example:8092'
  ]) {
    vi.stubEnv('E2E_SIMULATOR_BASE_URL', value)
    expect(() => simulatorRuntime()).toThrow('OWNED_MQTT_RUNTIME_REJECTED')
  }
  vi.stubEnv('E2E_SIMULATOR_BASE_URL', 'http://127.0.0.1:8092')
  for (const value of [
    'tcp://localhost:51884',
    'ssl://localhost:58883',
    'ssl://user@localhost:58884'
  ]) {
    vi.stubEnv('E2E_SIMULATOR_BROKER_URI', value)
    expect(() => simulatorRuntime()).toThrow('OWNED_MQTT_RUNTIME_REJECTED')
  }
})
it('其他显式受控TLS输入保持原能力，不从owned材料覆盖', () => {
  vi.stubEnv('E2E_OWNED_RUNTIME', '/not-used')
  const tls = { ca: Buffer.from('OTHER_PUBLIC_TEST_CA'), servername: 'other.test' }
  expect(currentValueMqttRuntime(54433, tls)).toEqual({ port: 54433, tls })
})
