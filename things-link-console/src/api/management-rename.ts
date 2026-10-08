import type { components } from '@/types/api/schema'
import { useUserStore } from '@/store/modules/user'
import { currentIdentityEpoch } from '@/utils/http/identity-scope'

export type ManagementKind = 'applications' | 'dashboards'
export type ManagementCatalog =
  | components['schemas']['ApplicationCatalogResponse']
  | components['schemas']['DashboardCatalogResponse']

/** 名称原样保存；按 Unicode 码点计数，与后端空白及控制字符合同一致。 */
export function validManagementName(value: string): boolean {
  return (
    [...value].length >= 1 &&
    [...value].length <= 80 &&
    ![...value].every((character) => /\p{Z}/u.test(character)) &&
    [...value].every((character) => {
      const code = character.codePointAt(0)!
      return code > 31 && (code < 127 || code > 159)
    })
  )
}

/** 管理名称没有恢复键；写请求仅发送一次，不自动刷新认证或重放未知结果。 */
export async function renameManagementResource(
  kind: ManagementKind,
  projectId: string,
  id: string,
  managementName: string
): Promise<ManagementCatalog> {
  if (!validManagementName(managementName))
    throw Error('请输入 1 至 80 个码点的非空白名称，不得含控制字符。')
  const identity = currentIdentityEpoch()
  const token = useUserStore().accessToken
  if (!token) throw Error('登录已失效，请重新登录后读取目录。')
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), 15000)
  const check = () => {
    if (
      controller.signal.aborted ||
      identity !== currentIdentityEpoch() ||
      token !== useUserStore().accessToken
    )
      throw Error('请求结果未知或身份已变化，请重新读取目录核对。')
  }
  try {
    check()
    const response = await fetch(
      `${import.meta.env.VITE_API_URL.replace(/\/$/, '')}/api/v1/projects/${encodeURIComponent(projectId)}/${kind}/${encodeURIComponent(id)}`,
      {
        method: 'PATCH',
        headers: {
          Authorization: `Bearer ${token}`,
          'Content-Type': 'application/json',
          Accept: 'application/json'
        },
        body: JSON.stringify({ managementName }),
        credentials: 'omit',
        cache: 'no-store',
        redirect: 'error',
        signal: controller.signal
      }
    )
    check()
    if (!response.ok) {
      if (response.status === 401 || response.status === 403)
        throw Error('登录或管理权限已失效，请重新确认权限后读取目录。')
      if (response.status === 404) throw Error('对象已不可见，请关闭窗口并重新读取目录。')
      if (response.status >= 500)
        throw Error('请求结果未知，请先重新读取目录核对，系统不会自动重试。')
      throw Error('重命名未完成，请检查名称和项目状态；输入已保留。')
    }
    const result = (await response.json()) as ManagementCatalog
    check()
    if (result.id !== id || result.managementName !== managementName)
      throw Error('响应不完整，请先重新读取目录核对。')
    return result
  } catch (error) {
    if (error instanceof TypeError || controller.signal.aborted)
      throw Error('网络中断或超时，结果未知，请先重新读取目录核对，系统不会自动重试。')
    throw error
  } finally {
    clearTimeout(timer)
  }
}
