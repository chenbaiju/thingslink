/**
 * HTTP 请求封装模块
 * 基于 Axios 封装的 HTTP 请求工具，提供统一的请求/响应处理
 *
 * ## 主要功能
 *
 * - 请求/响应拦截器（自动添加 Token、统一错误处理）
 * - 401 未授权自动登出（带防抖机制）
 * - 请求失败自动重试（可配置）
 * - 统一的成功/错误消息提示
 * - 支持 GET/POST/PUT/DELETE 等常用方法
 *
 * @module utils/http
 * @author Things Link Team
 */

import axios, { AxiosRequestConfig, AxiosResponse, InternalAxiosRequestConfig } from 'axios'
import { useUserStore } from '@/store/modules/user'
import { ApiStatus } from './status'
import { HttpError, handleError, preferApiError, showError, showSuccess } from './error'
import { $t } from '@/locales'
import type { ApiUrl, HttpMethod } from './paths'
import { RefreshCoordinator } from './refresh-coordinator'
import { assertCurrentIdentity, currentIdentityEpoch } from './identity-scope'
/**
 * 后端统一错误响应（架构文档 11.1）。
 *
 * 与 src/types/api/schema.d.ts 中由 OpenAPI 生成的 ApiError 同构。这里单独声明
 * 而不直接引用生成物，是因为 HTTP 层要在**契约之外**也能工作 ——
 * 网关返回的 502、Nginx 的 504 都不会有这个结构。
 */
interface ApiError {
  code: number
  message: string
  traceId?: string
  details?: string[]
}

/** 请求配置常量 */
const REQUEST_TIMEOUT = 15000
const LOGOUT_DELAY = 500
const MAX_RETRIES = 0
const RETRY_DELAY = 1000
const UNAUTHORIZED_DEBOUNCE_TIME = 3000

/** 401防抖状态 */
let unauthorizedIdentity: number | null = null
let unauthorizedTimer: NodeJS.Timeout | null = null

/** 扩展 AxiosRequestConfig */
interface ExtendedAxiosRequestConfig extends AxiosRequestConfig {
  showErrorMessage?: boolean
  showSuccessMessage?: boolean
  /** 内部标记：本请求已经因 401 刷新并重放过一次，不再重试（防死循环） */
  _retriedAfterRefresh?: boolean
  /** 调用时捕获，重放保留原值，不能重新绑定到后来的身份。 */
  _identityEpoch?: number
}

/**
 * 带路径约束的请求配置：`url` 必须是 OpenAPI 契约里真实存在、且支持该方法的路径。
 *
 * 后端改了路径或方法之后，调用点会**编译失败**，而不是在运行时 404
 * （见 {@link ApiUrl} 的说明）。
 */
type TypedRequestConfig<M extends HttpMethod> = Omit<ExtendedAxiosRequestConfig, 'url'> & {
  url: ApiUrl<M>
}

/** 认证接口路径。刷新自身 401 时不能再触发刷新，否则会无限递归 */
const LOGIN_URL = '/api/v1/auth/login'
const REFRESH_URL = '/api/v1/auth/refresh'
const LOGOUT_URL = '/api/v1/auth/logout'

const { VITE_API_URL, VITE_WITH_CREDENTIALS } = import.meta.env

/** Axios实例 */
const axiosInstance = axios.create({
  timeout: REQUEST_TIMEOUT,
  baseURL: VITE_API_URL,
  withCredentials: VITE_WITH_CREDENTIALS === 'true',
  validateStatus: (status) => status >= 200 && status < 300,
  transformResponse: [
    (data, headers) => {
      const contentType = headers['content-type']
      if (contentType?.includes('application/json')) {
        try {
          return JSON.parse(data)
        } catch {
          return data
        }
      }
      return data
    }
  ]
})

/** 请求拦截器 */
axiosInstance.interceptors.request.use(
  (request: InternalAxiosRequestConfig) => {
    assertRequestIdentity(request)
    const { accessToken } = useUserStore()
    // 必须带 Bearer 前缀。模板原本直接发裸令牌（对接它自家 mock 时够用），
    // 而后端是标准的 OAuth2 resource server，只认 `Bearer <token>` —— 少了前缀
    // 会被当成未认证，表现为登录成功后紧接着的 /me 返回 401，
    // 401 又触发自动登出，于是陷入「登录 → 立刻被踢回登录页」的循环
    if (accessToken) request.headers.set('Authorization', `Bearer ${accessToken}`)

    if (request.data && !(request.data instanceof FormData) && !request.headers['Content-Type']) {
      request.headers.set('Content-Type', 'application/json')
      request.data = JSON.stringify(request.data)
    }

    return request
  },
  (error) => {
    if (axios.isCancel(error)) return Promise.reject(error)
    showError(createHttpError($t('httpMsg.requestConfigError'), ApiStatus.error))
    return Promise.reject(error)
  }
)

