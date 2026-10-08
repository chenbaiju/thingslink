// @vitest-environment node
import { createHash } from 'node:crypto'
import { mkdirSync, mkdtempSync, rmSync, symlinkSync, writeFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { afterEach, describe, expect, it, vi } from 'vitest'
import {
  executeDatabaseFixture,
  parseOwnedDatabaseRuntime,
  readOwnedDatabaseMaterial,
  type DatabaseFixtureIO
} from '../e2e/database-fixture'

const pg = {
  container: 'tc-console-wp03-pg',
  containerId: 'a'.repeat(64),
  image: 'postgres:16',
  imageDigest: 'sha256:' + 'b'.repeat(64),
  port: 5549,
  database: 'tc_console_wp03_guard',
  user: 'thingslink',
  databaseOid: 16384
}
function fixture(change?: (runtime: any) => void) {
  const runtime = { qualification: 'WP-03-DEFAULT-MATRIX', postgres: { ...pg }, backendPid: 123 }
  change?.(runtime)
  const raw = Buffer.from(JSON.stringify(runtime))
  const env = {
    E2E_OWNED_RUNTIME: 'logs/wp03-runtime.json',
    E2E_OWNED_RUNTIME_SHA256: createHash('sha256').update(raw).digest('hex'),
    E2E_PG_CONTAINER: pg.container,
    E2E_PG_USER: pg.user,
    E2E_PG_DB: pg.database
  }
  const inspect: any = {
    Id: pg.containerId,
    Name: '/' + pg.container,
    Image: pg.imageDigest,
    ConfigImage: pg.image,
    Running: true,
    Labels: { 'thingslink.owner': 'console-wp03' },
    Ports: { '5432/tcp': [{ HostIp: '127.0.0.1', HostPort: '5549' }] }
  }
  const identity: any = { database: pg.database, user: pg.user, oid: pg.databaseOid }
  const io: DatabaseFixtureIO = {
    ownedMaterial: vi.fn(() => raw),
    legacySettings: vi.fn(() => ({ user: 'legacy_owner', database: 'legacy_db' })),
    docker: vi.fn((args, input) => {
      if (args[0] === 'inspect') return JSON.stringify(inspect)
      if (input === undefined) return JSON.stringify(identity)
      return '9\n'
    })
  }
  return { raw, env, io, inspect, identity }
}
const query = {
  legacy: 'deploy' as const,
  statement: 'BEGIN; SELECT 9; COMMIT;',
  variables: { email: 'test@example.com' }
}

describe('owned material filesystem boundary', () => {
  const created: string[] = []
  afterEach(() => {
    for (const path of created.splice(0)) rmSync(path, { recursive: true, force: true })
  })
  it('reads only bounded real files in owned logs and refuses outside symlink targets', () => {
    mkdirSync(resolve('logs'), { recursive: true })
    const inside = mkdtempSync(resolve('logs/wp03-database-test-'))
    const outside = mkdtempSync(resolve('logs', '..', 'wp03-database-outside-'))
    created.push(inside, outside)
    writeFileSync(resolve(inside, 'runtime.json'), '{}')
    writeFileSync(resolve(outside, 'runtime.json'), '{}')
    // 同盘外部目录确保真实路径逃逸检查覆盖Windows反斜杠边界。
    symlinkSync(
      outside,
      resolve(inside, 'escape'),
      process.platform === 'win32' ? 'junction' : 'dir'
    )
    expect(readOwnedDatabaseMaterial(resolve(inside, 'runtime.json')).toString()).toBe('{}')
    expect(() => readOwnedDatabaseMaterial(resolve(inside, 'escape', 'runtime.json'))).toThrow(
      'DATABASE_FIXTURE_SCOPE_REJECTED'
    )
    expect(() => readOwnedDatabaseMaterial(resolve(outside, 'runtime.json'))).toThrow(
      'DATABASE_FIXTURE_SCOPE_REJECTED'
    )
    expect(() => readOwnedDatabaseMaterial(inside)).toThrow('DATABASE_FIXTURE_SCOPE_REJECTED')
    writeFileSync(resolve(inside, 'runtime.json'), Buffer.alloc(16385))
    expect(() => readOwnedDatabaseMaterial(resolve(inside, 'runtime.json'))).toThrow(
      'DATABASE_FIXTURE_SCOPE_REJECTED'
    )
  })
})

describe('owned database fixture', () => {
  it('pins public runtime and rechecks every operation, using full ID plus same-connection fence', () => {
    const f = fixture()
    expect(executeDatabaseFixture(query, f.env, f.io)).toBe('9')
    expect(executeDatabaseFixture(query, f.env, f.io)).toBe('9')
    expect(f.io.legacySettings).not.toHaveBeenCalled()
    const calls = vi.mocked(f.io.docker).mock.calls
    expect(calls).toHaveLength(6)
    expect(calls[0]![1]).toBeUndefined()
    expect(calls[0]![0].join(' ')).not.toContain('Config.Env')
    expect(calls[1]![0][1]).toBe(pg.containerId)
    expect(calls[2]![0]).toContain(pg.containerId)
    expect(calls[2]![0]).not.toContain(pg.container)
    expect(calls[2]![1]).toContain(`current_database() IS DISTINCT FROM '${pg.database}'`)
    expect(calls[2]![1]).toContain(`current_user IS DISTINCT FROM '${pg.user}'`)
    expect(calls[2]![1]).toContain(`${pg.databaseOid}::oid`)
    expect(calls[2]![1]!.indexOf('DO $owned$')).toBeLessThan(
      calls[2]![1]!.indexOf('BEGIN; SELECT 9;')
    )
  })

  it.each([
    [
      'qualification',
      (r: any) => {
        r.qualification = '010-C'
      }
    ],
    [
      'shared container',
      (r: any) => {
        r.postgres.container = 'tc-postgres'
      }
    ],
    [
      'wrong port',
      (r: any) => {
        r.postgres.port = 5548
      }
    ],
    [
      'string port',
      (r: any) => {
        r.postgres.port = '5549'
      }
    ],
    [
      'shared database',
      (r: any) => {
        r.postgres.database = 'thingslink'
      }
    ],
    [
      'empty prefix',
      (r: any) => {
        r.postgres.database = 'tc_console_wp03_'
      }
    ],
    [
      'SQL role',
      (r: any) => {
        r.postgres.user = "owner';select 1"
      }
    ],
    [
      'extra field',
      (r: any) => {
        r.postgres.password = 'do-not-output'
      }
    ],
    [
      'missing OID',
      (r: any) => {
        delete r.postgres.databaseOid
      }
    ],
    [
      'fraction OID',
      (r: any) => {
        r.postgres.databaseOid = 1.5
      }
    ],
    [
      'overflow OID',
      (r: any) => {
        r.postgres.databaseOid = 0x100000000
      }
    ],
    [
      'malformed digest',
      (r: any) => {
        r.postgres.imageDigest = 'postgres:16'
      }
    ]
  ])('rejects %s before Docker or legacy resolution', (_name, mutate) => {
    const f = fixture(mutate)
    expect(() => executeDatabaseFixture(query, f.env, f.io)).toThrow(
      'DATABASE_FIXTURE_SCOPE_REJECTED'
    )
    expect(f.io.docker).not.toHaveBeenCalled()
    expect(f.io.legacySettings).not.toHaveBeenCalled()
  })

  it.each(['E2E_OWNED_RUNTIME_SHA256', 'E2E_PG_CONTAINER', 'E2E_PG_USER', 'E2E_PG_DB'] as const)(
    'refuses missing or mismatched %s without fallback',
    (field) => {
      for (const value of ['', 'different']) {
        const f = fixture()
        f.env[field] = value
        expect(() => executeDatabaseFixture(query, f.env, f.io)).toThrow(
          'DATABASE_FIXTURE_SCOPE_REJECTED'
        )
        expect(f.io.docker).not.toHaveBeenCalled()
        expect(f.io.legacySettings).not.toHaveBeenCalled()
      }
    }
  )

  it.each([
    [
      'ID',
      (v: any) => {
        v.Id = 'c'.repeat(64)
      }
    ],
    [
      'name',
      (v: any) => {
        v.Name = '/renamed'
      }
    ],
    [
      'image digest',
      (v: any) => {
        v.Image = 'sha256:' + 'c'.repeat(64)
      }
    ],
    [
      'image tag',
      (v: any) => {
        v.ConfigImage = 'postgres:15'
      }
    ],
    [
      'stopped',
      (v: any) => {
        v.Running = false
      }
    ],
    [
      'foreign owner',
      (v: any) => {
        v.Labels['thingslink.owner'] = 'console-010-c'
      }
    ],
    [
      'wildcard port',
      (v: any) => {
        v.Ports['5432/tcp'][0].HostIp = '0.0.0.0'
      }
    ],
    [
      'wrong port',
      (v: any) => {
        v.Ports['5432/tcp'][0].HostPort = '5547'
      }
    ],
    [
      'extra exposure',
      (v: any) => {
        v.Ports['5432/tcp'].push({ HostIp: '::', HostPort: '5549' })
      }
    ]
  ])('rejects live container %s before connecting to any DB', (_name, mutate) => {
    const f = fixture()
    mutate(f.inspect)
    expect(() => executeDatabaseFixture(query, f.env, f.io)).toThrow(
      'DATABASE_FIXTURE_SCOPE_REJECTED'
    )
    expect(f.io.docker).toHaveBeenCalledTimes(1)
  })

  it.each(['database', 'user', 'oid'])('rejects actual %s before business SQL', (field) => {
    const f = fixture()
    f.identity[field] = field === 'oid' ? pg.databaseOid + 1 : 'different'
    expect(() => executeDatabaseFixture(query, f.env, f.io)).toThrow(
      'DATABASE_FIXTURE_SCOPE_REJECTED'
    )
    expect(f.io.docker).toHaveBeenCalledTimes(2)
    expect(vi.mocked(f.io.docker).mock.calls.every((call) => call[1] === undefined)).toBe(true)
  })

  it('rejects a string OID even when its digits match the frozen numeric OID', () => {
    const f = fixture()
    f.identity.oid = String(pg.databaseOid)
    expect(() => executeDatabaseFixture(query, f.env, f.io)).toThrow(
      'DATABASE_FIXTURE_SCOPE_REJECTED'
    )
    expect(f.io.docker).toHaveBeenCalledTimes(2)
    expect(vi.mocked(f.io.docker).mock.calls.every((call) => call[1] === undefined)).toBe(true)
    expect(f.io.legacySettings).not.toHaveBeenCalled()
  })

  it('refuses a container replacement between queries and does not reuse a cached proof', () => {
    const f = fixture()
    executeDatabaseFixture(query, f.env, f.io)
    f.inspect.Id = 'd'.repeat(64)
    expect(() => executeDatabaseFixture(query, f.env, f.io)).toThrow(
      'DATABASE_FIXTURE_SCOPE_REJECTED'
    )
    expect(f.io.docker).toHaveBeenCalledTimes(4)
  })

  it('keeps path, parse, Docker, SQL and secret errors opaque without fallback', () => {
    for (const failAt of ['ownedMaterial', 'docker'] as const) {
      const f = fixture()
      vi.mocked(f.io[failAt]).mockImplementation(() => {
        throw new Error('SECRET-DATABASE-PAYLOAD')
      })
      try {
        executeDatabaseFixture(query, f.env, f.io)
        expect.unreachable()
      } catch (error) {
        expect((error as Error).message).toBe('DATABASE_FIXTURE_SCOPE_REJECTED')
        expect((error as Error).cause).toBeUndefined()
      }
      expect(f.io.legacySettings).not.toHaveBeenCalled()
    }
    const f = fixture()
    expect(() => parseOwnedDatabaseRuntime(Buffer.alloc(16385), f.env)).toThrow(
      'DATABASE_FIXTURE_SCOPE_REJECTED'
    )
  })
})

describe('legacy explicit CI compatibility', () => {
  it('retains deploy settings and explicit CI overrides only for tc-postgres', () => {
    const f = fixture()
    expect(executeDatabaseFixture(query, {}, f.io)).toBe('9')
    expect(vi.mocked(f.io.docker).mock.calls[0]![0]).toContain('tc-postgres')
    expect(vi.mocked(f.io.docker).mock.calls[0]![0]).toContain('legacy_db')
    expect(f.io.ownedMaterial).not.toHaveBeenCalled()
    expect(
      executeDatabaseFixture(
        { ...query, legacy: 'explicit' },
        { E2E_PG_CONTAINER: 'tc-postgres', E2E_PG_USER: 'ci_owner', E2E_PG_DB: 'ci_database' },
        f.io
      )
    ).toBe('9')
    expect(vi.mocked(f.io.docker).mock.calls[1]![0]).toContain('ci_database')
  })
  it('cannot target an owned container without its runtime or infer an explicit read context', () => {
    const f = fixture()
    expect(() => executeDatabaseFixture(query, { E2E_PG_CONTAINER: pg.container }, f.io)).toThrow(
      'DATABASE_FIXTURE_SCOPE_REJECTED'
    )
    expect(() => executeDatabaseFixture({ ...query, legacy: 'explicit' }, {}, f.io)).toThrow(
      'DATABASE_FIXTURE_SCOPE_REJECTED'
    )
    expect(f.io.docker).not.toHaveBeenCalled()
  })
  it('does not downgrade incomplete owned intent to legacy when the runtime path is missing', () => {
    const f = fixture()
    for (const env of [{ E2E_PG_DB: pg.database }, { E2E_OWNED_RUNTIME_SHA256: 'a'.repeat(64) }]) {
      expect(() => executeDatabaseFixture(query, env, f.io)).toThrow(
        'DATABASE_FIXTURE_SCOPE_REJECTED'
      )
    }
    expect(f.io.legacySettings).not.toHaveBeenCalled()
    expect(f.io.docker).not.toHaveBeenCalled()
  })
})
