import { createHash } from 'node:crypto'
import { execFileSync } from 'node:child_process'
import { readFileSync, realpathSync, statSync } from 'node:fs'
import { isAbsolute, relative, resolve, sep } from 'node:path'
import { fileURLToPath } from 'node:url'
import { parseDashboardRuntimeResponse } from '@things-link/client-contracts/dashboard/v1'
import { executeDatabaseFixture, parseOwnedDatabaseRuntime } from './database-fixture'

const consoleRoot = fileURLToPath(new URL('..', import.meta.url))
const repoRoot = resolve(consoleRoot, '..')
const hash = (value: Buffer | string) => createHash('sha256').update(value).digest('hex')
const hex = (value: unknown): value is string =>
  typeof value === 'string' && /^[0-9a-f]{64}$/.test(value)
const fail = () => new Error('OWNED_RUNTIME_QUALIFICATION_REJECTED')
const inspectFormat =
  '{"Id":{{json .Id}},"Name":{{json .Name}},"Image":{{json .Image}},"ConfigImage":{{json .Config.Image}},"Running":{{json .State.Running}},"Labels":{{json .Config.Labels}},"Ports":{{json .NetworkSettings.Ports}}}'

export interface OwnedRuntimeIO {
  realpath(path: string): string
  read(path: string, maximum: number): Buffer
  command(program: string, args: string[]): string
  database(env: NodeJS.ProcessEnv): string
}
const defaultIO: OwnedRuntimeIO = {
  realpath: realpathSync,
  read(path, maximum) {
    if (!statSync(path).isFile() || statSync(path).size > maximum) throw fail()
    return readFileSync(path)
  },
  command(program, args) {
    return execFileSync(program, args, {
      encoding: 'utf8',
      timeout: 5_000,
      maxBuffer: 256 * 1024,
      stdio: ['ignore', 'pipe', 'pipe']
    }).trim()
  },
  database: (env) =>
    executeDatabaseFixture(
      { legacy: 'explicit', statement: 'SELECT 1;', timeout: 5_000, maxBuffer: 4_096 },
      env
    )
}

function within(root: string, path: string, io: OwnedRuntimeIO) {
  if (!isAbsolute(path)) throw fail()
  const actual = io.realpath(path),
    suffix = relative(io.realpath(root), actual)
  if (!suffix || isAbsolute(suffix) || suffix === '..' || suffix.startsWith(`..${sep}`))
    throw fail()
  return actual
}
function material(path: string, expected: string, io: OwnedRuntimeIO, maximum = 512 * 1024 * 1024) {
  if (!hex(expected)) throw fail()
  const actual = within(resolve(consoleRoot, 'logs'), path, io)
  if (hash(io.read(actual, maximum)) !== expected) throw fail()
  return actual
}
function verifySources(entries: any, io: OwnedRuntimeIO) {
  if (!Array.isArray(entries) || !entries.length || entries.length > 32768) throw fail()
  const paths = new Set<string>()
  for (const entry of entries) {
    if (
      !entry ||
      Object.keys(entry).sort().join(',') !== 'path,sha256' ||
      typeof entry.path !== 'string' ||
      isAbsolute(entry.path) ||
      entry.path.includes('\\') ||
      entry.path.split('/').some((part: string) => !part || part === '..' || part === '.') ||
      paths.has(entry.path) ||
      !hex(entry.sha256)
    )
      throw fail()
    paths.add(entry.path)
    const path = within(repoRoot, resolve(repoRoot, entry.path), io)
    if (hash(io.read(path, 16 * 1024 * 1024)) !== entry.sha256) throw fail()
  }
  return paths
}
function processGuard(
  descriptor: any,
  port: number,
  io: OwnedRuntimeIO,
  expectedCwd: string | undefined,
  matches: (tokens: string[], command: string) => boolean
) {
  if (
    !descriptor ||
    !Number.isSafeInteger(descriptor.pid) ||
    descriptor.pid <= 1 ||
    !hex(descriptor.commandSha256) ||
    !isAbsolute(descriptor.cwd)
  )
    throw fail()
  const cwd = io.realpath(descriptor.cwd)
  if (expectedCwd && cwd !== io.realpath(expectedCwd)) throw fail()
  const command = io.command('ps', ['-p', String(descriptor.pid), '-o', 'command=']).trim()
  // 只接受未引用、无空白路径的本片启动命令；不会猜测不同shell的引号语义。
  const tokens = command.split(/\s+/)
  if (
    hash(command) !== descriptor.commandSha256 ||
    !matches(tokens, command) ||
    io.command('lsof', [
      '-nP',
      '-a',
      '-p',
      String(descriptor.pid),
      `-iTCP:${port}`,
      '-sTCP:LISTEN',
      '-t'
    ]) !== String(descriptor.pid)
  )
    throw fail()
  const actualCwd = io
    .command('lsof', ['-a', '-p', String(descriptor.pid), '-d', 'cwd', '-Fn'])
    .split('\n')
    .filter((line) => line.startsWith('n'))
    .map((line) => line.slice(1))
  if (actualCwd.length !== 1 || io.realpath(actualCwd[0]!) !== cwd) throw fail()
}