/**
 * 响应拦截器。
 *
 * 【与模板的差别：本项目不用 { code, msg, data } 信封】
 * 模板原本假设所有响应都包一层信封、HTTP 状态码恒为 200、靠 body 里的 code
 * 判断成败。本项目不这么做（架构文档 11.1）：
 *
 *   成功 → 2xx + 裸响应体
 *   失败 → 对应的 HTTP 状态码 + { code, message, traceId, details }
 *
 * 「HTTP 状态码表达语义类别，业务错误码表达具体原因，二者都要有」——
 * 信封式响应会让状态码永远是 200，等于废掉前半句：网关、监控、重试策略这些
 * 通用中间件全都失去判断依据，而且 OpenAPI 生成的类型会退化成 data: any。
 *
 * 因此这里的分工是：axios 按状态码决定走成功还是失败分支，失败分支从响应体里
 * 解析 ApiError。
 */
axiosInstance.interceptors.response.use(
  (response: AxiosResponse) => {
    assertRequestIdentity(response.config)
    return response
  },
  (error) => {
    if (axios.isCancel(error)) return Promise.reject(error)
    const originalConfig = error.config as ExtendedAxiosRequestConfig | undefined
    if (originalConfig) assertRequestIdentity(originalConfig)
    const identity = originalConfig?._identityEpoch ?? currentIdentityEpoch()
    const status = error.response?.status
    // 后端的错误响应体就是 ApiError；网络错误等情况下它不存在
    const apiError = error.response?.data as ApiError | undefined

    // 基线登记只能显式恢复原意图，任何错误都不能重放或携带不可信原文。
    if (originalConfig && isSingleAttemptOtaBaselineRegistration(originalConfig)) {
      return Promise.reject(
        createHttpError(
          '受控类型基线登记未自动重发；结果未知时请使用原正文和原键恢复。',
          status === ApiStatus.unauthorized
            ? ApiStatus.unauthorized
            : Number.isSafeInteger(apiError?.code)
              ? apiError!.code
              : (status ?? ApiStatus.error),
          { outcomeUnknown: !error.response || (typeof status === 'number' && status >= 500) }
        )
      )
    }

    // 根签包导入只能显式恢复原意图，任何错误都不能重放或携带不可信原文。
    if (originalConfig && isSingleAttemptOtaTrustImport(originalConfig)) {
      return Promise.reject(
        createHttpError(
          '受控签包导入未自动重发；结果未知时请使用原正文和原键恢复。',
          status === ApiStatus.unauthorized
            ? ApiStatus.unauthorized
            : Number.isSafeInteger(apiError?.code)
              ? apiError!.code
              : (status ?? ApiStatus.error),
          { outcomeUnknown: !error.response || (typeof status === 'number' && status >= 500) }
        )
      )
    }

    // 下载地址是短时能力；错误正文、签名查询串与认证重放均不得进入通用恢复流程。
    if (originalConfig && isSingleAttemptOtaDownload(originalConfig)) {
      return Promise.reject(
        createHttpError(
          '下载地址申领未自动重发；结果未知时请按原申请重试，已完成的地址不能回查。',
          status === ApiStatus.unauthorized
            ? ApiStatus.unauthorized
            : Number.isSafeInteger(apiError?.code)
              ? apiError!.code
              : (status ?? ApiStatus.error),
          { outcomeUnknown: !error.response || (typeof status === 'number' && status >= 500) }
        )
      )
    }

    // 产品注册秘密的生成/轮换没有幂等回查，任何失败都不能重发或传播不可信错误正文。
    if (originalConfig && isSingleAttemptProductCredential(originalConfig)) {
      return Promise.reject(
        createHttpError(
          status === ApiStatus.unauthorized
            ? '产品凭据请求未重发，请恢复登录后核对类型并重新确认轮换影响。'
            : '产品凭据结果未确认，请核对类型；秘密不能回查，再次申请会重新轮换。',
          status === ApiStatus.unauthorized
            ? ApiStatus.unauthorized
            : typeof apiError?.code === 'number'
              ? apiError.code
              : (status ?? ApiStatus.error)
        )
      )
    }

    if (status === ApiStatus.unauthorized) {
      const config = error.config as ExtendedAxiosRequestConfig | undefined
      const url = config?.url ?? ''

      // 分析调用只能发送一次；401也不能自动重放、丢弃原意图或推断没有消费。
      // 保留当前身份，由调用界面提示恢复登录后只读核对原调用；不携带原错误正文。
      if (config && isSingleAttemptAnalysis(config)) {
        return Promise.reject(
          createHttpError('分析请求未重发，请恢复登录后查询原调用状态。', ApiStatus.unauthorized)
        )
      }

      // 登录接口的 401 是「账号或口令错误」，不是“当前会话失效”。
      // 如果走全局登出逻辑，登录页会把自己反复写进 redirect 查询串。
      if (url.includes(LOGIN_URL)) {
        if (apiError && typeof apiError.code === 'number') {
          return Promise.reject(
            createHttpError(apiError.message, apiError.code, {
              traceId: apiError.traceId,
              details: apiError.details
            })
          )
        }
        return Promise.reject(handleError(error))
      }

      // 刷新接口自己 401 = 刷新令牌也失效了（过期 / 已撤销 / 被判定复用），
      // 这时没有任何凭据可用，只能重新登录。
      // 退出接口不该 401（它是幂等的），但即便如此也不该触发刷新
      const isAuthEndpoint = url.includes(REFRESH_URL) || url.includes(LOGOUT_URL)

      if (config && !isAuthEndpoint && !config._retriedAfterRefresh) {
        // 只重放一次。刷新成功却依然 401，说明问题不在令牌过期
        // （例如权限确实不够），继续重试只会变成死循环
        config._retriedAfterRefresh = true
        return refreshAccessToken(identity).then(
          () => {
            assertRequestIdentity(config)
            return axiosInstance.request(config)
          },
          // 只处理刷新本身失败；重放后的403/429/5xx必须沿业务错误路径，不能误当会话失效。
          (refreshError: unknown) => {
            assertRequestIdentity(config)
            if (axios.isCancel(refreshError)) throw refreshError
            return handleUnauthorizedError(preferApiError(refreshError, apiError), identity)
          }
        )
      }

      handleUnauthorizedError(apiError, identity)
    }

    if (apiError && typeof apiError.code === 'number') {
      // 用**业务错误码**构造 HttpError，而不是 HTTP 状态码 ——
      // 前者才能区分同一状态码下的多种原因（例如 400 既可能是参数不合法
      // 也可能是请求体格式错误）。traceId/details 一并透传，用户报障时能直接提供（A6-04）
      return Promise.reject(
        createHttpError(apiError.message, apiError.code, {
          traceId: apiError.traceId,
          details: apiError.details
        })
      )
    }

    return Promise.reject(handleError(error))
  }
)

