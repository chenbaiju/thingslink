// @vitest-environment node
import { afterEach, describe, expect, it, vi } from 'vitest'
import { alarmInboxConfiguration } from '../e2e/alarm-inbox-configuration'
import { AlarmInboxFixture } from '../e2e/alarm-inbox-fixture'
import { executeDatabaseFixture } from '../e2e/database-fixture'

vi.mock('../e2e/database-fixture', () => ({ executeDatabaseFixture: vi.fn() }))
const fields = [
  'E2E_ALARM_INBOX_OWNER_EMAIL',
  'E2E_ALARM_INBOX_OWNER_PASSWORD',
  'E2E_ALARM_INBOX_OWNER_PROJECT_KEY',
  'E2E_ALARM_INBOX_MEMBER_EMAIL',
  'E2E_ALARM_INBOX_MEMBER_PASSWORD',
  'E2E_ALARM_INBOX_MEMBER_PROJECT_KEY'
] as const
const owned = (): NodeJS.ProcessEnv => ({
  E2E_OWNED_RUNTIME: 'logs/owned-public-runtime.json',
  E2E_OWNER_EMAIL: 'ordinary-owner@example.com',
  E2E_MEMBER_EMAIL: 'ordinary-member@example.com',
  E2E_PROJECT_KEY: 'ordinary1',
  E2E_ALARM_INBOX_OWNER_EMAIL: 'wp03-inbox-owner@example.com',
  E2E_ALARM_INBOX_OWNER_PASSWORD: 'synthetic-owner-credential',
  E2E_ALARM_INBOX_OWNER_PROJECT_KEY: 'inbox001',
  E2E_ALARM_INBOX_MEMBER_EMAIL: 'wp03-inbox-member@example.com',
  E2E_ALARM_INBOX_MEMBER_PASSWORD: 'synthetic-member-credential',
  E2E_ALARM_INBOX_MEMBER_PROJECT_KEY: 'inbox002'
})
afterEach(() => {
  vi.unstubAllEnvs()
  vi.resetAllMocks()
})

describe('dedicated alarm inbox identities', () => {
  it('returns the same dedicated identity scope for database selection and browser login', () => {
    const config = alarmInboxConfiguration(owned())
    expect(config.owned).toBe(true)
    expect(config.owner).toEqual({
      email: 'wp03-inbox-owner@example.com',
      password: 'synthetic-owner-credential',
      projectName: 'E2E项目',
      projectKey: 'inbox001'
    })
    expect(config.member).toEqual({
      email: 'wp03-inbox-member@example.com',
      password: 'synthetic-member-credential',
      projectName: '成员项目',
      projectKey: 'inbox002'
    })
  })
  it.each(fields)('rejects missing %s without using ordinary identities', (field) => {
    for (const value of [undefined, '']) {
      const env = owned()
      env[field] = value
      expect(() => alarmInboxConfiguration(env)).toThrow(
        'ALARM_INBOX_DEDICATED_IDENTITIES_REQUIRED'
      )
      expect(executeDatabaseFixture).not.toHaveBeenCalled()
    }
  })
  it.each(['OWNER', 'MEMBER'] as const)(
    'cannot reuse either ordinary actor as dedicated %s',
    (role) => {
      for (const email of ['ORDINARY-OWNER@example.com', 'ordinary-member@example.com']) {
        const env = owned()
        env[`E2E_ALARM_INBOX_${role}_EMAIL`] = email
        expect(() => alarmInboxConfiguration(env)).toThrow(
          'ALARM_INBOX_DEDICATED_IDENTITIES_REQUIRED'
        )
      }
    }
  )
  it('rejects same actors, same projects and ordinary project reuse', () => {
    const changes = [
      { E2E_ALARM_INBOX_MEMBER_EMAIL: 'WP03-INBOX-OWNER@example.com' },
      { E2E_ALARM_INBOX_MEMBER_PROJECT_KEY: 'inbox001' },
      { E2E_ALARM_INBOX_OWNER_PROJECT_KEY: 'ordinary1' },
      { E2E_ALARM_INBOX_MEMBER_PROJECT_KEY: 'ordinary1' }
    ]
    for (const change of changes)
      expect(() => alarmInboxConfiguration({ ...owned(), ...change })).toThrow(
        'ALARM_INBOX_DEDICATED_IDENTITIES_REQUIRED'
      )
  })
  it('keeps invalid input errors opaque and rejects public project-key violations', () => {
    for (const change of [
      { E2E_ALARM_INBOX_OWNER_EMAIL: 'not-an-email' },
      { E2E_ALARM_INBOX_MEMBER_PASSWORD: 'short' },
      { E2E_ALARM_INBOX_OWNER_PROJECT_KEY: 'a'.repeat(65) },
      { E2E_ALARM_INBOX_MEMBER_PROJECT_KEY: 'bad key' }
    ]) {
      try {
        alarmInboxConfiguration({ ...owned(), ...change })
        expect.unreachable()
      } catch (error) {
        expect((error as Error).message).toBe('ALARM_INBOX_DEDICATED_IDENTITIES_REQUIRED')
        expect((error as Error).cause).toBeUndefined()
      }
    }
  })
  it('retains legacy default and explicit CI actors without requiring dedicated variables', () => {
    expect(alarmInboxConfiguration({}).owner.email).toBe('e2e-owner@example.com')
    expect(alarmInboxConfiguration({}).member.projectKey).toBe('')
    const env = {
      E2E_OWNER_EMAIL: 'ci-owner@example.com',
      E2E_OWNER_PASSWORD: 'ci-owner-password',
      E2E_MEMBER_EMAIL: 'ci-member@example.com',
      E2E_MEMBER_PASSWORD: 'ci-member-password',
      E2E_PROJECT_KEY: 'ci-key'
    }
    expect(alarmInboxConfiguration(env)).toEqual({
      owned: false,
      owner: {
        email: env.E2E_OWNER_EMAIL,
        password: env.E2E_OWNER_PASSWORD,
        projectKey: 'ci-key',
        projectName: 'E2E项目'
      },
      member: {
        email: env.E2E_MEMBER_EMAIL,
        password: env.E2E_MEMBER_PASSWORD,
        projectKey: '',
        projectName: '成员项目'
      }
    })
  })
  it('will not downgrade incomplete owned runtime selection to legacy', () => {
    for (const env of [
      { E2E_PG_DB: 'tc_console_wp03_test' },
      { E2E_OWNED_RUNTIME_SHA256: 'a'.repeat(64) }
    ]) {
      expect(() => alarmInboxConfiguration(env)).toThrow(
        'ALARM_INBOX_DEDICATED_IDENTITIES_REQUIRED'
      )
    }
  })
})

