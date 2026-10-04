<template>
  <section class="console-editor-section console-fragment" aria-label="应用发布与历史恢复">
    <h3 class="console-heading">发布与历史版本</h3>
    <p class="console-description">发布使用已保存草稿；回滚和撤回只改变运行版本，保留当前草稿。</p>
    <el-alert v-if="state.error" :title="state.error" type="error" :closable="false" />
    <el-alert v-if="state.notice" :title="state.notice" type="info" :closable="false" />
    <p
      class="console-description"
      v-if="state.catalog"
      data-testid="application-publication-status"
      :data-version-id="state.catalog.currentVersionId ?? ''"
    >
      当前发布：<span>{{
        state.catalog.currentVersionId ? currentVersionLabel : '未发布或已撤回'
      }}</span>
      <small>（发布修订 {{ state.catalog.publicationRevision }}）</small>
    </p>
    <div class="console-actions">
      <el-button
        data-testid="application-publication-refresh"
        :disabled="!canRead || !available || state.loading || state.writing || confirming"
        @click="refresh"
      >
        {{ state.pending ? '读取当前发布事实' : '刷新发布状态' }}
      </el-button>
      <template v-if="canManage">
        <el-button
          data-testid="application-publication-publish"
          type="primary"
          :disabled="!publishable"
          @click="confirmAction('PUBLISH')"
          >发布已保存草稿</el-button
        >
        <el-button
          data-testid="application-publication-withdraw"
          :disabled="!writable || !state.catalog?.currentVersionId"
          @click="confirmAction('WITHDRAW')"
          >撤回当前发布</el-button
        >
      </template>
    </div>
    <template v-if="canManage">
      <p class="console-description" v-if="dirty || saving || conflict"
        >请先完成草稿保存并处理冲突，再发布。</p
      >
    </template>
    <template v-if="state.pending">
      <p class="console-description" v-if="state.pending.status === 'UNKNOWN'"
        >上次操作结果尚未确认。读取当前状态不会证明上次操作是否完成，请重试原操作后再执行新操作。</p
      >
      <p class="console-description" v-else
        >上次操作已有完成回执，请读取当前发布事实；当前状态可能包含后续变更。</p
      >
      <el-button
        v-if="state.pending.status === 'UNKNOWN' && canManage"
        :disabled="!available || state.loading || state.writing || confirming"
        @click="publication.retry()"
        >重试原操作</el-button
      >
    </template>
    <p class="console-description" v-if="state.catalog && !state.loading && !state.history.length"
      >暂无已发布历史版本。</p
    >
    <ul>
      <li
        v-for="version in state.history"
        :key="version.id"
        :data-testid="`application-publication-version-${version.id}`"
        :data-version-number="version.versionNumber"
      >
        <span>版本 {{ version.versionNumber }} · {{ version.publishedAt }}</span>
        <span v-if="version.id === state.catalog?.currentVersionId">（当前发布）</span>
        <div class="console-actions">
          <el-button
            :disabled="!available || !canRead || state.loading || state.detailLoading"
            @click="publication.selectVersion(version.id)"
            >查看版本 {{ version.versionNumber }}</el-button
          >
          <el-button
            v-if="canManage"
            :disabled="!writable || version.id === state.catalog?.currentVersionId"
            @click="confirmAction('ROLLBACK', version.id)"
            >回滚到版本 {{ version.versionNumber }}</el-button
          >
        </div>
      </li>
    </ul>
    <el-button
      v-if="state.nextCursor"
      :disabled="!available || !canRead || state.loading"
      @click="publication.loadMore()"
      >下一页历史版本</el-button
    >
    <section v-if="state.selectedVersion" aria-label="不可变版本详情">
      <h4>版本 {{ state.selectedVersion.versionNumber }}</h4>
      <p class="console-description"
        >发布于 {{ state.selectedVersion.publishedAt }}；来源草稿修订
        {{ state.selectedVersion.sourceDraftRevision }}。</p
      >
      <p class="console-description">该版本只读，查看不会覆盖当前草稿。</p>
      <p class="console-description"
        >公开展示名：{{ state.selectedVersion.snapshot.displayName }}</p
      >
      <ol>
        <li
          v-for="reference in state.selectedVersion.snapshot.dashboardRefs"
          :key="reference.dashboardId"
        >
          {{ reference.title }} · 看板版本 {{ reference.dashboardVersionNumber }}
          <span v-if="reference.dashboardId === state.selectedVersion.snapshot.entryDashboardId"
            >（入口）</span
          >
          <ul
            ><li v-for="page in reference.pages" :key="page.id">{{ page.title }}</li></ul
          >
        </li>
      </ol>
      <details>
        <summary>查看版本内容</summary>
        <pre>{{ JSON.stringify(state.selectedVersion.snapshot, null, 2) }}</pre>
      </details>
    </section>
  </section>
