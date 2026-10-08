import { createHash } from 'node:crypto'
import { execFileSync } from 'node:child_process'
import { readFile, realpath } from 'node:fs/promises'
import { isAbsolute, relative, resolve } from 'node:path'

export type ReviewedRole = 'OWNER' | 'ADMIN' | 'OPERATOR' | 'VIEWER'
export interface ReviewedDevice {
  id: string
  name: string
  key: string
  modelVersionId: string
}
export interface ReviewedSeed {
  tenantId: string
  actors: Record<ReviewedRole, { email: string; accountId: string }>
  projectA: { id: string; name: string }
  projectB: { id: string; name: string }
  deviceA: ReviewedDevice
  deviceOther: ReviewedDevice
  deviceB: ReviewedDevice
  unknownDevice: ReviewedDevice
  propertyKeys: string[]
  expectedResult: {
    summary: string
    statement: string
    limitation: string
    usage: {
      promptTokens: number
      completionTokens: number
      totalTokens: number
      cacheHitTokens: number
      cacheMissTokens: number
    }
  }
}
const classNames = [
  'com.things.link.assistant.application.FrozenSyntheticReview',
  'com.things.link.assistant.application.SyntheticReviewedTransport',
  'com.things.link.assistant.application.SyntheticReviewedTransport$Target',
  'com.things.link.bootstrap.assistant.fixture.SyntheticReviewedFixtureProcess',
  'com.things.link.bootstrap.assistant.fixture.SyntheticReviewedFixtureProcess$FixtureManifest',
  'com.things.link.bootstrap.assistant.fixture.SyntheticReviewedFixtureProcess$FixtureConfiguration'
]
const digest = (value: Buffer) => createHash('sha256').update(value).digest('hex')
const uuid = (value: unknown) =>
  typeof value === 'string' && /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/.test(value)