describe('alarm fixture isolation before any fact mutation', () => {
  function fixture(existingMemberships = '0', existingEvents = '0') {
    for (const [key, value] of Object.entries(owned())) vi.stubEnv(key, value)
    const rows = [
      {
        accountId: 'owner-account',
        projectId: 'owner-project',
        tenantId: 'owner-tenant',
        name: 'E2E项目'
      },
      {
        accountId: 'member-account',
        projectId: 'member-project',
        tenantId: 'member-tenant',
        name: '成员项目'
      }
    ]
    vi.mocked(executeDatabaseFixture).mockImplementation((query) => {
      if (query.statement.includes('coalesce(jsonb_agg')) return JSON.stringify([rows.shift()])
      if (query.statement.includes('count(*) FROM sys_project_member')) return existingMemberships
      if (query.statement.includes('count(*) FROM alarm_event')) return existingEvents
      return ''
    })
    return new AlarmInboxFixture()
  }
  it('selects dedicated emails and exact public project keys, never ordinary identities', () => {
    const f = fixture()
    expect(f.configuration.owner.email).toBe('wp03-inbox-owner@example.com')
    const calls = vi.mocked(executeDatabaseFixture).mock.calls
    expect(calls[0]![0].variables).toEqual({
      email: 'wp03-inbox-owner@example.com',
      name: 'E2E项目',
      key: 'inbox001'
    })
    expect(calls[1]![0].variables).toEqual({
      email: 'wp03-inbox-member@example.com',
      name: '成员项目',
      key: 'inbox002'
    })
  })
  it('missing configuration rejects construction before querying any database', () => {
    vi.stubEnv('E2E_OWNED_RUNTIME', 'logs/runtime.json')
    vi.stubEnv('E2E_ALARM_INBOX_OWNER_EMAIL', undefined)
    expect(() => new AlarmInboxFixture()).toThrow('ALARM_INBOX_DEDICATED_IDENTITIES_REQUIRED')
    expect(executeDatabaseFixture).not.toHaveBeenCalled()
  })
  it('retains cross-member collision rejection and never changes that existing relationship', () => {
    const f = fixture('1')
    expect(() => f.create()).toThrow('通知E2E存在历史交叉成员，拒绝覆盖或复用')
    expect(vi.mocked(executeDatabaseFixture).mock.calls).toHaveLength(3)
    expect(
      vi
        .mocked(executeDatabaseFixture)
        .mock.calls.some(([q]) => q.statement.includes('INSERT INTO'))
    ).toBe(false)
  })
  it('rejects existing events in dedicated projects before adding facts or memberships', () => {
    const f = fixture('0', '1')
    expect(() => f.create()).toThrow('专用通知项目已有告警事实，拒绝混用')
    expect(vi.mocked(executeDatabaseFixture).mock.calls).toHaveLength(4)
    expect(
      vi
        .mocked(executeDatabaseFixture)
        .mock.calls.some(([q]) => q.statement.includes('INSERT INTO'))
    ).toBe(false)
  })
  it('only inserts new fixture UUIDs then cleans those IDs without deleting accounts or projects', () => {
    const f = fixture()
    f.create()
    f.dispose()
    const statements = vi.mocked(executeDatabaseFixture).mock.calls.map(([q]) => q.statement)
    expect(statements.filter((sql) => sql.includes('INSERT INTO sys_project_member'))).toHaveLength(
      1
    )
    const cleanup = statements.at(-1)!
    expect(cleanup).toContain('WHERE id IN (SELECT value::uuid')
    expect(cleanup).not.toMatch(/DELETE FROM sys_(account|project)\s/)
    expect(cleanup).not.toContain("role='VIEWER'")
  })
})
