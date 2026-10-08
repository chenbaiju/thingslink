import { execFileSync } from 'node:child_process'
import { createHash } from 'node:crypto'
import { existsSync, readFileSync, realpathSync, statSync } from 'node:fs'
import { isAbsolute, relative, resolve, sep } from 'node:path'
import { fileURLToPath } from 'node:url'

interface DatabaseScope {
  container: string
  containerId: string
  image: string
  imageDigest: string
  port: number
  database: string
  user: string
  databaseOid: number
}
export interface DatabaseFixtureIO {
  ownedMaterial(path: string): Buffer
  legacySettings(): { user: string; database: string }
  docker(args: string[], input: string | undefined, timeout: number, maxBuffer: number): string
}
interface Query {
  statement: string
  variables?: Record<string, string>
  legacy: 'deploy' | 'explicit'
  timeout?: number
  maxBuffer?: number
}
const fail = () => new Error('DATABASE_FIXTURE_SCOPE_REJECTED')
const identifier = (value: unknown): value is string =>
  typeof value === 'string' && /^[a-z_][a-z0-9_]{0,62}$/.test(value)
const digest = (raw: Buffer) => createHash('sha256').update(raw).digest('hex')

/** 只读取Console忽略日志目录内的有界公开材料，真实路径检查不能被符号链接绕过。 */
export function readOwnedDatabaseMaterial(path: string): Buffer {
  try {
    const root = realpathSync(resolve('logs')),
      target = realpathSync(resolve(path)),
      suffix = relative(root, target)
    if (!suffix || isAbsolute(suffix) || suffix === '..' || suffix.startsWith(`..${sep}`))
      throw fail()
    const stat = statSync(target)
    if (!stat.isFile() || stat.size > 16 * 1024) throw fail()
    return readFileSync(target)
  } catch {
    throw fail()
  }
}
const defaultIO: DatabaseFixtureIO = {
  ownedMaterial: readOwnedDatabaseMaterial,
  legacySettings() {
    const settings = { user: 'thingslink', database: 'thingslink' }
    const path = fileURLToPath(new URL('../../deploy/.env', import.meta.url))
    for (const line of (existsSync(path) ? readFileSync(path, 'utf8') : '').split(/\r?\n/)) {
      const match = /^(POSTGRES_USER|POSTGRES_DB)=(.*)$/.exec(line.trim())
      if (match) {
        const value = match[2]!.trim().replace(/^(['"])(.*)\1$/, '$2')
        if (match[1] === 'POSTGRES_USER') settings.user = value
        else settings.database = value
      }
    }
    return settings
  },
  docker(args, input, timeout, maxBuffer) {
    return execFileSync('docker', args, { input, encoding: 'utf8', timeout, maxBuffer })
  }
}

/** 根绑定整个公开运行材料；数据库子对象闭合，其他宿主字段由其所属守卫验证。 */
export function parseOwnedDatabaseRuntime(raw: Buffer, env: NodeJS.ProcessEnv): DatabaseScope {
  if (
    !raw.length ||
    raw.length > 16 * 1024 ||
    !/^[0-9a-f]{64}$/.test(env.E2E_OWNED_RUNTIME_SHA256 ?? '') ||
    digest(raw) !== env.E2E_OWNED_RUNTIME_SHA256
  )
    throw fail()
  let runtime
  try {
    runtime = JSON.parse(raw.toString('utf8'))
  } catch {
    throw fail()
  }
  const pg = runtime?.postgres
  const fields = [
    'container',
    'containerId',
    'image',
    'imageDigest',
    'port',
    'database',
    'user',
    'databaseOid'
  ]
  if (
    runtime?.qualification !== 'WP-03-DEFAULT-MATRIX' ||
    !pg ||
    typeof pg !== 'object' ||
    Array.isArray(pg) ||
    Object.keys(pg).length !== fields.length ||
    fields.some((field) => !Object.hasOwn(pg, field)) ||
    pg.container !== 'tc-console-wp03-pg' ||
    !/^[0-9a-f]{64}$/.test(pg.containerId ?? '') ||
    typeof pg.image !== 'string' ||
    pg.image.length > 256 ||
    !/^[A-Za-z0-9][A-Za-z0-9./:@_-]+$/.test(pg.image) ||
    !/^sha256:[0-9a-f]{64}$/.test(pg.imageDigest ?? '') ||
    pg.port !== 5549 ||
    !identifier(pg.database) ||
    !pg.database.startsWith('tc_console_wp03_') ||
    pg.database.length === 'tc_console_wp03_'.length ||
    !identifier(pg.user) ||
    !Number.isSafeInteger(pg.databaseOid) ||
    pg.databaseOid <= 0 ||
    pg.databaseOid > 0xffffffff ||
    env.E2E_PG_CONTAINER !== pg.container ||
    env.E2E_PG_USER !== pg.user ||
    env.E2E_PG_DB !== pg.database
  )
    throw fail()
  return Object.freeze({ ...pg }) as DatabaseScope
}

/** 所有读写及清理都先重新确权，随后按完整容器ID执行；错误不携带SQL或Docker输出。 */
export function executeDatabaseFixture(query: Query, env = process.env, io = defaultIO): string {
  try {
    let container: string,
      user: string,
      database: string,
      fence = ''
    if (env.E2E_OWNED_RUNTIME) {
      const pg = parseOwnedDatabaseRuntime(io.ownedMaterial(env.E2E_OWNED_RUNTIME), env)
      const format =
        '{"Id":{{json .Id}},"Name":{{json .Name}},"Image":{{json .Image}},"ConfigImage":{{json .Config.Image}},"Running":{{json .State.Running}},"Labels":{{json .Config.Labels}},"Ports":{{json .NetworkSettings.Ports}}}'
      const actual = JSON.parse(
        io.docker(['inspect', '--format', format, pg.container], undefined, 5000, 16 * 1024)
      )
      const ports = actual?.Ports?.['5432/tcp']
      if (
        actual?.Id !== pg.containerId ||
        actual?.Name !== '/' + pg.container ||
        actual?.Image !== pg.imageDigest ||
        actual?.ConfigImage !== pg.image ||
        actual?.Running !== true ||
        actual?.Labels?.['thingslink.owner'] !== 'console-wp03' ||
        !Array.isArray(ports) ||
        ports.length !== 1 ||
        ports[0]?.HostIp !== '127.0.0.1' ||
        ports[0]?.HostPort !== '5549'
      )
        throw fail()
      const identity = JSON.parse(
        io.docker(
          [
            'exec',
            pg.containerId,
            'psql',
            '-X',
            '-qAt',
            '-v',
            'ON_ERROR_STOP=1',
            '-U',
            pg.user,
            '-d',
            pg.database,
            '-c',
            "SELECT json_build_object('database',current_database(),'user',current_user,'oid',(SELECT oid::bigint FROM pg_database WHERE datname=current_database()))"
          ],
          undefined,
          5000,
          4096
        )
      )
      if (
        identity?.database !== pg.database ||
        identity?.user !== pg.user ||
        identity?.oid !== pg.databaseOid
      )
        throw fail()
      container = pg.containerId
      user = pg.user
      database = pg.database
      // 同一SQL连接在业务正文之前再核对，避免两次连接之间数据库被替换。
      fence = `DO $owned$ BEGIN IF current_database() IS DISTINCT FROM '${database}' OR current_user IS DISTINCT FROM '${user}' OR (SELECT oid FROM pg_database WHERE datname=current_database()) IS DISTINCT FROM ${pg.databaseOid}::oid THEN RAISE EXCEPTION 'DATABASE_FIXTURE_SCOPE_REJECTED'; END IF; END $owned$;\n`
    } else {
      if (
        env.E2E_OWNED_RUNTIME_SHA256 ||
        env.E2E_PG_DB?.startsWith('tc_console_wp03_') ||
        (env.E2E_PG_CONTAINER && env.E2E_PG_CONTAINER !== 'tc-postgres')
      )
        throw fail()
      const legacy = query.legacy === 'deploy' ? io.legacySettings() : undefined
      user = env.E2E_PG_USER ?? legacy?.user ?? ''
      database = env.E2E_PG_DB ?? legacy?.database ?? ''
      if (!identifier(user) || !identifier(database)) throw fail()
      container = 'tc-postgres'
    }
    const args = [
      'exec',
      '-i',
      container,
      'psql',
      '-X',
      '-qAt',
      '-v',
      'ON_ERROR_STOP=1',
      '-U',
      user,
      '-d',
      database
    ]
    for (const [key, value] of Object.entries(query.variables ?? {})) {
      if (!/^[A-Za-z][A-Za-z0-9_]*$/.test(key)) throw fail()
      args.push('-v', `${key}=${value}`)
    }
    return io
      .docker(
        args,
        fence + query.statement,
        query.timeout ?? 20000,
        query.maxBuffer ?? 8 * 1024 * 1024
      )
      .trim()
  } catch {
    throw fail()
  }
}
