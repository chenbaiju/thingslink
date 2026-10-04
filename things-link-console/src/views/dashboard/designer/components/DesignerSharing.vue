<template>
  <section
    class="console-fragment console-editor-section"
    v-if="canManage"
    aria-label="匿名只读分享管理"
  >
    <h3 class="console-heading">匿名只读分享</h3>
    <p class="console-description"
      >持有完整链接的人可查看选定版本的全部页面、静态内容、默认值及下列全部候选设备；无需登录，可转发链接。</p
    >
    <el-alert
      v-if="state.error || versions.error || pickerError"
      :title="state.error || versions.error || pickerError"
      type="error"
      :closable="false"
    />
    <el-alert v-if="state.notice" :title="state.notice" type="info" :closable="false" />
    <el-button data-testid="sharing-refresh" :disabled="!usable || busy" @click="refresh"
      >刷新分享管理</el-button
    >
    <p class="console-description" v-if="state.configuration && !state.configuration.available"
      >当前宿主未准备好，暂不能创建分享；已有分享仍可查询和撤销。</p
    >
    <p
      class="console-description"
      v-if="versions.catalog && !versions.history.length && !versions.loading"
      >暂无可选择的已发布版本。</p
    >
    <div v-for="version in versions.history" :key="version.id">
      <el-button :disabled="!selectable" @click="chooseVersion(version.id)"
        >选择分享版本 {{ version.versionNumber }}</el-button
      >
    </div>
    <el-button v-if="versions.nextCursor" :disabled="!selectable" @click="publication.loadMore()"
      >下一页可选版本</el-button
    >
    <div v-if="state.versionId" data-testid="sharing-selection" :data-version-id="state.versionId">
      <p class="console-description"
        >已选择版本 {{ state.versionNumber }}：包含全部
        {{ state.pageCount }} 个页面。分享不会随以后的草稿编辑或发布自动改变。</p
      >
      <p class="console-description"
        >期限范围5分钟～24小时；来源限制使用平台宿主，不允许嵌入其他网站。分享只能读取，不能控制设备或处理告警。</p
      >
      <el-form-item label="有效秒数">
        <el-input-number
          v-model="expiresInSeconds"
          :min="300"
          :max="86400"
          :precision="0"
          :disabled="!selectable"
          aria-label="分享有效秒数"
        />
      </el-form-item>
      <p class="console-description" v-if="!state.variables.length"
        >此版本没有设备变量，将分享所有静态页面内容。</p
      >
      <div v-for="variable in state.variables" :key="variable.variableKey">
        <h4>{{ variable.title }}</h4>
        <p class="console-description"
          >以下全部设备均获授权；不只限运行时当前选中的设备。默认设备必须保留。</p
        >
        <ul
          ><li v-for="id in variable.deviceIds" :key="id">
            {{ deviceNames[id] ?? id }}
            <span v-if="variable.defaultDeviceIds.includes(id)">（默认设备）</span>
            <el-button
              :disabled="!selectable || variable.defaultDeviceIds.includes(id)"
              @click="sharing.removeCandidate(variable.variableKey, id)"
              >移除候选</el-button
            >
          </li></ul
        >
        <el-button :disabled="!selectable || pickerBusy" @click="openPicker(variable.variableKey)"
          >为 {{ variable.title }} 添加设备</el-button
        >
      </div>
      <div v-if="pickerVariable">
        <p class="console-description">选择候选设备；创建时会复核每台设备的权限及精确物模型。</p>
        <div class="console-toolbar">
          <el-select
            v-model="pickerId"
            aria-label="分享候选设备"
            :disabled="!selectable || pickerBusy"
            placeholder="选择设备"
          >
            <el-option
              v-for="device in pickerItems"
              :key="device.id"
              :label="device.name ?? device.id"
              :value="device.id"
            />
          </el-select>
          <el-button :disabled="!selectable || !pickerId" @click="addCandidate"
            >加入候选范围</el-button
          >
          <el-button
            v-if="pickerCursor"
            :disabled="!selectable || pickerBusy"
            @click="loadDevices(true)"
            >下一页设备</el-button
          >
        </div>
      </div>
      <el-button
        data-testid="sharing-create"
        type="primary"
        :disabled="!creatable"
        @click="confirmCreate"
        >创建只读分享</el-button
      >
    </div>
    <div v-if="state.hasSecret" data-testid="sharing-created" :data-share-id="state.createdShareId">
      <p class="console-description">创建成功，到期时间：{{ state.createdExpiresAt }}</p>
      <p class="console-description">{{ state.maskedLink }}</p>
      <p class="console-description"
        >完整链接不会显示在页面。请立即复制；隐藏、离线或离开此页会清除本次复制能力。</p
      >
      <div class="console-actions">
        <el-button data-testid="sharing-copy" :disabled="!usable" @click="copy"
          >复制完整分享链接</el-button
        >
        <el-button :disabled="busy || !usable" @click="confirmRevoke(state.createdShareId!)"
          >撤销此分享</el-button
        >
      </div>
    </div>
    <div v-if="state.pending">
      <p class="console-description"
        >创建结果未知；请重试原操作，不能凭列表猜测本次创建是否成功。</p
      >
      <el-button data-testid="sharing-retry" :disabled="busy || !usable" @click="sharing.retry()"
        >重试原创建</el-button
      >
    </div>
    <div
      v-if="state.recoverShareId"
      data-testid="sharing-unrecoverable"
      :data-share-id="state.recoverShareId"
    >
      <p class="console-description"
        >已创建分享 {{ state.recoverShareId }}，完整链接无法恢复。撤销后才可明确重新创建。</p
      >
      <el-button :disabled="busy || !usable" @click="confirmRevoke(state.recoverShareId!)"
        >撤销无法恢复的分享</el-button
      >
    </div>
    <div v-if="state.revokePending">
      <p class="console-description">撤销结果尚未确认。</p>
      <el-button :disabled="busy || !usable" @click="sharing.revoke(state.revokePending!)"
        >重试原撤销</el-button
      >
    </div>
    <h4>分享记录</h4>
    <p class="console-description" v-if="state.listLoaded && !state.items.length && !state.loading"
      >当前页没有分享记录。</p
    >
    <ul
      ><li
        v-for="item in state.items"
        :key="item.shareId"
        :data-testid="`sharing-row-${item.shareId}`"
        :data-status="item.status"
      >
        版本 {{ item.dashboardVersionNumber }} · {{ statusLabel[item.status] }} · 到期
        {{ item.expiresAt }}
        <el-button
          :disabled="
            busy || !usable || !!state.pending || !!state.revokePending || item.status === 'REVOKED'
          "
          @click="confirmRevoke(item.shareId)"
          >撤销分享</el-button
        >
      </li></ul
    >
    <el-button v-if="state.nextCursor" :disabled="busy || !usable" @click="sharing.loadMore()"
      >下一页分享记录</el-button
    >
  </section>