/** 统一创建 HttpError；traceId/details 与请求元信息一并透传（A6-04） */
function createHttpError(
  message: string,
  code: number,
  options?: {
    traceId?: string
    details?: string[]
    url?: string
    method?: string
    outcomeUnknown?: boolean
  }
) {
  return new HttpError(message, code, options)
}

/**
 * 用 Cookie 里的刷新令牌换一个新的访问令牌（ADR 0010）。
 *
 * 【为什么用裸 axios 而不是本模块的 request()】
 * 两个原因：一是走本模块会经过响应拦截器，而刷新失败时的 401 正是拦截器要处理的
 * 情况，会绕回自身；二是本模块的 api 层由 src/api/auth.ts 调用，反过来引用它会
 * 形成循环依赖。
 *
 * 请求体是空的——刷新令牌在 HttpOnly Cookie 里，浏览器自动携带，**前端读不到也
 * 传不了**。这正是选 Cookie 方案的意义：脚本拿不到它，XSS 也就偷不走。
 *
 * @returns 新的访问令牌
 */
function performRefreshRequest(identity: number): Promise<string> {
  assertCurrentIdentity(identity)
  return (
    axios
      // 去掉基地址末尾的斜杠再拼接。不能用「把连续斜杠压成一个」那种写法 ——
      // 生产环境的基地址是 https://... ，那样会把协议里的 // 也压掉
      .post(`${VITE_API_URL.replace(/\/$/, '')}${REFRESH_URL}`, null, {
        withCredentials: true,
        timeout: REQUEST_TIMEOUT
      })
      .then((response) => {
        assertCurrentIdentity(identity)
        const token = response.data?.accessToken as string
        if (!token) {
          throw new Error('刷新响应里没有 accessToken')
        }
        // 只更新当前标签内存里的访问令牌。刷新令牌由后端通过 Set-Cookie 轮换；
        // 双标签只共享 Web Lock，不用 localStorage 交换令牌。
        useUserStore().setRefreshedToken(token, identity)
        return token
      })
  )
}

