<template>
  <section
    v-if="canManage"
    class="end-user-dashboard-grants"
    :aria-busy="loading || catalogLoading"
  >
    <ElDivider content-position="left">{{ account.username }}的看板读取授权</ElDivider>
    <ElAlert class="console-hint" type="info" show-icon :closable="false"
      >目录包含有效与已撤销记录。项目角色不代表看板读取授权；历史看板以稳定ID识别。</ElAlert
    >
    <ElAlert
      v-if="failed"
      title="授权目录读取失败，请重新进入页面；不能据此判断没有授权。"
      type="error"
      :closable="false"
      show-icon
    />
    <div class="end-user-dashboard-grants__table-wrap">
      <table class="end-user-dashboard-grants__table" aria-label="看板读取授权列表">
        <thead
          ><tr>
            <th scope="col">看板</th><th scope="col">权限</th><th scope="col">授权状态</th>
            <th scope="col">修订</th><th scope="col">建立时间</th><th scope="col">更新时间</th>
            <th scope="col">撤销时间</th><th scope="col">操作</th>
          </tr></thead
        >
        <tbody>
          <tr v-for="row in rows" :key="row.dashboardId" :data-dashboard-id="row.dashboardId">
            <td
              ><div
                class="end-user-dashboard-grants__details end-user-dashboard-grants__details--inline"
              >
                <strong>{{ dashboardName(row.dashboardId) }}</strong>
                <span class="end-user-dashboard-grants__id" :title="row.dashboardId"
                  >ID：{{ row.dashboardId }}</span
                >
              </div></td
            >
            <td
              ><ElTag size="small" type="info">读取（{{ row.permission }}）</ElTag></td
            >
            <td
              ><ElTag size="small" :type="row.status === 'ACTIVE' ? 'success' : 'info'">
                {{ row.status === 'ACTIVE' ? '已授权' : '已撤销' }}（{{ row.status }}）
              </ElTag></td
            >
            <td>{{ row.revision }}</td>
            <td>{{ formatTime(row.createdAt) }}</td>
            <td>{{ formatTime(row.updatedAt) }}</td>
            <td>{{ row.revokedAt ? formatTime(row.revokedAt) : '未撤销' }}</td>
            <td
              ><ElButton :disabled="loading" @click="showGrants(row.dashboardId)"
                >查看此看板授权</ElButton
              ></td
            >
          </tr>
        </tbody>
      </table>
    </div>
    <div
      class="end-user-dashboard-grants__pagination"
      role="navigation"
      aria-label="看板授权目录分页"
    >
      <ElButton :disabled="loading || pageIndex === 0" @click="load(pageIndex - 1)"
        >上一页授权</ElButton
      >
      <span>第{{ pageIndex + 1 }}页</span>
      <ElButton :disabled="loading || failed || !nextCursor" @click="next">下一页授权</ElButton>
    </div>
    <ElDivider content-position="left">选择当前项目看板</ElDivider>
    <ElAlert class="console-hint" type="info" show-icon :closable="false"
      >从服务端目录选择后，可显式查看或授予读取权限。未发布、已删除或角色无效等边界仍由服务端核验。</ElAlert
    >
    <ElAlert
      v-if="catalogFailed"
      title="可选看板读取失败，请重新进入页面。"
      type="error"
      :closable="false"
      show-icon
    />
    <div class="end-user-dashboard-grants__table-wrap">
      <table
        class="end-user-dashboard-grants__table end-user-dashboard-grants__table--catalog"
        aria-label="可选看板列表"
      >
        <thead
          ><tr><th scope="col">看板</th><th scope="col">操作</th></tr></thead
        >
        <tbody
          ><tr v-for="item in catalog" :key="item.id">
            <td
              ><div
                class="end-user-dashboard-grants__details end-user-dashboard-grants__details--inline"
              >
                <strong>{{ item.managementName }}</strong>
                <span class="end-user-dashboard-grants__meta" :title="item.id"
                  >看板 ID：{{ item.id }}</span
                >
              </div></td
            >
            <td
              ><ElButton :disabled="catalogLoading" @click="showGrants(item.id || '')"
                >选择此看板</ElButton
              ></td
            >
          </tr></tbody
        >
      </table>
    </div>
    <p v-if="!catalogLoading && !catalogFailed && !catalog.length">当前页暂无可选看板。</p>
    <ElButton
      v-if="catalogCursor"
      :disabled="catalogLoading || catalogFailed"
      @click="loadCatalog(catalogCursor)"
      >下一页看板</ElButton
    >
    <DesignerGrants
      v-if="target"
      ref="grantPanel"
      :project-id="projectId"
      :dashboard-id="target"
      :available="true"
      :can-manage="canManage"
      :fixed-user="fixedUser"
      :writable="allowWrite"
      :hide-trigger="true"
      @changed="refresh"
    >
      <template #context>
        <dl class="end-user-dashboard-grants__selection">
          <div>
            <dt>当前看板</dt>
            <dd
              ><strong>{{ dashboardName(target) }}</strong
              ><span class="end-user-dashboard-grants__meta">看板 ID：{{ target }}</span></dd
            >
          </div>
          <div
            ><dt>当前用户</dt
            ><dd
              ><strong>{{ account.username }}</strong></dd
            ></div
          >
        </dl>
        <ElAlert v-if="!allowWrite" class="console-hint" type="info" show-icon :closable="false"
          >项目不可写，仅可读取历史。</ElAlert
        >
      </template>
    </DesignerGrants>
  </section>
