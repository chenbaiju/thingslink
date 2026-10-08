import { describe, expect, it } from 'vitest'
import type { AppRouteRecord } from '@/types/router'
import { assembleConsoleDomains, consoleDomainRegistry } from '@/domains/registry'
import {
  DomainRegistrationError,
  type ConsoleDomainRegistration,
  type LocaleMessageTree
} from '@/domains/contract'
import { dashboardRoutes } from '@/router/modules/dashboard'
import { deviceRoutes } from '@/router/modules/device'
import { alarmRoutes } from '@/router/modules/alarm'
import { taskRoutes } from '@/router/modules/task'
import { otaRoutes } from '@/router/modules/ota'
import { ruleRoutes } from '@/router/modules/rule'
import { projectRoutes } from '@/router/modules/project'
import { systemStatusRoutes } from '@/router/modules/system-status'
import { planCatalogRoutes } from '@/router/modules/plan-catalog'
import { commercialRoutes } from '@/router/modules/commercial'
import { selfHostedEnrollmentRoutes } from '@/router/modules/self-hosted-enrollment'
import { selfHostedReviewRoutes } from '@/router/modules/self-hosted-review'
import { exceptionRoutes } from '@/router/modules/exception'
import userCenterDomain from '@/domains/user-center/registration'

/** X-02c2：构造只含前端装配事实的测试领域，不引入 API、store 或远程插件。 */
const domain = (
  id: string,
  order: number,
  routes: readonly AppRouteRecord[],
  zhCNMessages?: LocaleMessageTree
): ConsoleDomainRegistration => ({ id, order, routes, zhCNMessages })

/** 构造满足控制台路由最低类型约束的测试路由。 */
const route = (name: string, path: string, children?: AppRouteRecord[]): AppRouteRecord => ({
  name,
  path,
  component: `/fixture/${String(name)}`,
  meta: { title: `fixture.${String(name)}` },
  children
})

/** 断言装配失败使用稳定错误码，而不绑定包含来源信息的诊断文本。 */
const expectRegistrationError = (
  operation: () => unknown,
  code: DomainRegistrationError['code']
): void => {
  try {
    operation()
  } catch (error) {
    expect(error).toBeInstanceOf(DomainRegistrationError)
    expect((error as DomainRegistrationError).code).toBe(code)
    return
  }
  throw new Error(`期望领域注册失败：${code}`)
}

