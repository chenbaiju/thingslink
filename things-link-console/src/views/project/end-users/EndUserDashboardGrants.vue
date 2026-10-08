<template>
  <section v-if="canManage" class="end-user-dashboard-grants">
    <div class="console-toolbar">
      <h4>{{ account.username }}的看板读取授权</h4>
      <ElButton :disabled="loading" @click="refresh">刷新授权目录</ElButton>
    </div>
    <p>目录包含有效与已撤销记录。项目角色不代表看板读取授权；历史看板以稳定ID识别。</p>
    <ElAlert
      v-if="failed"
      title="授权目录读取失败，请刷新；不能据此判断没有授权。"
      type="error"
      :closable="false"
    />
    <ul>
      <li v-for="row in rows" :key="row.dashboardId" :data-dashboard-id="row.dashboardId">
        看板 {{ row.dashboardId }} · {{ row.permission }} / {{ row.status }} · 修订
        {{ row.revision }}
        <p
          >建立：{{ formatTime(row.createdAt) }}；更新：{{ formatTime(row.updatedAt) }}；撤销：{{
            row.revokedAt ? formatTime(row.revokedAt) : '无'
          }}</p
        >
        <ElButton :disabled="loading" @click="target = row.dashboardId">查看此看板授权</ElButton>
      </li>
    </ul>
    <p v-if="!loading && !failed && !rows.length">当前页暂无授权记录。</p>
    <div class="console-page-actions">
      <ElButton :disabled="loading || pageIndex === 0" @click="load(pageIndex - 1)"
        >上一页授权</ElButton
      >
      <span>第{{ pageIndex + 1 }}页</span>
      <ElButton :disabled="loading || failed || !nextCursor" @click="next">下一页授权</ElButton>
    </div>
    <h4>选择当前项目看板</h4>
    <p
      >从服务端目录选择后，可显式查看或授予读取权限。未发布、已删除或角色无效等边界仍由服务端核验。</p
    >
    <ElButton :disabled="catalogLoading" @click="loadCatalog()">刷新可选看板</ElButton>
    <ElAlert
      v-if="catalogFailed"
      title="可选看板读取失败，请刷新。"
      type="error"
      :closable="false"
    />
    <ul
      ><li v-for="item in catalog" :key="item.id">
        {{ item.managementName }}（{{ item.id }}）
        <ElButton :disabled="catalogLoading" @click="target = item.id || ''">选择此看板</ElButton>
      </li></ul
    >
    <p v-if="!catalogLoading && !catalogFailed && !catalog.length">当前页暂无可选看板。</p>
    <ElButton
      v-if="catalogCursor"
      :disabled="catalogLoading || catalogFailed"
      @click="loadCatalog(catalogCursor)"
      >下一页看板</ElButton
    >
    <section v-if="target">
      <p
        >当前看板：{{ target }}；当前用户：{{ account.username }}。{{
          allowWrite ? '' : '项目不可写，仅可读取历史。'
        }}</p
      >
      <DesignerGrants
        :project-id="projectId"
        :dashboard-id="target"
        :available="true"
        :can-manage="canManage"
        :fixed-user="fixedUser"
        :writable="allowWrite"
        @changed="refresh"
      />
    </section>
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
  const loading = ref(false),
    failed = ref(false),
    catalogLoading = ref(false),
    catalogFailed = ref(false)
  const target = ref(''),
    pageIndex = ref(0),
    nextCursor = ref<string | null>(null),
    catalogCursor = ref<string | null>(null)
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