</template>
<script setup lang="ts">
  import { fetchUserDashboardGrants } from '@/api/dashboard-grants'
  import { fetchDashboards, type DashboardCatalog } from '@/api/dashboard'
  import type { EndUser } from '@/api/end-users'
  import {
    parseDashboardGrant,
    type DashboardGrant,
    type GrantUser
  } from '@/features/dashboard/grant-model'
  import DesignerGrants from '@/views/dashboard/designer/components/DesignerGrants.vue'
  import { useUserStore } from '@/store/modules/user'
  import { currentIdentityEpoch } from '@/utils/http/identity-scope'
  import { formatTime } from '@/utils/time'
  const props = defineProps<{ projectId: string; account: EndUser; allowWrite: boolean }>()
  const user = useUserStore()
  const canManage = computed(() => (user.info.buttons || []).includes('enduser:manage'))
  const fixedUser = computed<GrantUser>(() => ({
    id: props.account.id || '',
    username: props.account.username || '',
    displayName: props.account.displayName ?? null,
    status: props.account.status || '',
    role: props.account.role ?? null,
    roleStatus: props.account.roleStatus ?? null,
    assignedAt: props.account.assignedAt ?? null
  }))
  const rows = ref<DashboardGrant[]>([]),
    catalog = ref<DashboardCatalog[]>([])
  const dashboardName = (id: string) =>
    catalog.value.find((item) => item.id === id)?.managementName || '看板'
  const loading = ref(false),
    failed = ref(false),
    catalogLoading = ref(false),
    catalogFailed = ref(false)
  const target = ref(''),
    pageIndex = ref(0),
    nextCursor = ref<string | null>(null),
    catalogCursor = ref<string | null>(null)
  const grantPanel = ref<InstanceType<typeof DesignerGrants>>()
  async function showGrants(id: string) {
    if (!id || !canManage.value) return
    target.value = id
    await nextTick()
    grantPanel.value?.openDialog()
  }
  let generation = 0,
    reads = 0,
    catalogReads = 0
  let cursors: (string | undefined)[] = [undefined]
  function cursor(
    page: { items?: unknown[]; hasMore?: boolean; nextCursor?: string | null },
    prior?: string
  ) {
    if (
      !Array.isArray(page.items) ||
      page.items.length > 20 ||
      typeof page.hasMore !== 'boolean' ||
      (page.hasMore &&
        (!page.items.length ||
          typeof page.nextCursor !== 'string' ||
          !page.nextCursor ||
          page.nextCursor.length > 2048 ||
          page.nextCursor === prior))
    )
      throw new Error('分页响应无效')
    return page.hasMore ? page.nextCursor! : null
  }
  async function load(index: number) {
    if (!canManage.value || !props.account.id || index < 0 || index >= cursors.length) return
    const scope = generation,
      sequence = ++reads,
      account = props.account.id
    rows.value = []
    loading.value = true
    failed.value = false
    nextCursor.value = null
    try {
      const page = await fetchUserDashboardGrants(props.projectId, account, cursors[index])
      if (scope !== generation || sequence !== reads) return
      const next = cursor(page, cursors[index])
      const result = page.items!.map((row) => parseDashboardGrant(row, account, row.dashboardId))
      if (
        result.some(
          (row) => !/^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/.test(row.dashboardId)
        ) ||
        new Set(result.map((row) => row.dashboardId)).size !== result.length
      )
        throw new Error('授权目录响应无效')
      rows.value = result
      nextCursor.value = next
      pageIndex.value = index
    } catch {
      if (scope === generation && sequence === reads) failed.value = true
    } finally {
      if (scope === generation && sequence === reads) loading.value = false
    }
  }
  function refresh() {
    cursors = [undefined]
    pageIndex.value = 0
    void load(0)
  }
  function next() {
    if (!loading.value && nextCursor.value) {
      cursors[pageIndex.value + 1] = nextCursor.value
      void load(pageIndex.value + 1)
    }
  }
  async function loadCatalog(after?: string) {
    if (!canManage.value) return
    const scope = generation,
      sequence = ++catalogReads
    catalog.value = []
    catalogLoading.value = true
    catalogFailed.value = false
    catalogCursor.value = null
    try {
      const page = await fetchDashboards(props.projectId, after)
      if (scope !== generation || sequence !== catalogReads) return
      const next = cursor(page, after)
      if (
        page.items!.some(
          (row) =>
            typeof row.id !== 'string' ||
            !/^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/.test(row.id) ||
            typeof row.managementName !== 'string'
        ) ||
        new Set(page.items!.map((row) => row.id)).size !== page.items!.length
      )
        throw new Error('看板目录响应无效')
      catalog.value = page.items!
      catalogCursor.value = next
    } catch {
      if (scope === generation && sequence === catalogReads) catalogFailed.value = true
    } finally {
      if (scope === generation && sequence === catalogReads) catalogLoading.value = false
    }
  }
  watch(
    [
      () => props.projectId,
      () => props.account.id,
      () => user.info.userId,
      () => user.info.tenantId,
      currentIdentityEpoch,
      canManage
    ],
    () => {
      generation++
      reads++
      catalogReads++
      target.value = ''
      rows.value = []
      catalog.value = []
      loading.value = false
      catalogLoading.value = false
      failed.value = false
      catalogFailed.value = false
      nextCursor.value = null
      catalogCursor.value = null
      cursors = [undefined]
      pageIndex.value = 0
      if (canManage.value) {
        refresh()
        void loadCatalog()
      }
    },
    { immediate: true, flush: 'sync' }
  )
  onBeforeUnmount(() => {
    generation++
    reads++
    catalogReads++
  })
