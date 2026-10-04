import { AppRouteRecordRaw } from '@/utils/router'

/**
 * 静态路由配置（不需要权限就能访问的路由）
 *
 * 属性说明：
 * isHideTab: true 表示不在标签页中显示
 *
 * 注意事项：
 * 1、path、name 不要和动态路由冲突，否则会导致路由冲突无法访问
 * 2、静态路由不管是否登录都可以访问
 */
export const staticRoutes: AppRouteRecordRaw[] = [
  {
    path: '/auth/project-invitation/:invitationId',
    name: 'ProjectInvitation',
    component: () => import('@views/auth/project-invitation/index.vue'),
    meta: { title: '项目协作邀请', isHideTab: true }
  },
  // 不需要登录就能访问的路由写在这里，例如将来的大屏分享页
  // （架构文档 11.3：只读分享令牌绑定看板版本 + 有效期，不携带账号权限）
  {
    path: '/auth/login',
    name: 'Login',
    component: () => import('@views/auth/login/index.vue'),
    meta: { title: 'menus.login.title', isHideTab: true }
  },
  {
    path: '/auth/register',
    name: 'Register',
    component: () => import('@views/auth/register/index.vue'),
    meta: { title: 'menus.register.title', isHideTab: true }
  },
  {
    // 邮件里的验证链接指向这里。路径与后端 EmailVerificationMailer.VERIFY_PATH
    // 一一对应，改一处必须改两处 —— 不一致的表现是用户点开邮件得到 404，
    // 而后端日志里一切正常
    path: '/auth/verify-email',
    name: 'VerifyEmail',
    component: () => import('@views/auth/verify-email/index.vue'),
    meta: { title: 'menus.verifyEmail.title', isHideTab: true }
  },
  {
    path: '/auth/forget-password',
    name: 'ForgetPassword',
    component: () => import('@views/auth/forget-password/index.vue'),
    meta: { title: 'menus.forgetPassword.title', isHideTab: true }
  },
  {
    // 邮件里的重置链接指向这里。与后端 EmailVerificationMailer.RESET_PATH
    // 一一对应，改一处必须改两处
    path: '/auth/reset-password',
    name: 'ResetPassword',
    component: () => import('@views/auth/reset-password/index.vue'),
    meta: { title: 'menus.resetPassword.title', isHideTab: true }
  },
  {
    path: '/403',
    name: 'Exception403',
    component: () => import('@views/exception/403/index.vue'),
    meta: { title: '403', isHideTab: true }
  },
  {
    path: '/:pathMatch(.*)*',
    name: 'Exception404',
    component: () => import('@views/exception/404/index.vue'),
    meta: { title: '404', isHideTab: true }
  },
  {
    path: '/500',
    name: 'Exception500',
    component: () => import('@views/exception/500/index.vue'),
    meta: { title: '500', isHideTab: true }
  },
  {
    path: '/outside',
    component: () => import('@views/index/index.vue'),
    name: 'Outside',
    meta: { title: 'menus.outside.title' },
    children: [
      // iframe 内嵌页面
      {
        path: '/outside/iframe/:path',
        name: 'Iframe',
        component: () => import('@/views/outside/Iframe.vue'),
        meta: { title: 'iframe' }
      }
    ]
  }
]
