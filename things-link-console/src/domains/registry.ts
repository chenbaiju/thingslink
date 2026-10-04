import type { AppRouteRecord } from '@/types/router'
import {
  DomainRegistrationError,
  type ConsoleDomainRegistration,
  type ConsoleDomainRegistry,
  type LocaleMessageTree,
  type LocaleMessageValue
} from './contract'

/** 域标识只承载源码所有权，限制字符可让路径、日志和测试定位保持一致。 */
const DOMAIN_ID_PATTERN = /^[a-z][a-z0-9]*(?:-[a-z0-9]+)*$/

/** 这些键会访问普通对象原型链，不能成为业务域文案路径。 */
const PROTOTYPE_LOCALE_KEYS = new Set(['__proto__', 'prototype', 'constructor'])

/** 文案节点来源，用于在冲突消息中指出两个实际注册文件。 */
interface LocaleNodeOwner {
  readonly kind: 'branch' | 'leaf'
  readonly source: string
}

/** 可写的装配期文案树；返回前不会再暴露此内部形状。 */
interface MutableLocaleMessageTree {
  [key: string]: string | readonly string[] | MutableLocaleMessageTree
}

/**
 * 从静态模块集合装配 Console 域。
 *
 * 参数形状与 Vite `import.meta.glob` 的结果一致，因此测试可以注入纯对象覆盖冲突，
 * 生产代码则由构建器静态收集 registration.ts，无需每增加一个域就修改中心清单。
 * Vite构建不会执行本函数；冲突由聚焦测试提前发现，并在宿主初始化导入时最终拒绝。
 */
export function assembleConsoleDomains(
  modules: Readonly<Record<string, ConsoleDomainRegistration>>
): ConsoleDomainRegistry {
  const entries = Object.entries(modules).sort(([left], [right]) => left.localeCompare(right))
  const domainIds = new Map<string, string>()
  const domainOrders = new Map<number, string>()

  for (const [source, domain] of entries) {
    validateDomain(source, domain)
    rejectDuplicate(
      domainIds,
      domain.id,
      source,
      'DUPLICATE_DOMAIN_ID',
      `业务域标识重复：${domain.id}`
    )
    rejectDuplicate(
      domainOrders,
      domain.order,
      source,
      'DUPLICATE_DOMAIN_ORDER',
      `业务域顺序重复：${domain.order}`
    )
  }

  const domains = entries
    .map(([, domain]) => domain)
    .sort((left, right) => left.order - right.order || left.id.localeCompare(right.id))
  validateRoutes(domains)

  const localeOwners = new Map<string, LocaleNodeOwner>()
  const zhCNMessages: MutableLocaleMessageTree = {}
  for (const domain of domains) {
    mergeLocaleTree(zhCNMessages, domain.zhCNMessages ?? {}, domain.id, [], localeOwners)
  }

  return Object.freeze({
    domains: Object.freeze([...domains]),
    routes: Object.freeze(domains.flatMap((domain) => [...domain.routes])),
    zhCNMessages: freezeLocaleTree(zhCNMessages)
  })
}

/**
 * 将核心文案和域文案合并，并对叶节点覆盖执行同一 fail-fast 规则。
 *
 * 核心登录壳仍由 locales/langs/zh.json 持有；业务域不能借合并顺序覆盖核心提示。
 */
export function mergeConsoleLocaleMessages(
  coreMessages: LocaleMessageTree,
  domainMessages: LocaleMessageTree
): LocaleMessageTree {
  const owners = new Map<string, LocaleNodeOwner>()
  const merged: MutableLocaleMessageTree = {}
  mergeLocaleTree(merged, coreMessages, 'core', [], owners)
  mergeLocaleTree(merged, domainMessages, 'domains', [], owners)
  return freezeLocaleTree(merged)
}

/** 校验单个自动发现模块，避免错误默认导出在启动后才表现为 undefined。 */
function validateDomain(source: string, domain: ConsoleDomainRegistration): void {
  if (
    !domain ||
    typeof domain !== 'object' ||
    !DOMAIN_ID_PATTERN.test(domain.id) ||
    !Number.isSafeInteger(domain.order) ||
    !Array.isArray(domain.routes)
  ) {
    throw new DomainRegistrationError(
      'INVALID_DOMAIN',
      `业务域注册无效：${source} 必须提供合法 id、整数 order 和 routes`
    )
  }
}

