import type { AppRouteRecord } from '@/types/router'

/**
 * 单个中文文案节点。
 *
 * 域注册只允许声明静态文案树；数组保留给现有下拉选项等有序文案。这里不接受函数，
 * 避免域在国际化装配期间执行副作用，也不把运行时格式化逻辑塞进注册表。
 */
export type LocaleMessageValue = string | readonly string[] | LocaleMessageTree

/**
 * 简体中文文案树。
 *
 * X-02c2 只建立当前单语言产品的分域扩展点。后续若产品范围正式增加语言，必须在
 * 同一域注册合同中显式增加对应语言，而不是让各域自行创建第二个 i18n 实例。
 */
export interface LocaleMessageTree {
  readonly [key: string]: LocaleMessageValue
}

/**
 * Console 业务域的静态注册合同。
 *
 * 路由记录同时承担前端兜底菜单定义；真实运行时的可见性仍由后端 MenuCatalog 与
 * 服务端授权决定。API 使用静态模块导入，Pinia store 按首次使用注册，二者都不进入
 * 本合同，避免把类型安全的依赖改成运行时服务定位器。
 */
export interface ConsoleDomainRegistration {
  /** 稳定的小写业务域标识，用于冲突诊断，不作为权限或租户身份。 */
  readonly id: string
  /** 菜单聚合顺序；显式排序避免文件系统遍历顺序改变导航。 */
  readonly order: number
  /** 本域的前端兜底路由；页面仍放在既有 views 业务目录中。 */
  readonly routes: readonly AppRouteRecord[]
  /** 本域拥有的简体中文文案；与其他域或核心文案重复时装配直接失败。 */
  readonly zhCNMessages?: LocaleMessageTree
}

/** 域装配失败的稳定原因，测试和诊断无需依赖整段中文消息。 */
export type DomainRegistrationErrorCode =
  | 'INVALID_DOMAIN'
  | 'DUPLICATE_DOMAIN_ID'
  | 'DUPLICATE_DOMAIN_ORDER'
  | 'INVALID_ROUTE_PATH'
  | 'UNSUPPORTED_ROUTE_ALIAS'
  | 'DUPLICATE_ROUTE_NAME'
  | 'DUPLICATE_ROUTE_PATH'
  | 'INVALID_LOCALE_KEY'
  | 'DUPLICATE_LOCALE_PATH'

/**
 * 域装配异常。
 *
 * 这些错误表示源码注册表本身不一致，继续初始化宿主只会静默丢菜单或覆盖文案，
 * 因此导入注册表时必须 fail-fast；它不是面向终端用户的业务异常。
 */
export class DomainRegistrationError extends Error {
  /** 可机器断言的稳定失败原因。 */
  readonly code: DomainRegistrationErrorCode

  /** 创建带稳定原因的装配异常。 */
  constructor(code: DomainRegistrationErrorCode, message: string) {
    super(message)
    this.name = 'DomainRegistrationError'
    this.code = code
  }
}

/** 聚合后的只读域注册表。 */
export interface ConsoleDomainRegistry {
  /** 按 order、id 确定排序后的域。 */
  readonly domains: readonly ConsoleDomainRegistration[]
  /** 按域顺序展平的前端兜底路由。 */
  readonly routes: readonly AppRouteRecord[]
  /** 已校验无冲突的域内简体中文文案。 */
  readonly zhCNMessages: LocaleMessageTree
}
