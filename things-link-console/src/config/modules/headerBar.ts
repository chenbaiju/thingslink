/**
 * 顶部栏功能配置
 *
 * 统一管理顶部栏各个功能模块的启用状态。
 * 通过修改此配置文件可以快速启用或禁用顶部栏的功能按钮。
 *
 * @module config/headerBar
 * @author Things Link Team
 */

import { HeaderBarFeatureConfig } from '@/types'

/**
 * 顶部栏功能配置对象
 */
export const headerBarConfig: HeaderBarFeatureConfig = {
  menuButton: {
    enabled: true,
    description: '控制左侧菜单的展开/收起按钮'
  },
  refreshButton: {
    enabled: true,
    description: '页面刷新按钮'
  },
  fastEnter: {
    // 关闭。模板快速入口没有真实业务目标，继续显示会把演示链接误当成功能入口。
    enabled: false,
    description: '快速入口功能（待接入真实业务入口后开启）'
  },
  breadcrumb: {
    enabled: true,
    description: '面包屑导航，显示当前页面路径'
  },
  globalSearch: {
    // 关闭。当前没有跨资源搜索接口，模板本地搜索只能搜菜单，容易误导为业务搜索。
    enabled: false,
    description: '全局搜索功能（待后端搜索接口后开启）'
  },
  fullscreen: {
    enabled: true,
    description: '全屏切换功能'
  },
  notification: {
    // ADR0093：当前项目个人告警通知；后台Web Push及模板消息/待办不属于此入口。
    enabled: true,
    description: '当前项目告警通知与个人已读'
  },
  chat: {
    enabled: true,
    description: '聊天功能（待接入AI助手后端会话能力）'
  },
  language: {
    // 关闭。项目只提供简体中文，英文语言包已在 S1 切片 3 删除。
    //
    // 【为什么删掉英文而不是留着慢慢补】
    // 模板自带的 en.json 只覆盖模板自己的页面，业务功能一个都没有。留着它，
    // 每加一个页面就欠一笔翻译债，而没人会去还 —— 结果是用户切到英文后看到
    // 一半英文一半中文，比只有中文更糟。
    //
    // vue-i18n 本身保留：菜单标题由后端下发 i18n key（如 menus.dashboard.title），
    // 翻译在前端完成。真要做多语言时，恢复顺序是「先补齐语言包，再打开这个开关」。
    enabled: false,
    description: '多语言切换功能（当前仅简体中文，已关闭）'
  },
  settings: {
    enabled: true,
    description: '系统设置面板'
  },
  themeToggle: {
    enabled: true,
    description: '主题切换功能（明暗主题）'
  }
}

export default headerBarConfig
