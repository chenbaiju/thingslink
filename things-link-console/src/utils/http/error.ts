/**
 * HTTP 错误处理模块
 *
 * 提供统一的 HTTP 请求错误处理机制
 *
 * ## 主要功能
 *
 * - 自定义 HttpError 错误类，封装错误信息、状态码、时间戳等
 * - 错误拦截和转换，将 Axios 错误转换为标准的 HttpError
 * - 错误消息国际化处理，根据状态码返回对应的多语言错误提示
 * - 错误日志记录，便于问题追踪和调试
 * - 错误和成功消息的统一展示
 * - 类型守卫函数，用于判断错误类型
 *
 * ## 使用场景
 *
 * - HTTP 请求拦截器中统一处理错误
 * - 业务代码中捕获和处理特定错误
 * - 错误日志收集和上报
 *
 * @module utils/http/error
 * @author Things Link Team
 */
import { AxiosError } from 'axios'
import { ApiStatus } from './status'
import { $t } from '@/locales'

// 错误响应接口（与后端 ApiError 一致：{ code, message, traceId, details }，不含信封 data）
export interface ErrorResponse {
  /** 业务错误码 */
  code: number
  /** 错误消息 */
  message: string
  /** 追踪 ID（后端为每次失败分配，用户报障凭据） */
  traceId?: string
  /** 错误明细 */
  details?: string[]
}

/** 后端 ApiError 的最小结构；避免 error.ts 反向依赖 http/index.ts（完整 ApiError 定义在那里）。 */
export interface ApiErrorShape {
  code: number
  message: string
  traceId?: string
  details?: string[]
}

/**
 * 从「刷新失败」这类 axios 异常里提取标准 ApiError 的诊断字段。
 * 优先用该异常自身的 traceId/details（刷新响应是标准 ApiError 时），
 * 缺失或非标准结构时回退到原始 401 的 ApiError（A6-04「全路径要求」）。
 */
export function preferApiError(
  error: unknown,
  fallback?: Pick<ApiErrorShape, 'message' | 'traceId' | 'details'>
): Pick<ApiErrorShape, 'message' | 'traceId' | 'details'> | undefined {
  const body = (error as { response?: { data?: ApiErrorShape } } | null | undefined)?.response?.data
  if (body && typeof body.code === 'number') {
    return { message: body.message, traceId: body.traceId, details: body.details }
  }
  return fallback
}

// 错误日志数据接口
export interface ErrorLogData {
  /** 错误状态码 */
  code: number
  /** 错误消息 */
  message: string
  /** 错误附加数据 */
  data?: unknown
  /** 追踪 ID */
  traceId?: string
  /** 错误明细 */
  details?: string[]
  /** 请求可能已到达服务端但响应不可见；写操作据此决定是否保留幂等键。 */
  outcomeUnknown: boolean
  /** 错误发生时间戳 */
  timestamp: string
  /** 请求 URL */
  url?: string
  /** 请求方法 */
  method?: string
  /** 错误堆栈信息 */
  stack?: string
}

// 自定义 HttpError 类
export class HttpError extends Error {
  public readonly code: number
  public readonly data?: unknown
  /** 追踪 ID：后端失败响应的 traceId，用户报障凭据（A6-04） */
  public readonly traceId?: string
  /** 错误明细（后端 details 数组） */
  public readonly details?: string[]
  /** 网络中断/超时/取消时无法判断服务端是否已提交写入。 */
  public readonly outcomeUnknown: boolean
  public readonly timestamp: string
  public readonly url?: string
  public readonly method?: string

  constructor(
    message: string,
    code: number,
    options?: {
      data?: unknown
      traceId?: string
      details?: string[]
      outcomeUnknown?: boolean
      url?: string
      method?: string
    }
  ) {
    super(message)
    this.name = 'HttpError'
    this.code = code
    this.data = options?.data
    this.traceId = options?.traceId
    this.details = options?.details
    this.outcomeUnknown = options?.outcomeUnknown ?? false
    this.timestamp = new Date().toISOString()
    this.url = options?.url
    this.method = options?.method
  }