/** 对显式唯一键给出两个真实来源，防止后注册项被静默覆盖。 */
function rejectDuplicate<Key>(
  seen: Map<Key, string>,
  key: Key,
  source: string,
  code: 'DUPLICATE_DOMAIN_ID' | 'DUPLICATE_DOMAIN_ORDER',
  summary: string
): void {
  const previous = seen.get(key)
  if (previous) {
    throw new DomainRegistrationError(code, `${summary}（${previous} 与 ${source}）`)
  }
  seen.set(key, source)
}

/** 跨全部域校验路由身份；既有 RouteValidator 的 warning 不足以保护自动聚合。 */
function validateRoutes(domains: readonly ConsoleDomainRegistration[]): void {
  const names = new Map<string, string>()
  const paths = new Map<string, string>()

  for (const domain of domains) {
    visitRoutes(domain.routes, domain.id, '', names, paths)
  }
}

/** 深度遍历子路由，按 Vue Router 的绝对/相对路径规则计算完整路径。 */
function visitRoutes(
  routes: readonly AppRouteRecord[],
  domainId: string,
  parentPath: string,
  names: Map<string, string>,
  paths: Map<string, string>
): void {
  for (const route of routes) {
    validateRoutePathAndAlias(route, domainId)
    const fullPath = resolveRoutePath(parentPath, route.path)
    const matchIdentity = createRouteMatchIdentity(fullPath)
    const previousPathOwner = paths.get(matchIdentity)
    if (previousPathOwner) {
      throw new DomainRegistrationError(
        'DUPLICATE_ROUTE_PATH',
        `完整路由匹配形状重复：${matchIdentity}（${previousPathOwner} 与 ${domainId}）`
      )
    }
    paths.set(matchIdentity, domainId)

    if (route.name !== undefined && route.name !== null) {
      const routeName = String(route.name)
      const previousNameOwner = names.get(routeName)
      if (previousNameOwner) {
        throw new DomainRegistrationError(
          'DUPLICATE_ROUTE_NAME',
          `路由名称重复：${routeName}（${previousNameOwner} 与 ${domainId}）`
        )
      }
      names.set(routeName, domainId)
    }

    if (route.children?.length) {
      visitRoutes(route.children, domainId, fullPath, names, paths)
    }
  }
}

/**
 * 收窄生产路由语法。
 *
 * Vue Router默认大小写不敏感，源码若混用大小写会制造视觉上不同、实际重叠的路径；
 * alias还会为一条记录引入第二组匹配身份。当前没有alias需求，因此直接禁止，避免
 * 注册表漏检隐含入口；未来确有产品需求时应先把alias集合纳入完整冲突矩阵。
 */
function validateRoutePathAndAlias(route: AppRouteRecord, domainId: string): void {
  if (route.alias !== undefined) {
    throw new DomainRegistrationError(
      'UNSUPPORTED_ROUTE_ALIAS',
      `业务域路由暂不允许alias：${domainId}/${String(route.name ?? route.path)}`
    )
  }
  if (typeof route.path !== 'string' || route.path !== route.path.toLowerCase()) {
    throw new DomainRegistrationError(
      'INVALID_ROUTE_PATH',
      `业务域路由path必须使用小写：${domainId}/${String(route.path)}`
    )
  }
}

/** 绝对子路径从根开始；相对子路径继承父路径，空路径保留父路径语义。 */
function resolveRoutePath(parentPath: string, routePath: string): string {
  if (routePath.startsWith('/')) return normalizeRoutePath(routePath)
  return normalizeRoutePath(`${parentPath}/${routePath}`)
}

/** 只归一连续斜杠和尾斜杠，不改写 Vue Router 允许的参数片段。 */
function normalizeRoutePath(path: string): string {
  const normalized = path.replace(/\/{2,}/g, '/').replace(/\/$/, '')
  return normalized || '/'
}

/**
 * 生成Vue Router意义下的路径冲突身份。
 *
 * 参数名只供组件取值，`/device/:id`与`/device/:deviceId`匹配同一URL集合，因此统一
 * 替换为占位符；自定义正则及`?`/`+`/`*`修饰符决定集合边界，必须逐字保留。扫描时
 * 跳过自定义正则括号内部，避免把`(?:...)`等正则内容误当成第二个路由参数。
 */
function createRouteMatchIdentity(path: string): string {
  let identity = ''
  let regexpDepth = 0

  for (let index = 0; index < path.length; index += 1) {
    const character = path[index]

    if (character === '\\') {
      identity += character
      if (index + 1 < path.length) identity += path[(index += 1)]
      continue
    }
    if (character === '(') {
      regexpDepth += 1
      identity += character
      continue
    }
    if (character === ')' && regexpDepth > 0) {
      regexpDepth -= 1
      identity += character
      continue
    }
    if (character === ':' && regexpDepth === 0) {
      identity += ':_'
      while (index + 1 < path.length && /[a-z0-9_]/i.test(path[index + 1])) index += 1
      continue
    }
    identity += character
  }

  return identity
}