</script>

<style scoped lang="scss">
  .end-user-dashboard-grants__details {
    display: flex;
    flex-direction: column;
    gap: 4px;
    min-width: 0;
    strong {
      font-weight: 500;
      overflow-wrap: anywhere;
    }
    p {
      margin: 0;
      font-size: 12px;
      color: var(--el-text-color-secondary);
    }
  }
  .end-user-dashboard-grants__details--inline {
    flex-direction: row;
    gap: 16px;
    align-items: baseline;
    overflow: hidden;
    white-space: nowrap;
    strong {
      flex-shrink: 0;
    }
    .end-user-dashboard-grants__meta {
      min-width: 0;
      overflow: hidden;
      text-overflow: ellipsis;
    }
  }
  .end-user-dashboard-grants__meta {
    font-size: 12px;
    color: var(--el-text-color-secondary);
    overflow-wrap: anywhere;
  }
  .end-user-dashboard-grants__pagination {
    display: flex;
    flex-wrap: wrap;
    gap: 10px;
    align-items: center;
    justify-content: flex-end;
    padding-block: 5px;
    margin-top: 5px;
    > .el-button {
      margin: 0;
    }
  }
  .end-user-dashboard-grants__table-wrap {
    max-width: 100%;
    margin-top: 12px;
    overflow-x: auto;
  }
  .end-user-dashboard-grants__table {
    width: 100%;
    font-size: 13px;
    text-align: left;
    border-collapse: collapse;
    th,
    td {
      padding: 12px;
      white-space: nowrap;
      border-bottom: 1px solid var(--el-border-color-lighter);
    }
    th {
      font-weight: 500;
      color: var(--el-text-color-secondary);
      background: var(--el-fill-color-light);
    }
    th:first-child {
      min-width: 360px;
    }
    tbody tr:hover {
      background: var(--el-table-row-hover-bg-color, var(--el-fill-color-light));
    }
  }
  .end-user-dashboard-grants__table--catalog {
    th,
    td {
      padding-block: 8px;
    }
    td:last-child {
      width: 1%;
      text-align: right;
    }
  }
  .end-user-dashboard-grants__id {
    max-width: 280px;
    overflow: hidden;
    font-size: 12px;
    color: var(--el-text-color-secondary);
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .end-user-dashboard-grants__selection {
    display: grid;
    grid-template-columns: minmax(0, 2fr) minmax(0, 1fr);
    gap: 16px;
    padding: 12px 16px;
    margin: 0 0 12px;
    background: var(--el-fill-color-light);
    dt {
      margin-bottom: 6px;
      font-size: 12px;
      color: var(--el-text-color-secondary);
    }
    dd {
      display: flex;
      flex-direction: column;
      gap: 4px;
      margin: 0;
      overflow-wrap: anywhere;
    }
    strong {
      font-weight: 500;
    }
  }
  @media (width <= 680px) {
    .end-user-dashboard-grants__selection {
      grid-template-columns: minmax(0, 1fr);
    }
  }
</style>