</template>
<script setup lang="ts">
  import { computed, onBeforeUnmount, ref, shallowRef, watch } from 'vue'
  import { ElMessageBox } from 'element-plus'
  import { useUserStore } from '@/store/modules/user'
  import { currentIdentityEpoch } from '@/utils/http/identity-scope'
  import { createApplicationPublication } from '@/features/application/publication-model'
  import {
    fetchApplicationPublicationCatalog,
    fetchApplicationPublicationHistory,
    fetchApplicationPublicationVersion,
    writeApplicationPublicationIntent
  } from '@/api/application-publication'
  const props = defineProps<{
    projectId: string
    applicationId: string
    draftRevision: string
    dirty: boolean
    saving: boolean
    conflict: boolean
    available: boolean
    canRead: boolean
    canManage: boolean
  }>()
  const emit = defineEmits<{
    pending: [value: boolean]
    working: [value: boolean]
    accessDenied: []
  }>()
  const user = useUserStore()
  const confirming = ref(false)
  const context = () => ({ ...props, identity: currentIdentityEpoch() })
  const publication = createApplicationPublication({
    context,
    accessDenied: () => emit('accessDenied'),
    detail: fetchApplicationPublicationCatalog,
    list: fetchApplicationPublicationHistory,
    version: fetchApplicationPublicationVersion,
    write: writeApplicationPublicationIntent,
    newKey: () => crypto.randomUUID(),
    changed: (snapshot) => {
      state.value = snapshot
      emit('pending', !!snapshot.pending)
    }
  })
  const state = shallowRef(publication.getSnapshot())
  watch(
    () => state.value.writing || confirming.value,
    (value) => emit('working', value),
    { flush: 'sync' }
  )
  const writable = computed(
    () =>
      props.canManage &&
      props.canRead &&
      props.available &&
      !!state.value.catalog &&
      !state.value.loading &&
      !state.value.writing &&
      !state.value.pending &&
      !confirming.value
  )
  const publishable = computed(
    () => writable.value && !props.dirty && !props.saving && !props.conflict
  )
  const currentVersionLabel = computed(() => {
    const version = state.value.history.find(
      (entry) => entry.id === state.value.catalog?.currentVersionId
    )
    return version ? `版本 ${version.versionNumber}` : '已有发布版本（未在当前历史页）'
  })
  watch(
    [
      () => props.projectId,
      () => props.applicationId,
      () => props.available,
      () => props.canRead,
      () => props.canManage,
      () => user.info.userId,
      () => user.info.tenantId
    ],
    () => {
      publication.reset()
      if (props.available && props.canRead && props.projectId && props.applicationId)
        void publication.open()
    },
    { immediate: true, flush: 'sync' }
  )
  onBeforeUnmount(() => {
    publication.reset()
    emit('working', false)
  })
  function refresh() {
    return state.value.pending ? publication.recover() : publication.open()
  }
  async function confirmAction(kind: 'PUBLISH' | 'ROLLBACK' | 'WITHDRAW', versionId?: string) {
    if (!writable.value || (kind === 'PUBLISH' && !publishable.value)) return
    const confirmationContext = () =>
      JSON.stringify({
        context: context(),
        publicationRevision: state.value.catalog?.publicationRevision
      })
    const before = confirmationContext()
    const texts = {
      PUBLISH: ['发布当前已保存草稿？尚未保存的内容不会进入发布版本。', '确认发布', '发布'],
      ROLLBACK: ['将运行入口切换到选中的历史版本？当前草稿保持不变。', '确认回滚', '回滚'],
      WITHDRAW: ['撤回后该应用的运行入口不可用。历史版本和当前草稿会保留。', '确认撤回', '撤回']
    } as const
    confirming.value = true
    try {
      await ElMessageBox.confirm(texts[kind][0], texts[kind][1], {
        confirmButtonText: texts[kind][2],
        cancelButtonText: '取消',
        type: 'warning'
      })
      // 确认窗口停留期间草稿、账号或项目发生变化，不能把旧意图应用到新上下文。
      if (confirmationContext() !== before) return
      if (kind === 'PUBLISH') await publication.publish()
      else if (kind === 'ROLLBACK' && versionId) await publication.rollback(versionId)
      else if (kind === 'WITHDRAW') await publication.withdraw()
    } catch {
      /* 取消确认不发写请求。领域错误由发布状态模型提供安全提示。 */
    } finally {
      confirming.value = false
    }
  }
</script>