/** 浏览器启动前只读取公开身份和冻结材料；任何未知或漂移均固定错误且不回退共享栈。 */
export function verifyOwnedRuntime(env = process.env, io = defaultIO) {
  try {
    if (
      !env.E2E_OWNED_RUNTIME ||
      !hex(env.E2E_OWNED_RUNTIME_SHA256) ||
      env.E2E_BASE_URL !== 'http://127.0.0.1:3019' ||
      env.CONTRACT_BASE_URL !== 'http://127.0.0.1:8090'
    )
      throw fail()
    const runtimePath = within(
      resolve(consoleRoot, 'logs'),
      resolve(consoleRoot, env.E2E_OWNED_RUNTIME),
      io
    )
    const bytes = io.read(runtimePath, 16 * 1024)
    if (!bytes.length || hash(bytes) !== env.E2E_OWNED_RUNTIME_SHA256) throw fail()
    parseDashboardRuntimeResponse(bytes)
    const runtime = JSON.parse(bytes.toString('utf8'))
    const pg = parseOwnedDatabaseRuntime(bytes, env)
    if (
      runtime.qualification !== 'WP-03-DEFAULT-MATRIX' ||
      !/^[0-9a-f]{40}$/.test(runtime.sourceCommit ?? '') ||
      io.command('git', ['-C', repoRoot, 'rev-parse', 'HEAD']) !== runtime.sourceCommit ||
      runtime.backendPort !== 8090 ||
      runtime.vitePort !== 3019 ||
      runtime.webappPort !== 4018 ||
      runtime.redisPort !== 56390 ||
      runtime.redisDatabase !== 0 ||
      runtime.mqttPort !== 51884 ||
      runtime.mqttTlsPort !== 58884 ||
      runtime.brokerApiPort !== 58084 ||
      runtime.kafkaPort !== 59093 ||
      runtime.simulatorPort !== 8092 ||
      !hex(runtime.effectiveConfigSha256)
    )
      throw fail()
    const jar = material(runtime.frozenJar, runtime.jarSha256, io)
    material(runtime.configPath, runtime.configSha256, io, 128 * 1024)
    const descriptor = material(
      runtime.hostDescriptorPath,
      runtime.hostDescriptorSha256,
      io,
      128 * 1024
    )
    within(io.realpath(runtime.hostRegistry), descriptor, io)
    const paths = verifySources(runtime.sourceFiles, io)
    const snapshotPath = material(
      runtime.sourceSnapshotPath,
      runtime.sourceSnapshotSha256,
      io,
      2 * 1024 * 1024
    )
    const snapshotBytes = io.read(snapshotPath, 2 * 1024 * 1024)
    parseDashboardRuntimeResponse(snapshotBytes)
    const snapshot = JSON.parse(snapshotBytes.toString('utf8'))
    if (
      Object.keys(snapshot).sort().join(',') !== 'files,sourceCommit' ||
      snapshot.sourceCommit !== runtime.sourceCommit
    )
      throw fail()
    const snapshotPaths = verifySources(snapshot.files, io)
    for (const path of paths) if (!snapshotPaths.has(path)) throw fail()
    for (const path of [
      'things-link-console/e2e/owned-runtime-setup.ts',
      'things-link-console/playwright.config.ts',
      'things-link-console/vite.config.ts'
    ]) {
      if (!paths.has(path)) throw fail()
    }
    if (
      runtime.backend?.pid !== runtime.pid ||
      new Set([runtime.pid, runtime.vite?.pid, runtime.webapp?.pid]).size !== 3
    )
      throw fail()
    processGuard(
      runtime.backend,
      8090,
      io,
      undefined,
      (tokens) =>
        /(?:^|\/)java$/.test(tokens[0] ?? '') &&
        tokens[tokens.indexOf('-jar') + 1] === jar &&
        tokens.includes('-jar')
    )
    for (const [role, port, directory] of [
      ['vite', 3019, consoleRoot],
      ['webapp', 4018, resolve(repoRoot, 'things-link-webapp')]
    ] as const) {
      const descriptor = runtime[role]
      if (
        !descriptor ||
        descriptor.proxyTarget !== env.CONTRACT_BASE_URL ||
        !isAbsolute(descriptor.nodePath) ||
        !isAbsolute(descriptor.scriptPath)
      )
        throw fail()
      const node = io.realpath(descriptor.nodePath),
        script = within(directory, descriptor.scriptPath, io)
      if (!/(?:^|\/)node$/.test(node) || !script.endsWith('/vite/bin/vite.js')) throw fail()
      processGuard(
        descriptor,
        port,
        io,
        directory,
        (tokens) =>
          isAbsolute(tokens[0] ?? '') &&
          io.realpath(tokens[0]!) === node &&
          isAbsolute(tokens[1] ?? '') &&
          io.realpath(tokens[1]!) === script &&
          tokens.includes('--strictPort') &&
          tokens[tokens.indexOf('--port') + 1] === String(port) &&
          tokens.includes('--port')
      )
    }
    const roles = {
      postgres: 'tc-console-wp03-pg',
      redis: 'tc-console-wp03-redis',
      emqx: 'tc-console-wp03-emqx',
      kafka: 'tc-console-wp03-kafka'
    }
    if (
      !runtime.containers ||
      Object.keys(runtime.containers).sort().join(',') !== Object.keys(roles).sort().join(',')
    )
      throw fail()
    const mappings: Record<string, Record<string, number>> = {
      postgres: { '5432/tcp': pg.port },
      redis: { '6379/tcp': 56390 },
      emqx: { '1883/tcp': 51884, '8883/tcp': 58884, '18083/tcp': 58084 },
      kafka: { '19092/tcp': 59093 }
    }
    for (const [role, name] of Object.entries(roles)) {
      const expected = runtime.containers[role]
      if (
        !expected ||
        Object.keys(expected).sort().join(',') !== 'ConfigImage,Id,Image,Labels' ||
        !hex(expected.Id) ||
        !/^sha256:[0-9a-f]{64}$/.test(expected.Image ?? '') ||
        typeof expected.ConfigImage !== 'string' ||
        expected.Labels?.['thingslink.owner'] !== 'console-wp03'
      )
        throw fail()
      const actual = JSON.parse(io.command('docker', ['inspect', '--format', inspectFormat, name]))
      if (
        actual.Id !== expected.Id ||
        actual.Name !== '/' + name ||
        actual.Image !== expected.Image ||
        actual.ConfigImage !== expected.ConfigImage ||
        actual.Running !== true ||
        Object.entries(expected.Labels).some(([key, value]) => actual.Labels?.[key] !== value)
      )
        throw fail()
      if (
        role === 'postgres' &&
        (expected.Id !== pg.containerId ||
          expected.Image !== pg.imageDigest ||
          expected.ConfigImage !== pg.image)
      )
        throw fail()
      for (const [target, host] of Object.entries(mappings[role]!)) {
        const ports = actual.Ports?.[target]
        if (
          !Array.isArray(ports) ||
          ports.length !== 1 ||
          ports[0]?.HostIp !== '127.0.0.1' ||
          ports[0]?.HostPort !== String(host)
        )
          throw fail()
      }
    }
    if (io.database(env) !== '1') throw fail()
    if (env.E2E_SIMULATOR_BASE_URL) {
      if (env.E2E_SIMULATOR_BASE_URL !== 'http://127.0.0.1:8092') throw fail()
      const simulatorJar = material(runtime.simulator?.jarPath, runtime.simulator?.jarSha256, io)
      processGuard(
        runtime.simulator,
        8092,
        io,
        undefined,
        (tokens) =>
          /(?:^|\/)java$/.test(tokens[0] ?? '') &&
          tokens.includes('-jar') &&
          tokens[tokens.indexOf('-jar') + 1] === simulatorJar
      )
    }
  } catch {
    throw fail()
  }
}

export default function ownedRuntimeSetup() {
  verifyOwnedRuntime()
}
