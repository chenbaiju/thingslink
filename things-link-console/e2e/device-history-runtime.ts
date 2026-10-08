import { createHash } from 'node:crypto'
import { execFileSync } from 'node:child_process'
import { readFile, realpath } from 'node:fs/promises'
import { isAbsolute, relative, resolve } from 'node:path'

interface SeedMessage {
  messageId: string
  eventKey: 'alarm' | 'empty'
  temperatureText: string | null
}
export interface Seed {
  status: string
  sourceJarSha256: string
  anchorSource: 'NEW_C_V2'
  exactNumericText: boolean
  projectId: string
  projectName: string
  tenantId: string
  deviceId: string
  deviceName: string
  deviceKey: string
  thingModelVersionId: string
  modelVersion: string
  originalAlarmMessageId: string
  occurredAt: string
  eventCount: number
  messages: SeedMessage[]
}
const sha = (bytes: Buffer) => createHash('sha256').update(bytes).digest('hex')
const uuid = (value: unknown): value is string =>
  typeof value === 'string' && /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/.test(value)
async function ownedPath(value: string) {
  if (typeof value !== 'string' || !value) throw new Error('EVENT_OWNED_PATH_REQUIRED')
  const root = await realpath(resolve('logs')),
    target = await realpath(resolve(value)),
    suffix = relative(root, target)
  if (!suffix || isAbsolute(suffix) || suffix === '..' || suffix.startsWith('../'))
    throw new Error('EVENT_PATH_OUTSIDE_OWNED_LOGS')
  return target
}
function inspect(name: string) {
  try {
    // Docker只返回公开身份/镜像/端口/标签，避免读取容器环境中的认证材料。
    const format =
      '{"Id":{{json .Id}},"Image":{{json .Image}},"Config":{"Image":{{json .Config.Image}},"Labels":{{json .Config.Labels}}},"State":{"Running":{{json .State.Running}}},"NetworkSettings":{"Ports":{{json .NetworkSettings.Ports}}}}'
    return JSON.parse(
      execFileSync('docker', ['inspect', '--format', format, name], {
        encoding: 'utf8',
        timeout: 5_000
      })
    )
  } catch {
    throw new Error('EVENT_CONTAINER_INSPECTION_FAILED')
  }
}
function port(container: any, target: string, host: number) {
  const entries = container.NetworkSettings?.Ports?.[target]
  return (
    Array.isArray(entries) &&
    entries.length > 0 &&
    entries.every((entry: any) => entry.HostPort === String(host))
  )
}
function labels(container: any, expected: Record<string, string>) {
  return Object.entries(expected).every(([key, value]) => container.Config?.Labels?.[key] === value)
}
export async function guardDeviceHistoryRuntime(
  baseURL: string,
  scope: {
    runtimePath: string
    seedPath: string
    qualification: 'BE-001-C-HISTORY' | 'BE-002-A-DEVICE-TYPE'
  }
) {
  const runtimePath = await ownedPath(scope.runtimePath),
    seedPath = await ownedPath(scope.seedPath),
    runtime = JSON.parse(await readFile(runtimePath, 'utf8')),
    seedBytes = await readFile(seedPath),
    seed: Seed = JSON.parse(seedBytes.toString('utf8')),
    origin = new URL(baseURL)
  if (
    origin.protocol !== 'http:' ||
    !['localhost', '127.0.0.1'].includes(origin.hostname) ||
    origin.port !== '3017' ||
    runtime.qualification !== scope.qualification ||
    !/^tc_console_012a_[A-Za-z0-9_]+$/.test(runtime.database) ||
    runtime.apiBase !== 'http://127.0.0.1:8088' ||
    runtime.backendPort !== 8088 ||
    runtime.vitePort !== 3017 ||
    runtime.redisDatabase !== 14 ||
    runtime.postgresContainer !== 'tc-console-012a-pg' ||
    runtime.postgresPort !== 5547 ||
    runtime.emqxContainer !== 'tc-console-016c-emqx' ||
    runtime.kafkaContainer !== 'tc-console-016c-kafka' ||
    runtime.mqttPort !== 51883 ||
    runtime.mqttTlsPort !== 58883 ||
    runtime.dashboardPort !== 58083 ||
    runtime.kafkaPort !== 59092 ||
    !Number.isSafeInteger(runtime.pid) ||
    runtime.pid <= 1 ||
    !/^[0-9a-f]{64}$/.test(runtime.jarSha256) ||
    !/^[0-9a-f]{64}$/.test(runtime.postgresContainerId) ||
    !/^sha256:[0-9a-f]{64}$/.test(runtime.postgresImageDigest) ||
    runtime.emqxContainerLabels?.['thingslink.owner'] !== 'console-016-c' ||
    runtime.kafkaContainerLabels?.['thingslink.owner'] !== 'console-016-c'
  )
    throw new Error('EVENT_RUNTIME_SCOPE_REJECTED')
  const jar = await ownedPath(runtime.frozenJar),
    config = await ownedPath(runtime.configPath),
    fixturePath = await ownedPath(runtime.fixturePath)
  if (
    jar !== (await ownedPath(runtime.jarPath)) ||
    seedPath !== (await ownedPath(runtime.seedPath)) ||
    sha(await readFile(jar)) !== runtime.jarSha256 ||
    sha(await readFile(config)) !== runtime.configSha256 ||
    sha(await readFile(fixturePath)) !== runtime.fixtureSha256 ||
    sha(seedBytes) !== runtime.seedSha256 ||
    sha(await readFile(await ownedPath(runtime.mqttCaPath))) !== runtime.mqttCaSha256
  )
    throw new Error('EVENT_FROZEN_MATERIAL_DRIFT')
  const fixture = JSON.parse(await readFile(fixturePath, 'utf8'))
  if (
    seed.status !== 'PASS' ||
    seed.anchorSource !== 'NEW_C_V2' ||
    seed.exactNumericText !== true ||
    (scope.qualification === 'BE-001-C-HISTORY' && seed.sourceJarSha256 !== runtime.jarSha256) ||
    seed.sourceJarSha256 !== runtime.seedSourceJarSha256 ||
    !/^[0-9a-f]{64}$/.test(seed.sourceJarSha256) ||
    ![
      seed.projectId,
      seed.tenantId,
      seed.deviceId,
      seed.thingModelVersionId,
      seed.originalAlarmMessageId
    ].every(uuid) ||
    fixture.projectId !== seed.projectId ||
    fixture.tenantId !== seed.tenantId ||
    runtime.projectId !== seed.projectId ||
    !seed.projectName ||
    !seed.deviceName ||
    !seed.deviceKey ||
    seed.modelVersion !== '1.0.0' ||
    seed.eventCount !== 29 ||
    !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$/.test(seed.occurredAt) ||
    !Array.isArray(seed.messages) ||
    seed.messages.length !== 27 ||
    new Set(seed.messages.map((value) => value.messageId)).size !== 27 ||
    seed.messages.filter((value) => value.eventKey === 'alarm').length !== 25 ||
    seed.messages.filter((value) => value.eventKey === 'empty').length !== 2 ||
    seed.messages.some(
      (value) =>
        !uuid(value.messageId) ||
        (value.eventKey === 'alarm'
          ? !/^(?:\d{38}|0\.\d{38})$/.test(value.temperatureText ?? '')
          : value.temperatureText !== null)
    )
  )
    throw new Error('EVENT_PUBLIC_SEED_INVALID_OR_NOT_READY')
  try {
    const command = execFileSync('ps', ['-p', String(runtime.pid), '-o', 'command='], {
      encoding: 'utf8',
      timeout: 3_000
    })
    const listener = execFileSync(
      'lsof',
      ['-nP', '-a', '-p', String(runtime.pid), '-iTCP:8088', '-sTCP:LISTEN', '-t'],
      { encoding: 'utf8', timeout: 3_000 }
    )
    if (!command.includes(jar) || listener.trim() !== String(runtime.pid))
      throw new Error('invalid')
  } catch {
    throw new Error('EVENT_BACKEND_PID_OR_LISTENER_MISMATCH')
  }
  const pg = inspect(runtime.postgresContainer),
    broker = inspect(runtime.emqxContainer),
    kafka = inspect(runtime.kafkaContainer)
  if (
    pg.Id !== runtime.postgresContainerId ||
    pg.Image !== runtime.postgresImageDigest ||
    pg.State?.Running !== true ||
    !labels(pg, runtime.postgresContainerLabels ?? {}) ||
    !port(pg, '5432/tcp', 5547) ||
    broker.State?.Running !== true ||
    kafka.State?.Running !== true ||
    broker.Config?.Image !== runtime.emqxImage ||
    kafka.Config?.Image !== runtime.kafkaImage ||
    !labels(broker, runtime.emqxContainerLabels) ||
    !labels(kafka, runtime.kafkaContainerLabels) ||
    !port(broker, '1883/tcp', 51883) ||
    !port(broker, '8883/tcp', 58883) ||
    !port(broker, '18083/tcp', 58083) ||
    !port(kafka, '19092/tcp', 59092)
  )
    throw new Error('EVENT_CONTAINER_ID_IMAGE_LABEL_OR_PORT_MISMATCH')
  return seed
}
