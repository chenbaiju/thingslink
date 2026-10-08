/**
 * 用户状态管理模块
 *
 * 提供用户相关的状态管理
 *
 * ## 主要功能
 *
 * - 用户登录状态管理
 * - 用户信息存储
 * - 访问令牌和刷新令牌管理
 * - 语言设置
 * - 搜索历史记录
 * - 锁屏状态和密码管理
 * - 登出清理逻辑
 *
 * ## 使用场景
 *
 * - 用户登录和认证
 * - 权限验证
 * - 个人信息展示
 * - 多语言切换
 * - 锁屏功能
 * - 搜索历史管理
 *
 * ## 持久化
 *
 * - 使用 localStorage 存储
 * - 存储键：sys-v{version}-user
 * - 登出时自动清理
 *
 * @module store/modules/user
 * @author Things Link Team
 */
import { clearRecentResources } from '@/utils/workbench-recent'
import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { LanguageEnum } from '@/enums/appEnum'
import { router } from '@/router'
import { useSettingStore } from './setting'
import { useWorktabStore } from './worktab'
import { AppRouteRecord } from '@/types/router'
import { setPageTitle } from '@/utils/router'
import { resetRouterState } from '@/router/guards/beforeEach'
import { RoutesAlias } from '@/router/routesAlias'
import { useMenuStore } from './menu'
import { StorageConfig } from '@/utils/storage/storage-config'
import { assertCurrentIdentity, invalidateIdentity } from '@/utils/http/identity-scope'

/**
 * 用户状态管理
 * 管理用户登录状态、个人信息、语言设置、搜索历史、锁屏状态等
 */
