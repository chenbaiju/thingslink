import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import axios, { AxiosError, type AxiosAdapter, type InternalAxiosRequestConfig } from 'axios'
import { createPinia, setActivePinia } from 'pinia'
import { useUserStore } from '@/store/modules/user'
import http from '@/utils/http'
import { currentIdentityEpoch, invalidateIdentity } from '@/utils/http/identity-scope'
import { HttpError, showError, showSuccess } from '@/utils/http/error'

vi.hoisted(() => {
  vi.stubEnv('VITE_API_URL', '')
})

vi.mock('@/router', () => ({
  router: { currentRoute: { value: { path: '/home', fullPath: '/home' } }, push: vi.fn() }
}))
vi.mock('@/router/guards/beforeEach', () => ({ resetRouterState: vi.fn() }))
vi.mock('@/utils/router', () => ({ setPageTitle: vi.fn() }))
vi.mock('@/store/modules/setting', () => ({ useSettingStore: () => ({ $state: {} }) }))
vi.mock('@/store/modules/worktab', () => ({
  useWorktabStore: () => ({ opened: [], keepAliveExclude: [] })
}))
vi.mock('@/store/modules/menu', () => ({ useMenuStore: () => ({ setHomePath: vi.fn() }) }))
vi.mock('@/locales', () => ({ $t: (key: string) => key }))
vi.mock('@/utils/http/error', async (original) => ({
  ...(await original<typeof import('@/utils/http/error')>()),
  showError: vi.fn(),
  showSuccess: vi.fn()
}))

/** 手动交付真实Axios adapter结果，测试不以sleep猜测网络交错。 */
function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason: unknown) => void
  const promise = new Promise<T>((done, fail) => {
    resolve = done
    reject = fail
  })
  return { promise, resolve, reject }
}

/** Axios自定义adapter必须按HTTP状态主动reject，沿真实响应拦截器处理。 */
function unauthorized(config: InternalAxiosRequestConfig) {
  const response = {
    data: { code: 20020, message: '旧会话失效' },
    status: 401,
    statusText: 'Unauthorized',
    headers: {},
    config
  }
  return new AxiosError('401', AxiosError.ERR_BAD_REQUEST, config, undefined, response)
}

/** 成功响应形状保持真实adapter契约。 */
function success(config: InternalAxiosRequestConfig, data: unknown = { value: 'result' }) {
  return { data, status: 200, statusText: 'OK', headers: {}, config }
}

/** 接住拒绝再驱动交错，避免测试自身产生unhandled rejection。 */
function outcome<T>(promise: Promise<T>) {
  return promise.then(
    (value) => value,
    (error: unknown) => error
  )
}

/** 用户展示信息不同于身份字段，不能把昵称更新误判为新会话。 */
function identity(userId = 'account-a', currentProjectId = 'project-a') {
  return {
    userId,
    currentProjectId,
    tenantId: 'tenant-a',
    roles: [],
    buttons: [],
    userName: '读者',
    email: 'reader@example.test'
  }
}

let user: ReturnType<typeof useUserStore>
const originalAdapter = axios.defaults.adapter

beforeEach(() => {
  vi.useFakeTimers()
  vi.clearAllMocks()
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue({}))
  vi.stubGlobal('localStorage', { getItem: vi.fn(), setItem: vi.fn(), removeItem: vi.fn() })
  vi.stubGlobal('sessionStorage', { getItem: vi.fn(), setItem: vi.fn(), removeItem: vi.fn() })
  setActivePinia(createPinia())
  user = useUserStore()
  user.setToken('initial-token')
  user.setUserInfo(identity())
})

afterEach(() => {
  invalidateIdentity()
  vi.clearAllTimers()
  vi.useRealTimers()
  vi.unstubAllGlobals()
  axios.defaults.adapter = originalAdapter
})