  public toLogData(): ErrorLogData {
    return {
      code: this.code,
      message: this.message,
      data: this.data,
      traceId: this.traceId,
      details: this.details,
      outcomeUnknown: this.outcomeUnknown,
      timestamp: this.timestamp,
      url: this.url,
      method: this.method,
      stack: this.stack
    }
  }
}

/**
 * 获取错误消息
 * @param status 错误状态码
 * @returns 错误消息
 */
const getErrorMessage = (status: number): string => {
  const errorMap: Record<number, string> = {
    [ApiStatus.unauthorized]: 'httpMsg.unauthorized',
    [ApiStatus.forbidden]: 'httpMsg.forbidden',
    [ApiStatus.notFound]: 'httpMsg.notFound',
    [ApiStatus.methodNotAllowed]: 'httpMsg.methodNotAllowed',
    [ApiStatus.requestTimeout]: 'httpMsg.requestTimeout',
    [ApiStatus.internalServerError]: 'httpMsg.internalServerError',
    [ApiStatus.badGateway]: 'httpMsg.badGateway',
    [ApiStatus.serviceUnavailable]: 'httpMsg.serviceUnavailable',
    [ApiStatus.gatewayTimeout]: 'httpMsg.gatewayTimeout'
  }

  return $t(errorMap[status] || 'httpMsg.internalServerError')
}

/**
 * 处理错误
 * @param error 错误对象
 * @returns 错误对象
 */
export function handleError(error: AxiosError<ErrorResponse>): never {
  // 处理取消的请求
  if (error.code === 'ERR_CANCELED') {
    console.warn('Request cancelled:', error.message)
    throw new HttpError($t('httpMsg.requestCancelled'), ApiStatus.error, { outcomeUnknown: true })
  }

  const statusCode = error.response?.status
  const errorMessage = error.response?.data?.message || error.message
  const requestConfig = error.config

  // 处理网络错误
  if (!error.response) {
    throw new HttpError($t('httpMsg.networkError'), ApiStatus.error, {
      outcomeUnknown: true,
      url: requestConfig?.url,
      method: requestConfig?.method?.toUpperCase()
    })
  }

  // 处理 HTTP 状态码错误：后端可能仍带 traceId/details，一并透传用于报障与日志
  const message = statusCode
    ? getErrorMessage(statusCode)
    : errorMessage || $t('httpMsg.requestFailed')
  throw new HttpError(message, statusCode || ApiStatus.error, {
    data: error.response.data,
    traceId: error.response.data?.traceId,
    details: error.response.data?.details,
    url: requestConfig?.url,
    method: requestConfig?.method?.toUpperCase()
  })
}

/**
 * 判断是否为「未归类/服务端」错误：内部错误段（9xxxx）或未经 ApiError 包装的 5xx。
 * 这类错误没有稳定业务码，用户只能凭 traceId 报障。
 */
const isUnexpectedError = (code: number): boolean =>
  (code >= 90000 && code <= 99999) || (code >= 500 && code < 600)

/**
 * 组合展示用错误消息：未归类/服务端错误带 traceId 时附加恢复建议（A6-04/§11.5），
 * 已知业务码只显示映射消息、不打扰用户。抽成纯函数以便单测。
 */
export const buildErrorMessage = (message: string, code: number, traceId?: string): string =>
  traceId && isUnexpectedError(code)
    ? `${message}（traceId：${traceId}，请联系支持并提供该编号）`
    : message

/**
 * 显示错误消息
 * @param error 错误对象
 * @param showMessage 是否显示错误消息
 */
export function showError(error: HttpError, showMessage: boolean = true): void {
  if (showMessage) {
    ElMessage.error(buildErrorMessage(error.message, error.code, error.traceId))
  }
  // 记录错误日志
  console.error('[HTTP Error]', error.toLogData())
}

/**
 * 显示成功消息
 * @param message 成功消息
 * @param showMessage 是否显示消息
 */
export function showSuccess(message: string, showMessage: boolean = true): void {
  if (showMessage) {
    ElMessage.success(message)
  }
}

/**
 * 判断是否为 HttpError 类型
 * @param error 错误对象
 * @returns 是否为 HttpError 类型
 */
export const isHttpError = (error: unknown): error is HttpError => {
  return error instanceof HttpError
}
