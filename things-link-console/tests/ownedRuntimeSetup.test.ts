// @vitest-environment node
import { createHash } from 'node:crypto'
import { resolve as nativeResolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, expect, it, vi } from 'vitest'
import { verifyOwnedRuntime, type OwnedRuntimeIO } from '../e2e/owned-runtime-setup'

// 模拟Unix运行进程时统一夹具路径，不改变真实运行守卫。
const resolve = (...parts: string[]) => nativeResolve(...parts).replaceAll('\\', '/')
const consoleRoot = fileURLToPath(new URL('..', import.meta.url))
const repoRoot = resolve(consoleRoot, '..')
const logs = resolve(consoleRoot, 'logs')
const sha = (value: string | Buffer) => createHash('sha256').update(value).digest('hex')
function fixture() {
  const files = new Map<string, Buffer>()
  const add = (name: string, text: string) => {
    const path = resolve(logs, name),
      bytes = Buffer.from(text)
    files.set(path, bytes)
    return { path, sha256: sha(bytes) }
  }
  const jar = add('wp03-backend.jar', 'frozen jar'),
    config = add('wp03-config.json', '{"redisDatabase":0}')
  const host = add('host-registry/hosts/1.0.0/host-candidate.json', '{"hostVersion":"1.0.0"}')
  const runtime: any = {
    qualification: 'WP-03-DEFAULT-MATRIX',
    sourceCommit: 'a'.repeat(40),
    postgres: {
      container: 'tc-console-wp03-pg',
      containerId: 'b'.repeat(64),
      image: 'postgres:17',
      imageDigest: 'sha256:' + 'c'.repeat(64),
      port: 5549,
      database: 'tc_console_wp03_guard',
      user: 'thingslink',
      databaseOid: 19384
    },
    backendPort: 8090,
    vitePort: 3019,
    webappPort: 4018,
    redisPort: 56390,
    redisDatabase: 0,
    mqttPort: 51884,
    mqttTlsPort: 58884,
    brokerApiPort: 58084,
    kafkaPort: 59093,
    simulatorPort: 8092,
    pid: 101,
    frozenJar: jar.path,
    jarSha256: jar.sha256,
    configPath: config.path,
    configSha256: config.sha256,
    effectiveConfigSha256: 'd'.repeat(64),
    hostRegistry: resolve(logs, 'host-registry'),
    hostDescriptorPath: host.path,
    hostDescriptorSha256: host.sha256,
    sourceFiles: [
      'things-link-console/e2e/owned-runtime-setup.ts',
      'things-link-console/playwright.config.ts',
      'things-link-console/vite.config.ts'
    ].map((path) => {
      const bytes = Buffer.from(path)
      files.set(resolve(repoRoot, path), bytes)
      return { path, sha256: sha(bytes) }
    }),
    containers: {}
  }
  const processes = new Map<number, { command: string; cwd: string; port: number }>()
  const snapshot = add(
    'wp03-source-snapshot.json',
    JSON.stringify({ sourceCommit: runtime.sourceCommit, files: runtime.sourceFiles })
  )
  runtime.sourceSnapshotPath = snapshot.path
  runtime.sourceSnapshotSha256 = snapshot.sha256
  const backendCommand = `/jdk/bin/java -jar ${jar.path}`
  runtime.backend = { pid: 101, cwd: repoRoot, commandSha256: sha(backendCommand) }
  processes.set(101, { command: backendCommand, cwd: repoRoot, port: 8090 })
  for (const [role, pid, port, cwd] of [
    ['vite', 102, 3019, consoleRoot],
    ['webapp', 103, 4018, resolve(repoRoot, 'things-link-webapp')]
  ] as const) {
    const scriptPath = resolve(cwd, 'node_modules/vite/bin/vite.js'),
      nodePath = '/runtime/bin/node'
    const command = `${nodePath} ${scriptPath} --strictPort --port ${port}`
    runtime[role] = {
      pid,
      cwd,
      nodePath,
      scriptPath,
      proxyTarget: 'http://127.0.0.1:8090',
      commandSha256: sha(command)
    }
    processes.set(pid, { command, cwd, port })
  }
  const actuals: Record<string, any> = {}
  for (const [role, name, target, port] of [
    ['postgres', 'pg', '5432/tcp', 5549],
    ['redis', 'redis', '6379/tcp', 56390],
    ['emqx', 'emqx', '1883/tcp', 51884],
    ['kafka', 'kafka', '19092/tcp', 59093]
  ] as const) {
    const expected =
      role === 'postgres'
        ? {
            Id: runtime.postgres.containerId,
            Image: runtime.postgres.imageDigest,
            ConfigImage: runtime.postgres.image
          }
        : { Id: sha(role), Image: 'sha256:' + sha(role + '-image'), ConfigImage: role + ':frozen' }
    runtime.containers[role] = { ...expected, Labels: { 'thingslink.owner': 'console-wp03' } }
    const ports: any = { [target]: [{ HostIp: '127.0.0.1', HostPort: String(port) }] }
    if (role === 'emqx')
      Object.assign(ports, {
        '8883/tcp': [{ HostIp: '127.0.0.1', HostPort: '58884' }],
        '18083/tcp': [{ HostIp: '127.0.0.1', HostPort: '58084' }]
      })
    if (role === 'kafka') ports['9644/tcp'] = [{ HostIp: '127.0.0.1', HostPort: '59645' }]
    actuals[`tc-console-wp03-${name}`] = {
      ...runtime.containers[role],
      Name: `/tc-console-wp03-${name}`,
      Running: true,
      Ports: ports
    }
  }
  const env: NodeJS.ProcessEnv = {
    E2E_OWNED_RUNTIME: resolve(logs, 'wp03-runtime.json'),
    E2E_OWNED_RUNTIME_SHA256: '',
    E2E_BASE_URL: 'http://127.0.0.1:3019',
    CONTRACT_BASE_URL: 'http://127.0.0.1:8090',
    E2E_PG_CONTAINER: runtime.postgres.container,
    E2E_PG_USER: runtime.postgres.user,
    E2E_PG_DB: runtime.postgres.database
  }
  const io: OwnedRuntimeIO = {
    realpath: (path) => resolve(path),
    read: vi.fn((path, maximum) => {
      const bytes = files.get(path)
      if (!bytes || bytes.length > maximum) throw new Error('private diagnostic')
      return bytes
    }),
    command: vi.fn((program, args) => {
      if (program === 'git') return runtime.sourceCommit
      if (program === 'docker') return JSON.stringify(actuals[args.at(-1)!])
      const pid = Number(args[args.indexOf('-p') + 1]),
        process = processes.get(pid)
      if (!process) throw new Error('private diagnostic')
      if (program === 'ps') return process.command
      if (args.includes('cwd')) return `p${pid}\nfcwd\nn${process.cwd}`
      return args.includes(`-iTCP:${process.port}`) ? String(pid) : ''
    }),
    database: vi.fn(() => '1')
  }
  const bind = () => {
    const bytes = Buffer.from(JSON.stringify(runtime))
    files.set(env.E2E_OWNED_RUNTIME!, bytes)
    env.E2E_OWNED_RUNTIME_SHA256 = sha(bytes)
  }
  bind()
  return { runtime, env, io, files, processes, actuals, bind }
}

