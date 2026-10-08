import { fetchDeviceTypePage } from '@/api/device'
import { fetchDeviceGroups } from '@/api/device-group'
import { fetchDashboards } from '@/api/dashboard'
import { fetchApplications } from '@/api/application'
import { fetchEndUsers } from '@/api/end-users'
import { fetchTaskJobs } from '@/api/task'
import { fetchAlarmRules, fetchAlarmNotificationGroups } from '@/api/alarm'
import { fetchOtaFirmwares } from '@/api/ota'
import { listRules } from '@/api/rule-management'
import { listAutomations } from '@/api/automation-management'
import { listScenes } from '@/api/scene-management'

type Row = { id?: string }
type Page = { items?: Row[]; nextCursor?: string | null; hasMore?: boolean }

/** 完整读取游标目录并去重；身份失效时停止续页，不将半页或失败响应当成总数。 */
export async function countResourcePages(
  read: (cursor?: string) => Promise<Page>,
  current: () => boolean
): Promise<number> {
  const ids = new Set<string>()
  const cursors = new Set<string>()
  let cursor: string | undefined
  do {
    if (!current()) throw new Error('资源统计所属身份已改变')
    const page = await read(cursor)
    if (!current()) throw new Error('资源统计所属身份已改变')
    if (!Array.isArray(page.items)) throw new Error('资源目录缺少条目')
    for (const row of page.items) {
      if (!row.id) throw new Error('资源条目缺少标识')
      ids.add(row.id)
    }
    cursor = page.nextCursor || undefined
    if (page.hasMore && !cursor) throw new Error('资源目录缺少续页游标')
    if (cursor && cursors.has(cursor)) throw new Error('资源目录游标重复')
    if (cursor) cursors.add(cursor)
  } while (cursor)
  return ids.size
}

/** 当前项目已有业务目录；每个统计项沿用对应页面的读取权限和后端过滤口径。 */
export const workbenchResources = [
  { label: '设备类型', path: '/device/types', icon: 'ri:box-3-line', read: fetchDeviceTypePage },
  {
    label: '设备组',
    path: '/device/groups',
    icon: 'ri:organization-chart',
    read: fetchDeviceGroups
  },
  { label: '看板', path: '/dashboard/designer', icon: 'ri:dashboard-line', read: fetchDashboards },
  {
    label: '消息规则',
    path: '/rule/messages',
    icon: 'ri:terminal-box-line',
    read: (id: string, cursor?: string) => listRules(id, { cursor })
  },
  { label: '任务', path: '/task/jobs', icon: 'ri:calendar-check-line', read: fetchTaskJobs },
  { label: '告警规则', path: '/alarm/rules', icon: 'ri:error-warning-line', read: fetchAlarmRules },
  {
    label: '自动化',
    path: '/rule/automations',
    icon: 'ri:robot-2-line',
    read: (id: string, cursor?: string) => listAutomations(id, { cursor })
  },
  {
    label: '场景',
    path: '/rule/scenes',
    icon: 'ri:play-circle-line',
    read: (id: string, cursor?: string) => listScenes(id, { cursor })
  },
  {
    label: '告警通知组',
    path: '/alarm/notification-groups',
    icon: 'ri:notification-3-line',
    read: fetchAlarmNotificationGroups
  },
  {
    label: '项目应用',
    path: '/dashboard/applications',
    icon: 'ri:apps-line',
    read: fetchApplications
  },
  { label: '用户', path: '/project/end-users', icon: 'ri:group-line', read: fetchEndUsers },
  {
    label: 'OTA 版本',
    path: '/ota/firmwares',
    icon: 'ri:upload-cloud-2-line',
    read: fetchOtaFirmwares
  }
]

export async function countWorkbenchResource(
  resource: (typeof workbenchResources)[number],
  projectId: string,
  current: () => boolean
) {
  return countResourcePages(async (cursor) => {
    const value = await resource.read(projectId, cursor)
    return Array.isArray(value) ? { items: value } : value
  }, current)
}
