import type { ConsoleDomainRegistration } from '../contract'
import { exceptionRoutes } from '@/router/modules/exception'

/** 异常页域注册；保留既有末序，避免改变前端兜底菜单的历史顺序。 */
const exceptionDomain = {
  id: 'exception',
  order: 80,
  routes: [exceptionRoutes],
  zhCNMessages: {
    menus: {
      exception: {
        title: '异常页面',
        forbidden: '403',
        notFound: '404',
        serverError: '500'
      }
    }
  }
} satisfies ConsoleDomainRegistration

export default exceptionDomain