describe('HTTP当前标签身份围栏（ADR0094）', () => {
  it('退出、同账号重新登录、项目与账号切换均换代，普通refresh与昵称修改不换代', () => {
    let epoch = currentIdentityEpoch()
    user.setRefreshedToken('rotated-token', epoch)
    user.setUserInfo({ ...identity(), userName: '新昵称' })
    expect(currentIdentityEpoch()).toBe(epoch)
    user.setToken('login-token')
    expect(currentIdentityEpoch()).toBeGreaterThan(epoch)
    epoch = currentIdentityEpoch()
    user.setLoginStatus(true)
    expect(currentIdentityEpoch()).toBeGreaterThan(epoch)
    epoch = currentIdentityEpoch()
    user.setUserInfo(identity('account-a', 'project-b'))
    expect(currentIdentityEpoch()).toBeGreaterThan(epoch)
    epoch = currentIdentityEpoch()
    user.setUserInfo(identity('account-b', 'project-b'))
    expect(currentIdentityEpoch()).toBeGreaterThan(epoch)
    epoch = currentIdentityEpoch()
    user.logOut()
    expect(currentIdentityEpoch()).toBeGreaterThan(epoch)
    expect(() => user.setRefreshedToken('late-token', epoch)).toThrow()
    expect(user.accessToken).toBe('')
  })

  it('请求调用后、真正进入adapter前身份改变，不向新身份发送旧请求', async () => {
    const adapter = vi.fn<AxiosAdapter>(async (config) => success(config))
    const result = outcome(http.get({ url: '/api/v1/projects', adapter }))
    user.setToken('new-login')
    expect(axios.isCancel(await result)).toBe(true)
    expect(adapter).not.toHaveBeenCalled()
    expect(showError).not.toHaveBeenCalled()
  })

  it.each(['account', 'project', 'logout'])(
    '旧200在%s变更后安静拒绝且不显示成功消息',
    async (change) => {
      const entered = deferred<InternalAxiosRequestConfig>()
      const response = deferred<ReturnType<typeof success>>()
      const adapter: AxiosAdapter = (config) => {
        entered.resolve(config)
        return response.promise
      }
      const result = outcome(
        http.get({ url: '/api/v1/projects', adapter, showSuccessMessage: true })
      )
      const config = await entered.promise
      if (change === 'logout') user.logOut()
      else
        user.setUserInfo(
          change === 'project' ? identity('account-a', 'project-b') : identity('account-b')
        )
      response.resolve(success(config))
      expect(axios.isCancel(await result)).toBe(true)
      expect(showError).not.toHaveBeenCalled()
      expect(showSuccess).not.toHaveBeenCalled()
    }
  )

  it('旧401不进入refresh、不重放、不安排新身份登出', async () => {
    const entered = deferred<InternalAxiosRequestConfig>()
    const response = deferred<ReturnType<typeof success>>()
    const refresh = vi.fn<AxiosAdapter>(async (config) =>
      success(config, { accessToken: 'forbidden' })
    )
    axios.defaults.adapter = refresh
    const adapter = vi.fn<AxiosAdapter>((config) => {
      entered.resolve(config)
      return response.promise
    })
    const logout = vi.spyOn(user, 'logOut')
    const result = outcome(http.get({ url: '/api/v1/projects', adapter }))
    const config = await entered.promise
    user.setToken('new-login')
    response.reject(unauthorized(config))
    expect(axios.isCancel(await result)).toBe(true)
    await vi.advanceTimersByTimeAsync(4000)
    expect(refresh).not.toHaveBeenCalled()
    expect(adapter).toHaveBeenCalledTimes(1)
    expect(logout).not.toHaveBeenCalled()
    expect(showError).not.toHaveBeenCalled()
  })

  it.each(['success', 'failure'])('旧refresh的%s不能覆盖新token或登出新身份', async (mode) => {
    const refreshEntered = deferred<InternalAxiosRequestConfig>()
    const refreshResponse = deferred<ReturnType<typeof success>>()
    axios.defaults.adapter = (config) => {
      refreshEntered.resolve(config)
      return refreshResponse.promise
    }
    const adapter = vi.fn<AxiosAdapter>(async (config) => {
      throw unauthorized(config)
    })
    const logout = vi.spyOn(user, 'logOut')
    const result = outcome(http.get({ url: '/api/v1/projects', adapter }))
    const refreshConfig = await refreshEntered.promise
    user.setToken('new-login')
    if (mode === 'success')
      refreshResponse.resolve(success(refreshConfig, { accessToken: 'late-old-token' }))
    else refreshResponse.reject(unauthorized(refreshConfig))
    expect(axios.isCancel(await result)).toBe(true)
    await vi.advanceTimersByTimeAsync(4000)
    expect(user.accessToken).toBe('new-login')
    expect(adapter).toHaveBeenCalledTimes(1)
    expect(logout).not.toHaveBeenCalled()
    expect(showError).not.toHaveBeenCalled()
  })

  it('新身份401不会加入旧身份refresh promise，旧组结束不清掉新组', async () => {
    const started = [deferred<InternalAxiosRequestConfig>(), deferred<InternalAxiosRequestConfig>()]
    const replies = [deferred<ReturnType<typeof success>>(), deferred<ReturnType<typeof success>>()]
    let refreshCount = 0
    axios.defaults.adapter = (config) => {
      const n = refreshCount++
      started[n].resolve(config)
      return replies[n].promise
    }
    const oldAdapter: AxiosAdapter = async (config) => {
      throw unauthorized(config)
    }
    const old = outcome(http.get({ url: '/api/v1/projects', adapter: oldAdapter }))
    const oldConfig = await started[0].promise
    user.setToken('new-login')
    const newAdapter = vi.fn<AxiosAdapter>(async (config) => {
      if (config.headers.get('Authorization') !== 'Bearer new-refreshed') throw unauthorized(config)
      return success(config)
    })
    const first = outcome(http.get({ url: '/api/v1/projects', adapter: newAdapter }))
    const newConfig = await started[1].promise
    replies[0].resolve(success(oldConfig, { accessToken: 'old-refreshed' }))
    expect(axios.isCancel(await old)).toBe(true)
    const second = outcome(http.get({ url: '/api/v1/projects', adapter: newAdapter }))
    // flush微任务确保第二个401已经到协调器，不靠定时等待猜是否加入。
    await vi.advanceTimersByTimeAsync(0)
    expect(refreshCount).toBe(2)
    replies[1].resolve(success(newConfig, { accessToken: 'new-refreshed' }))
    expect(await first).toEqual({ value: 'result' })
    expect(await second).toEqual({ value: 'result' })
    expect(user.accessToken).toBe('new-refreshed')
    expect(showError).not.toHaveBeenCalled()
  })

  it('同身份并发401仍只refresh一次，两个请求用新Bearer正常重放', async () => {
    const started = deferred<InternalAxiosRequestConfig>()
    const response = deferred<ReturnType<typeof success>>()
    const refresh = vi.fn<AxiosAdapter>((config) => {
      started.resolve(config)
      return response.promise
    })
    axios.defaults.adapter = refresh
    const adapter = vi.fn<AxiosAdapter>(async (config) => {
      if (config.headers.get('Authorization') !== 'Bearer refreshed') throw unauthorized(config)
      return success(config)
    })
    const epoch = currentIdentityEpoch()
    const results = Promise.all([
      http.get({ url: '/api/v1/projects', adapter }),
      http.get({ url: '/api/v1/projects', adapter })
    ])
    const config = await started.promise
    response.resolve(success(config, { accessToken: 'refreshed' }))
    expect(await results).toEqual([{ value: 'result' }, { value: 'result' }])
    expect(refresh).toHaveBeenCalledTimes(1)
    expect(adapter).toHaveBeenCalledTimes(4)
    expect(currentIdentityEpoch()).toBe(epoch)
  })

  it.each([
    { status: 403, code: 30002 },
    { status: 429, code: 20030 },
    { status: 500, code: 90000 }
  ])('正常刷新后的重放$status保留业务错误，不触发登出', async ({ status, code }) => {
    axios.defaults.adapter = async (config) => success(config, { accessToken: 'refreshed' })
    const adapter: AxiosAdapter = async (config) => {
      if (config.headers.get('Authorization') !== 'Bearer refreshed') throw unauthorized(config)
      throw new AxiosError('业务拒绝', AxiosError.ERR_BAD_REQUEST, config, undefined, {
        data: { code, message: '业务拒绝', traceId: 'business-trace' },
        status,
        statusText: 'Rejected',
        headers: {},
        config
      })
    }
    const logout = vi.spyOn(user, 'logOut')
    const result = await outcome(http.get({ url: '/api/v1/projects', adapter }))
    expect(result).toBeInstanceOf(HttpError)
    expect(result).toMatchObject({ code, traceId: 'business-trace' })
    await vi.advanceTimersByTimeAsync(4000)
    expect(logout).not.toHaveBeenCalled()
    expect(showError).toHaveBeenCalledTimes(1)
  })

  it('正常401失败保留原诊断并在500ms登出，同一身份只提示一次', async () => {
    axios.defaults.adapter = async (config) => {
      throw unauthorized(config)
    }
    const adapter: AxiosAdapter = async (config) => {
      throw unauthorized(config)
    }
    const logout = vi.spyOn(user, 'logOut')
    const result = await outcome(http.get({ url: '/api/v1/projects', adapter }))
    expect(result).toBeInstanceOf(HttpError)
    expect(showError).toHaveBeenCalledTimes(1)
    expect(logout).not.toHaveBeenCalled()
    await vi.advanceTimersByTimeAsync(500)
    expect(logout).toHaveBeenCalledTimes(1)
  })

  it('旧身份500ms延迟登出不能清掉新登录', async () => {
    axios.defaults.adapter = async (config) => {
      throw unauthorized(config)
    }
    const adapter: AxiosAdapter = async (config) => {
      throw unauthorized(config)
    }
    const logout = vi.spyOn(user, 'logOut')
    expect(await outcome(http.get({ url: '/api/v1/projects', adapter }))).toBeInstanceOf(HttpError)
    user.setToken('new-login')
    await vi.advanceTimersByTimeAsync(4000)
    expect(logout).not.toHaveBeenCalled()
    expect(user.accessToken).toBe('new-login')
  })

  it('401后取消原请求，共享refresh成功仍续期并只重放未取消的同身份请求', async () => {
    const started = deferred<InternalAxiosRequestConfig>()
    const refreshResponse = deferred<ReturnType<typeof success>>()
    const refresh = vi.fn<AxiosAdapter>((config) => {
      started.resolve(config)
      return refreshResponse.promise
    })
    axios.defaults.adapter = refresh
    const adapter = vi.fn<AxiosAdapter>(async (config) => {
      if (config.headers.get('Authorization') !== 'Bearer shared-refreshed')
        throw unauthorized(config)
      return success(config)
    })
    const controller = new AbortController()
    const logout = vi.spyOn(user, 'logOut')
    const cancelled = outcome(
      http.get({ url: '/api/v1/projects', adapter, signal: controller.signal })
    )
    const refreshConfig = await started.promise
    const remaining = outcome(http.get({ url: '/api/v1/projects', adapter }))
    // 驱动另一个真实401进入同身份单飞后再取消，证明abort不取消共享的会话续期。
    await vi.advanceTimersByTimeAsync(0)
    expect(refresh).toHaveBeenCalledTimes(1)
    controller.abort()
    refreshResponse.resolve(success(refreshConfig, { accessToken: 'shared-refreshed' }))
    expect(axios.isCancel(await cancelled)).toBe(true)
    expect(await remaining).toEqual({ value: 'result' })
    expect(user.accessToken).toBe('shared-refreshed')
    expect(adapter).toHaveBeenCalledTimes(3)
    await vi.advanceTimersByTimeAsync(4000)
    expect(logout).not.toHaveBeenCalled()
    expect(showError).not.toHaveBeenCalled()
    expect(showSuccess).not.toHaveBeenCalled()
  })

  it('401后取消唯一原请求，随后共享refresh失败不重放、不提示、不登出', async () => {
    const started = deferred<InternalAxiosRequestConfig>()
    const refreshResponse = deferred<ReturnType<typeof success>>()
    axios.defaults.adapter = (config) => {
      started.resolve(config)
      return refreshResponse.promise
    }
    const adapter = vi.fn<AxiosAdapter>(async (config) => {
      throw unauthorized(config)
    })
    const controller = new AbortController()
    const logout = vi.spyOn(user, 'logOut')
    const cancelled = outcome(
      http.get({ url: '/api/v1/projects', adapter, signal: controller.signal })
    )
    const refreshConfig = await started.promise
    controller.abort()
    refreshResponse.reject(unauthorized(refreshConfig))
    expect(axios.isCancel(await cancelled)).toBe(true)
    expect(user.accessToken).toBe('initial-token')
    expect(adapter).toHaveBeenCalledTimes(1)
    await vi.advanceTimersByTimeAsync(4000)
    expect(logout).not.toHaveBeenCalled()
    expect(showError).not.toHaveBeenCalled()
    expect(showSuccess).not.toHaveBeenCalled()
  })

  it('AbortSignal取消的请求沿Axios取消语义退出，不显示网络错误或登出', async () => {
    const entered = deferred<InternalAxiosRequestConfig>()
    const response = deferred<ReturnType<typeof success>>()
    const controller = new AbortController()
    const adapter: AxiosAdapter = (config) => {
      entered.resolve(config)
      return response.promise
    }
    const result = outcome(
      http.get({ url: '/api/v1/projects', adapter, signal: controller.signal })
    )
    const config = await entered.promise
    controller.abort()
    response.resolve(success(config))
    expect(axios.isCancel(await result)).toBe(true)
    expect(showError).not.toHaveBeenCalled()
  })
})

