export interface AlarmInboxIdentity {
  email: string
  password: string
  projectName: string
  projectKey: string
}
export interface AlarmInboxConfiguration {
  owned: boolean
  owner: AlarmInboxIdentity
  member: AlarmInboxIdentity
}
const fail = () => new Error('ALARM_INBOX_DEDICATED_IDENTITIES_REQUIRED')

/** 独占默认矩阵必须使用专用通知供给，浏览器和数据库夹具读取同一组身份。 */
export function alarmInboxConfiguration(
  env: NodeJS.ProcessEnv = process.env
): AlarmInboxConfiguration {
  const ordinaryOwner = env.E2E_OWNER_EMAIL ?? 'e2e-owner@example.com'
  const ordinaryMember = env.E2E_MEMBER_EMAIL ?? 'e2e-member@example.com'
  if (!env.E2E_OWNED_RUNTIME) {
    if (env.E2E_OWNED_RUNTIME_SHA256 || env.E2E_PG_DB?.startsWith('tc_console_wp03_')) throw fail()
    return {
      owned: false,
      owner: {
        email: ordinaryOwner,
        password: env.E2E_OWNER_PASSWORD ?? 'contract-pass-123',
        projectName: 'E2E项目',
        projectKey: env.E2E_PROJECT_KEY ?? ''
      },
      member: {
        email: ordinaryMember,
        password: env.E2E_MEMBER_PASSWORD ?? 'contract-pass-123',
        projectName: '成员项目',
        projectKey: ''
      }
    }
  }
  const read = (role: 'OWNER' | 'MEMBER', projectName: string): AlarmInboxIdentity => {
    const email = env[`E2E_ALARM_INBOX_${role}_EMAIL`]
    const password = env[`E2E_ALARM_INBOX_${role}_PASSWORD`]
    const projectKey = env[`E2E_ALARM_INBOX_${role}_PROJECT_KEY`]
    if (
      typeof email !== 'string' ||
      !/^[A-Za-z0-9._+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}$/.test(email) ||
      email.length > 254 ||
      !password ||
      password.length < 10 ||
      password.length > 128 ||
      !projectKey ||
      !/^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$/.test(projectKey)
    )
      throw fail()
    return { email: email.toLowerCase(), password, projectName, projectKey }
  }
  const owner = read('OWNER', 'E2E项目'),
    member = read('MEMBER', '成员项目')
  const ordinary = [ordinaryOwner.toLowerCase(), ordinaryMember.toLowerCase()]
  if (
    owner.email === member.email ||
    ordinary.includes(owner.email) ||
    ordinary.includes(member.email) ||
    owner.projectKey === member.projectKey ||
    [owner.projectKey, member.projectKey].includes(env.E2E_PROJECT_KEY ?? '')
  )
    throw fail()
  return { owned: true, owner, member }
}
