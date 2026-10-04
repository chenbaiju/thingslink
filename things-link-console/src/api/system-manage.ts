import request from '@/utils/http'
import type { AppRouteRecord } from '@/types/router'
import type { components } from '@/types/api/schema'

/**
 * 控制台系统接口。
 *
 * 【类型来自 OpenAPI 生成物，不手写】
 * 架构文档 11.1：OpenAPI 是唯一契约来源。后端改了字段名，`pnpm api:check`
 * 会在 CI 里变红，而不是等到运行时才发现 undefined。
 *
 * 本文件原先还有 fetchGetUserList / fetchGetRoleList 两个函数，指向
 * /api/user/list 与 /api/role/list —— 那是后台模板对接自家 Apifox Mock 的地址，
 * 后端从来没有这两个接口，调用它们只会 404。对应的页面已在 S0-7 删除，函数
 * 也一并删掉：留着的死代码会让人误以为这些接口已经存在。成员管理接口按 S1
 * 后续切片实现，届时重新加回，路径为 /api/v1/...。
 */

/** 菜单树节点（后端契约） */
export type MenuNodeResponse = components['schemas']['MenuNodeResponse']

/**
 * 取当前用户可见的菜单树。
 *
 * 服务端已按权限过滤完毕，前端拿到什么就渲染什么，不做二次筛选。
 * 因此**不要**把它当成授权依据 —— 菜单只影响界面显示，授权在服务端逐个接口校验。
 *
 * 返回值断言成 AppRouteRecord[]：生成类型里每个字段都是可选的（OpenAPI 的
 * required 语义与 TypeScript 的可选性不对应），而路由注册要求 meta.title 必然
 * 存在。这个缺口由后端的 MenuEndpointTests 补上 —— 它对实际下发的 JSON 逐字段
 * 断言，包括 isHideTab 这类容易被序列化改名的字段。
 */
export function fetchGetMenuList() {
  return request.get<MenuNodeResponse[]>({
    url: '/api/v1/system/menus'
  }) as Promise<AppRouteRecord[]>
}