describe('控制台领域注册表', () => {
  it('按冻结顺序聚合十三个生产领域且保持既有路由树', () => {
    expect(consoleDomainRegistry.domains.map(({ id, order }) => ({ id, order }))).toEqual([
      { id: 'dashboard', order: 10 },
      { id: 'device', order: 20 },
      { id: 'alarm', order: 30 },
      { id: 'task', order: 40 },
      { id: 'ota', order: 45 },
      { id: 'rule', order: 50 },
      { id: 'project', order: 60 },
      { id: 'system-status', order: 70 },
      { id: 'plan-catalog', order: 75 },
      { id: 'commercial', order: 76 },
      { id: 'self-hosted-enrollment', order: 77 },
      { id: 'exception', order: 80 },
      { id: 'user-center', order: 110 }
    ])
    expect(consoleDomainRegistry.routes).toEqual([
      dashboardRoutes,
      deviceRoutes,
      alarmRoutes,
      taskRoutes,
      otaRoutes,
      ruleRoutes,
      projectRoutes,
      systemStatusRoutes,
      planCatalogRoutes,
      commercialRoutes,
      selfHostedEnrollmentRoutes,
      selfHostedReviewRoutes,
      exceptionRoutes,
      ...userCenterDomain.routes
    ])
  })

  it('登记工作台与项目概况并保留既有业务菜单译文', () => {
    expect(consoleDomainRegistry.zhCNMessages).toEqual({
      menus: {
        dashboard: {
          title: '工作台',
          workbench: '首页',
          overview: '项目概况',
          designer: '看板设计器',
          applications: '应用管理'
        },
        device: {
          title: '设备',
          list: '所有设备',
          groups: '设备组',
          messages: '消息日志',
          types: '设备类型',
          topology: '拓扑管理',
          modbusPoints: 'Modbus 点位'
        },
        alarm: {
          title: '告警',
          rules: '告警规则',
          history: '告警历史',
          notificationGroups: '通知组',
          notificationTemplates: '通知模板'
        },
        task: { title: '任务', jobs: '任务调度' },
        ota: {
          title: 'OTA升级',
          firmwares: '固件管理',
          campaigns: '灰度活动',
          jobs: '设备作业',
          audits: '审计时间线'
        },
        rule: {
          title: '规则中心',
          messages: '消息规则',
          scenes: '手动场景',
          automations: '自动化',
          executions: '执行记录'
        },
        project: {
          title: '项目',
          list: '项目列表',
          add: '添加项目',
          recycleBin: '项目回收站',
          members: '项目成员',
          endUsers: '终端用户',
          settings: '项目设置',
          apiKeys: 'API Key',
          webhooks: 'Webhook'
        },
        systemStatus: { title: '系统状态' },
        commercialOperations: { title: '商业运营' },
        selfHostedEnrollment: { title: '自部署授权申请' },
        selfHostedReview: { title: '自部署申请审核' },
        system: { userCenter: '个人中心' },
        planCatalog: { title: '套餐与权益' },
        exception: {
          title: '异常页面',
          forbidden: '403',
          notFound: '404',
          serverError: '500'
        }
      },
      userCenter: {
        title: '个人中心',
        description: '查看当前控制台账号与会话信息。',
        readOnly: '以下为当前登录账号的资料。',
        accountSection: '账号信息',
        accountId: '账号 ID',
        email: '注册邮箱',
        tenantId: '租户 ID',
        currentProjectId: '当前项目 ID',
        roles: '当前角色',
        noProject: '未选择项目',
        noRoles: '暂无角色信息'
      }
    })
  })

  it('允许通过纯装配函数加入伪领域', () => {
    const fixture = domain('fixture', 90, [route('FixtureHome', '/fixture')], {
      menus: { fixture: { title: '测试领域' } }
    })

    const assembled = assembleConsoleDomains({ fixture })

    expect(assembled.domains).toEqual([fixture])
    expect(assembled.routes).toEqual(fixture.routes)
    expect(assembled.zhCNMessages).toEqual({ menus: { fixture: { title: '测试领域' } } })
  })

  it('输入对象键顺序不影响按order得到的确定结果', () => {
    const early = domain('early', 10, [route('Early', '/early')], {
      menus: { early: { title: '早' } }
    })
    const late = domain('late', 20, [route('Late', '/late')], {
      menus: { late: { title: '晚' } }
    })

    const forward = assembleConsoleDomains({ early, late })
    const reverse = assembleConsoleDomains({ late, early })

    expect(reverse).toEqual(forward)
    expect(reverse.domains.map(({ id }) => id)).toEqual(['early', 'late'])
  })

  it('重复领域id立即拒绝', () => {
    expectRegistrationError(
      () =>
        assembleConsoleDomains({
          first: domain('same', 10, [route('First', '/first')]),
          second: domain('same', 20, [route('Second', '/second')])
        }),
      'DUPLICATE_DOMAIN_ID'
    )
  })

  it('重复领域order立即拒绝', () => {
    expectRegistrationError(
      () =>
        assembleConsoleDomains({
          first: domain('first', 10, [route('First', '/first')]),
          second: domain('second', 10, [route('Second', '/second')])
        }),
      'DUPLICATE_DOMAIN_ORDER'
    )
  })

  it('非法领域id立即拒绝', () => {
    expectRegistrationError(
      () =>
        assembleConsoleDomains({
          invalid: domain('Invalid_Domain', 10, [route('InvalidDomain', '/invalid-domain')])
        }),
      'INVALID_DOMAIN'
    )
  })

  it('递归计算完整路由path并拒绝跨领域冲突', () => {
    expectRegistrationError(
      () =>
        assembleConsoleDomains({
          parent: domain('parent', 10, [
            route('Parent', '/parent', [route('SharedChild', '/shared')])
          ]),
          direct: domain('direct', 20, [route('Direct', '/shared')])
        }),
      'DUPLICATE_ROUTE_PATH'
    )
  })

  it('大小写不同的路由不能绕过小写路径约束', () => {
    expectRegistrationError(
      () =>
        assembleConsoleDomains({
          lower: domain('lower', 10, [route('Lower', '/foo')]),
          upper: domain('upper', 20, [route('Upper', '/Foo')])
        }),
      'INVALID_ROUTE_PATH'
    )
  })

  it('动态段名称不同仍按同一路由身份拒绝', () => {
    expectRegistrationError(
      () =>
        assembleConsoleDomains({
          first: domain('first', 10, [route('FirstUser', '/u/:id')]),
          second: domain('second', 20, [route('SecondUser', '/u/:name')])
        }),
      'DUPLICATE_ROUTE_PATH'
    )
  })

  it('动态段的正则、可选和重复语义差异保持为不同路由', () => {
    const assembled = assembleConsoleDomains({
      numeric: domain('numeric', 10, [route('Numeric', '/regex/:id(\\d+)')]),
      alphabetic: domain('alphabetic', 20, [route('Alphabetic', '/regex/:name([a-z]+)')]),
      required: domain('required', 30, [route('Required', '/optional/:id')]),
      optional: domain('optional', 40, [route('Optional', '/optional/:name?')]),
      oneOrMore: domain('one-or-more', 50, [route('OneOrMore', '/repeat/:id+')]),
      zeroOrMore: domain('zero-or-more', 60, [route('ZeroOrMore', '/repeat/:name*')])
    })

    expect(assembled.routes).toHaveLength(6)
  })

  it.each([['/legacy'], [['/legacy', '/previous']]])('明确拒绝静态alias注册：%j', (alias) => {
    expectRegistrationError(
      () =>
        assembleConsoleDomains({
          aliased: domain('aliased', 10, [
            { ...route('Aliased', '/canonical'), alias } as AppRouteRecord
          ])
        }),
      'UNSUPPORTED_ROUTE_ALIAS'
    )
  })

  it('递归拒绝跨领域重复路由name', () => {
    expectRegistrationError(
      () =>
        assembleConsoleDomains({
          parent: domain('parent', 10, [
            route('Parent', '/parent', [route('RepeatedName', 'child')])
          ]),
          other: domain('other', 20, [route('RepeatedName', '/other')])
        }),
      'DUPLICATE_ROUTE_NAME'
    )
  })

  it('locale同一路径的叶值与对象冲突立即拒绝', () => {
    expectRegistrationError(
      () =>
        assembleConsoleDomains({
          leaf: domain('leaf', 10, [route('Leaf', '/leaf')], {
            menus: { shared: '叶值' }
          }),
          branch: domain('branch', 20, [route('Branch', '/branch')], {
            menus: { shared: { title: '对象值' } }
          })
        }),
      'DUPLICATE_LOCALE_PATH'
    )
  })

  it.each([
    [
      '__proto__自有键',
      JSON.parse('{"__proto__":{"domainRegistryPolluted":"yes"}}') as LocaleMessageTree
    ],
    ['constructor键', { constructor: { title: '非法' } } as LocaleMessageTree],
    ['含点键', { 'menus.invalid': { title: '非法' } } as LocaleMessageTree],
    ['空键', { '': { title: '非法' } } as LocaleMessageTree]
  ])('locale拒绝非法路径段：%s，且不污染对象原型', (_caseName, messages) => {
    if (_caseName === '__proto__自有键') expect(Object.hasOwn(messages, '__proto__')).toBe(true)
    expect((Object.prototype as Record<string, unknown>).domainRegistryPolluted).toBeUndefined()

    expectRegistrationError(
      () =>
        assembleConsoleDomains({
          malicious: domain('malicious', 10, [route('Malicious', '/malicious')], messages)
        }),
      'INVALID_LOCALE_KEY'
    )

    expect((Object.prototype as Record<string, unknown>).domainRegistryPolluted).toBeUndefined()
    expect(({} as Record<string, unknown>).domainRegistryPolluted).toBeUndefined()
  })

  it('locale拒绝通过对象字面量__proto__改写原型且不污染全局原型', () => {
    const messages = {
      __proto__: { domainRegistryPolluted: 'yes' }
    } as LocaleMessageTree
    expect(Object.hasOwn(messages, '__proto__')).toBe(false)
    expect((messages as Record<string, unknown>).domainRegistryPolluted).toBe('yes')
    expect(({} as Record<string, unknown>).domainRegistryPolluted).toBeUndefined()

    expectRegistrationError(
      () =>
        assembleConsoleDomains({
          literal: domain('literal', 10, [route('Literal', '/literal')], messages)
        }),
      'INVALID_LOCALE_KEY'
    )

    expect((Object.prototype as Record<string, unknown>).domainRegistryPolluted).toBeUndefined()
    expect(({} as Record<string, unknown>).domainRegistryPolluted).toBeUndefined()
  })
})