</template>
<script setup lang="ts">
  import { computed, onBeforeUnmount, ref, shallowRef, watch } from 'vue'
  import { ElMessageBox } from 'element-plus'
  import { useUserStore } from '@/store/modules/user'
  import { currentIdentityEpoch } from '@/utils/http/identity-scope'
  import { createDashboardSharing } from '@/features/dashboard/sharing-model'
  import { createDashboardPublication } from '@/features/dashboard/publication-model'
  import {
    fetchShareConfiguration,
    fetchDashboardShares,
    createDashboardShareIntent,
    revokeDashboardShare
  } from '@/api/dashboard-sharing'
  import {
    fetchDashboardPublicationCatalog,
    fetchDashboardPublicationHistory,
    fetchDashboardPublicationVersion,
    writeDashboardPublicationIntent
  } from '@/api/dashboard-publication'
  import { fetchSearchDevices, type DeviceResponse } from '@/api/device'
  import { fetchBindingMetadata, createDesignerReadScope } from '@/api/dashboard-binding'
  const props = defineProps<{
    projectId: string
    dashboardId: string
    available: boolean
    canManage: boolean
  }>()
  const user = useUserStore(),
    visible = ref(!document.hidden),
    confirming = ref(false),
    expiresInSeconds = ref(3600)
  const usable = computed(() => props.available && props.canManage && visible.value)
  const context = () => ({ ...props, available: usable.value, identity: currentIdentityEpoch() })
  let localEpoch = 0,
    lastMetadataAt = 0,
    metadataDeadline = 0
  let metadataScope: ReturnType<typeof createDesignerReadScope> | undefined
  const sharing = createDashboardSharing({
    context,
    configuration: fetchShareConfiguration,
    list: fetchDashboardShares,
    create: createDashboardShareIntent,
    revoke: revokeDashboardShare,
    newKey: () => crypto.randomUUID(),
    changed: (snapshot) => {
      state.value = snapshot
    },
    deviceMetadata: async (projectId, id) => {
      const epoch = localEpoch
      const delay = Math.max(0, 300 - (performance.now() - lastMetadataAt))
      if (delay) await new Promise((resolve) => setTimeout(resolve, delay))
      if (epoch !== localEpoch || !usable.value || performance.now() >= metadataDeadline)
        throw new Error('读取轮已终止')
      lastMetadataAt = performance.now()
      metadataScope = createDesignerReadScope()
      const scope = metadataScope
      const timer = setTimeout(
        () => scope.close(),
        Math.max(0, metadataDeadline - performance.now())
      )
      let result
      try {
        result = await fetchBindingMetadata(projectId, id, scope)
      } finally {
        clearTimeout(timer)
        scope.close()
        if (metadataScope === scope) metadataScope = undefined
      }
      if (epoch !== localEpoch || !usable.value || performance.now() >= metadataDeadline)
        throw new Error('读取轮已终止')
      return result
    }
  })
  const state = shallowRef(sharing.getSnapshot())
  const publication = createDashboardPublication({
    context: () => ({
      ...context(),
      canRead: props.canManage,
      draftRevision: '0',
      dirty: false,
      saving: false,
      conflict: false
    }),
    detail: fetchDashboardPublicationCatalog,
    list: fetchDashboardPublicationHistory,
    version: fetchDashboardPublicationVersion,
    write: writeDashboardPublicationIntent,
    newKey: () => crypto.randomUUID(),
    changed: (snapshot) => {
      versions.value = snapshot
    }
  })
  const versions = shallowRef(publication.getSnapshot())
  const pickerVariable = ref(''),
    pickerId = ref(''),
    pickerItems = ref<(DeviceResponse & { id: string })[]>([]),
    pickerCursor = ref<string | undefined>(),
    pickerBusy = ref(false),
    pickerError = ref(''),
    deviceNames = ref<Record<string, string>>({})
  const busy = computed(() => state.value.loading || state.value.writing || confirming.value)
  const selectable = computed(
    () =>
      usable.value &&
      !busy.value &&
      !state.value.pending &&
      !state.value.revokePending &&
      !state.value.recoverShareId &&
      !versions.value.loading &&
      !versions.value.detailLoading
  )
  const creatable = computed(
    () =>
      selectable.value &&
      !!state.value.versionId &&
      state.value.configuration?.available &&
      state.value.variables.every((variable) => variable.deviceIds.length > 0)
  )
  const statusLabel = { ACTIVE: '有效', EXPIRED: '已到期', REVOKED: '已撤销' }
  function clearPicker() {
    pickerVariable.value = ''
    pickerId.value = ''
    pickerItems.value = []
    pickerCursor.value = undefined
    pickerBusy.value = false
    pickerError.value = ''
    deviceNames.value = {}
  }
  function stop(scopeChanged: boolean) {
    localEpoch++
    metadataScope?.close()
    metadataScope = undefined
    clearPicker()
    publication.reset()
    if (scopeChanged) sharing.reset()
    else sharing.suspend()
  }
  watch(
    () => state.value.scopeDenied,
    (denied) => {
      if (!denied) return
      localEpoch++
      metadataScope?.close()
      metadataScope = undefined
      publication.reset()
      clearPicker()
    },
    { flush: 'sync' }
  )
  async function refresh() {
    if (!usable.value || busy.value) return
    await sharing.open()
    if (state.value.scopeDenied) {
      publication.reset()
      clearPicker()
    } else if (usable.value) await publication.open()
  }
  watch(
    () => [props.projectId, props.dashboardId, user.info.userId, props.canManage],
    () => {
      stop(true)
      if (usable.value) void refresh()
    },
    { immediate: true, flush: 'sync' }
  )
  watch(
    () => [props.available, visible.value],
    () => {
      stop(false)
      if (usable.value) void refresh()
    },
    { flush: 'sync' }
  )
  const visibility = () => {
    visible.value = !document.hidden
  }
  document.addEventListener('visibilitychange', visibility)
  onBeforeUnmount(() => {
    document.removeEventListener('visibilitychange', visibility)
    stop(true)
  })
  async function chooseVersion(id: string) {
    if (!selectable.value) return
    const epoch = localEpoch
    await publication.selectVersion(id)
    if (epoch !== localEpoch || !usable.value) return
    const version = versions.value.selectedVersion,
      catalog = versions.value.catalog
    if (version && catalog) {
      sharing.selectVersion(version, catalog.publicationRevision)
      clearPicker()
    }
  }
  async function openPicker(key: string) {
    pickerVariable.value = key
    pickerCursor.value = undefined
    await loadDevices()
  }
  async function loadDevices(next = false) {
    if (!selectable.value || pickerBusy.value) return
    const epoch = localEpoch
    pickerBusy.value = true
    pickerError.value = ''
    pickerItems.value = []
    pickerId.value = ''
    try {
      const page = await fetchSearchDevices(props.projectId, {
        limit: 20,
        ...(next && pickerCursor.value ? { cursor: pickerCursor.value } : {})
      })
      if (epoch !== localEpoch || !usable.value) return
      if (!Array.isArray(page.items) || page.items.length > 20) throw new Error('invalid page')
      const devices = page.items.filter(
        (device): device is DeviceResponse & { id: string } =>
          typeof device.id === 'string' &&
          /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/.test(device.id)
      )
      if (devices.length !== page.items.length) throw new Error('invalid device identity')
      pickerItems.value = devices
      pickerCursor.value = page.hasMore ? (page.nextCursor ?? undefined) : undefined
      for (const device of page.items)
        if (device.id) deviceNames.value[device.id] = device.name ?? device.id
    } catch {
      if (epoch === localEpoch) pickerError.value = '设备目录读取失败，请重试。'
    } finally {
      if (epoch === localEpoch) pickerBusy.value = false
    }
  }
  function addCandidate() {
    if (selectable.value && pickerVariable.value && pickerId.value) {
      sharing.addCandidate(pickerVariable.value, pickerId.value)
      pickerId.value = ''
    }
  }
  async function confirmCreate() {
    if (!creatable.value) return
    const epoch = localEpoch,
      before = JSON.stringify({
        context: context(),
        variables: state.value.variables,
        version: state.value.versionId,
        expiresInSeconds: expiresInSeconds.value
      })
    confirming.value = true
    try {
      await ElMessageBox.confirm(
        '确认向持有链接的人开放选定版本的全部页面、静态内容及所有候选设备？该链接只读，但可被转发。',
        '创建只读分享',
        { confirmButtonText: '创建分享', cancelButtonText: '取消', type: 'warning' }
      )
      if (
        epoch !== localEpoch ||
        !usable.value ||
        before !==
          JSON.stringify({
            context: context(),
            variables: state.value.variables,
            version: state.value.versionId,
            expiresInSeconds: expiresInSeconds.value
          })
      )
        return
      metadataDeadline = performance.now() + 30_000
      await sharing.create(expiresInSeconds.value)
    } catch {
      /* 取消不签发，错误由模型以固定文案显示。 */
    } finally {
      confirming.value = false
    }
  }
  async function confirmRevoke(id: string) {
    if (busy.value || !usable.value) return
    const epoch = localEpoch
    confirming.value = true
    try {
      await ElMessageBox.confirm('撤销后该分享链接将永久失效，无法恢复。', '撤销只读分享', {
        confirmButtonText: '撤销分享',
        cancelButtonText: '取消',
        type: 'warning'
      })
      if (epoch === localEpoch && usable.value) await sharing.revoke(id)
    } catch {
      /* 取消不撤销。 */
    } finally {
      confirming.value = false
    }
  }
  function copy() {
    return sharing.copyLink((value) => navigator.clipboard.writeText(value))
  }
</script>