describe('owned default matrix global setup', () => {
  it('checks frozen artifacts, live processes and all container identities before database read', () => {
    const f = fixture()
    expect(() => verifyOwnedRuntime(f.env, f.io)).not.toThrow()
    expect(f.io.database).toHaveBeenCalledOnce()
    const commands = vi.mocked(f.io.command).mock.calls
    expect(
      commands.filter(([program, args]) => program === 'docker' && args[0] === 'inspect')
    ).toHaveLength(4)
    expect(JSON.stringify(commands)).not.toContain('Config.Env')
    expect(JSON.stringify(commands)).not.toContain('redis-cli')
  })
  it.each([
    [
      'missing material',
      (f: ReturnType<typeof fixture>) => {
        delete f.env.E2E_OWNED_RUNTIME
      }
    ],
    [
      'whole digest drift',
      (f: ReturnType<typeof fixture>) => {
        f.env.E2E_OWNED_RUNTIME_SHA256 = 'e'.repeat(64)
      }
    ],
    [
      'wrong API',
      (f: ReturnType<typeof fixture>) => {
        f.env.CONTRACT_BASE_URL = 'http://localhost:8080'
      }
    ],
    [
      'jar drift',
      (f: ReturnType<typeof fixture>) => {
        f.files.set(f.runtime.frozenJar, Buffer.from('changed'))
      }
    ],
    [
      'source drift',
      (f: ReturnType<typeof fixture>) => {
        f.files.set(resolve(repoRoot, f.runtime.sourceFiles[0].path), Buffer.from('changed'))
      }
    ],
    [
      'command drift',
      (f: ReturnType<typeof fixture>) => {
        f.processes.get(101)!.command += ' --changed'
      }
    ],
    [
      'cwd drift',
      (f: ReturnType<typeof fixture>) => {
        f.processes.get(102)!.cwd = '/other/project'
      }
    ],
    [
      'listener drift',
      (f: ReturnType<typeof fixture>) => {
        f.processes.get(103)!.port = 3006
      }
    ],
    [
      'container replacement',
      (f: ReturnType<typeof fixture>) => {
        f.actuals['tc-console-wp03-emqx'].Id = 'e'.repeat(64)
      }
    ],
    [
      'image drift',
      (f: ReturnType<typeof fixture>) => {
        f.actuals['tc-console-wp03-kafka'].Image = 'sha256:' + 'e'.repeat(64)
      }
    ],
    [
      'owner drift',
      (f: ReturnType<typeof fixture>) => {
        f.actuals['tc-console-wp03-redis'].Labels = { 'thingslink.owner': 'developer' }
      }
    ],
    [
      'wildcard exposure',
      (f: ReturnType<typeof fixture>) => {
        f.actuals['tc-console-wp03-pg'].Ports['5432/tcp'][0].HostIp = '0.0.0.0'
      }
    ],
    [
      'duplicate binding',
      (f: ReturnType<typeof fixture>) => {
        f.actuals['tc-console-wp03-redis'].Ports['6379/tcp'].push({
          HostIp: '::',
          HostPort: '56390'
        })
      }
    ],
    [
      'DB refusal',
      (f: ReturnType<typeof fixture>) => {
        f.io.database = () => {
          throw new Error('private SQL output')
        }
      }
    ],
    [
      'unfrozen simulator',
      (f: ReturnType<typeof fixture>) => {
        f.env.E2E_SIMULATOR_BASE_URL = 'http://127.0.0.1:8092'
      }
    ]
  ])('rejects %s with a fixed error and no private cause', (_, change) => {
    const f = fixture()
    change(f)
    try {
      verifyOwnedRuntime(f.env, f.io)
      throw new Error('unexpected acceptance')
    } catch (error) {
      expect((error as Error).message).toBe('OWNED_RUNTIME_QUALIFICATION_REJECTED')
      expect((error as Error).cause).toBeUndefined()
    }
  })
  it('rejects an unknown container field even under a newly approved whole digest', () => {
    const f = fixture()
    f.runtime.containers.redis.ConfigEnv = ['private']
    f.bind()
    expect(() => verifyOwnedRuntime(f.env, f.io)).toThrow('OWNED_RUNTIME_QUALIFICATION_REJECTED')
  })
  it('rejects duplicate JSON keys before interpreting bound metadata', () => {
    const f = fixture(),
      bytes = Buffer.from(
        '{"qualification":"WP-03-DEFAULT-MATRIX","qualification":"WP-03-DEFAULT-MATRIX"}'
      )
    f.files.set(f.env.E2E_OWNED_RUNTIME!, bytes)
    f.env.E2E_OWNED_RUNTIME_SHA256 = sha(bytes)
    expect(() => verifyOwnedRuntime(f.env, f.io)).toThrow('OWNED_RUNTIME_QUALIFICATION_REJECTED')
    expect(f.io.command).not.toHaveBeenCalled()
  })
  it('accepts the actual pnpm symlink path in the frozen node command', () => {
    const f = fixture(),
      original = f.io.realpath
    f.io.realpath = (path) =>
      path.endsWith('/node_modules/vite/bin/vite.js')
        ? path.replace('/node_modules/vite/', '/node_modules/.pnpm/vite@frozen/node_modules/vite/')
        : original(path)
    expect(() => verifyOwnedRuntime(f.env, f.io)).not.toThrow()
  })
  it('checks an optional simulator only when explicitly requested and freezes its process and JAR', () => {
    const f = fixture(),
      jarPath = resolve(logs, 'wp03-simulator.jar'),
      bytes = Buffer.from('simulator')
    f.files.set(jarPath, bytes)
    const command = `/jdk/bin/java -jar ${jarPath}`
    f.runtime.simulator = {
      pid: 104,
      cwd: repoRoot,
      commandSha256: sha(command),
      jarPath,
      jarSha256: sha(bytes)
    }
    f.processes.set(104, { command, cwd: repoRoot, port: 8092 })
    f.env.E2E_SIMULATOR_BASE_URL = 'http://127.0.0.1:8092'
    f.bind()
    expect(() => verifyOwnedRuntime(f.env, f.io)).not.toThrow()
    f.processes.get(104)!.port = 8090
    expect(() => verifyOwnedRuntime(f.env, f.io)).toThrow('OWNED_RUNTIME_QUALIFICATION_REJECTED')
  })
  it.each(['files drift', 'wrong source identity', 'unknown snapshot field'])(
    'rejects %s in the separately bound full source snapshot',
    (kind) => {
      const f = fixture(),
        snapshot: any = { sourceCommit: f.runtime.sourceCommit, files: f.runtime.sourceFiles }
      if (kind === 'files drift')
        snapshot.files = [{ path: 'things-link-console/vite.config.ts', sha256: 'e'.repeat(64) }]
      else if (kind === 'wrong source identity') snapshot.sourceCommit = 'e'.repeat(40)
      else snapshot.uncontrolled = true
      const bytes = Buffer.from(JSON.stringify(snapshot))
      f.files.set(f.runtime.sourceSnapshotPath, bytes)
      f.runtime.sourceSnapshotSha256 = sha(bytes)
      f.bind()
      expect(() => verifyOwnedRuntime(f.env, f.io)).toThrow('OWNED_RUNTIME_QUALIFICATION_REJECTED')
    }
  )
})