/** 同标签 Promise 单飞 + 同源标签 Web Locks 串行，共同保护旋转型 refresh Cookie。 */
const refreshCoordinator = new RefreshCoordinator(performRefreshRequest)

/** @return 当前单飞/跨标签协调后的新访问令牌 */
function refreshAccessToken(identity: number): Promise<string> {
  return refreshCoordinator.run(identity, () => assertCurrentIdentity(identity))
}

/** 处理401错误（带防抖）；traceId/details 一并透传，与业务错误路径口径一致（A6-04） */
function handleUnauthorizedError(
  apiError: Pick<ApiError, 'message' | 'traceId' | 'details'> | undefined,
  identity: number
): never {
  assertCurrentIdentity(identity)
  const error = createHttpError(
    apiError?.message || $t('httpMsg.unauthorized'),
    ApiStatus.unauthorized,
    { traceId: apiError?.traceId, details: apiError?.details }
  )

  if (unauthorizedIdentity !== identity) {
    resetUnauthorizedError()
    unauthorizedIdentity = identity
    logOut(identity)

    unauthorizedTimer = setTimeout(resetUnauthorizedError, UNAUTHORIZED_DEBOUNCE_TIME)

    showError(error, true)
    throw error
  }

  throw error
}

/** 重置401防抖状态 */
function resetUnauthorizedError() {
  unauthorizedIdentity = null
  if (unauthorizedTimer) clearTimeout(unauthorizedTimer)
  unauthorizedTimer = null
}

/** 退出登录函数 */
function logOut(identity: number) {
  setTimeout(() => {
    if (identity === currentIdentityEpoch()) useUserStore().logOut()
  }, LOGOUT_DELAY)
}

/** 请求取消/身份切换必须在所有副作用之前拒绝，包括排队后真正发送与延迟重试。 */
function assertRequestIdentity(config: ExtendedAxiosRequestConfig): void {
  assertCurrentIdentity(config._identityEpoch ?? currentIdentityEpoch())
  if (config.signal?.aborted) throw new axios.CanceledError('请求已取消')
}

/** 是否需要重试 */
function shouldRetry(statusCode: number) {
  return [
    ApiStatus.requestTimeout,
    ApiStatus.internalServerError,
    ApiStatus.badGateway,
    ApiStatus.serviceUnavailable,
    ApiStatus.gatewayTimeout
  ].includes(statusCode)
}

/** 请求重试逻辑 */
async function retryRequest<T>(
  config: ExtendedAxiosRequestConfig,
  retries: number = MAX_RETRIES
): Promise<T> {
  if (
    isSingleAttemptAnalysis(config) ||
    isSingleAttemptProductCredential(config) ||
    isSingleAttemptOtaDownload(config) ||
    isSingleAttemptOtaTrustImport(config) ||
    isSingleAttemptOtaBaselineRegistration(config)
  )
    return request<T>(config)
  try {
    return await request<T>(config)
  } catch (error) {
    if (retries > 0 && error instanceof HttpError && shouldRetry(error.code)) {
      await delay(RETRY_DELAY)
      return retryRequest<T>(config, retries - 1)
    }
    throw error
  }
}