describe('分析提交单次发送', () => {
  const url =
    '/api/v1/projects/11111111-2222-4333-8444-555555555555/assistant/analysis-runs' as const
  it.each([401, 403, 429, 500, 503, 'network', 'timeout'])(
    '%s不重发原分析请求，401不刷新或自动清除身份',
    async (kind) => {
      const refresh = vi.fn<AxiosAdapter>(async (config) => success(config, { accessToken: 'new' }))
      axios.defaults.adapter = refresh
      const adapter = vi.fn<AxiosAdapter>(async (config) => {
        if (typeof kind !== 'number')
          throw new AxiosError(
            'network unavailable',
            kind === 'timeout' ? 'ECONNABORTED' : 'ERR_NETWORK',
            config
          )
        throw new AxiosError('failure', 'ERR_BAD_RESPONSE', config, undefined, {
          status: kind,
          statusText: 'Failed',
          headers: {},
          config,
          data: { code: kind, message: 'PRIVATE_RESPONSE', details: ['PRIVATE_DETAIL'] }
        })
      })
      const logout = vi.spyOn(user, 'logOut')
      const result = await outcome(
        http.post({ url, data: { template: 'STATUS_SUMMARY' }, adapter, showErrorMessage: false })
      )
      expect(result).toBeInstanceOf(HttpError)
      await vi.advanceTimersByTimeAsync(4000)
      expect(adapter).toHaveBeenCalledOnce()
      expect(refresh).not.toHaveBeenCalled()
      expect(logout).not.toHaveBeenCalled()
      expect(user.accessToken).toBe('initial-token')
      if (kind === 401) {
        expect(result).toMatchObject({
          code: 401,
          message: '分析请求未重发，请恢复登录后查询原调用状态。'
        })
        expect(JSON.stringify(result)).not.toContain('PRIVATE_')
        expect(showError).not.toHaveBeenCalled()
      }
    }
  )

  it.each(['status-get', 'other-post'])('%s仍按原合同刷新一次并恢复', async (kind) => {
    const refresh = vi.fn<AxiosAdapter>(async (config) =>
      success(config, { accessToken: 'refreshed' })
    )
    axios.defaults.adapter = refresh
    const adapter = vi.fn<AxiosAdapter>(async (config) => {
      if (config.headers.get('Authorization') !== 'Bearer refreshed') throw unauthorized(config)
      return success(config)
    })
    const result =
      kind === 'status-get'
        ? await http.get({ url: `${url}/status`, adapter })
        : await http.post({ url: '/api/v1/projects', data: {}, adapter })
    expect(result).toEqual({ value: 'result' })
    expect(refresh).toHaveBeenCalledOnce()
    expect(adapter).toHaveBeenCalledTimes(2)
  })

  it('分析401不加入另一只读请求正在进行的刷新，也不在刷新成功后再发送', async () => {
    const started = deferred<InternalAxiosRequestConfig>()
    const response = deferred<ReturnType<typeof success>>()
    axios.defaults.adapter = (config) => {
      started.resolve(config)
      return response.promise
    }
    const reader: AxiosAdapter = async (config) => {
      if (config.headers.get('Authorization') !== 'Bearer refreshed') throw unauthorized(config)
      return success(config)
    }
    const reading = http.get({ url: '/api/v1/projects', adapter: reader })
    const refreshConfig = await started.promise
    const analysis = vi.fn<AxiosAdapter>(async (config) => {
      throw unauthorized(config)
    })
    const result = await outcome(http.post({ url, adapter: analysis }))
    expect(result).toMatchObject({ code: 401 })
    response.resolve(success(refreshConfig, { accessToken: 'refreshed' }))
    expect(await reading).toEqual({ value: 'result' })
    expect(analysis).toHaveBeenCalledOnce()
    expect(user.accessToken).toBe('refreshed')
  })

  it('分析在途切换身份后，旧401只取消，不提示或影响新登录', async () => {
    const entered = deferred<InternalAxiosRequestConfig>()
    const response = deferred<ReturnType<typeof success>>()
    const adapter = vi.fn<AxiosAdapter>((config) => {
      entered.resolve(config)
      return response.promise
    })
    const refresh = vi.fn<AxiosAdapter>(async (config) => success(config))
    axios.defaults.adapter = refresh
    const result = outcome(http.post({ url, adapter }))
    const config = await entered.promise
    user.setToken('next-login')
    response.reject(unauthorized(config))
    expect(axios.isCancel(await result)).toBe(true)
    expect(adapter).toHaveBeenCalledOnce()
    expect(refresh).not.toHaveBeenCalled()
    expect(showError).not.toHaveBeenCalled()
    expect(user.accessToken).toBe('next-login')
  })
})