/**
 * 深合并静态文案并记录每一节点的来源。
 *
 * 两个域可以共同提供 `menus` 这样的父对象；任何叶节点重复、对象与叶节点互换都会
 * 直接失败，禁止依赖对象展开顺序决定最终中文文案。
 */
function mergeLocaleTree(
  target: MutableLocaleMessageTree,
  sourceTree: LocaleMessageTree,
  source: string,
  path: readonly string[],
  owners: Map<string, LocaleNodeOwner>
): void {
  // 对象字面量的__proto__不会出现在Object.entries中，必须在枚举路径前检查实际原型。
  validateLocaleTreePrototype(sourceTree, source, path)
  for (const [key, value] of Object.entries(sourceTree)) {
    // 必须先校验再读取或写入 target[key]，否则特殊键已经可能触发原型访问器。
    validateLocaleKey(key, source)
    const nextPath = [...path, key]
    const pathText = nextPath.join('.')
    const branch = isLocaleBranch(value)
    if (branch) validateLocaleTreePrototype(value, source, nextPath)
    const currentOwner = owners.get(pathText)

    if (currentOwner && (currentOwner.kind !== 'branch' || !branch)) {
      throw new DomainRegistrationError(
        'DUPLICATE_LOCALE_PATH',
        `中文文案路径重复：${pathText}（${currentOwner.source} 与 ${source}）`
      )
    }

    if (!currentOwner) owners.set(pathText, { kind: branch ? 'branch' : 'leaf', source })

    if (branch) {
      const current = target[key]
      if (current !== undefined && !isMutableLocaleBranch(current)) {
        throw new DomainRegistrationError('DUPLICATE_LOCALE_PATH', `中文文案路径重复：${pathText}`)
      }
      const child = current ?? {}
      target[key] = child
      mergeLocaleTree(child, value, source, nextPath, owners)
    } else {
      target[key] = Array.isArray(value) ? Object.freeze([...value]) : value
    }
  }
}

/**
 * 只允许普通静态对象或显式无原型字典作为文案分支。
 *
 * 这既排除Date/类实例等带行为对象，也能捕获`{ __proto__: value }`对象字面量造成的
 * 隐式原型替换；后者没有同名自有键，单靠路径段枚举无法发现。
 */
function validateLocaleTreePrototype(
  tree: LocaleMessageTree,
  source: string,
  path: readonly string[]
): void {
  const prototype = Object.getPrototypeOf(tree)
  if (prototype !== Object.prototype && prototype !== null) {
    throw new DomainRegistrationError(
      'INVALID_LOCALE_KEY',
      `中文文案分支原型无效：${path.length > 0 ? path.join('.') : '<root>'}（${source}）`
    )
  }
}

/**
 * 校验单个文案路径段。
 *
 * 点号会让两个不同树结构产生相同诊断路径；空段无法可靠定位；原型特殊键会使普通
 * 对象的读取或赋值偏离自有属性语义。三类输入都在首次属性访问前拒绝。
 */
function validateLocaleKey(key: string, source: string): void {
  if (key.length === 0 || key.includes('.') || PROTOTYPE_LOCALE_KEYS.has(key)) {
    throw new DomainRegistrationError(
      'INVALID_LOCALE_KEY',
      `中文文案路径段无效：${JSON.stringify(key)}（${source}）`
    )
  }
}

/** 数组是有序叶值，只有普通对象继续递归合并。 */
function isLocaleBranch(value: LocaleMessageValue): value is LocaleMessageTree {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

/** 装配期树的分支判定。 */
function isMutableLocaleBranch(
  value: string | readonly string[] | MutableLocaleMessageTree
): value is MutableLocaleMessageTree {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

/** 返回前递归冻结新建文案树，防止某个域运行时改写其他域的翻译。 */
function freezeLocaleTree(tree: MutableLocaleMessageTree): LocaleMessageTree {
  for (const value of Object.values(tree)) {
    if (isMutableLocaleBranch(value)) freezeLocaleTree(value)
  }
  return Object.freeze(tree) as LocaleMessageTree
}

// Vite在构建期只展开精确模块集合；实际装配在宿主初始化导入时执行，不是远程插件加载。
const discoveredDomainModules = import.meta.glob<ConsoleDomainRegistration>('./*/registration.ts', {
  eager: true,
  import: 'default'
})

/** 当前构建内自动发现、完整校验后的 Console 域注册表。 */
export const consoleDomainRegistry = assembleConsoleDomains(discoveredDomainModules)