async function owned(value: string) {
  if (typeof value !== 'string' || !value) throw new Error('REVIEWED_RUNTIME_PATH_REQUIRED')
  const root = await realpath(resolve('logs')),
    path = await realpath(resolve(value)),
    suffix = relative(root, path)
  if (!suffix || isAbsolute(suffix) || suffix === '..' || suffix.startsWith('../'))
    throw new Error('REVIEWED_RUNTIME_PATH_OUTSIDE_LOGS')
  return path
}
function processGuard(pid: number, port: number, matches: (command: string) => boolean) {
  try {
    if (!Number.isSafeInteger(pid) || pid <= 1) throw new Error('invalid')
    const command = execFileSync('ps', ['-p', String(pid), '-o', 'command='], {
      encoding: 'utf8',
      timeout: 3_000
    })
    const listener = execFileSync(
      'lsof',
      ['-nP', '-a', '-p', String(pid), `-iTCP:${port}`, '-sTCP:LISTEN', '-t'],
      { encoding: 'utf8', timeout: 3_000 }
    )
    if (listener.trim() !== String(pid) || !matches(command)) throw new Error('invalid')
  } catch {
    throw new Error('REVIEWED_RUNTIME_PROCESS_OR_LISTENER_MISMATCH')
  }
}
export async function guardReviewedRuntime(baseURL: string): Promise<ReviewedSeed> {
  const runtimePath = await owned(process.env.E2E_AGENT_REVIEWED_RUNTIME!),
    seedPath = await owned(process.env.E2E_AGENT_REVIEWED_SEED!),
    runtime = JSON.parse(await readFile(runtimePath, 'utf8')),
    seedBytes = await readFile(seedPath),
    seed: ReviewedSeed = JSON.parse(seedBytes.toString('utf8')),
    origin = new URL(baseURL)
  if (
    runtime.qualification !== '010-C-SYNTHETIC-REVIEWED-INTERACTION' ||
    runtime.testTransport !== 'SYNTHETIC_NO_EGRESS' ||
    runtime.apiBase !== 'http://127.0.0.1:8089' ||
    runtime.backendPort !== 8089 ||
    runtime.vitePort !== 3018 ||
    origin.protocol !== 'http:' ||
    !['localhost', '127.0.0.1'].includes(origin.hostname) ||
    origin.port !== '3018' ||
    !/^tc_console_010c_[A-Za-z0-9_]+$/.test(runtime.database) ||
    runtime.postgresContainer !== 'tc-console-010c-pg' ||
    runtime.postgresPort !== 5548 ||
    runtime.redisContainer !== 'tc-console-010c-redis' ||
    runtime.redisPort !== 56389 ||
    runtime.redisDatabase !== 0 ||
    !/^[0-9a-f]{64}$/.test(runtime.postgresContainerId) ||
    !/^sha256:[0-9a-f]{64}$/.test(runtime.postgresImageDigest) ||
    typeof runtime.postgresUser !== 'string' ||
    !/^[A-Za-z0-9_]+$/.test(runtime.postgresUser)
  )
    throw new Error('REVIEWED_RUNTIME_SCOPE_REJECTED')
  const jar = await owned(runtime.frozenJar),
    classRoot = await owned(runtime.testClasspathRoot)
  if (
    jar !== (await owned(runtime.jarPath ?? runtime.frozenJar)) ||
    digest(await readFile(jar)) !== runtime.jarSha256 ||
    seedPath !== (await owned(runtime.fixturePath)) ||
    digest(seedBytes) !== runtime.fixtureSha256 ||
    digest(await readFile(await owned(runtime.configPath))) !== runtime.configSha256 ||
    digest(await readFile(await owned(runtime.scopeFixturePath))) !== runtime.scopeFixtureSha256 ||
    !/^[0-9a-f]{64}$/.test(runtime.effectiveConfigSha256) ||
    !Array.isArray(runtime.testClasses) ||
    runtime.testClasses.length !== classNames.length ||
    new Set(runtime.testClasses.map((value: any) => value.className)).size !== classNames.length
  )
    throw new Error('REVIEWED_RUNTIME_FROZEN_MATERIAL_MISMATCH')
  for (const entry of runtime.testClasses) {
    if (!classNames.includes(entry.className))
      throw new Error('REVIEWED_RUNTIME_CLASS_NOT_ALLOWLISTED')
    const path = await owned(entry.path)
    if (
      path !== resolve(classRoot, entry.className.replaceAll('.', '/') + '.class') ||
      digest(await readFile(path)) !== entry.sha256
    )
      throw new Error('REVIEWED_RUNTIME_TEST_CLASS_MISMATCH')
  }
  processGuard(
    runtime.pid,
    8089,
    (command) =>
      command.includes(jar) &&
      command.includes(classRoot) &&
      command.includes(
        'com.things.link.bootstrap.assistant.fixture.SyntheticReviewedFixtureProcess'
      )
  )
  const consoleRoot = await realpath(resolve('.'))
  processGuard(
    runtime.vitePid,
    3018,
    (command) => command.includes('vite') && command.includes(consoleRoot)
  )
  try {
    const format =
      '{"Id":{{json .Id}},"Image":{{json .Image}},"State":{{json .State.Running}},"Labels":{{json .Config.Labels}},"Ports":{{json .NetworkSettings.Ports}}}'
    const pg = JSON.parse(
      execFileSync('docker', ['inspect', '--format', format, runtime.postgresContainer], {
        encoding: 'utf8',
        timeout: 5_000
      })
    )
    const redis = JSON.parse(
      execFileSync('docker', ['inspect', '--format', format, runtime.redisContainer], {
        encoding: 'utf8',
        timeout: 5_000
      })
    )
    if (
      pg.Id !== runtime.postgresContainerId ||
      pg.Image !== runtime.postgresImageDigest ||
      pg.State !== true ||
      pg.Labels?.['thingslink.owner'] !== 'console-010-c' ||
      redis.Labels?.['thingslink.owner'] !== 'console-010-c' ||
      !Array.isArray(pg.Ports?.['5432/tcp']) ||
      !pg.Ports['5432/tcp'].length ||
      pg.Ports['5432/tcp'].some((value: any) => value.HostPort !== '5548') ||
      redis.Id !== runtime.redisContainerId ||
      redis.Image !== runtime.redisImageDigest ||
      redis.State !== true ||
      !Array.isArray(redis.Ports?.['6379/tcp']) ||
      !redis.Ports['6379/tcp'].length ||
      redis.Ports['6379/tcp'].some((value: any) => value.HostPort !== '56389')
    )
      throw new Error('invalid')
    const database = execFileSync(
      'docker',
      [
        'exec',
        runtime.postgresContainer,
        'psql',
        '-U',
        runtime.postgresUser,
        '-d',
        runtime.database,
        '-At',
        '-c',
        'SELECT current_database()'
      ],
      { encoding: 'utf8', timeout: 5_000 }
    )
    if (database.trim() !== runtime.database) throw new Error('invalid')
  } catch {
    throw new Error('REVIEWED_RUNTIME_POSTGRES_ID_IMAGE_PORT_OR_DATABASE_MISMATCH')
  }
  if (
    !uuid(seed.tenantId) ||
    !uuid(seed.projectA?.id) ||
    !uuid(seed.projectB?.id) ||
    seed.projectA.id === seed.projectB.id ||
    !seed.projectA.name ||
    !seed.projectB.name ||
    ['OWNER', 'ADMIN', 'OPERATOR', 'VIEWER'].some((role) => {
      const actor = seed.actors?.[role as ReviewedRole]
      return (
        !actor ||
        !uuid(actor.accountId) ||
        typeof actor.email !== 'string' ||
        !actor.email.endsWith('@example.com')
      )
    }) ||
    [seed.deviceA, seed.deviceOther, seed.deviceB, seed.unknownDevice].some(
      (device) =>
        !device || !uuid(device.id) || !uuid(device.modelVersionId) || !device.name || !device.key
    ) ||
    new Set([seed.deviceA.id, seed.deviceOther.id, seed.deviceB.id, seed.unknownDevice.id]).size !==
      4 ||
    !Array.isArray(seed.propertyKeys) ||
    !seed.propertyKeys.length ||
    seed.propertyKeys.length > 10 ||
    seed.propertyKeys.some((value) => !/^[A-Za-z0-9_-]{1,64}$/.test(value)) ||
    !/<(?:b|script)>/.test(seed.expectedResult?.summary ?? '') ||
    !seed.expectedResult.statement ||
    !seed.expectedResult.limitation ||
    !seed.expectedResult.usage ||
    Object.values(seed.expectedResult.usage).some(
      (value) => !Number.isSafeInteger(value) || value < 0
    )
  )
    throw new Error('REVIEWED_PUBLIC_FIXTURE_INVALID')
  return seed
}