export const useUserStore = defineStore(
  'userStore',
  () => {
    // 语言设置
    const language = ref(LanguageEnum.ZH)
    // 登录状态
    const isLogin = ref(false)
    // 用户信息
    const info = ref<Partial<Api.Auth.UserInfo>>({})
    // 搜索历史记录
    const searchHistory = ref<AppRouteRecord[]>([])
    // 访问令牌。
    //
    // 【只在内存里，不持久化】ADR 0010。刷新页面后它必然为空，此时由 HTTP 层用
    // Cookie 里的刷新令牌静默换一个新的。存进 localStorage 会让任何一段注入的
    // 脚本都能读到它。
    //
    // 它没有出现在下方的 persist.pick 白名单里 —— 那份白名单才是真正的开关，
    // 改动时务必确认它没被加回去。
    const accessToken = ref('')

    // 计算属性：获取用户信息
    const getUserInfo = computed(() => info.value)
    // 计算属性：获取设置状态
    const getSettingState = computed(() => useSettingStore().$state)
    // 计算属性：获取工作台状态
    const getWorktabState = computed(() => useWorktabStore().$state)

    /**
     * 设置用户信息
     * @param newInfo 新的用户信息
     */
    const setUserInfo = (newInfo: Api.Auth.UserInfo) => {
      if (
        info.value.userId !== newInfo.userId ||
        info.value.tenantId !== newInfo.tenantId ||
        info.value.currentProjectId !== newInfo.currentProjectId
      )
        invalidateIdentity()
      info.value = newInfo
    }

    /**
     * 设置登录状态
     * @param status 登录状态
     */
    const setLoginStatus = (status: boolean) => {
      // 重新登录即使账号相同也属于新会话；普通refresh不调用此入口。
      if (status || status !== isLogin.value) invalidateIdentity()
      isLogin.value = status
    }

    /**
     * 设置语言
     * @param lang 语言枚举值
     */
    const setLanguage = (lang: LanguageEnum) => {
      setPageTitle(router.currentRoute.value)
      language.value = lang
    }

    /**
     * 设置搜索历史
     * @param list 搜索历史列表
     */
    const setSearchHistory = (list: AppRouteRecord[]) => {
      searchHistory.value = list
    }

    /**
     * 设置访问令牌
     *
     * 只有一个参数：刷新令牌由后端写在 HttpOnly Cookie 里，前端读不到也存不了
     * （ADR 0010）。模板原本的第二个参数已移除 —— 留着会让人以为前端还该管它。
     *
     * @param newAccessToken 访问令牌
     */
    const setToken = (newAccessToken: string) => {
      invalidateIdentity()
      accessToken.value = newAccessToken
    }

    /** 正常refresh保持当前身份，仅允许发起刷新时的代次写回内存。 */
    const setRefreshedToken = (newAccessToken: string, expectedEpoch: number) => {
      assertCurrentIdentity(expectedEpoch)
      accessToken.value = newAccessToken
    }

    /**
     * 通知后端作废整族刷新令牌并清除 Cookie。
     *
     * 【为什么用裸 fetch 而不是 @/api/auth】
     * 那会形成循环依赖：api/auth → utils/http → store/modules/user → api/auth。
     * 退出只是一个无请求体、无返回值的 POST，为它绕开封装是划算的。
     *
     * keepalive 让请求在页面跳转（跳回登录页）后仍能完成 —— 否则浏览器会把
     * 这个刚发出的请求直接取消，服务端的令牌就永远不会被作废。
     *
     * 失败静默：接口幂等，且退出的界面效果不该被网络状况左右。
     */
    const revokeSession = () => {
      clearRecentResources()
      const base = String(import.meta.env.VITE_API_URL || '').replace(/\/$/, '')
      fetch(`${base}/api/v1/auth/logout`, {
        method: 'POST',
        credentials: 'include',
        keepalive: true
      }).catch(() => {
        /* 忽略：退出必须总是成功 */
      })
    }

    /**
     * 退出登录
     * 清空所有用户相关状态并跳转到登录页
     * 如果是同一账号重新登录，保留工作台标签页
     */
    const logOut = () => {
      // 必须在网络撤销和延迟路由清理之前同步失效，禁止旧请求复活当前会话。
      invalidateIdentity()
      // 通知后端作废整族刷新令牌，并清除 Cookie。
      //
      // 【为什么不 await】退出必须立刻生效于界面。网络慢或后端不可用时，
      // 用户会看到「点了退出但还停在原页面」，那是最容易让人反复点击的状态。
      // 接口是幂等的，失败了也不会留下脏数据。
      //
      // 【只清前端状态是不够的】不调它的话，Cookie 里的刷新令牌在服务端依然有效，
      // 谁拿到它都能继续换新令牌 —— 那等于「退出登录」只是把钥匙藏起来，没换锁。
      revokeSession()

      // 保存当前用户 ID，用于下次登录时判断是否为同一用户
      const currentUserId = info.value.userId
      if (currentUserId) {
        localStorage.setItem(StorageConfig.LAST_USER_ID_KEY, String(currentUserId))
      }

      // 清空用户信息
      info.value = {}
      // 重置登录状态
      isLogin.value = false
      // 清空访问令牌（刷新令牌在 Cookie 里，由后端的 /logout 清除）
      accessToken.value = ''
      // 注意：不清空工作台标签页，等下次登录时根据用户判断
      // 移除iframe路由缓存
      sessionStorage.removeItem('iframeRoutes')
      // 清空主页路径
      useMenuStore().setHomePath('')
      // 重置路由状态
      resetRouterState(500)
      // 跳转到登录页，携带当前路由作为 redirect 参数
      const currentRoute = router.currentRoute.value
      const redirect = currentRoute.path !== RoutesAlias.Login ? currentRoute.fullPath : undefined
      router.push({
        name: 'Login',
        query: redirect ? { redirect } : undefined
      })
    }

    /**
     * 检查并清理工作台标签页
     * 如果不是同一用户登录，清空工作台标签页
     * 应在登录成功后调用
     */
    const checkAndClearWorktabs = () => {
      const lastUserId = localStorage.getItem(StorageConfig.LAST_USER_ID_KEY)
      const currentUserId = info.value.userId

      // 无法获取当前用户 ID，跳过检查
      if (!currentUserId) return

      // 首次登录或缓存已清除，保留现有标签页
      if (!lastUserId) {
        return
      }

      // 不同用户登录，清空工作台标签页
      if (String(currentUserId) !== lastUserId) {
        const worktabStore = useWorktabStore()
        worktabStore.opened = []
        worktabStore.keepAliveExclude = []
      }

      // 清除临时存储
      localStorage.removeItem(StorageConfig.LAST_USER_ID_KEY)
    }

    return {
      language,
      isLogin,
      info,
      searchHistory,
      accessToken,
      getUserInfo,
      getSettingState,
      getWorktabState,
      setUserInfo,
      setLoginStatus,
      setLanguage,
      setSearchHistory,
      setToken,
      setRefreshedToken,
      logOut,
      checkAndClearWorktabs
    }
  },
  {
    persist: {
      key: 'user',
      storage: localStorage,
      /**
       * 【白名单，不是黑名单】
       *
       * 只有列在这里的字段会进 localStorage。用 pick 而不是 omit 是刻意的：
       * omit 意味着「默认持久化，记得排除敏感项」——将来新增一个字段时，
       * 忘了排除就会被默默写进去，而这类疏漏没有任何症状。
       *
       * accessToken **不在这里**，它只存在于内存（ADR 0010）。
       * refreshToken 字段已经删掉了，它在 HttpOnly Cookie 里。
       *
       * 下面这些都不是凭据，持久化只是为了刷新页面后少一次抖动：
       * isLogin / info 是显示用的提示，真正的凭据是 Cookie —— 手动改它们
       * 拿不到任何数据，第一个接口调用就会 401。
       */
      pick: ['language', 'isLogin', 'info', 'searchHistory']
    }
  }
)
