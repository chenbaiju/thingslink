<template>
  <section class="console-fragment console-editor-section" aria-label="看板发布与历史恢复">
    <h3 class="console-heading">发布与历史版本</h3>
    <p class="console-description">发布使用已保存草稿；回滚和撤回只改变运行版本，保留当前草稿。</p>
    <el-alert v-if="state.error" :title="state.error" type="error" :closable="false" />
    <el-alert v-if="state.notice" :title="state.notice" type="info" :closable="false" />
    <p
      class="console-description"
      v-if="state.catalog"
      data-testid="publication-status"
      :data-version-id="state.catalog.currentVersionId ?? ''"
    >
      当前发布：<span>{{
        state.catalog.currentVersionId ? currentVersionLabel : '未发布或已撤回'
      }}</span>
      <small>（发布修订 {{ state.catalog.publicationRevision }}）</small>
    </p>
    <div class="console-actions">
      <el-button
        data-testid="publication-refresh"
        :disabled="!canRead || !available || state.loading || state.writing || confirming"
        @click="refresh"
      >
        {{ state.pending ? '读取当前发布事实' : '刷新发布状态' }}
      </el-button>
      <template v-if="canManage">
        <el-button
          data-testid="publication-publish"
          type="primary"
          :disabled="!publishable"
          @click="confirmAction('PUBLISH')"
          >发布已保存草稿</el-button
        >
        <el-button
          data-testid="publication-withdraw"
          :disabled="!writable || !state.catalog?.currentVersionId"
          @click="confirmAction('WITHDRAW')"
          >撤回当前发布</el-button
        >
        <el-button
          data-testid="publication-soft-delete"
          type="danger"
          :disabled="!publishable"
          @click="confirmAction('SOFT_DELETE')"
          >软删除看板</el-button
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
        data-testid="publication-retry"
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
        :data-testid="`publication-version-${version.id}`"
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
      <details>
        <summary>查看版本内容</summary>
        <pre>{{ JSON.stringify(state.selectedVersion.schema, null, 2) }}</pre>
      </details>
    </section>
  </section>
</template>
<script setup lang="ts">
  import { computed, onBeforeUnmount, ref, shallowRef, watch } from 'vue'
  import { ElMessageBox } from 'element-plus'
  import { useUserStore } from '@/store/modules/user'
  import { currentIdentityEpoch } from '@/utils/http/identity-scope'
  import { createDashboardPublication } from '@/features/dashboard/publication-model'
  import type { PublicationSnapshot } from '@/features/dashboard/publication-model'
  import {
    fetchDashboardPublicationCatalog,
    fetchDashboardPublicationHistory,
    fetchDashboardPublicationVersion,
    writeDashboardPublicationIntent
  } from '@/api/dashboard-publication'
  const props = defineProps<{
    projectId: string
    dashboardId: string
    draftRevision: string
    dirty: boolean
    saving: boolean
    conflict: boolean
    available: boolean
    canRead: boolean
    canManage: boolean
  }>()
  const user = useUserStore()
  const emit = defineEmits<{
    deleteLock: [locked: boolean]
    deleted: [result: NonNullable<PublicationSnapshot['deleted']>]
  }>()
  let emittedDeletion = false
  const confirming = ref(false)
  const context = () => ({ ...props, identity: currentIdentityEpoch() })
  const publication = createDashboardPublication({
    context,
    detail: fetchDashboardPublicationCatalog,
    list: fetchDashboardPublicationHistory,
    version: fetchDashboardPublicationVersion,
    write: writeDashboardPublicationIntent,
    newKey: () => crypto.randomUUID(),
    changed: (snapshot) => {
      state.value = snapshot
      emit('deleteLock', snapshot.pending?.kind === 'SOFT_DELETE' || !!snapshot.deleted)
      if (snapshot.deleted && !emittedDeletion) {
        emittedDeletion = true
        emit('deleted', snapshot.deleted)
      }
    }
  })
  const state = shallowRef(publication.getSnapshot())
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
    () => [
      props.projectId,
      props.dashboardId,
      props.canRead,
      props.canManage,
      user.info.userId,
      user.info.tenantId,
      JSON.stringify(user.info.roles),
      JSON.stringify(user.info.buttons),
      currentIdentityEpoch()
    ],
    () => {
      emittedDeletion = false
      publication.reset()
      if (props.available && props.canRead && props.projectId && props.dashboardId)
        void publication.open()
    },
    { immediate: true, flush: 'sync' }
  )
  watch(
    () => props.available,
    () => {
      // 暂时离线保留同身份删除原意图；权限或资源变化仍立即销毁。
      if (state.value.pending?.kind === 'SOFT_DELETE' || state.value.deleted) return
      publication.reset()
      if (props.available && props.canRead && props.projectId && props.dashboardId)
        void publication.open()
    },
    { flush: 'sync' }
  )
  onBeforeUnmount(() => publication.reset())
  function refresh() {
    return state.value.pending ? publication.recover() : publication.open()
  }
  async function confirmAction(
    kind: 'PUBLISH' | 'ROLLBACK' | 'WITHDRAW' | 'SOFT_DELETE',
    versionId?: string
  ) {
    if (!writable.value || ((kind === 'PUBLISH' || kind === 'SOFT_DELETE') && !publishable.value))
      return
    const confirmationContext = () =>
      JSON.stringify({
        context: context(),
        user: {
          userId: user.info.userId,
          tenantId: user.info.tenantId,
          roles: user.info.roles,
          buttons: user.info.buttons
        },
        publicationRevision: state.value.catalog?.publicationRevision
      })
    const before = confirmationContext()
    const texts = {
      PUBLISH: ['发布当前已保存草稿？尚未保存的内容不会进入发布版本。', '确认发布', '发布'],
      ROLLBACK: ['将运行入口切换到选中的历史版本？当前草稿保持不变。', '确认回滚', '回滚'],
      WITHDRAW: [
        '撤回后当前运行入口及依赖此看板的分享将不可用。历史版本和草稿会保留。',
        '确认撤回',
        '撤回'
      ],
      SOFT_DELETE: [
        '软删除不可恢复。看板运行入口及依赖它的分享将失效，应用将无法运行此看板；历史版本、应用历史引用和用户历史授权保留，不会自动解绑。请确认已保存本地修改。',
        '确认软删除看板',
        '软删除'
      ]
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
      else if (kind === 'SOFT_DELETE') await publication.softDelete()
    } catch {
      /* 取消确认不发写请求。领域错误由发布状态模型提供安全提示。 */
    } finally {
      confirming.value = false
    }
  }
</script>