/** 精确匹配分析提交操作；状态GET和其他项目写操作沿原认证恢复策略。 */
function isSingleAttemptAnalysis(config: ExtendedAxiosRequestConfig): boolean {
  return (
    config.method?.toUpperCase() === 'POST' &&
    /^\/api\/v1\/projects\/[^/?#]+\/assistant\/analysis-runs(?:[?#].*)?$/.test(config.url ?? '')
  )
}

/** 产品生成/轮换只能显式单次发送，其他设备和类型操作沿各自既有恢复合同。 */
function isSingleAttemptProductCredential(config: ExtendedAxiosRequestConfig): boolean {
  return (
    config.method?.toUpperCase() === 'POST' &&
    /^\/api\/v1\/projects\/[^/?#]+\/device-types\/[^/?#]+\/product-credential(?:[?#].*)?$/.test(
      config.url ?? ''
    )
  )
}

/** 管理下载申领只发送一次；其他固件写入口保持原有认证恢复合同。 */
function isSingleAttemptOtaDownload(config: ExtendedAxiosRequestConfig): boolean {
  return (
    config.method?.toUpperCase() === 'POST' &&
    /^\/api\/v1\/projects\/[^/?#]+\/ota\/firmwares\/[^/?#]+\/release\/downloads(?:[?#].*)?$/.test(
      config.url ?? ''
    )
  )
}

/** 精确受控根签包导入，其他信任读取及OTA管理写沿原恢复合同。 */
function isSingleAttemptOtaTrustImport(config: ExtendedAxiosRequestConfig): boolean {
  return (
    config.method?.toUpperCase() === 'POST' &&
    /^\/api\/v1\/projects\/[^/?#]+\/ota\/trust-domains\/[^/?#]+\/bundles(?:[?#].*)?$/.test(
      config.url ?? ''
    )
  )
}

/** 精确类型基线登记只能显式单次发送，管理GET和其他写路径不改变恢复合同。 */
function isSingleAttemptOtaBaselineRegistration(config: ExtendedAxiosRequestConfig): boolean {
  return (
    config.method?.toUpperCase() === 'POST' &&
    /^\/api\/v1\/projects\/[^/?#]+\/ota\/device-types\/[^/?#]+\/baseline\/registrations(?:[?#].*)?$/.test(
      config.url ?? ''
    )
  )
}

/** 延迟函数 */
function delay(ms: number) {
  return new Promise((resolve) => setTimeout(resolve, ms))
}

/** 请求函数 */
async function request<T = any>(config: ExtendedAxiosRequestConfig): Promise<T> {
  config._identityEpoch ??= currentIdentityEpoch()
  assertRequestIdentity(config)
  // POST | PUT | PATCH 参数自动填充。
  //
  // PATCH 是加 patch() 时补上的：漏掉它的话，`params` 会被当成查询串发出去，
  // 而不是请求体。症状很隐蔽——请求发得出去、也是 200 之外的某个码，
  // 但服务端看到的是一个空 body，报「参数不合法」，而浏览器里看 URL 又一切正常。
  if (
    ['POST', 'PUT', 'PATCH'].includes(config.method?.toUpperCase() || '') &&
    config.params &&
    !config.data
  ) {
    config.data = config.params
    config.params = undefined
  }

  try {
    // 响应体就是接口的返回值本身，没有信封可拆
    const res = await axiosInstance.request<T>(config)
    assertRequestIdentity(config)

    if (config.showSuccessMessage) {
      showSuccess($t('httpMsg.requestSuccess'))
    }

    return res.data
  } catch (error) {
    assertRequestIdentity(config)
    if (axios.isCancel(error)) return Promise.reject(error)
    if (error instanceof HttpError && error.code !== ApiStatus.unauthorized) {
      const showMsg = config.showErrorMessage !== false
      showError(error, showMsg)
    }
    return Promise.reject(error)
  }
}

/**
 * API 方法集合。
 *
 * 五个具名方法的 `url` 都受 OpenAPI 契约约束：路径不存在、拼错、或者该路径不支持
 * 这个方法，都会在编译期报错。{@link api.request} 是**不受约束的逃生口**，
 * 留给契约之外的地址（目前只有富文本编辑器的图片上传），用它要有明确理由。
 */
const api = {
  get<T>(config: TypedRequestConfig<'get'>) {
    return retryRequest<T>({ ...config, method: 'GET' })
  },
  post<T>(config: TypedRequestConfig<'post'>) {
    return retryRequest<T>({ ...config, method: 'POST' })
  },
  put<T>(config: TypedRequestConfig<'put'>) {
    return retryRequest<T>({ ...config, method: 'PUT' })
  },
  /**
   * 局部更新。
   *
   * 模板原本只有 PUT，但 PUT 的语义是「整个资源被请求体替换」——用它发一个只带
   * 一个字段的请求体，等于声明「其余字段请设为默认值」。改成员角色只改 role，
   * 加入时刻与成员 ID 显然不该由调用方决定，所以是 PATCH。
   */
  patch<T>(config: TypedRequestConfig<'patch'>) {
    return retryRequest<T>({ ...config, method: 'PATCH' })
  },
  del<T>(config: TypedRequestConfig<'delete'>) {
    return retryRequest<T>({ ...config, method: 'DELETE' })
  },
  /**
   * 不受契约约束的原始请求。
   *
   * 只在目标地址**不在 OpenAPI 契约里**时使用——目前唯一的场景是富文本编辑器的
   * 图片上传（模板自带，地址由使用方传入）。走业务接口一律用上面五个具名方法，
   * 否则就白白放弃了路径与方法的编译期检查。
   */
  request<T>(config: ExtendedAxiosRequestConfig) {
    return retryRequest<T>(config)
  }
}

export default api
