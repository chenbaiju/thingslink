import type { ConsoleDomainRegistration } from '../contract'

/**
 * 个人中心路由供离线前端菜单使用；正式环境仍须由后端 MenuCatalog 下发同一路由。
 * 页面仅消费会话初始化取得的当前用户资料，不增加资料编辑或业务接口。
 */
const userCenterDomain = {
  id: 'user-center',
  order: 110,
  routes: [
    {
      path: '/system/user-center',
      name: 'UserCenter',
      component: '/system/user-center',
      meta: {
        title: 'menus.system.userCenter',
        icon: 'ri:user-3-line',
        keepAlive: false,
        isHide: true,
        isHideTab: true
      }
    }
  ],
  zhCNMessages: {
    menus: {
      system: {
        userCenter: '个人中心'
      }
    },
    userCenter: {
      title: '个人中心',
      description: '查看当前控制台账号与会话信息。',
      readOnly: '以下信息来自当前登录账号资料，仅供查看。',
      accountSection: '账号信息',
      accountId: '账号 ID',
      email: '注册邮箱',
      tenantId: '租户 ID',
      currentProjectId: '当前项目 ID',
      roles: '当前角色',
      noProject: '未选择项目',
      noRoles: '暂无角色信息'
    }
  }
} satisfies ConsoleDomainRegistration

export default userCenterDomain
