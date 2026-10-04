import createClient from 'openapi-fetch'
import { afterAll, beforeAll, describe, expect, it } from 'vitest'
import type { paths } from '@/types/api/schema'

/**
 * OpenAPI 行为契约测试（G1-C2c）。
 *
 * 用测试专用 openapi-fetch 派生客户端（直接消费生成的 paths 类型，不改造生产 Axios 请求层）
 * 调用真实后端 + 真实数据库，证明「类型没漂」之外的运行时行为：成功、验证失败、未认证、
 * 授权失败、并发冲突、限流、幂等完成墓碑，以及错误响应的 { code, message, traceId, details } 形状一致。
 *
 * 前置：真实后端已在 CONTRACT_BASE_URL（默认 http://localhost:8080）运行；
 * 已通过 deploy/scripts/add-console-account.sh 预置两个**已验证**账号（OWNER 与成员），
 * 凭据经环境变量 CONTRACT_OWNER_EMAIL / CONTRACT_OWNER_PASSWORD / CONTRACT_MEMBER_EMAIL /
 * CONTRACT_MEMBER_PASSWORD 传入（见 scripts/run-contract-tests.sh）。
 *
 * 注意：本测试使用共享本地 deploy 数据库，每次运行用唯一后缀（账号/项目/标识）避免撞车，
 * 但不清理已写入的数据——「隔离环境」由 CI 工作流在临时栈上实现，本文件只证明行为契约。
 */
const baseUrl = process.env.CONTRACT_BASE_URL ?? 'http://localhost:8080'
const client = createClient<paths>({ baseUrl })

const ownerEmail = process.env.CONTRACT_OWNER_EMAIL ?? 'contract-owner@example.com'
const ownerPassword = process.env.CONTRACT_OWNER_PASSWORD ?? 'contract-owner-pass-123'
const memberEmail = process.env.CONTRACT_MEMBER_EMAIL ?? 'contract-member@example.com'
const memberPassword = process.env.CONTRACT_MEMBER_PASSWORD ?? 'contract-member-pass-123'

/** 唯一后缀，避免多轮运行撞上「邮箱/标识已存在」。 */
const suffix = `${Date.now()}`

/** ApiError 形状：生成类型把四个字段都标为可选，但运行时权威契约要求全部存在（code/message/traceId 必填、details 恒为数组）。 */
const assertApiError = (error: unknown, expectedCode: number) => {
  const err = error as
    | { code?: number; message?: string; traceId?: string; details?: string[] }
    | undefined
  expect(err).toBeDefined()
  expect(err?.code).toBe(expectedCode)
  expect(typeof err?.message).toBe('string')
  expect((err?.message ?? '').length).toBeGreaterThan(0)
  expect(typeof err?.traceId).toBe('string')
  expect((err?.traceId ?? '').length).toBeGreaterThan(0)
  // 权威契约（ERROR_CODES.md）：details 无内容时也是空数组，不允许缺省
  expect(Array.isArray(err?.details)).toBe(true)
}

/** 从 Set-Cookie 里取 tc_refresh 值（切换项目与刷新令牌都要带它，ADR 0010）。 */
const extractRefreshToken = (setCookie: string | null): string =>
  setCookie?.match(/tc_refresh=([^;]+)/)?.[1] ?? ''

