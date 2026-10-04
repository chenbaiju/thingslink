/**
 * 国际化配置
 *
 * 基于 vue-i18n，目前只提供简体中文。
 *
 * ## 为什么保留 i18n 而不是直接写中文
 *
 * 菜单标题由**后端下发 i18n key**（如 menus.dashboard.title），翻译在前端完成。
 * 后端不持有任何语言版本的文案 —— 下发中文的话，将来切语言时菜单会是唯一
 * 不跟着变的部分。所以这层不能省。
 *
 * ## 为什么只有中文
 *
 * 模板自带的 en.json 只覆盖模板自己的演示页面，业务功能一个都没有，已在
 * S1 切片 3 删除。理由见 config/modules/headerBar.ts 的 language 项。
 * 语言切换器同时被该配置关掉（顶部栏与登录页两处）。
 *
 * @module locales
 * @author Things Link Team
 */

import { createI18n } from 'vue-i18n'
import type { I18n, I18nOptions } from 'vue-i18n'
import { LanguageEnum } from '@/enums/appEnum'
import { getSystemStorage } from '@/utils/storage'
import { StorageKeyManager } from '@/utils/storage/storage-key-manager'
import { consoleDomainRegistry, mergeConsoleLocaleMessages } from '@/domains/registry'

// 核心登录壳仍同步导入；业务域文案由静态域注册表自动发现并执行冲突校验。
import zhMessages from './langs/zh.json'

/**
 * 存储键管理器实例
 */
const storageKeyManager = new StorageKeyManager()

/**
 * 语言消息对象
 */
const messages = {
  [LanguageEnum.ZH]: mergeConsoleLocaleMessages(zhMessages, consoleDomainRegistry.zhCNMessages)
}

/**
 * 语言选项列表
 * 用于语言切换下拉框
 *
 * 只有一项，因此切换器本身已由 headerBarConfig.language.enabled = false 关闭。
 * 这个导出保留着：加第二种语言时，打开开关即可，不用重新接线。
 */
export const languageOptions = [{ value: LanguageEnum.ZH, label: '简体中文' }]

/**
 * 从存储中获取语言设置
 * @returns 语言设置，如果获取失败则返回默认语言
 */
const getDefaultLanguage = (): LanguageEnum => {
  // 尝试从版本化的存储中获取语言设置
  try {
    const storageKey = storageKeyManager.getStorageKey('user')
    const userStore = localStorage.getItem(storageKey)

    if (userStore) {
      const { language } = JSON.parse(userStore)
      if (language && Object.values(LanguageEnum).includes(language)) {
        return language
      }
    }
  } catch (error) {
    console.warn('[i18n] 从版本化存储获取语言设置失败:', error)
  }

  // 尝试从系统存储中获取语言设置
  try {
    const sys = getSystemStorage()
    if (sys) {
      const { user } = JSON.parse(sys)
      if (user?.language && Object.values(LanguageEnum).includes(user.language)) {
        return user.language
      }
    }
  } catch (error) {
    console.warn('[i18n] 从系统存储获取语言设置失败:', error)
  }

  // 返回默认语言
  console.debug('[i18n] 使用默认语言:', LanguageEnum.ZH)
  return LanguageEnum.ZH
}

/**
 * i18n 配置选项
 */
const i18nOptions: I18nOptions = {
  locale: getDefaultLanguage(),
  legacy: false,
  globalInjection: true,
  fallbackLocale: LanguageEnum.ZH,
  messages
}

/**
 * i18n 实例
 */
const i18n: I18n = createI18n(i18nOptions)

/**
 * 翻译函数类型
 */
interface Translation {
  (key: string): string
}

/**
 * 全局翻译函数
 * 可在任何地方使用，无需导入 useI18n
 */
export const $t = i18n.global.t as Translation

export default i18n