describe('OpenAPI 行为契约（真实后端）', () => {
  let ownerToken = ''
  let ownerRefresh = ''
  let memberToken = ''
  let memberRefresh = ''
  let projectId = ''

  beforeAll(async () => {
    const ownerLogin = await client.POST('/api/v1/auth/login', {
      body: { email: ownerEmail, password: ownerPassword }
    })
    expect(ownerLogin.response.status).toBe(200)
    expect(ownerLogin.data?.accessToken).toBeTruthy()
    ownerToken = ownerLogin.data!.accessToken!
    ownerRefresh = extractRefreshToken(ownerLogin.response.headers.get('set-cookie'))

    const memberLogin = await client.POST('/api/v1/auth/login', {
      body: { email: memberEmail, password: memberPassword }
    })
    expect(memberLogin.response.status).toBe(200)
    memberToken = memberLogin.data!.accessToken!
    memberRefresh = extractRefreshToken(memberLogin.response.headers.get('set-cookie'))
  })

  afterAll(() => {
    // 契约测试在共享本地栈运行，不在此清理数据；「隔离环境」由 CI 临时栈负责（见 run-contract-tests.sh 的 CI 化）。
  })

  it('未认证访问返回 401 + 20020，错误形状含 message/traceId', async () => {
    const { response, error } = await client.GET('/api/v1/projects')
    expect(response.status).toBe(401)
    assertApiError(error, 20020)
  })

  it('注册参数校验失败返回 400 + 10001', async () => {
    const { response, error } = await client.POST('/api/v1/auth/register', {
      body: { email: 'not-an-email', password: 'short' }
    })
    expect(response.status).toBe(400)
    assertApiError(error, 10001)
  })

  it('登录口令错误返回 401 + 20001', async () => {
    const { response, error } = await client.POST('/api/v1/auth/login', {
      body: { email: ownerEmail, password: 'definitely-wrong-pass' }
    })
    expect(response.status).toBe(401)
    assertApiError(error, 20001)
  })

  it('OWNER 创建项目成功，再切换项目拿到项目作用域令牌', async () => {
    const created = await client.POST('/api/v1/projects', {
      body: { name: `契约测试项目-${suffix}`, region: 'sh-1' },
      headers: { Authorization: `Bearer ${ownerToken}` }
    })
    expect(created.response.status).toBe(200)
    expect(created.data?.id).toBeTruthy()
    projectId = created.data!.id!

    const switched = await client.POST('/api/v1/auth/switch-project', {
      body: { projectId },
      headers: { Authorization: `Bearer ${ownerToken}`, Cookie: `tc_refresh=${ownerRefresh}` }
    })
    expect(switched.response.status).toBe(200)
    expect(switched.data?.accessToken).toBeTruthy()
    ownerToken = switched.data!.accessToken!
  })

  it('并发两次邀请同一成员：一个 200、一个 409/50011', async () => {
    const invite = () =>
      client.POST('/api/v1/projects/{projectId}/members', {
        params: { path: { projectId } },
        body: { email: memberEmail, role: 'VIEWER' },
        headers: { Authorization: `Bearer ${ownerToken}` }
      })
    const [a, b] = await Promise.all([invite(), invite()])
    const statuses = [a.response.status, b.response.status].sort()
    expect(statuses).toEqual([200, 409])
    const conflict = a.response.status === 409 ? a.error : b.error
    assertApiError(conflict, 50011)
  })

  it('VIEWER 成员写操作返回 403 + 30002', async () => {
    const switched = await client.POST('/api/v1/auth/switch-project', {
      body: { projectId },
      headers: { Authorization: `Bearer ${memberToken}`, Cookie: `tc_refresh=${memberRefresh}` }
    })
    expect(switched.response.status).toBe(200)
    const viewerToken = switched.data!.accessToken!

    const create = await client.POST('/api/v1/projects/{projectId}/device-types', {
      params: { path: { projectId } },
      body: {
        typeKey: 'viewer_cannot',
        name: '越权',
        deviceKind: 'DIRECT',
        payloadProtocol: 'STANDARD',
        networkType: 'WIFI'
      },
      headers: { Authorization: `Bearer ${viewerToken}` }
    })
    expect(create.response.status).toBe(403)
    assertApiError(create.error, 30002)
  })

  it('设备类型创建、重复标识 409/30003、发布成功', async () => {
    const body = {
      typeKey: `contract_type_${suffix}`,
      name: '契约设备类型',
      deviceKind: 'DIRECT',
      payloadProtocol: 'STANDARD',
      networkType: 'WIFI'
    } as const

    const createType = await client.POST('/api/v1/projects/{projectId}/device-types', {
      params: { path: { projectId } },
      body,
      headers: { Authorization: `Bearer ${ownerToken}` }
    })
    expect(createType.response.status).toBe(201)
    const typeId = createType.data!.id!

    const duplicate = await client.POST('/api/v1/projects/{projectId}/device-types', {
      params: { path: { projectId } },
      body: { ...body, name: '重复' },
      headers: { Authorization: `Bearer ${ownerToken}` }
    })
    expect(duplicate.response.status).toBe(409)
    assertApiError(duplicate.error, 30003)

    const publish = await client.POST('/api/v1/projects/{projectId}/device-types/{id}/publish', {
      params: { path: { projectId, id: typeId } },
      headers: { Authorization: `Bearer ${ownerToken}` }
    })
    expect(publish.response.status).toBe(200)
  })

  it('设备创建、同键完成墓碑 409/10014、同键异体 409/10009、无键重复 409/30021', async () => {
    const list = await client.GET('/api/v1/projects/{projectId}/device-types', {
      params: { path: { projectId } },
      headers: { Authorization: `Bearer ${ownerToken}` }
    })
    const typeId = list.data?.find((t) => t.typeKey === `contract_type_${suffix}`)?.id
    expect(typeId).toBeTruthy()

    const deviceBody = {
      deviceTypeId: typeId,
      deviceKey: `contract_dev_${suffix}`,
      name: '契约设备'
    }
    const idemKey = `contract-dev-${suffix}`

    const createDevice = await client.POST('/api/v1/projects/{projectId}/devices', {
      params: { path: { projectId } },
      body: deviceBody,
      headers: { Authorization: `Bearer ${ownerToken}`, 'Idempotency-Key': idemKey }
    })
    expect(createDevice.response.status).toBe(201)
    const firstDeviceId = createDevice.data?.id
    expect(firstDeviceId).toBeTruthy()

    // 公共层不保存历史响应；同一主体同键同请求命中完成墓碑，客户端需另行查询业务结果。
    const replay = await client.POST('/api/v1/projects/{projectId}/devices', {
      params: { path: { projectId } },
      body: deviceBody,
      headers: { Authorization: `Bearer ${ownerToken}`, 'Idempotency-Key': idemKey }
    })
    expect(replay.response.status).toBe(409)
    assertApiError(replay.error, 10014)
    expect(replay.response.headers.get('idempotency-replayed')).toBeNull()

    // 同键不同请求 → 409/10009
    const conflict = await client.POST('/api/v1/projects/{projectId}/devices', {
      params: { path: { projectId } },
      body: { ...deviceBody, deviceKey: `contract_dev_alt_${suffix}` },
      headers: { Authorization: `Bearer ${ownerToken}`, 'Idempotency-Key': idemKey }
    })
    expect(conflict.response.status).toBe(409)
    assertApiError(conflict.error, 10009)

    // 无幂等键重复设备标识 → 409/30021（业务唯一约束）
    const dupDevice = await client.POST('/api/v1/projects/{projectId}/devices', {
      params: { path: { projectId } },
      body: { ...deviceBody, name: '重复设备' },
      headers: { Authorization: `Bearer ${ownerToken}` }
    })
    expect(dupDevice.response.status).toBe(409)
    assertApiError(dupDevice.error, 30021)
  })

  it('注册限流：严格序列 [204, 204, 204, 429]，第四次 10029', async () => {
    // 限流计数在 RegistrationService 内（@Valid 通过之后），必须用合法载荷才会触发；
    // 每个合法注册都创建一个未验证账号，窗口上限 3，第 4 次应 429。
    // 前提：本测试的后端以专用 Redis DB 启动（run-contract-tests.sh 每次运行先 FLUSHDB 该 DB），
    // 注册 IP 限流计数每轮从 0 开始，因此「前三次成功、第四次限流」可严格断言；
    // 不得在共享 dev Redis DB 0 上运行本断言（计数会跨轮累计导致假绿/假红）。
    const statuses: number[] = []
    let fourthError: unknown
    for (let i = 0; i < 4; i++) {
      const { response, error } = await client.POST('/api/v1/auth/register', {
        body: {
          email: `ratelimit-${suffix}-${i}@example.com`,
          password: 'contract-ratelimit-pass-123'
        }
      })
      statuses.push(response.status)
      if (i === 3) fourthError = error
    }
    expect(statuses).toEqual([204, 204, 204, 429])
    assertApiError(fourthError, 10029)
  })
})
